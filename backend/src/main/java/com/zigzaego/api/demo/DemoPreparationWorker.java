package com.zigzaego.api.demo;

import com.zigzaego.api.global.config.StoreClock;
import com.zigzaego.api.global.config.WebServerProfile;
import com.zigzaego.api.recommendation.service.RecommendationService;
import java.sql.Date;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.HashSet;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 자료를 커밋한 DATA_READY만 읽고 추천과 READY를 한 번에 커밋한다. 복수 웹 인스턴스도 행 잠금으로 직렬화한다. */
@Slf4j
@Component
@Profile(WebServerProfile.EXPRESSION)
public class DemoPreparationWorker {

	private final JdbcTemplate jdbc;
	private final StoreClock clock;
	private final DemoPreparationService preparation;
	private final DemoTemplateAttacher attacher;
	private final RecommendationService recommendations;
	private final TransactionTemplate transaction;
	private final JsonMapper mapper;
	private final boolean autoAttach;

	public DemoPreparationWorker(JdbcTemplate jdbc, StoreClock clock, DemoPreparationService preparation,
			DemoTemplateAttacher attacher, RecommendationService recommendations,
			PlatformTransactionManager transactionManager, JsonMapper mapper,
			@Value("${app.demo-preparation.auto-attach:true}") boolean autoAttach) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.preparation = preparation;
		this.attacher = attacher;
		this.recommendations = recommendations;
		this.mapper = mapper;
		this.autoAttach = autoAttach;
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@Scheduled(fixedDelayString = "${app.demo-preparation.poll-delay:1000}",
			initialDelayString = "${app.demo-preparation.initial-delay:1000}")
	public void poll() {
		if (autoAttach) {
			List<Long> pendingAttach = jdbc.queryForList(
					"SELECT store_id FROM demo_preparation WHERE status='PREPARING' AND phase='ATTACH'", Long.class);
			for (long storeId : pendingAttach) {
				processAttach(storeId);
			}
		}
		List<Long> stores = jdbc.queryForList(
				"SELECT store_id FROM demo_preparation WHERE status='DATA_READY'", Long.class);
		for (long storeId : stores) {
			process(storeId);
		}
	}

	public void processAttach(long storeId) {
		int[] attempt = {0};
		long started = System.nanoTime();
		try {
			transaction.executeWithoutResult(tx -> {
				List<Integer> claimed = jdbc.queryForList("""
						SELECT attempt FROM demo_preparation
						WHERE store_id=? AND status='PREPARING' AND phase='ATTACH' FOR UPDATE SKIP LOCKED
						""", Integer.class, storeId);
				if (claimed.isEmpty() || !clock.isDemoStore(storeId)) {
					return;
				}
				attempt[0] = claimed.getFirst();
				if (!preparation.hasCoreData(storeId)) {
					throw new PreparationFailure("DEMO_CORE_DATA_MISSING");
				}
				attacher.attach(storeId);
				jdbc.update("""
						UPDATE demo_preparation SET status='DATA_READY',phase='RECOMMENDATION',
						    error_code=NULL,retryable=false,updated_at=now()
						WHERE store_id=? AND status='PREPARING' AND attempt=?
						""", storeId, attempt[0]);
				log.info("시연 자료 연결 완료 — storeId={}, attempt={}, elapsedMs={}",
						storeId, attempt[0], (System.nanoTime() - started) / 1_000_000L);
			});
		} catch (RuntimeException failure) {
			if (attempt[0] == 0) {
				throw failure;
			}
			String code = failure instanceof PreparationFailure known ? known.code : "DEMO_ATTACH_FAILED";
			transaction.executeWithoutResult(tx -> jdbc.update("""
					UPDATE demo_preparation SET status='FAILED',phase='ATTACH',
					    error_code=?,retryable=true,updated_at=now()
					WHERE store_id=? AND status='PREPARING' AND phase='ATTACH' AND attempt=?
					""", code, storeId, attempt[0]));
			log.warn("시연 자료 연결 실패 — storeId={}, attempt={}, code={}", storeId, attempt[0], code, failure);
		}
	}

	public void process(long storeId) {
		int[] attempt = {0};
		try {
			transaction.executeWithoutResult(tx -> {
				List<Integer> claimed = jdbc.queryForList("""
						SELECT attempt FROM demo_preparation
						WHERE store_id=? AND status='DATA_READY' FOR UPDATE SKIP LOCKED
						""", Integer.class, storeId);
				if (claimed.isEmpty() || !clock.isDemoStore(storeId)) {
					return;
				}
				attempt[0] = claimed.getFirst();
				if (!preparation.hasCoreData(storeId)) {
					throw new PreparationFailure("DEMO_CORE_DATA_MISSING");
				}
				ZoneId zone = ZoneId.of(jdbc.queryForObject(
						"SELECT timezone FROM store WHERE id=?", String.class, storeId));
				LocalDate today = clock.today(storeId, zone);
				LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
				List<String> forecasts = jdbc.queryForList(
						"SELECT days::text FROM sales_forecast_week WHERE store_id=? AND week_start=?",
						String.class, storeId, Date.valueOf(monday));
				if (forecasts.isEmpty() || !validForecast(forecasts.getFirst(), today, monday)) {
					throw new PreparationFailure("DEMO_FORECAST_MISSING");
				}
				// force는 과거 조기 실행이 남긴 빈 완료 캐시도 복구한다. 기존 추천/결정은 유지한다.
				recommendations.run(storeId, null, true);
				jdbc.update("""
						UPDATE demo_preparation SET status='READY',phase='RECOMMENDATION',
						    error_code=NULL,retryable=false,updated_at=now() WHERE store_id=? AND attempt=?
						""", storeId, attempt[0]);
			});
		} catch (RuntimeException failure) {
			if (attempt[0] == 0) {
				throw failure;
			}
			String code = failure instanceof PreparationFailure known ? known.code : "DEMO_RECOMMENDATION_FAILED";
			// 추천 트랜잭션의 롤백 후 별도 커밋. 원본 예외/SQL/외부 응답은 공개하지 않는다.
			transaction.executeWithoutResult(tx -> jdbc.update("""
					UPDATE demo_preparation SET status='FAILED',phase='RECOMMENDATION',
					    error_code=?,retryable=true,updated_at=now()
					WHERE store_id=? AND status='DATA_READY' AND attempt=?
					""", code, storeId, attempt[0]));
			log.warn("시연 추천 준비 실패 — storeId={}, attempt={}, code={}", storeId, attempt[0], code);
		}
	}

	private boolean validForecast(String json, LocalDate today, LocalDate monday) {
		try {
			var days = mapper.readTree(json);
			if (!days.isArray()) return false;
			var dates = new HashSet<LocalDate>();
			for (var day : days) {
				if (!day.path("date").isString() || !day.path("value").isNumber()) return false;
				LocalDate date = LocalDate.parse(day.path("date").asString());
				if (date.isBefore(monday) || date.isAfter(monday.plusDays(6)) || !dates.add(date)
						|| !Double.isFinite(day.path("value").asDouble()) || day.path("value").asDouble() < 0) return false;
			}
			return today.datesUntil(monday.plusDays(7)).allMatch(dates::contains);
		} catch (RuntimeException invalid) {
			return false;
		}
	}

	static final class PreparationFailure extends RuntimeException {
		final String code;
		PreparationFailure(String code) {
			this.code = code;
		}
	}
}
