package com.zigzaego.api.inventory;

import static com.zigzaego.api.inventory.InventoryTestData.insertClosedDay;
import static com.zigzaego.api.inventory.InventoryTestData.insertCount;
import static com.zigzaego.api.inventory.InventoryTestData.insertDoc;
import static com.zigzaego.api.inventory.InventoryTestData.insertDocLine;
import static com.zigzaego.api.inventory.InventoryTestData.insertIngredient;
import static com.zigzaego.api.inventory.InventoryTestData.insertMenu;
import static com.zigzaego.api.inventory.InventoryTestData.insertPoItem;
import static com.zigzaego.api.inventory.InventoryTestData.insertPurchaseOrder;
import static com.zigzaego.api.inventory.InventoryTestData.insertRecipe;
import static com.zigzaego.api.inventory.InventoryTestData.insertSale;
import static com.zigzaego.api.inventory.InventoryTestData.insertStore;
import static com.zigzaego.api.inventory.InventoryTestData.insertSupplier;
import static com.zigzaego.api.inventory.InventoryTestData.insertSupplierProduct;
import static org.assertj.core.api.Assertions.assertThat;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.inventory.dto.InventoryEstimateResponse;
import com.zigzaego.api.inventory.service.InventoryEstimateService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 추정 재고 손계산 검증. 기준일 T = 2026-10-10(토), 가게 시간대 Asia/Seoul. 기대값은 각 테스트 주석의 손계산 숫자 리터럴이다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class InventoryEstimateServiceTest {

	private static final LocalDate T = LocalDate.of(2026, 10, 10);
	private static final ZoneOffset KST = ZoneOffset.ofHours(9);

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private InventoryEstimateService service;

	/**
	 * <pre>
	 * 등심 G BOM (돈까스 1인분 150 G)
	 *   실사 10-08 09:00 = 10000
	 *   판매 돈까스 10-07 100(실사 전, 무시) · 10-08 10 · 10-09 20 · 10-10 5  → 소비 1500 + 3000 + 750 = 5250
	 *   입고 ① 발주 줄 10-09 12:00 받음 2팩 × 1000 = 2000 / 10-07 받음 5팩(실사 전, 무시)
	 *        ② RECEIVE 문서 10-10 확인 줄 500 (상품 미연결 → 그대로)
	 *        무시: PAST 문서 99999 · RECEIVE 문서의 PENDING 줄 777 · 발주서 연결 문서 888 · PARSED(미확인) 문서 999 · IGNORED 줄 666
	 *   → 10000 + 2000 + 500 − 5250 = 7250.000
	 *   소진일: 이력 10-07 ~ 10-09 (첫 판매일부터, 요일 표본 1개씩 → 최근 평균) = (15000 + 1500 + 3000) / 3 = 6500/일
	 *           7250 − 6500 = 750 (10-11) → −5750 (10-12) → 2026-10-12
	 *
	 * 콜라 EA 1:1 (콜라 메뉴 1개당 1)
	 *   실사 10-09 23:00 = 50 — 실사 당일 판매부터 뺀다
	 *   판매 콜라 10-09 10 · 10-10 5 → 50 − 15 = 35.000
	 *   소진일: 이력 10-07 ~ 10-09 평균 10 / 3 = 3.333/일 → 11일째(36.67 ≥ 35) → 2026-10-21
	 * </pre>
	 */
	@Test
	void 사흘치_BOM과_일대일_재료가_손계산과_같다() {
		long store = insertStore(jdbc, "손계산");
		long 등심 = insertIngredient(jdbc, store, "등심", "G", "BOM");
		long 콜라재료 = insertIngredient(jdbc, store, "콜라", "EA", "ONE_TO_ONE");
		long 돈까스 = insertMenu(jdbc, store, "돈까스");
		long 콜라 = insertMenu(jdbc, store, "콜라");
		insertRecipe(jdbc, store, 돈까스, 등심, "150");
		insertRecipe(jdbc, store, 콜라, 콜라재료, "1");

		insertCount(jdbc, store, 등심, OffsetDateTime.of(2026, 10, 8, 9, 0, 0, 0, KST), "10000");
		insertCount(jdbc, store, 콜라재료, OffsetDateTime.of(2026, 10, 9, 23, 0, 0, 0, KST), "50");
		insertSale(jdbc, store, LocalDate.of(2026, 10, 7), 돈까스, "100");
		insertSale(jdbc, store, LocalDate.of(2026, 10, 8), 돈까스, "10");
		insertSale(jdbc, store, LocalDate.of(2026, 10, 9), 돈까스, "20");
		insertSale(jdbc, store, LocalDate.of(2026, 10, 10), 돈까스, "5");
		insertSale(jdbc, store, LocalDate.of(2026, 10, 9), 콜라, "10");
		insertSale(jdbc, store, LocalDate.of(2026, 10, 10), 콜라, "5");

		long 거래처 = insertSupplier(jdbc, store, "거래처");
		long 등심상품 = insertSupplierProduct(jdbc, store, 거래처, 등심, "1000");
		long 발주 = insertPurchaseOrder(jdbc, store, 거래처);
		insertPoItem(jdbc, store, 발주, 등심상품, 등심, "2", OffsetDateTime.of(2026, 10, 9, 12, 0, 0, 0, KST));
		insertPoItem(jdbc, store, 발주, 등심상품, 등심, "5", OffsetDateTime.of(2026, 10, 7, 12, 0, 0, 0, KST));

		long 입고문서 = insertDoc(jdbc, store, LocalDate.of(2026, 10, 10), "RECEIVE", "CONFIRMED", null);
		insertDocLine(jdbc, store, 입고문서, 1, 등심, "500", "CONFIRMED", null);
		insertDocLine(jdbc, store, 입고문서, 2, 등심, "666", "IGNORED", null);
		long 과거문서 = insertDoc(jdbc, store, LocalDate.of(2026, 10, 9), "PAST", "CONFIRMED", null);
		insertDocLine(jdbc, store, 과거문서, 1, 등심, "99999", "CONFIRMED", null);
		long 미확인줄문서 = insertDoc(jdbc, store, LocalDate.of(2026, 10, 9), "RECEIVE", "CONFIRMED", null);
		insertDocLine(jdbc, store, 미확인줄문서, 1, 등심, "777", "PENDING", null);
		long 발주연결문서 = insertDoc(jdbc, store, LocalDate.of(2026, 10, 9), "RECEIVE", "CONFIRMED", 발주);
		insertDocLine(jdbc, store, 발주연결문서, 1, 등심, "888", "CONFIRMED", null);
		long 미확인문서 = insertDoc(jdbc, store, LocalDate.of(2026, 10, 9), "RECEIVE", "PARSED", null);
		insertDocLine(jdbc, store, 미확인문서, 1, 등심, "999", "CONFIRMED", null);

		List<InventoryEstimateResponse> result = service.estimate(store, T);

		assertThat(lines(result)).containsExactly(
				"등심|G|BOM|7250.000|false|2026-10-12|COUNT",
				"콜라|EA|ONE_TO_ONE|35.000|false|2026-10-21|COUNT");
		assertThat(result.get(0).ingredientId()).isEqualTo(String.valueOf(등심));
		assertThat(result.get(0).lastCountedAt()).isEqualTo("2026-10-08T09:00:00+09:00");
	}

	@Test
	void 실사_이전_영수증은_재고에_더하지_않는다() {
		// 실사 100, PAST 문서 1000 → 그대로 100. kind 가 비어 있는(날짜 못 읽은) 문서도 안 더한다
		long store = insertStore(jdbc, "과거영수증");
		long 등심 = insertIngredient(jdbc, store, "등심", "G", "BOM");
		insertCount(jdbc, store, 등심, OffsetDateTime.of(2026, 10, 1, 9, 0, 0, 0, KST), "100");
		long 과거 = insertDoc(jdbc, store, LocalDate.of(2026, 10, 5), "PAST", "CONFIRMED", null);
		insertDocLine(jdbc, store, 과거, 1, 등심, "1000", "CONFIRMED", null);
		long 미분류 = insertDoc(jdbc, store, LocalDate.of(2026, 10, 6), null, "CONFIRMED", null);
		insertDocLine(jdbc, store, 미분류, 1, 등심, "1000", "CONFIRMED", null);

		assertThat(lines(service.estimate(store, T))).containsExactly("등심|G|BOM|100.000|false|null|COUNT");
	}

	@Test
	void 음수_재고는_0으로_자르고_실사_필요로_표시하며_오늘_소진이다() {
		// 실사 100, 판매 10 × 150 = 1500 → −1400 → 0 + 실사 필요, 소진일 = 오늘
		long store = insertStore(jdbc, "음수");
		long 등심 = insertIngredient(jdbc, store, "등심", "G", "BOM");
		long 돈까스 = insertMenu(jdbc, store, "돈까스");
		insertRecipe(jdbc, store, 돈까스, 등심, "150");
		insertCount(jdbc, store, 등심, OffsetDateTime.of(2026, 10, 8, 9, 0, 0, 0, KST), "100");
		insertSale(jdbc, store, LocalDate.of(2026, 10, 9), 돈까스, "10");

		assertThat(lines(service.estimate(store, T))).containsExactly("등심|G|BOM|0.000|true|2026-10-10|COUNT");
	}

	@Test
	void 실사가_없으면_기준_0에_실사_필요로_표시한다() {
		// 실사 없음: 0 + RECEIVE 500 − 판매 2 × 150 = 200. 실사 필요. 받은 날 이전 판매(10-04)도 뺀다 (기준점이 없으니 전체)
		long store = insertStore(jdbc, "실사없음");
		long 등심 = insertIngredient(jdbc, store, "등심", "G", "BOM");
		long 돈까스 = insertMenu(jdbc, store, "돈까스");
		insertRecipe(jdbc, store, 돈까스, 등심, "150");
		long 문서 = insertDoc(jdbc, store, LocalDate.of(2026, 10, 5), "RECEIVE", "CONFIRMED", null);
		insertDocLine(jdbc, store, 문서, 1, 등심, "800", "CONFIRMED", null);
		insertSale(jdbc, store, LocalDate.of(2026, 10, 4), 돈까스, "2");
		insertSale(jdbc, store, LocalDate.of(2026, 10, 6), 돈까스, "2");

		List<InventoryEstimateResponse> result = service.estimate(store, T);

		assertThat(lines(result)).containsExactly("등심|G|BOM|200.000|true|2026-10-12|NO_COUNT");
		// 소진일: 이력 10-04 ~ 10-09 6일 평균 600 / 6 = 100/일 → 10-11 100 → 10-12 0 → 2026-10-12
		assertThat(result.get(0).lastCountedAt()).isNull();
	}

	@Test
	void 소진일은_요일_평균으로_계산하고_휴무일은_건너뛴다() {
		// 오늘 08:00 실사 3000, 최근 28일 매일 돈까스 2 → 300/일(요일 표본 4개씩). 휴무일 T+3 → 열흘째 영업일 = T+11
		long store = insertStore(jdbc, "소진일");
		long 등심 = insertIngredient(jdbc, store, "등심", "G", "BOM");
		long 돈까스 = insertMenu(jdbc, store, "돈까스");
		insertRecipe(jdbc, store, 돈까스, 등심, "150");
		insertCount(jdbc, store, 등심, OffsetDateTime.of(2026, 10, 10, 8, 0, 0, 0, KST), "3000");
		for (int i = 28; i >= 1; i--) {
			insertSale(jdbc, store, T.minusDays(i), 돈까스, "2");
		}
		insertClosedDay(jdbc, store, T.plusDays(3));

		assertThat(lines(service.estimate(store, T))).containsExactly("등심|G|BOM|3000.000|false|2026-10-21|COUNT");
	}

	@Test
	void 판매_이력이_없으면_소진일이_null이다() {
		long store = insertStore(jdbc, "이력없음");
		long 등심 = insertIngredient(jdbc, store, "등심", "G", "BOM");
		insertCount(jdbc, store, 등심, OffsetDateTime.of(2026, 10, 1, 9, 0, 0, 0, KST), "500");

		assertThat(lines(service.estimate(store, T))).containsExactly("등심|G|BOM|500.000|false|null|COUNT");
	}

	/**
	 * <pre>
	 * 식용유 ML CYCLE. 매일 25그릇 판매 (T−28 ~ T)
	 *   입고 T−8 PAST 문서 1000 / T−3 RECEIVE 문서 1000 + 같은 날 발주 줄 1팩 × 500 → 한 건 1500
	 *   그릇당 = 1000 ÷ (T−8 ~ T−4 = 5일 × 25 = 125) = 8
	 *   추정 = 1500 − 8 × (T−3 ~ T = 4일 × 25 = 100) = 700.000
	 *   소진일: 하루 25그릇 × 8 = 200 → 700 − 200×3 = 100 → 4일째 → T+4
	 * </pre>
	 */
	@Test
	void 주기형은_판매가_있으면_그릇당_소비량으로_계산한다() {
		long store = insertStore(jdbc, "주기형판매");
		long 식용유 = insertIngredient(jdbc, store, "식용유", "ML", "CYCLE");
		long 돈까스 = insertMenu(jdbc, store, "돈까스");
		for (int i = 28; i >= 0; i--) {
			insertSale(jdbc, store, T.minusDays(i), 돈까스, "25");
		}
		long 과거 = insertDoc(jdbc, store, T.minusDays(8), "PAST", "CONFIRMED", null);
		insertDocLine(jdbc, store, 과거, 1, 식용유, "1000", "CONFIRMED", null);
		long 최근 = insertDoc(jdbc, store, T.minusDays(3), "RECEIVE", "CONFIRMED", null);
		insertDocLine(jdbc, store, 최근, 1, 식용유, "1000", "CONFIRMED", null);
		long 거래처 = insertSupplier(jdbc, store, "기름집");
		long 상품 = insertSupplierProduct(jdbc, store, 거래처, 식용유, "500");
		long 발주 = insertPurchaseOrder(jdbc, store, 거래처);
		insertPoItem(jdbc, store, 발주, 상품, 식용유, "1", T.minusDays(3).atTime(14, 0).atOffset(KST));

		assertThat(lines(service.estimate(store, T))).containsExactly(
				"식용유|ML|CYCLE|700.000|false|2026-10-14|CYCLE_SALES");
	}

	@Test
	void 주기형_음수_재고는_0으로_자르고_실사_필요로_표시한다() {
		// 그릇당 8, 마지막 입고 100 뒤 100그릇 → 100 − 800 < 0 → 0 + 실사 필요, 소진일 오늘
		long store = insertStore(jdbc, "주기형음수");
		long 식용유 = insertIngredient(jdbc, store, "식용유", "ML", "CYCLE");
		long 돈까스 = insertMenu(jdbc, store, "돈까스");
		for (int i = 28; i >= 0; i--) {
			insertSale(jdbc, store, T.minusDays(i), 돈까스, "25");
		}
		long 첫 = insertDoc(jdbc, store, T.minusDays(8), "PAST", "CONFIRMED", null);
		insertDocLine(jdbc, store, 첫, 1, 식용유, "1000", "CONFIRMED", null);
		long 둘 = insertDoc(jdbc, store, T.minusDays(3), "RECEIVE", "CONFIRMED", null);
		insertDocLine(jdbc, store, 둘, 1, 식용유, "100", "CONFIRMED", null);

		assertThat(lines(service.estimate(store, T))).containsExactly(
				"식용유|ML|CYCLE|0.000|true|2026-10-10|CYCLE_SALES");
	}

	@Test
	void 주기형은_사이에_판매가_없으면_평균_간격을_쓴다() {
		// 입고 T−10 · T−4 (간격 6일), 판매 없음 → 소진일 T−4 + 6 = T+2, 추정 재고 null
		long store = insertStore(jdbc, "주기형간격");
		long 식용유 = insertIngredient(jdbc, store, "식용유", "ML", "CYCLE");
		long 첫 = insertDoc(jdbc, store, T.minusDays(10), "PAST", "CONFIRMED", null);
		insertDocLine(jdbc, store, 첫, 1, 식용유, "1000", "CONFIRMED", null);
		long 둘 = insertDoc(jdbc, store, T.minusDays(4), "RECEIVE", "CONFIRMED", null);
		insertDocLine(jdbc, store, 둘, 1, 식용유, "1000", "CONFIRMED", null);

		assertThat(lines(service.estimate(store, T))).containsExactly(
				"식용유|ML|CYCLE|null|false|2026-10-12|CYCLE_INTERVAL");
	}

	@Test
	void 주기형은_영수증이_한_장이면_수집_중이다() {
		long store = insertStore(jdbc, "주기형수집중");
		long 식용유 = insertIngredient(jdbc, store, "식용유", "ML", "CYCLE");
		long 첫 = insertDoc(jdbc, store, T.minusDays(2), "RECEIVE", "CONFIRMED", null);
		insertDocLine(jdbc, store, 첫, 1, 식용유, "1000", "CONFIRMED", null);

		assertThat(lines(service.estimate(store, T))).containsExactly(
				"식용유|ML|CYCLE|null|false|null|CYCLE_COLLECTING");
	}

	// ---------------------------------------------------------------- 주기형 (S15P31A308-107)

	/**
	 * 설계 5.1절 주기형 소진식 — 생크림 손계산. 소진일 2026-10-17.
	 *
	 * <pre>
	 * 생크림 ML CYCLE. 레시피 없음 (메뉴별 판매와 연결하지 않는다, DECISIONS #26)
	 *   판매 9/12 ~ 10/10 매일 까르보나라 25 + 로제 15 = 40그릇 (메뉴 구분 없이 전체 그릇 수)
	 *   입고 ① 10-01 PAST 문서(실사일 이전, 재고엔 안 더하지만 속도 계산엔 쓴다):
	 *          2팩 × 1000 + 마트 줄 800 (상품 미연결) = 2800 / PENDING 줄 9999 는 무시
	 *        ② 10-08 RECEIVE 문서 4팩 × 1000 = 4000
	 *   판매량 정규화: 그릇당 소비량 = 2800 ÷ (10-01 ~ 10-07 7일 × 40 = 280그릇) = 10 ML
	 *   추정 재고    = 4000 − 10 × (10-08 ~ 10-10 3일 × 40 = 120그릇) = 2800.000
	 *   하루 예상 소비 = 일별 예측 그릇 수 40(최근 4주 같은 요일 평균) × 10 = 400
	 *   남은 일수    = 2800 ÷ 400 = 7일 → 10-11 2400 … 10-16 400 → 10-17 0 → 2026-10-17
	 * </pre>
	 */
	@Test
	void 주기형_생크림_예시는_10월_17일에_소진된다() {
		long store = insertStore(jdbc, "생크림");
		long 생크림 = insertIngredient(jdbc, store, "생크림", "ML", "CYCLE");
		long 까르보나라 = insertMenu(jdbc, store, "까르보나라");
		long 로제 = insertMenu(jdbc, store, "로제");
		for (int i = 28; i >= 0; i--) {
			insertSale(jdbc, store, T.minusDays(i), 까르보나라, "25");
			insertSale(jdbc, store, T.minusDays(i), 로제, "15");
		}
		long 거래처 = insertSupplier(jdbc, store, "유제품");
		long 팩 = insertSupplierProduct(jdbc, store, 거래처, 생크림, "1000");
		long 과거 = insertDoc(jdbc, store, LocalDate.of(2026, 10, 1), "PAST", "CONFIRMED", null);
		insertDocLine(jdbc, store, 과거, 1, 생크림, "2", "CONFIRMED", 팩);
		insertDocLine(jdbc, store, 과거, 2, 생크림, "800", "CONFIRMED", null);
		insertDocLine(jdbc, store, 과거, 3, 생크림, "9999", "PENDING", null);
		long 입고 = insertDoc(jdbc, store, LocalDate.of(2026, 10, 8), "RECEIVE", "CONFIRMED", null);
		insertDocLine(jdbc, store, 입고, 1, 생크림, "4", "CONFIRMED", 팩);

		List<InventoryEstimateResponse> result = service.estimate(store, T);

		assertThat(lines(result)).containsExactly("생크림|ML|CYCLE|2800.000|false|2026-10-17|CYCLE_SALES");
		assertThat(result.get(0).lastCountedAt()).isNull();
	}

	@Test
	void 주기형은_실사_이전_영수증만_있어도_소비_속도를_계산한다() {
		// PAST 영수증 둘뿐이어도 속도 계산에 쓴다 (DECISIONS #41 — PAST 는 재고에 안 더하고 주기형 속도에만)
		// 그릇당 = 1000 ÷ (T−8 ~ T−4 = 5일 × 25 = 125) = 8, 추정 = 1000 − 8 × (T−3 ~ T = 100) = 200, 하루 200 → T+1
		long store = insertStore(jdbc, "주기형과거만");
		long 식용유 = insertIngredient(jdbc, store, "식용유", "ML", "CYCLE");
		long 돈까스 = insertMenu(jdbc, store, "돈까스");
		for (int i = 28; i >= 0; i--) {
			insertSale(jdbc, store, T.minusDays(i), 돈까스, "25");
		}
		long 첫 = insertDoc(jdbc, store, T.minusDays(8), "PAST", "CONFIRMED", null);
		insertDocLine(jdbc, store, 첫, 1, 식용유, "1000", "CONFIRMED", null);
		long 둘 = insertDoc(jdbc, store, T.minusDays(3), "PAST", "CONFIRMED", null);
		insertDocLine(jdbc, store, 둘, 1, 식용유, "1000", "CONFIRMED", null);

		assertThat(lines(service.estimate(store, T))).containsExactly(
				"식용유|ML|CYCLE|200.000|false|2026-10-11|CYCLE_SALES");
	}

	@Test
	void 주기형은_영수증이_한_장이면_판매가_있어도_수집_중이다() {
		// 기록 1개는 판매가 있어도 속도를 못 낸다 → 기록 쌓는 중. 미확인(PARSED) 문서 · 발주서 연결 문서는 기록으로 안 센다
		long store = insertStore(jdbc, "주기형기록1개");
		long 식용유 = insertIngredient(jdbc, store, "식용유", "ML", "CYCLE");
		long 돈까스 = insertMenu(jdbc, store, "돈까스");
		for (int i = 28; i >= 0; i--) {
			insertSale(jdbc, store, T.minusDays(i), 돈까스, "25");
		}
		long 확인 = insertDoc(jdbc, store, T.minusDays(5), "RECEIVE", "CONFIRMED", null);
		insertDocLine(jdbc, store, 확인, 1, 식용유, "1000", "CONFIRMED", null);
		long 미확인 = insertDoc(jdbc, store, T.minusDays(9), "PAST", "PARSED", null);
		insertDocLine(jdbc, store, 미확인, 1, 식용유, "1000", "CONFIRMED", null);
		long 거래처 = insertSupplier(jdbc, store, "기름집");
		long 발주 = insertPurchaseOrder(jdbc, store, 거래처);
		long 발주연결 = insertDoc(jdbc, store, T.minusDays(12), "PAST", "CONFIRMED", 발주);
		insertDocLine(jdbc, store, 발주연결, 1, 식용유, "1000", "CONFIRMED", null);

		assertThat(lines(service.estimate(store, T))).containsExactly(
				"식용유|ML|CYCLE|null|false|null|CYCLE_COLLECTING");
	}

	@Test
	void 주기형은_영수증이_없으면_수집_중이다() {
		// 입고 기록 0개 = 품목 없음. 판매가 있어도 둘 다 null
		long store = insertStore(jdbc, "주기형기록없음");
		insertIngredient(jdbc, store, "식용유", "ML", "CYCLE");
		long 돈까스 = insertMenu(jdbc, store, "돈까스");
		insertSale(jdbc, store, T.minusDays(1), 돈까스, "25");

		assertThat(lines(service.estimate(store, T))).containsExactly(
				"식용유|ML|CYCLE|null|false|null|CYCLE_COLLECTING");
	}

	@Test
	void 주기형은_영수증_세_장에_사이_판매가_없으면_평균_간격을_쓴다() {
		// 입고 T−12 · T−7 · T−3 (간격 5 · 4 → 평균 4.5 → 반올림 5), 그 사이 판매 없음(T−1 판매는 마지막 입고 뒤라 안 센다)
		// → 소진일 T−3 + 5 = T+2, 추정 재고 null
		long store = insertStore(jdbc, "주기형간격3건");
		long 식용유 = insertIngredient(jdbc, store, "식용유", "ML", "CYCLE");
		long 돈까스 = insertMenu(jdbc, store, "돈까스");
		insertSale(jdbc, store, T.minusDays(1), 돈까스, "25");
		for (int d : new int[] {12, 7, 3}) {
			long 문서 = insertDoc(jdbc, store, T.minusDays(d), d > 5 ? "PAST" : "RECEIVE", "CONFIRMED", null);
			insertDocLine(jdbc, store, 문서, 1, 식용유, "1000", "CONFIRMED", null);
		}

		assertThat(lines(service.estimate(store, T))).containsExactly(
				"식용유|ML|CYCLE|null|false|2026-10-12|CYCLE_INTERVAL");
	}

	@Test
	void 주기형은_평균_간격이_이미_지났으면_오늘_소진이다() {
		// 입고 T−20 · T−15 (간격 5) → T−10 은 지났으니 오늘
		long store = insertStore(jdbc, "주기형간격지남");
		long 식용유 = insertIngredient(jdbc, store, "식용유", "ML", "CYCLE");
		long 첫 = insertDoc(jdbc, store, T.minusDays(20), "PAST", "CONFIRMED", null);
		insertDocLine(jdbc, store, 첫, 1, 식용유, "1000", "CONFIRMED", null);
		long 둘 = insertDoc(jdbc, store, T.minusDays(15), "PAST", "CONFIRMED", null);
		insertDocLine(jdbc, store, 둘, 1, 식용유, "1000", "CONFIRMED", null);

		assertThat(lines(service.estimate(store, T))).containsExactly(
				"식용유|ML|CYCLE|null|false|2026-10-10|CYCLE_INTERVAL");
	}

	@Test
	void 주기형은_메뉴_구성비와_무관하며_메뉴별_계수를_저장하지_않는다() {
		// 메뉴별 판매와 연결하지 않는다 (DECISIONS #26): 같은 전체 그릇 수면 메뉴 구성이 달라도 결과가 같고,
		// 계산 뒤에도 주기형 재료의 레시피 줄(메뉴별 계수)이 생기지 않는다
		long storeA = insertStore(jdbc, "주기형메뉴A");
		long storeB = insertStore(jdbc, "주기형메뉴B");
		long 식용유A = cycleWithTwoMenus(storeA, "20", "5");
		long 식용유B = cycleWithTwoMenus(storeB, "5", "20");

		List<String> a = lines(service.estimate(storeA, T));
		List<String> b = lines(service.estimate(storeB, T));

		assertThat(a).containsExactly("식용유|ML|CYCLE|700.000|false|2026-10-14|CYCLE_SALES");
		assertThat(b).isEqualTo(a);
		Integer recipeRows = jdbc.queryForObject(
				"SELECT COUNT(*) FROM recipe WHERE ingredient_id IN (?, ?)", Integer.class, 식용유A, 식용유B);
		assertThat(recipeRows).isZero();
	}

	/** 매일 두 메뉴 합 25그릇, 입고 T−8 PAST 1000 · T−3 RECEIVE 1500 → 그릇당 8, 추정 700, 소진일 T+4 (cycle_withSales 와 같은 수) */
	private long cycleWithTwoMenus(long store, String qtyA, String qtyB) {
		long 식용유 = insertIngredient(jdbc, store, "식용유", "ML", "CYCLE");
		long 돈까스 = insertMenu(jdbc, store, "돈까스");
		long 생선까스 = insertMenu(jdbc, store, "생선까스");
		for (int i = 28; i >= 0; i--) {
			insertSale(jdbc, store, T.minusDays(i), 돈까스, qtyA);
			insertSale(jdbc, store, T.minusDays(i), 생선까스, qtyB);
		}
		long 과거 = insertDoc(jdbc, store, T.minusDays(8), "PAST", "CONFIRMED", null);
		insertDocLine(jdbc, store, 과거, 1, 식용유, "1000", "CONFIRMED", null);
		long 최근 = insertDoc(jdbc, store, T.minusDays(3), "RECEIVE", "CONFIRMED", null);
		insertDocLine(jdbc, store, 최근, 1, 식용유, "1500", "CONFIRMED", null);
		return 식용유;
	}

	@Test
	void 다른_가게_데이터는_섞이지_않는다() {
		// 가게 B 의 실사 · 판매 · 문서 줄(가게 A 의 등심을 가리키는 줄 포함)은 가게 A 결과에 섞이지 않는다
		// B: 999 − 1 × 100 + 1 = 900. 소진일: 이력 10-05 ~ 10-09 평균 100 / 5 = 20/일 → 45일째 → 2026-11-24
		long storeA = insertStore(jdbc, "격리A");
		long storeB = insertStore(jdbc, "격리B");
		long 등심A = insertIngredient(jdbc, storeA, "등심", "G", "BOM");
		long 등심B = insertIngredient(jdbc, storeB, "등심", "G", "BOM");
		insertCount(jdbc, storeA, 등심A, OffsetDateTime.of(2026, 10, 1, 9, 0, 0, 0, KST), "100");
		insertCount(jdbc, storeB, 등심B, OffsetDateTime.of(2026, 10, 1, 9, 0, 0, 0, KST), "999");
		long 돈까스B = insertMenu(jdbc, storeB, "돈까스");
		insertRecipe(jdbc, storeB, 돈까스B, 등심B, "100");
		insertSale(jdbc, storeB, LocalDate.of(2026, 10, 5), 돈까스B, "1");
		long 문서B = insertDoc(jdbc, storeB, LocalDate.of(2026, 10, 6), "RECEIVE", "CONFIRMED", null);
		insertDocLine(jdbc, storeB, 문서B, 1, 등심A, "5000", "CONFIRMED", null);
		insertDocLine(jdbc, storeB, 문서B, 2, 등심B, "1", "CONFIRMED", null);

		assertThat(lines(service.estimate(storeA, T))).containsExactly("등심|G|BOM|100.000|false|null|COUNT");
		assertThat(lines(service.estimate(storeB, T))).containsExactly("등심|G|BOM|900.000|false|2026-11-24|COUNT");
	}

	/**
	 * <pre>
	 * 날짜별 소비량은 최근 28일(09-12 ~ 10-10)만 읽고, 그 앞은 재료별 합계로 뺀다. 둘을 합친 값이 전 구간을 다 읽던 때와 같아야 한다.
	 *
	 * 등심 G BOM (돈까스 150) — 실사 T−60(08-11) 10000
	 *   판매 돈까스 T−50 10 · T−30 20 (28일 밖) · T−28 ~ T−1 매일 1
	 *   → 10000 − 150 × (10 + 20 + 28) = 1300.000. 150/일 → T+8 100 → T+9 −50 → 2026-10-19
	 * 치즈 G BOM (치즈돈까스 40) — 실사 없음, RECEIVE 문서 T−40 2010
	 *   판매 치즈돈까스 T−45 3 (28일 밖, 받은 날 이전이지만 기준점이 없으니 뺀다) · T−28 ~ T−1 매일 1
	 *   → 0 + 2010 − 40 × (3 + 28) = 770.000, 실사 필요. 40/일 → T+19 10 → T+20 −30 → 2026-10-30
	 * 가게 B 가 T−40 에 가게 A 의 돈까스를 100개 판 줄은 어느 쪽에도 안 들어간다
	 * </pre>
	 */
	@Test
	void 이력_창보다_오래된_판매는_재료별_합계로_차감한다() {
		long store = insertStore(jdbc, "오래된판매");
		long other = insertStore(jdbc, "오래된판매B");
		long 등심 = insertIngredient(jdbc, store, "등심", "G", "BOM");
		long 치즈 = insertIngredient(jdbc, store, "치즈", "G", "BOM");
		long 돈까스 = insertMenu(jdbc, store, "돈까스");
		long 치즈돈까스 = insertMenu(jdbc, store, "치즈돈까스");
		insertRecipe(jdbc, store, 돈까스, 등심, "150");
		insertRecipe(jdbc, store, 치즈돈까스, 치즈, "40");
		insertCount(jdbc, store, 등심, OffsetDateTime.of(2026, 8, 11, 9, 0, 0, 0, KST), "10000");
		long 문서 = insertDoc(jdbc, store, T.minusDays(40), "RECEIVE", "CONFIRMED", null);
		insertDocLine(jdbc, store, 문서, 1, 치즈, "2010", "CONFIRMED", null);
		insertSale(jdbc, store, T.minusDays(50), 돈까스, "10");
		insertSale(jdbc, store, T.minusDays(30), 돈까스, "20");
		insertSale(jdbc, store, T.minusDays(45), 치즈돈까스, "3");
		for (int d = 1; d <= 28; d++) {
			insertSale(jdbc, store, T.minusDays(d), 돈까스, "1");
			insertSale(jdbc, store, T.minusDays(d), 치즈돈까스, "1");
		}
		insertSale(jdbc, other, T.minusDays(40), 돈까스, "100");

		assertThat(lines(service.estimate(store, T))).containsExactly(
				"등심|G|BOM|1300.000|false|2026-10-19|COUNT",
				"치즈|G|BOM|770.000|true|2026-10-30|NO_COUNT");
	}
	@Test
	void 재료가_없으면_빈_목록을_돌려준다() {
		assertThat(service.estimate(insertStore(jdbc, "빈가게"), T)).isEmpty();
	}

	/** 반올림하지 않는다 — 서비스가 낸 estimatedQty 를 문자열 그대로 비교한다. */
	private static List<String> lines(List<InventoryEstimateResponse> rows) {
		return rows.stream()
				.map(r -> r.ingredientName() + "|" + r.unit() + "|" + r.kind() + "|"
						+ (r.estimatedQty() == null ? "null" : r.estimatedQty().toPlainString()) + "|"
						+ r.needsCount() + "|" + r.runOutDate() + "|" + r.basis())
				.toList();
	}
}
