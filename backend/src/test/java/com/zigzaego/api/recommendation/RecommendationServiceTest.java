package com.zigzaego.api.recommendation;

import static com.zigzaego.api.recommendation.RecommendationTestData.countRecommendations;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertClosedDay;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertCount;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertForecast;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertIngredient;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertMenu;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertPendingPoItem;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertPriceHistory;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertPurchaseOrder;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertRecipe;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertRecommendation;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertSale;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertStore;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertSupplier;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertSupplierProduct;
import static com.zigzaego.api.recommendation.RecommendationTestData.setPrimaryProduct;
import static org.assertj.core.api.Assertions.assertThat;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.domain.price.PriceIndexCalculator.PriceLevel;
import com.zigzaego.api.global.review.ReviewReason;
import com.zigzaego.api.recommendation.dto.RecommendationResponse;
import com.zigzaego.api.recommendation.dto.RecommendationRunResponse;
import com.zigzaego.api.recommendation.entity.RecommendationStatus;
import com.zigzaego.api.recommendation.entity.SafetyStockMethod;
import com.zigzaego.api.recommendation.service.RecommendationService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 추천 생성 손계산 검증. 기준일 T = 2026-10-10(토), 가게 시간대 Asia/Seoul. 기대값은 각 테스트 주석의 손계산 숫자 리터럴이다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RecommendationServiceTest {

	private static final LocalDate T = LocalDate.of(2026, 10, 10);
	private static final ZoneOffset KST = ZoneOffset.ofHours(9);

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private RecommendationService service;

	/**
	 * <pre>
	 * 등심 G BOM 냉장, N=2, 돈까스 1인분 150, 거래처 L=1 R=1, 상품 1팩 = 1000 g
	 *   판매 돈까스 10-03 ~ 10-09 매일 10          → 소비 1500/일 (이력 7일 &lt; 28 → DAYS_N)
	 *   실사 10-09 00:00 = 4000                     → 추정 재고 4000 − 1500(10-09) = 2500, 소진일 10-12
	 *   예측(요일 평균, 이력 14일 미만 → 최근 평균) 10/일 → 하루 예상 소비 1500, timesfm 없음 → dow_avg
	 *   SS  = 최근 7일 평균 1500 × 2 = 3000        ROP = 1500 × 1 + 3000 = 4500  → 2500 ≤ 4500 발주
	 *   S   = 1500 × (1 + 1) + 3000 = 6000        raw = 6000 − 2500 = 3500 → 포장 1000 올림 = 4000
	 * </pre>
	 */
	@Test
	void 손계산_DAYS_N_발주() {
		long store = insertStore(jdbc, "손계산");
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

		RecommendationRunResponse run = service.run(store, T);

		assertThat(run.runDate()).isEqualTo("2026-10-10");
		assertThat(run.createdCount()).isEqualTo(1);
		assertThat(run.existingCount()).isZero();
		assertThat(run.recommendations()).hasSize(1);
		RecommendationResponse r = run.recommendations().get(0);
		assertThat(r.ingredientId()).isEqualTo(String.valueOf(등심));
		assertThat(r.ingredientName()).isEqualTo("등심");
		assertThat(r.estStock()).isEqualByComparingTo("2500.000");
		assertThat(r.onOrder()).isEqualByComparingTo("0");
		assertThat(r.leadTimeDemand()).isEqualByComparingTo("1500.000");
		assertThat(r.safetyStock()).isEqualByComparingTo("3000.000");
		assertThat(r.reorderPoint()).isEqualByComparingTo("4500.000");
		assertThat(r.recommendedQty()).isEqualByComparingTo("4000.000");
		assertThat(r.cycleDaysUsed()).isEqualByComparingTo("1.00");
		assertThat(r.priceRatio()).isNull();
		assertThat(r.priceLevel()).isNull();
		assertThat(r.status()).isEqualTo(RecommendationStatus.PENDING);
		assertThat(r.forecastModel()).isEqualTo("dow_avg");
		assertThat(r.ssMethod()).isEqualTo(SafetyStockMethod.DAYS_N);
		assertThat(r.runoutDate()).isEqualTo("2026-10-12");
		assertThat(r.nextOrderDate()).isEqualTo("2026-10-11"); // 다음 도착일 10-12 에 맞춰 주문할 날 (L=1)
		assertThat(r.supplierProductId()).isEqualTo(String.valueOf(product));
		assertThat(r.needsReview()).isFalse();
		assertThat(r.reviewReasons()).isEmpty();
		assertThat(r.reason())
				.isEqualTo("현재 추정 재고 2500g, 입고 예정 0g. 배송까지 1일간 예상 소비 1500g, 안전재고 3000g. "
						+ "10월 12일 소진 예상. 오늘 주문하면 10월 11일 도착.");

		// DB 에도 forecast_model · ss_method 가 남는다
		Map<String, Object> row = jdbc.queryForMap(
				"SELECT forecast_model, ss_method, status FROM recommendation WHERE store_id = ? AND ingredient_id = ?",
				store, 등심);
		assertThat(row.get("forecast_model")).isEqualTo("dow_avg");
		assertThat(row.get("ss_method")).isEqualTo("DAYS_N");
		assertThat(row.get("status")).isEqualTo("PENDING");
	}

	@Test
	void 같은_날_두_번_실행해도_1건() {
		long store = insertStore(jdbc, "두번");
		long 등심 = insertIngredient(jdbc, store, "등심", "G", "BOM", "CHILLED", "2", false);
		long 돈까스 = insertMenu(jdbc, store, "돈까스");
		insertRecipe(jdbc, store, 돈까스, 등심, "150", "MANUAL");
		for (int i = 3; i <= 9; i++) {
			insertSale(jdbc, store, LocalDate.of(2026, 10, i), 돈까스, "10");
		}
		insertCount(jdbc, store, 등심, OffsetDateTime.of(2026, 10, 9, 0, 0, 0, 0, KST), "4000");

		RecommendationRunResponse first = service.run(store, T);
		long firstId = Long.parseLong(first.recommendations().get(0).id());

		// 두 번째 실행 전에 실사가 바뀌어도 기존 건을 둔다 (하루 한 재료 한 건)
		insertCount(jdbc, store, 등심, OffsetDateTime.of(2026, 10, 9, 1, 0, 0, 0, KST), "100");
		RecommendationRunResponse second = service.run(store, T);

		assertThat(first.createdCount()).isEqualTo(1);
		assertThat(second.createdCount()).isZero();
		assertThat(second.existingCount()).isEqualTo(1);
		assertThat(second.recommendations()).hasSize(1);
		assertThat(second.recommendations().get(0).id()).isEqualTo(String.valueOf(firstId));
		assertThat(second.recommendations().get(0).estStock()).isEqualByComparingTo("2500.000");
		assertThat(countRecommendations(jdbc, store, T)).isEqualTo(1);
		assertThat(service.list(store, T)).hasSize(1);
	}

	/**
	 * 재료 셋 중 등심은 오늘 이미 만들어 두었다(수량 1234). 나머지 둘은 배치 INSERT 한 번으로 들어가고 기존 건은 그대로다.
	 * 빵가루 · 양배추: 소비 300 · 800/일, 실사 100 → 추정 재고 0 (음수 보정) → 발주. 대표 상품이 없어 SUPPLIER_UNSET.
	 */
	@Test
	void 여러_재료를_한_번에_만들고_이미_있는_건은_둔다() {
		long store = insertStore(jdbc, "배치");
		long 등심 = insertIngredient(jdbc, store, "등심", "G", "BOM", "CHILLED", "2", false);
		long 빵가루 = insertIngredient(jdbc, store, "빵가루", "G", "BOM", "AMBIENT", "2", false);
		long 양배추 = insertIngredient(jdbc, store, "양배추", "G", "BOM", "CHILLED", "2", false);
		long 돈까스 = insertMenu(jdbc, store, "돈까스");
		insertRecipe(jdbc, store, 돈까스, 등심, "150", "MANUAL");
		insertRecipe(jdbc, store, 돈까스, 빵가루, "30", "MANUAL");
		insertRecipe(jdbc, store, 돈까스, 양배추, "80", "MANUAL");
		for (int i = 3; i <= 9; i++) {
			insertSale(jdbc, store, LocalDate.of(2026, 10, i), 돈까스, "10");
		}
		OffsetDateTime countedAt = OffsetDateTime.of(2026, 10, 9, 0, 0, 0, 0, KST);
		insertCount(jdbc, store, 등심, countedAt, "4000");
		insertCount(jdbc, store, 빵가루, countedAt, "100");
		insertCount(jdbc, store, 양배추, countedAt, "100");
		long existingId = insertRecommendation(jdbc, store, 등심, T, "1234");

		RecommendationRunResponse run = service.run(store, T);

		assertThat(run.createdCount()).isEqualTo(2);
		assertThat(run.existingCount()).isEqualTo(1);
		assertThat(run.noOrderCount()).isZero();
		assertThat(run.recommendations()).extracting(RecommendationResponse::ingredientId)
				.containsExactly(String.valueOf(등심), String.valueOf(빵가루), String.valueOf(양배추));
		RecommendationResponse kept = run.recommendations().get(0);
		assertThat(kept.id()).isEqualTo(String.valueOf(existingId));
		assertThat(kept.recommendedQty()).isEqualByComparingTo("1234");
		assertThat(run.recommendations().get(1).estStock()).isEqualByComparingTo("0");
		assertThat(run.recommendations().get(1).reviewReasons()).containsExactly(ReviewReason.SUPPLIER_UNSET);
		assertThat(run.recommendations().get(2).safetyStock()).isEqualByComparingTo("1600.000");
		assertThat(countRecommendations(jdbc, store, T)).isEqualTo(3);

		// 다시 돌리면 셋 다 기존 건 — 새로 만들지 않는다
		RecommendationRunResponse again = service.run(store, T);
		assertThat(again.createdCount()).isZero();
		assertThat(again.existingCount()).isEqualTo(3);
		assertThat(countRecommendations(jdbc, store, T)).isEqualTo(3);
	}

	/**
	 * 소스 ML BOM 의 레시피 줄이 AI 추정 → UNANSWERED. 실사가 없어 NEEDS_COUNT. 대표 상품이 없어 SUPPLIER_UNSET (L=R=1 기본).
	 * 등심(MANUAL · 실사 있음 · 거래처 설정 있음)은 확인 필요가 아니다.
	 */
	@Test
	void 추정값이_섞인_재료는_needsReview() {
		long store = insertStore(jdbc, "추정");
		long 등심 = insertIngredient(jdbc, store, "등심", "G", "BOM", "CHILLED", "2", false);
		long 소스 = insertIngredient(jdbc, store, "소스", "ML", "BOM", "AMBIENT", "1", false);
		long 돈까스 = insertMenu(jdbc, store, "돈까스");
		insertRecipe(jdbc, store, 돈까스, 등심, "150", "MANUAL");
		insertRecipe(jdbc, store, 돈까스, 소스, "30", "AI");
		long supplier = insertSupplier(jdbc, store, "정육", 1, 1);
		setPrimaryProduct(jdbc, store, 등심, insertSupplierProduct(jdbc, store, supplier, 등심, "1000", null));
		for (int i = 3; i <= 9; i++) {
			insertSale(jdbc, store, LocalDate.of(2026, 10, i), 돈까스, "10");
		}
		insertCount(jdbc, store, 등심, OffsetDateTime.of(2026, 10, 9, 0, 0, 0, 0, KST), "4000");

		List<RecommendationResponse> list = service.run(store, T).recommendations();

		assertThat(list).hasSize(2);
		RecommendationResponse 등심줄 = list.get(0);
		RecommendationResponse 소스줄 = list.get(1);
		assertThat(등심줄.ingredientName()).isEqualTo("등심");
		assertThat(등심줄.needsReview()).isFalse();
		assertThat(소스줄.ingredientName()).isEqualTo("소스");
		assertThat(소스줄.needsReview()).isTrue();
		assertThat(소스줄.reviewReasons())
				.containsExactly(ReviewReason.UNANSWERED, ReviewReason.NEEDS_COUNT, ReviewReason.SUPPLIER_UNSET);
		assertThat(소스줄.supplierProductId()).isNull();
		// 실사 없음 → 0 기준 − 소비 → 0 으로 보정
		assertThat(소스줄.estStock()).isEqualByComparingTo("0");
	}

	/**
	 * 거래처 L · R 이 비어 있어도(영수증으로 생긴 거래처) 1일 · 1일로 계산하고 SUPPLIER_UNSET 을 단다.
	 */
	@Test
	void 거래처_리드타임이_없으면_기본값으로_계산하고_SUPPLIER_UNSET() {
		long store = insertStore(jdbc, "거래처미설정");
		long 등심 = insertIngredient(jdbc, store, "등심", "G", "BOM", "CHILLED", "2", false);
		long 돈까스 = insertMenu(jdbc, store, "돈까스");
		insertRecipe(jdbc, store, 돈까스, 등심, "150", "MANUAL");
		long supplier = insertSupplier(jdbc, store, "영수증거래처", null, null);
		long product = insertSupplierProduct(jdbc, store, supplier, 등심, null, null);
		setPrimaryProduct(jdbc, store, 등심, product);
		for (int i = 3; i <= 9; i++) {
			insertSale(jdbc, store, LocalDate.of(2026, 10, i), 돈까스, "10");
		}
		insertCount(jdbc, store, 등심, OffsetDateTime.of(2026, 10, 9, 0, 0, 0, 0, KST), "4000");

		RecommendationResponse r = service.run(store, T).recommendations().get(0);

		assertThat(r.supplierProductId()).isEqualTo(String.valueOf(product));
		assertThat(r.reviewReasons()).containsExactly(ReviewReason.SUPPLIER_UNSET);
		// 포장 환산을 몰라 올림 없음: raw 3500 그대로
		assertThat(r.recommendedQty()).isEqualByComparingTo("3500.000");
	}

	/**
	 * <pre>
	 * 이력 30일(09-10 ~ 10-09) 매일 10 → 소비 1500/일 일정 → STAT, σ = 0 → SS 0
	 * timesfm 10-11 · 10-12 = 20 → 하루 예상 소비 3000, forecast_model = timesfm
	 * 실사 10-09 = 2000 → 추정 재고 500. 입고 예정 2팩 × 1000 = 2000
	 *   ROP = 3000 × 1 + 0 = 3000 → 500 + 2000 = 2500 ≤ 3000 발주
	 *   S = 3000 × 2 = 6000, raw = 6000 − 2500 = 3500 → 포장 올림 4000
	 * </pre>
	 */
	@Test
	void 이력_28일_이상이면_STAT_timesfm_있으면_timesfm_입고_예정_반영() {
		long store = insertStore(jdbc, "STAT");
		long 등심 = insertIngredient(jdbc, store, "등심", "G", "BOM", "FROZEN", "2", false);
		long 돈까스 = insertMenu(jdbc, store, "돈까스");
		insertRecipe(jdbc, store, 돈까스, 등심, "150", "MANUAL");
		long supplier = insertSupplier(jdbc, store, "정육", 1, 1);
		long product = insertSupplierProduct(jdbc, store, supplier, 등심, "1000", null);
		setPrimaryProduct(jdbc, store, 등심, product);
		for (LocalDate d = LocalDate.of(2026, 9, 10); !d.isAfter(LocalDate.of(2026, 10, 9)); d = d.plusDays(1)) {
			insertSale(jdbc, store, d, 돈까스, "10");
		}
		insertCount(jdbc, store, 등심, OffsetDateTime.of(2026, 10, 9, 0, 0, 0, 0, KST), "2000");
		insertForecast(jdbc, store, 돈까스, T.plusDays(1), "20");
		insertForecast(jdbc, store, 돈까스, T.plusDays(2), "20");
		long po = insertPurchaseOrder(jdbc, store, supplier);
		insertPendingPoItem(jdbc, store, po, product, 등심, "2");

		RecommendationResponse r = service.run(store, T).recommendations().get(0);

		assertThat(r.ssMethod()).isEqualTo(SafetyStockMethod.STAT);
		assertThat(r.safetyStock()).isEqualByComparingTo("0");
		assertThat(r.forecastModel()).isEqualTo("timesfm");
		assertThat(r.estStock()).isEqualByComparingTo("500.000");
		assertThat(r.onOrder()).isEqualByComparingTo("2000.000");
		assertThat(r.leadTimeDemand()).isEqualByComparingTo("3000.000");
		assertThat(r.reorderPoint()).isEqualByComparingTo("3000.000");
		assertThat(r.recommendedQty()).isEqualByComparingTo("4000.000");
	}

	/**
	 * <pre>
	 * 이력 27일(09-13 ~ 10-09): 09-13 ~ 10-02 매일 20 · 10-03 ~ 10-09 매일 10 → 소비 3000 → 1500 (σ ≠ 0)
	 * 28일 미만이라 STAT 대신 DAYS_N → SS = 최근 7일 평균 1500 × N 2 = 3000
	 * 실사 10-09 = 4000 → 추정 재고 2500 ≤ ROP(≥ 3000) → 발주
	 * </pre>
	 */
	@Test
	void 이력_27일이면_STAT_대신_DAYS_N() {
		long store = insertStore(jdbc, "27일");
		long 등심 = insertIngredient(jdbc, store, "등심", "G", "BOM", "CHILLED", "2", false);
		long 돈까스 = insertMenu(jdbc, store, "돈까스");
		insertRecipe(jdbc, store, 돈까스, 등심, "150", "MANUAL");
		long supplier = insertSupplier(jdbc, store, "정육", 1, 1);
		long product = insertSupplierProduct(jdbc, store, supplier, 등심, "1000", null);
		setPrimaryProduct(jdbc, store, 등심, product);
		for (LocalDate d = LocalDate.of(2026, 9, 13); !d.isAfter(LocalDate.of(2026, 10, 9)); d = d.plusDays(1)) {
			insertSale(jdbc, store, d, 돈까스, d.isBefore(LocalDate.of(2026, 10, 3)) ? "20" : "10");
		}
		insertCount(jdbc, store, 등심, OffsetDateTime.of(2026, 10, 9, 0, 0, 0, 0, KST), "4000");

		RecommendationRunResponse run = service.run(store, T);

		assertThat(run.recommendations()).hasSize(1);
		RecommendationResponse r = run.recommendations().get(0);
		assertThat(r.ssMethod()).isEqualTo(SafetyStockMethod.DAYS_N);
		assertThat(r.safetyStock()).isEqualByComparingTo("3000.000");
		assertThat(jdbc.queryForObject(
				"SELECT ss_method FROM recommendation WHERE store_id = ? AND ingredient_id = ?", String.class, store, 등심))
				.isEqualTo("DAYS_N");
	}

	@Test
	void 발주가_필요_없으면_만들지_않고_주기형은_뺀다() {
		long store = insertStore(jdbc, "불필요");
		long 등심 = insertIngredient(jdbc, store, "등심", "G", "BOM", "CHILLED", "1", false);
		insertIngredient(jdbc, store, "김치", "G", "CYCLE", null, "1", false);
		long 돈까스 = insertMenu(jdbc, store, "돈까스");
		insertRecipe(jdbc, store, 돈까스, 등심, "150", "MANUAL");
		for (int i = 3; i <= 9; i++) {
			insertSale(jdbc, store, LocalDate.of(2026, 10, i), 돈까스, "10");
		}
		// 재고 넉넉: 100000 − 1500 ≫ ROP 3000
		insertCount(jdbc, store, 등심, OffsetDateTime.of(2026, 10, 9, 0, 0, 0, 0, KST), "100000");

		RecommendationRunResponse run = service.run(store, T);

		assertThat(run.createdCount()).isZero();
		assertThat(run.noOrderCount()).isEqualTo(1);
		assertThat(run.recommendations()).isEmpty();
		assertThat(countRecommendations(jdbc, store, T)).isZero();
	}

	/**
	 * 판매 · 실사 기록이 없는 새 가게(지라 219): 추정 재고 0, 하루 예상 소비 0 → ROP 0, 0 ≤ 0 이라 "발주" 판단이지만
	 * S − 위치 = 0 이라 수량 0. 수량 0 PENDING 은 사장님이 할 일이 없으므로 만들지 않고 noOrder 로 센다.
	 */
	@Test
	void 판매_재고_기록이_없는_가게는_수량_0_추천을_만들지_않는다() {
		long store = insertStore(jdbc, "새가게");
		long 등심 = insertIngredient(jdbc, store, "등심", "G", "BOM", "CHILLED", "1", false);
		long 양파 = insertIngredient(jdbc, store, "양파", "G", "BOM", "FRESH", "1", false);
		long 돈까스 = insertMenu(jdbc, store, "돈까스");
		insertRecipe(jdbc, store, 돈까스, 등심, "150", "MANUAL");
		insertRecipe(jdbc, store, 돈까스, 양파, "30", "MANUAL");

		RecommendationRunResponse run = service.run(store, T);

		assertThat(run.createdCount()).isZero();
		assertThat(run.noOrderCount()).isEqualTo(2);
		assertThat(run.recommendations()).isEmpty();
		assertThat(countRecommendations(jdbc, store, T)).isZero();
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM recommendation WHERE store_id = ? AND status = 'PENDING'", Long.class, store)).isZero();
	}

	/**
	 * 판매는 있는데 실사만 없는 가게(지라 219 의 반대 경우): 추정 재고는 0(NO_COUNT)이지만 예측 소비가 있어 목표 재고 &gt; 0,
	 * 수량이 양수라 추천을 만들고 NEEDS_COUNT 로 표시한다. 수량 0 거르기가 이 경우를 삼키면 안 된다.
	 */
	@Test
	void 판매는_있고_실사만_없으면_추천을_만들고_NEEDS_COUNT() {
		long store = insertStore(jdbc, "실사없음");
		long 등심 = insertIngredient(jdbc, store, "등심", "G", "BOM", "CHILLED", "1", false);
		long 돈까스 = insertMenu(jdbc, store, "돈까스");
		insertRecipe(jdbc, store, 돈까스, 등심, "150", "MANUAL");
		for (int i = 3; i <= 9; i++) {
			insertSale(jdbc, store, LocalDate.of(2026, 10, i), 돈까스, "10");
		}

		RecommendationRunResponse run = service.run(store, T);

		assertThat(run.createdCount()).isEqualTo(1);
		assertThat(run.noOrderCount()).isZero();
		RecommendationResponse r = run.recommendations().getFirst();
		assertThat(r.estStock()).isZero();
		assertThat(r.recommendedQty()).isPositive();
		assertThat(r.needsReview()).isTrue();
		assertThat(r.reviewReasons()).contains(ReviewReason.NEEDS_COUNT);
	}

	// ---------------------------------------------------------------- 배송 일정 · 휴무일 (3주 계획 4.4절, DECISIONS #89, S15P31A308-263)

	/**
	 * 월 · 수 · 금 배송 거래처, L=1. T=10-10(토) 주문 → 일요일은 배송 안 함 → 10-12(월) 도착, 다음은 10-14(수).
	 * 실제 L=2 · R=2. 소비 1500/일(이력 7일 → 평평한 평균), N=1 → SS 1500.
	 * <pre>
	 * 리드타임 수요 = 10-11 + 10-12 = 3000, ROP = 4500 → 추정 2500 ≤ 4500 발주
	 * 주기 수요     = 10-13 + 10-14 = 3000, S = 3000 + 3000 + 1500 = 7500, raw 5000 → 포장 1000 → 5000
	 * 다음 주문일   = 10-14 − L(1) = 10-13
	 * </pre>
	 */
	@Test
	void 배송_요일이_있으면_다음_배송까지_덮는다() {
		long store = insertStore(jdbc, "월수금");
		long 등심 = insertIngredient(jdbc, store, "등심", "G", "BOM", "CHILLED", "1", false);
		long 돈까스 = insertMenu(jdbc, store, "돈까스");
		insertRecipe(jdbc, store, 돈까스, 등심, "150", "MANUAL");
		long supplier = insertSupplier(jdbc, store, "정육", 1, 1, "{MON,WED,FRI}", null);
		long product = insertSupplierProduct(jdbc, store, supplier, 등심, "1000", null);
		setPrimaryProduct(jdbc, store, 등심, product);
		for (int i = 3; i <= 9; i++) {
			insertSale(jdbc, store, LocalDate.of(2026, 10, i), 돈까스, "10");
		}
		insertCount(jdbc, store, 등심, OffsetDateTime.of(2026, 10, 9, 0, 0, 0, 0, KST), "4000");

		RecommendationResponse r = service.run(store, T).recommendations().get(0);

		assertThat(r.leadTimeDemand()).isEqualByComparingTo("3000.000");
		assertThat(r.safetyStock()).isEqualByComparingTo("1500.000");
		assertThat(r.reorderPoint()).isEqualByComparingTo("4500.000");
		assertThat(r.recommendedQty()).isEqualByComparingTo("5000.000");
		assertThat(r.cycleDaysUsed()).isEqualByComparingTo("2.00");
		assertThat(r.nextOrderDate()).isEqualTo("2026-10-13");
		assertThat(r.reason()).contains("배송까지 2일간 예상 소비 3000g").endsWith("오늘 주문하면 10월 12일 도착.");
	}

	/**
	 * 휴무일은 소비 0 (지라 263). L=2 · R=1, 10-11(일) 휴무.
	 * <pre>
	 * 리드타임 수요 = 10-11(휴무 0) + 10-12(1500) = 1500, SS 1500, ROP 3000 → 추정 2500 ≤ 3000 발주
	 * 주기 수요     = 10-13 = 1500, S = 1500 + 1500 + 1500 = 4500, raw 2000 → 포장 1000 → 2000
	 * (휴무가 없으면 리드타임 수요 3000 · ROP 4500 · S 6000 · raw 3500 → 4000)
	 * </pre>
	 */
	@Test
	void 예측_구간의_휴무일은_소비_0으로_본다() {
		long store = insertStore(jdbc, "휴무");
		long 등심 = insertIngredient(jdbc, store, "등심", "G", "BOM", "CHILLED", "1", false);
		long 돈까스 = insertMenu(jdbc, store, "돈까스");
		insertRecipe(jdbc, store, 돈까스, 등심, "150", "MANUAL");
		long supplier = insertSupplier(jdbc, store, "정육", 2, 1);
		long product = insertSupplierProduct(jdbc, store, supplier, 등심, "1000", null);
		setPrimaryProduct(jdbc, store, 등심, product);
		for (int i = 3; i <= 9; i++) {
			insertSale(jdbc, store, LocalDate.of(2026, 10, i), 돈까스, "10");
		}
		insertCount(jdbc, store, 등심, OffsetDateTime.of(2026, 10, 9, 0, 0, 0, 0, KST), "4000");
		insertClosedDay(jdbc, store, LocalDate.of(2026, 10, 11));

		RecommendationResponse r = service.run(store, T).recommendations().get(0);

		assertThat(r.leadTimeDemand()).isEqualByComparingTo("1500.000");
		assertThat(r.reorderPoint()).isEqualByComparingTo("3000.000");
		assertThat(r.recommendedQty()).isEqualByComparingTo("2000.000");
		assertThat(r.reason()).contains("배송까지 2일간 예상 소비 1500g");
	}

	// ---------------------------------------------------------------- 가격 연동 (3주 계획 4.8절, S15P31A308-72)

	/**
	 * 손계산 사례(등심 · 소비 1500/일 · 추정 재고 2500 · SS 3000)에 거래처 L=1 R=2, 냉동 price_sensitive 를 켠다.
	 * 조정이 없으면 S = 1500 × (2 + 1) + 3000 = 7500, raw = 5000 → 5000.
	 *
	 * @param currentPackPrice 10-09(어제) 1kg 값. 기준은 09-20 · 09-27 · 10-04 의 4,000원 → 기준 단가 4원/g
	 */
	private long 가격연동_가게(String name, int currentPackPrice, int baseSampleCount) {
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
		LocalDate[] baseDays = {LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 27), LocalDate.of(2026, 10, 4)};
		for (int i = 0; i < baseSampleCount; i++) {
			insertPriceHistory(jdbc, store, product, baseDays[i], 4000, "RECEIVE");
		}
		insertPriceHistory(jdbc, store, product, LocalDate.of(2026, 10, 9), currentPackPrice, "RECEIVE");
		return store;
	}

	/**
	 * <pre>
	 * 현재 4,600원/kg = 4.6원/g, 기준 4원/g → 지수 1.15 ≥ 1.10 비쌈 → R' = max(1, 2 × 0.5) = 1
	 * S = 1500 × (1 + 1) + 3000 = 6000, raw = 6000 − 2500 = 3500 → 포장 1000 올림 4000  (조정 전 5000)
	 * SS · ROP 는 그대로 3000 · 4500. 이유 끝에 "단가 4.6원/g, 최근 8주보다 15% 높음. 2일치만 주문."
	 * </pre>
	 */
	@Test
	void 비쌀_때_R을_절반으로_줄여_적게_산다() {
		long store = 가격연동_가게("비쌈", 4600, 3);

		RecommendationResponse r = service.run(store, T).recommendations().get(0);

		assertThat(r.priceRatio()).isEqualByComparingTo("1.1500");
		assertThat(r.priceLevel()).isEqualTo(PriceLevel.HIGH);
		assertThat(r.cycleDaysUsed()).isEqualByComparingTo("1.00");
		assertThat(r.safetyStock()).isEqualByComparingTo("3000.000");
		assertThat(r.reorderPoint()).isEqualByComparingTo("4500.000");
		assertThat(r.recommendedQty()).isEqualByComparingTo("4000.000");
		assertThat(r.reason()).isEqualTo(
				"현재 추정 재고 2500g, 입고 예정 0g. 배송까지 1일간 예상 소비 1500g, 안전재고 3000g. "
						+ "10월 12일 소진 예상. 오늘 주문하면 10월 11일 도착. "
						+ "단가 4.6원/g, 최근 8주보다 15% 높음. 2일치만 주문.");
	}

	/**
	 * <pre>
	 * 현재 3,400원/kg = 3.4원/g → 지수 0.85 ≤ 0.90 쌈 → R' = 2 × 2 = 4
	 * S = 1500 × (4 + 1) + 3000 = 10500, raw = 10500 − 2500 = 8000 → 8000  (유통기한 · max_order_qty 없음)
	 * SS · ROP 는 그대로. 이유 끝에 "단가 3.4원/g, 최근 8주보다 15% 낮음. 5일치 미리 주문."
	 * </pre>
	 */
	@Test
	void 쌀_때_R을_두_배로_늘려_미리_산다() {
		long store = 가격연동_가게("쌈", 3400, 3);

		RecommendationResponse r = service.run(store, T).recommendations().get(0);

		assertThat(r.priceRatio()).isEqualByComparingTo("0.8500");
		assertThat(r.priceLevel()).isEqualTo(PriceLevel.LOW);
		assertThat(r.cycleDaysUsed()).isEqualByComparingTo("4.00");
		assertThat(r.safetyStock()).isEqualByComparingTo("3000.000");
		assertThat(r.reorderPoint()).isEqualByComparingTo("4500.000");
		assertThat(r.recommendedQty()).isEqualByComparingTo("8000.000");
		assertThat(r.reason()).endsWith("오늘 주문하면 10월 11일 도착. 단가 3.4원/g, 최근 8주보다 15% 낮음. 5일치 미리 주문.");
	}

	/**
	 * 가격 연동 R' 도 배송 요일에 맞춘다 (MR 221 리뷰). 위 비쌈 사례에 월 · 수 · 금 배송을 더한다.
	 * <pre>
	 * T=10-10(토), L=1 → 10-11(일) 배송 없음 → 10-12(월) 도착, L=2. 다음 도착 10-14(수), R=2
	 * 비쌈 R' = max(1, 2 × 0.5) = 1 → 10-13(화) 배송 없음 → 10-14(수), R' = 2 (조정 전과 같다 — 수요일보다 일찍 받을 수 없다)
	 * 리드타임 수요 = 10-11 + 10-12 = 3000, 주기 수요 = 10-13 + 10-14 = 3000, SS 3000 → S = 9000, raw 6500 → 7000
	 * 다음 주문일 = 10-14 − L(1) = 10-13
	 * </pre>
	 * 배송 요일에 맞추지 않으면 예측 구간이 10-13 에서 끝나 수요일 도착 전까지의 소비 하루가 빠진다.
	 */
	@Test
	void 가격_연동_주기도_배송_요일에_맞춘다() {
		long store = 가격연동_가게("비쌈월수금", 4600, 3);
		jdbc.update("UPDATE supplier SET delivery_days = CAST(? AS text[]) WHERE store_id = ?", "{MON,WED,FRI}", store);

		RecommendationResponse r = service.run(store, T).recommendations().get(0);

		assertThat(r.priceLevel()).isEqualTo(PriceLevel.HIGH);
		assertThat(r.cycleDaysUsed()).isEqualByComparingTo("2.00");
		assertThat(r.leadTimeDemand()).isEqualByComparingTo("3000.000");
		assertThat(r.recommendedQty()).isEqualByComparingTo("7000.000");
		assertThat(r.nextOrderDate()).isEqualTo("2026-10-13");
	}

	/**
	 * 기준 기간 표본이 2건뿐이면 지수 1(보통, TOO_FEW) → R 그대로 2 → 조정 전 값 5000. 이유에 가격 줄 없음.
	 * 단가가 4,600원으로 비싸 보여도 "평소"를 모르니 조정하지 않는다.
	 */
	@Test
	void 단가_이력이_3건_미만이면_조정하지_않는다() {
		long store = 가격연동_가게("이력부족", 4600, 2);

		RecommendationResponse r = service.run(store, T).recommendations().get(0);

		assertThat(r.priceRatio()).isEqualByComparingTo("1.0000");
		assertThat(r.priceLevel()).isEqualTo(PriceLevel.NORMAL);
		assertThat(r.cycleDaysUsed()).isEqualByComparingTo("2.00");
		assertThat(r.recommendedQty()).isEqualByComparingTo("5000.000");
		assertThat(r.reason()).endsWith("오늘 주문하면 10월 11일 도착.");
		assertThat(r.reason()).doesNotContain("단가");
	}

	/** price_sensitive 가 꺼진 재료는 이력이 충분해도 지수를 계산하지 않는다 (price_ratio · price_level null). */
	@Test
	void 가격_연동을_끈_재료는_이력이_있어도_조정하지_않는다() {
		long store = 가격연동_가게("끔", 4600, 3);
		jdbc.update("UPDATE ingredient SET price_sensitive = false WHERE store_id = ?", store);

		RecommendationResponse r = service.run(store, T).recommendations().get(0);

		assertThat(r.priceRatio()).isNull();
		assertThat(r.priceLevel()).isNull();
		assertThat(r.cycleDaysUsed()).isEqualByComparingTo("2.00");
		assertThat(r.recommendedQty()).isEqualByComparingTo("5000.000");
		assertThat(r.reason()).doesNotContain("단가");
	}

	@Test
	void 다른_가게의_데이터는_섞이지_않는다() {
		long mine = insertStore(jdbc, "내가게");
		long other = insertStore(jdbc, "남의가게");
		long 등심 = insertIngredient(jdbc, mine, "등심", "G", "BOM", "CHILLED", "2", false);
		long 돈까스 = insertMenu(jdbc, mine, "돈까스");
		insertRecipe(jdbc, mine, 돈까스, 등심, "150", "MANUAL");
		for (int i = 3; i <= 9; i++) {
			insertSale(jdbc, mine, LocalDate.of(2026, 10, i), 돈까스, "10");
		}
		insertCount(jdbc, mine, 등심, OffsetDateTime.of(2026, 10, 9, 0, 0, 0, 0, KST), "4000");

		long 남의등심 = insertIngredient(jdbc, other, "등심", "G", "BOM", "CHILLED", "2", false);
		long 남의돈까스 = insertMenu(jdbc, other, "돈까스");
		insertRecipe(jdbc, other, 남의돈까스, 남의등심, "150", "AI");

		RecommendationRunResponse run = service.run(mine, T);

		assertThat(run.recommendations()).hasSize(1);
		assertThat(run.recommendations().get(0).ingredientId()).isEqualTo(String.valueOf(등심));
		assertThat(run.recommendations().get(0).reviewReasons()).doesNotContain(ReviewReason.UNANSWERED);
		assertThat(countRecommendations(jdbc, other, T)).isZero();
		assertThat(service.list(other, T)).isEmpty();
	}
}
