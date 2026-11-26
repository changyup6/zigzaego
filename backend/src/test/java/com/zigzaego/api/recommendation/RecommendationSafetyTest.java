package com.zigzaego.api.recommendation;

import static com.zigzaego.api.auth.AuthTestSupport.insertAccount;
import static com.zigzaego.api.auth.AuthTestSupport.uniqueEmail;
import static com.zigzaego.api.recommendation.RecommendationTestData.countRecommendations;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertCount;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertIngredient;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertMenu;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertPriceHistory;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertRecipe;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertRecommendation;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertSale;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertStore;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertSupplier;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertSupplierProduct;
import static com.zigzaego.api.recommendation.RecommendationTestData.setPrimaryProduct;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.domain.price.PriceIndexCalculator.PriceLevel;
import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.recommendation.dto.RecommendationAction;
import com.zigzaego.api.recommendation.dto.RecommendationDecisionRequest;
import com.zigzaego.api.recommendation.dto.RecommendationResponse;
import com.zigzaego.api.recommendation.dto.RecommendationRunResponse;
import com.zigzaego.api.recommendation.exception.RecommendationErrorCode;
import com.zigzaego.api.recommendation.service.RecommendationService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 3주 계획 8절 안전장치 점검 (S15P31A308-73). 기존 테스트가 비워 둔 칸만 채운다.
 *
 * <pre>
 * unique       DB 제약이 직접 중복을 막고, 같은 날 동시에 두 번 돌려도 1건
 * 상태 전이    동시에 두 번 승인해도 한 번만 먹고 나머지는 409
 * max_order_qty 서비스까지 통째로 돌려 수량이 잘리고 이유 문장 끝에 경고가 남는다
 * 쌀 때 상한   가격 연동으로 R' 을 늘려도 유통기한 · max_order_qty 를 넘지 않고, SS · ROP 는 그대로
 * </pre>
 *
 * 기준일 T = 2026-10-10(토), 손계산 수치는 {@link RecommendationServiceTest} 와 같다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RecommendationSafetyTest {

	private static final LocalDate T = LocalDate.of(2026, 10, 10);
	private static final ZoneOffset KST = ZoneOffset.ofHours(9);

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private RecommendationService service;

	@Autowired
	private PasswordEncoder passwordEncoder;

	// ---------------------------------------------------------------- unique

	@Test
	void unique_같은_가게_재료_날짜를_직접_넣으면_DB가_막는다() {
		long store = insertStore(jdbc, "unique");
		long 등심 = insertIngredient(jdbc, store, "등심", "G", "BOM", "CHILLED", "2", false);
		insertRecommendation(jdbc, store, 등심, T, "4000");

		assertThatThrownBy(() -> insertRecommendation(jdbc, store, 등심, T, "5000"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("uq_recommendation_store_ingredient_date");

		// 다른 날 · 다른 가게의 같은 재료 이름은 막지 않는다
		insertRecommendation(jdbc, store, 등심, T.plusDays(1), "4000");
		long other = insertStore(jdbc, "unique-other");
		long 남의등심 = insertIngredient(jdbc, other, "등심", "G", "BOM", "CHILLED", "2", false);
		insertRecommendation(jdbc, other, 남의등심, T, "4000");
		assertThat(countRecommendations(jdbc, store, T)).isEqualTo(1);
	}

	@Test
	void unique_같은_날_동시에_두_번_실행해도_1건() throws Exception {
		long store = 손계산_가게("동시실행");

		List<RecommendationRunResponse> results = concurrently(2, () -> service.run(store, T));

		int created = results.stream().mapToInt(RecommendationRunResponse::createdCount).sum();
		assertThat(created).isEqualTo(1);
		assertThat(countRecommendations(jdbc, store, T)).isEqualTo(1);
		assertThat(results).allSatisfy(r -> assertThat(r.recommendations()).hasSize(1));
		assertThat(results.get(0).recommendations().get(0).id()).isEqualTo(results.get(1).recommendations().get(0).id());
	}

	// ---------------------------------------------------------------- 상태 전이

	@Test
	void 전이_동시에_두_번_승인해도_한_번만_먹고_나머지는_409() throws Exception {
		long store = insertStore(jdbc, "동시승인");
		long account = insertAccount(jdbc, passwordEncoder, uniqueEmail());
		long 등심 = insertIngredient(jdbc, store, "등심", "G", "BOM", "CHILLED", "2", false);
		long id = insertRecommendation(jdbc, store, 등심, T, "4000");
		RecommendationDecisionRequest approve = new RecommendationDecisionRequest(RecommendationAction.APPROVE, null, null);
		RecommendationDecisionRequest modify = new RecommendationDecisionRequest(RecommendationAction.MODIFY,
				new BigDecimal("1000"), null);

		List<Object> results = concurrently(2,
				() -> decideOrError(store, account, id, approve),
				() -> decideOrError(store, account, id, modify));

		assertThat(results).filteredOn(r -> r instanceof RecommendationResponse).hasSize(1);
		assertThat(results).filteredOn(r -> r instanceof BusinessException).hasSize(1)
				.allSatisfy(e -> assertThat(((BusinessException) e).getErrorCode())
						.isEqualTo(RecommendationErrorCode.ALREADY_DECIDED));

		Map<String, Object> row = jdbc.queryForMap(
				"SELECT status, final_qty, decided_by, decided_at FROM recommendation WHERE id = ?", id);
		assertThat(row.get("status")).isEqualTo("APPROVED");
		assertThat(row.get("decided_by")).isEqualTo(account);
		assertThat(row.get("decided_at")).isNotNull();
		// 먼저 먹은 쪽의 수량만 남는다 — 둘 중 하나, 섞이지 않는다
		assertThat((BigDecimal) row.get("final_qty")).isIn(new BigDecimal("4000.000"), new BigDecimal("1000.000"));
	}

	// ---------------------------------------------------------------- 상한

	/**
	 * 손계산 가게(raw 3500 → 포장 1000 올림 4000)에 max_order_qty 3000 → 3000 으로 자르고 이유 끝에 경고.
	 * SS · ROP 는 상한과 무관하게 3000 · 4500 그대로.
	 */
	@Test
	void max_order_qty_초과면_자르고_이유_문장_끝에_경고() {
		long store = 손계산_가게("최대발주량");
		jdbc.update("UPDATE ingredient SET max_order_qty = 3000 WHERE store_id = ?", store);

		RecommendationResponse r = service.run(store, T).recommendations().get(0);

		assertThat(r.recommendedQty()).isEqualByComparingTo("3000.000");
		assertThat(r.safetyStock()).isEqualByComparingTo("3000.000");
		assertThat(r.reorderPoint()).isEqualByComparingTo("4500.000");
		assertThat(r.reason()).isEqualTo(
				"현재 추정 재고 2500g, 입고 예정 0g. 배송까지 1일간 예상 소비 1500g, 안전재고 3000g. "
						+ "10월 12일 소진 예상. 오늘 주문하면 10월 11일 도착. "
						+ "1회 최대 발주량 3000g을 넘어 3000g로 줄임.");
		assertThat(jdbc.queryForObject("SELECT reason FROM recommendation WHERE store_id = ?", String.class, store))
				.endsWith("1회 최대 발주량 3000g을 넘어 3000g로 줄임.");
	}

	/** 상한에 안 걸리면 경고 줄이 없다 (손계산 가게 그대로, max_order_qty 가 넉넉함). */
	@Test
	void max_order_qty_안_넘으면_경고_없음() {
		long store = 손계산_가게("최대발주량여유");
		jdbc.update("UPDATE ingredient SET max_order_qty = 4000 WHERE store_id = ?", store);

		RecommendationResponse r = service.run(store, T).recommendations().get(0);

		assertThat(r.recommendedQty()).isEqualByComparingTo("4000.000");
		assertThat(r.reason()).endsWith("오늘 주문하면 10월 11일 도착.");
		assertThat(r.reason()).doesNotContain("줄임");
	}

	/**
	 * <pre>
	 * 쌈(지수 0.85) → R' = 4, S = 1500 × 5 + 3000 = 10500, raw = 8000 (상한 없으면 8000)
	 * max_order_qty 6000 → 6000 으로 자름. SS · ROP 3000 · 4500 그대로. 가격 줄 다음에 경고 줄.
	 * </pre>
	 */
	@Test
	void 쌀_때_상한은_max_order_qty_SS_ROP_불변() {
		long store = 가격연동_쌈_가게("쌈-최대");
		jdbc.update("UPDATE ingredient SET max_order_qty = 6000 WHERE store_id = ?", store);

		RecommendationResponse r = service.run(store, T).recommendations().get(0);

		assertThat(r.priceLevel()).isEqualTo(PriceLevel.LOW);
		assertThat(r.cycleDaysUsed()).isEqualByComparingTo("4.00");
		assertThat(r.safetyStock()).isEqualByComparingTo("3000.000");
		assertThat(r.reorderPoint()).isEqualByComparingTo("4500.000");
		assertThat(r.recommendedQty()).isEqualByComparingTo("6000.000");
		assertThat(r.reason()).endsWith("단가 3.4원/g, 최근 8주보다 15% 낮음. 5일치 미리 주문. "
				+ "1회 최대 발주량 6000g을 넘어 6000g로 줄임.");
	}

	/**
	 * <pre>
	 * 쌈 → raw 8000. 유통기한 4일 → 상한 = 1500 × (4 − 1) − 2500 = 2000 → 포장 1000 배수 2000.
	 * </pre>
	 */
	@Test
	void 쌀_때_상한은_유통기한() {
		long store = 가격연동_쌈_가게("쌈-유통기한");
		jdbc.update("UPDATE ingredient SET shelf_life_days = 4 WHERE store_id = ?", store);

		RecommendationResponse r = service.run(store, T).recommendations().get(0);

		assertThat(r.priceLevel()).isEqualTo(PriceLevel.LOW);
		assertThat(r.safetyStock()).isEqualByComparingTo("3000.000");
		assertThat(r.reorderPoint()).isEqualByComparingTo("4500.000");
		assertThat(r.recommendedQty()).isEqualByComparingTo("2000.000");
		assertThat(r.reason()).endsWith("5일치 미리 주문. 유통기한 안에 쓸 수 있는 2000g로 줄임.");
	}

	// ---------------------------------------------------------------- 가게 만들기

	/** 등심 BOM 냉장 N=2, 돈까스 150g, L=1 R=1, 포장 1000g, 7일 매일 10개, 실사 4000 → raw 3500 → 4000. */
	private long 손계산_가게(String name) {
		long store = insertStore(jdbc, name);
		long 등심 = insertIngredient(jdbc, store, "등심", "G", "BOM", "CHILLED", "2", false);
		long 돈까스 = insertMenu(jdbc, store, "돈까스");
		insertRecipe(jdbc, store, 돈까스, 등심, "150", "MANUAL");
		long supplier = insertSupplier(jdbc, store, "정육", 1, 1);
		long product = insertSupplierProduct(jdbc, store, supplier, 등심, "1000", null);
		setPrimaryProduct(jdbc, store, 등심, product);
		for (int i = 3; i <= 9; i++) {
			insertSale(jdbc, store, LocalDate.of(2026, 10, i), 돈까스, "10");
		}
		insertCount(jdbc, store, 등심, OffsetDateTime.of(2026, 10, 9, 0, 0, 0, 0, KST), "4000");
		return store;
	}

	/** 손계산 가게에 가격 연동(냉동, R=2) + 기준 4000원 3건, 현재 3400원 → 지수 0.85 쌈. */
	private long 가격연동_쌈_가게(String name) {
		long store = insertStore(jdbc, name);
		long 등심 = insertIngredient(jdbc, store, "등심", "G", "BOM", "FROZEN", "2", true);
		long 돈까스 = insertMenu(jdbc, store, "돈까스");
		insertRecipe(jdbc, store, 돈까스, 등심, "150", "MANUAL");
		long supplier = insertSupplier(jdbc, store, "정육", 1, 2);
		long product = insertSupplierProduct(jdbc, store, supplier, 등심, "1000", null);
		setPrimaryProduct(jdbc, store, 등심, product);
		for (int i = 3; i <= 9; i++) {
			insertSale(jdbc, store, LocalDate.of(2026, 10, i), 돈까스, "10");
		}
		insertCount(jdbc, store, 등심, OffsetDateTime.of(2026, 10, 9, 0, 0, 0, 0, KST), "4000");
		for (LocalDate day : List.of(LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 27), LocalDate.of(2026, 10, 4))) {
			insertPriceHistory(jdbc, store, product, day, 4000, "RECEIVE");
		}
		insertPriceHistory(jdbc, store, product, LocalDate.of(2026, 10, 9), 3400, "RECEIVE");
		return store;
	}

	// ---------------------------------------------------------------- 동시 실행

	private Object decideOrError(long store, long account, long id, RecommendationDecisionRequest request) {
		try {
			return service.decide(store, account, String.valueOf(id), request);
		} catch (BusinessException e) {
			return e;
		}
	}

	/** 같은 작업을 n 번 동시에. */
	private static <T> List<T> concurrently(int n, java.util.concurrent.Callable<T> task) throws Exception {
		List<java.util.concurrent.Callable<T>> tasks = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			tasks.add(task);
		}
		return concurrently(n, tasks);
	}

	@SafeVarargs
	private static <T> List<T> concurrently(int n, java.util.concurrent.Callable<T>... tasks) throws Exception {
		return concurrently(n, List.of(tasks));
	}

	/** 스레드를 전부 준비시킨 뒤 한 번에 출발시켜 실제로 겹치게 한다. */
	private static <T> List<T> concurrently(int n, List<java.util.concurrent.Callable<T>> tasks) throws Exception {
		CountDownLatch ready = new CountDownLatch(n);
		CountDownLatch go = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(n);
		try {
			List<Future<T>> futures = new ArrayList<>();
			for (java.util.concurrent.Callable<T> task : tasks) {
				futures.add(pool.submit(() -> {
					ready.countDown();
					go.await(10, TimeUnit.SECONDS);
					return task.call();
				}));
			}
			ready.await(10, TimeUnit.SECONDS);
			go.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> f : futures) {
				results.add(f.get(30, TimeUnit.SECONDS));
			}
			return results;
		} finally {
			pool.shutdownNow();
		}
	}
}
