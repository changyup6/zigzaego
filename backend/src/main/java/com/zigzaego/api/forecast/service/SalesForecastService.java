package com.zigzaego.api.forecast.service;

import com.zigzaego.api.account.entity.Store;
import com.zigzaego.api.account.repository.StoreRepository;
import com.zigzaego.api.forecast.dto.SalesForecastResponse;
import com.zigzaego.api.forecast.dto.SalesForecastResponse.Day;
import com.zigzaego.api.forecast.dto.SalesForecastResponse.Factor;
import com.zigzaego.api.global.config.StoreClock;
import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.exception.CommonErrorCode;
import java.sql.Date;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 이번 주 매출 예측 조회 (S15P31A308-267). AI 배치가 sales_forecast_week 에 쓴 줄을 그대로 읽는다 — 계산하지 않는다.
 * 이번 주 = 가게 시간대 오늘이 든 주(월 ~ 일). 모든 SQL 에 store_id 를 건다 (ADR-0002).
 */
@Service
@RequiredArgsConstructor
public class SalesForecastService {

	private static final String WEEK_SQL = """
			SELECT pct, reason, days, factors, analysis FROM sales_forecast_week
			WHERE store_id = :storeId AND week_start = :weekStart
			""";

	private final StoreRepository storeRepository;
	private final NamedParameterJdbcTemplate jdbc;
	private final JsonMapper jsonMapper;
	private final StoreClock storeClock;

	@Transactional(readOnly = true)
	public SalesForecastResponse thisWeek(long storeId) {
		Store store = storeRepository.findById(storeId)
				.orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
		return thisWeek(storeId, storeClock.today(storeId, ZoneId.of(store.getTimezone())));
	}

	/** @param today 가게 시간대의 오늘. 테스트가 날짜를 고정할 때 넘긴다 */
	@Transactional(readOnly = true)
	public SalesForecastResponse thisWeek(long storeId, LocalDate today) {
		LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
		String range = range(monday);
		List<Map<String, Object>> rows = jdbc.queryForList(WEEK_SQL,
				Map.of("storeId", storeId, "weekStart", Date.valueOf(monday)));
		if (rows.isEmpty()) {
			return new SalesForecastResponse(null, null, range, List.of(), List.of(), null);
		}
		Map<String, Object> row = rows.getFirst();
		String todayText = today.toString();
		List<Day> days = read(row.get("days")).valueStream()
				.map(d -> {
					boolean isToday = todayText.equals(d.get("date").asString());
					return new Day(isToday ? "오늘" : d.get("label").asString(), d.get("value").asInt(), isToday);
				})
				.toList();
		List<Factor> factors = read(row.get("factors")).valueStream()
				.map(f -> new Factor(f.get("name").asString(), f.get("note").asString(), f.get("pct").asInt()))
				.toList();
		return new SalesForecastResponse((Integer) row.get("pct"), (String) row.get("reason"), range, days, factors,
				(String) row.get("analysis"));
	}

	private JsonNode read(Object jsonb) {
		return jsonMapper.readTree(jsonb.toString());
	}

	private static String range(LocalDate monday) {
		LocalDate sunday = monday.plusDays(6);
		String end = sunday.getMonth() == monday.getMonth() ? sunday.getDayOfMonth() + "일"
				: sunday.getMonthValue() + "월 " + sunday.getDayOfMonth() + "일";
		return monday.getMonthValue() + "월 " + monday.getDayOfMonth() + "일 ~ " + end;
	}
}
