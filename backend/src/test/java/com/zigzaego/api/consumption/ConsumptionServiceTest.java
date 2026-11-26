package com.zigzaego.api.consumption;

import static com.zigzaego.api.consumption.ConsumptionTestData.insertComponent;
import static com.zigzaego.api.consumption.ConsumptionTestData.insertIngredient;
import static com.zigzaego.api.consumption.ConsumptionTestData.insertMenu;
import static com.zigzaego.api.consumption.ConsumptionTestData.insertRecipe;
import static com.zigzaego.api.consumption.ConsumptionTestData.insertSale;
import static com.zigzaego.api.consumption.ConsumptionTestData.insertStore;
import static org.assertj.core.api.Assertions.assertThat;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.consumption.dto.DailyConsumptionResponse;
import com.zigzaego.api.consumption.service.ConsumptionService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 재료별 일 소비량 손계산 검증. 기대값은 아래 표를 손으로 계산한 숫자 리터럴이다.
 *
 * <pre>
 * [가게 A] 재료 (id 오름차순으로 넣음)
 *   등심 G BOM / 치즈 G BOM / 소스 ML BOM / 콜라 EA ONE_TO_ONE / 포장용기 EA BOM / 식용유 ML CYCLE
 * 레시피 (1인분)
 *   돈까스(MAIN)      = 등심 150 + 소스 12.345 + 식용유 50(CYCLE)
 *   치즈돈까스(MAIN)  = 등심 150 + 치즈 40 + 소스 12.345
 *   콜라(MAIN)        = 콜라 1                      ← 1:1 재료는 "레시피 qty 1 줄"
 *   치즈추가(OPTION)  = 치즈 30
 *   돈까스세트(SET)   = 포장용기 1 (세트 자기 레시피) + 구성: 돈까스 1 · 콜라 1
 *
 * 판매 (pos_sale, 채널별)
 *   날짜        돈까스      치즈돈까스  돈까스세트  콜라   치즈추가
 *   10-01      D3 + H2     H4          D1          H2     H2
 *   10-02      H1          D2 + H1     D2 + H1     -      -
 *   10-03      D4          -           -           D5     -
 *
 * 세트 풀기 후 실제 인분 (채널 합산 + 세트 × 구성 qty)
 *   날짜   돈까스      치즈돈까스  세트  콜라      치즈추가
 *   10-01  5+1 = 6     4           1     2+1 = 3   2
 *   10-02  1+3 = 4     3           3     0+3 = 3   0
 *   10-03  4           0           0     5         0
 *
 * 손계산 소비량 (식용유는 CYCLE 이라 결과에 없음, 0 인 줄 없음)
 *   날짜   등심                   치즈                 소스                    콜라  포장용기
 *   10-01  (6+4)×150 = 1500 G    4×40+2×30 = 220 G    10×12.345 = 123.450 ML  3 EA  1 EA
 *   10-02  (4+3)×150 = 1050 G    3×40      = 120 G    7×12.345  =  86.415 ML  3 EA  3 EA
 *   10-03  4×150     =  600 G    (줄 없음)            4×12.345  =  49.380 ML  5 EA  (줄 없음)
 *
 * [가게 B] 같은 날짜에 판매가 있는 다른 가게 + 가게 A 를 오염시키려는 줄
 *   재료 등심 G BOM, 메뉴 돈까스 = 등심 200, 10-01 돈까스 D100 → 등심 20000 G (10-01 한 줄만)
 *   오염 줄: B 의 recipe 가 A 의 돈까스를 가리킴(등심(B) 999) · B 의 menu_component 가 A 의 세트에 A 의 치즈돈까스를 붙임
 *           · B 의 pos_sale 이 A 의 돈까스를 50개 팜 → 어느 쪽 결과에도 반영되면 안 된다
 *
 * [1:1 전용 가게] 콜라 메뉴(콜라 EA ONE_TO_ONE 1개) 10-01 2개 · 10-02 0개(qty 0 줄) · 10-03 5개
 *   → 10-01 2 EA · 10-02 줄 없음 · 10-03 5 EA
 * </pre>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ConsumptionServiceTest {

	private static final LocalDate D1 = LocalDate.of(2026, 10, 1);
	private static final LocalDate D2 = LocalDate.of(2026, 10, 2);
	private static final LocalDate D3 = LocalDate.of(2026, 10, 3);

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ConsumptionService consumptionService;

	private long storeA;
	private long storeB;
	private long 등심A;
	private long 식용유A;

	@BeforeEach
	void setUp() {
		storeA = insertStore(jdbc, "소비량A");
		storeB = insertStore(jdbc, "소비량B");

		등심A = insertIngredient(jdbc, storeA, "등심", "G", "BOM");
		long 치즈 = insertIngredient(jdbc, storeA, "치즈", "G", "BOM");
		long 소스 = insertIngredient(jdbc, storeA, "소스", "ML", "BOM");
		long 콜라재료 = insertIngredient(jdbc, storeA, "콜라", "EA", "ONE_TO_ONE");
		long 포장용기 = insertIngredient(jdbc, storeA, "포장용기", "EA", "BOM");
		식용유A = insertIngredient(jdbc, storeA, "식용유", "ML", "CYCLE");

		long 돈까스 = insertMenu(jdbc, storeA, "돈까스", "MAIN");
		long 치즈돈까스 = insertMenu(jdbc, storeA, "치즈돈까스", "MAIN");
		long 콜라 = insertMenu(jdbc, storeA, "콜라", "MAIN");
		long 치즈추가 = insertMenu(jdbc, storeA, "치즈추가", "OPTION");
		long 세트 = insertMenu(jdbc, storeA, "돈까스세트", "SET");

		insertRecipe(jdbc, storeA, 돈까스, 등심A, "150");
		insertRecipe(jdbc, storeA, 돈까스, 소스, "12.345");
		insertRecipe(jdbc, storeA, 돈까스, 식용유A, "50");
		insertRecipe(jdbc, storeA, 치즈돈까스, 등심A, "150");
		insertRecipe(jdbc, storeA, 치즈돈까스, 치즈, "40");
		insertRecipe(jdbc, storeA, 치즈돈까스, 소스, "12.345");
		insertRecipe(jdbc, storeA, 콜라, 콜라재료, "1");
		insertRecipe(jdbc, storeA, 치즈추가, 치즈, "30");
		insertRecipe(jdbc, storeA, 세트, 포장용기, "1");
		insertComponent(jdbc, storeA, 세트, 돈까스, "1");
		insertComponent(jdbc, storeA, 세트, 콜라, "1");

		insertSale(jdbc, storeA, D1, 돈까스, "DELIVERY", "3");
		insertSale(jdbc, storeA, D1, 돈까스, "HALL", "2");
		insertSale(jdbc, storeA, D1, 치즈돈까스, "HALL", "4");
		insertSale(jdbc, storeA, D1, 세트, "DELIVERY", "1");
		insertSale(jdbc, storeA, D1, 콜라, "HALL", "2");
		insertSale(jdbc, storeA, D1, 치즈추가, "HALL", "2");
		insertSale(jdbc, storeA, D2, 돈까스, "HALL", "1");
		insertSale(jdbc, storeA, D2, 치즈돈까스, "DELIVERY", "2");
		insertSale(jdbc, storeA, D2, 치즈돈까스, "HALL", "1");
		insertSale(jdbc, storeA, D2, 세트, "DELIVERY", "2");
		insertSale(jdbc, storeA, D2, 세트, "HALL", "1");
		insertSale(jdbc, storeA, D3, 돈까스, "DELIVERY", "4");
		insertSale(jdbc, storeA, D3, 콜라, "DELIVERY", "5");

		// 가게 B: 자기 판매 + 가게 A 를 오염시키려는 줄
		long 등심B = insertIngredient(jdbc, storeB, "등심", "G", "BOM");
		long 돈까스B = insertMenu(jdbc, storeB, "돈까스", "MAIN");
		insertRecipe(jdbc, storeB, 돈까스B, 등심B, "200");
		insertSale(jdbc, storeB, D1, 돈까스B, "DELIVERY", "100");
		insertRecipe(jdbc, storeB, 돈까스, 등심B, "999");
		insertComponent(jdbc, storeB, 세트, 치즈돈까스, "1");
		insertSale(jdbc, storeB, D1, 돈까스, "HALL", "50");
	}

	@Test
	void 사흘치가_손계산과_같다() {
		List<DailyConsumptionResponse> result = consumptionService.daily(storeA, D1, D3);

		assertThat(lines(result)).containsExactly(
				"2026-10-01|등심|G|1500.000",
				"2026-10-01|치즈|G|220.000",
				"2026-10-01|소스|ML|123.450",
				"2026-10-01|콜라|EA|3.000",
				"2026-10-01|포장용기|EA|1.000",
				"2026-10-02|등심|G|1050.000",
				"2026-10-02|치즈|G|120.000",
				"2026-10-02|소스|ML|86.415",
				"2026-10-02|콜라|EA|3.000",
				"2026-10-02|포장용기|EA|3.000",
				"2026-10-03|등심|G|600.000",
				"2026-10-03|소스|ML|49.380",
				"2026-10-03|콜라|EA|5.000");
		assertThat(result.get(0).ingredientId()).isEqualTo(String.valueOf(등심A));
	}

	@Test
	void 하루_구간은_그날만_센다() {
		List<DailyConsumptionResponse> result = consumptionService.daily(storeA, D2, D2);

		assertThat(lines(result)).containsExactly(
				"2026-10-02|등심|G|1050.000",
				"2026-10-02|치즈|G|120.000",
				"2026-10-02|소스|ML|86.415",
				"2026-10-02|콜라|EA|3.000",
				"2026-10-02|포장용기|EA|3.000");
	}

	@Test
	void 일대일_콜라는_판매_0인_날을_빼고_2와_5로_센다() {
		long store = insertStore(jdbc, "콜라가게");
		long 콜라재료 = insertIngredient(jdbc, store, "콜라", "EA", "ONE_TO_ONE");
		long 콜라 = insertMenu(jdbc, store, "콜라", "MAIN");
		insertRecipe(jdbc, store, 콜라, 콜라재료, "1");
		insertSale(jdbc, store, D1, 콜라, "HALL", "2");
		insertSale(jdbc, store, D2, 콜라, "HALL", "0");
		insertSale(jdbc, store, D3, 콜라, "DELIVERY", "5");

		assertThat(lines(consumptionService.daily(store, D1, D3))).containsExactly(
				"2026-10-01|콜라|EA|2.000",
				"2026-10-03|콜라|EA|5.000");
	}

	@Test
	void 세트는_구성_메뉴로_풀고_세트_자기_레시피도_센다() {
		// 10-02: 세트 3개(D2 + H1) → 돈까스 3 · 콜라 3 · 포장용기 3. 단품 콜라는 0, 단품 돈까스 1, 치즈돈까스 3
		List<String> d2 = lines(consumptionService.daily(storeA, D2, D2));

		assertThat(d2).contains(
				"2026-10-02|등심|G|1050.000",
				"2026-10-02|콜라|EA|3.000",
				"2026-10-02|포장용기|EA|3.000");
	}

	@Test
	void 주기형_재료는_결과에_나오지_않는다() {
		List<DailyConsumptionResponse> result = consumptionService.daily(storeA, D1, D3);

		assertThat(result).isNotEmpty();
		assertThat(result).extracting(DailyConsumptionResponse::ingredientId)
				.doesNotContain(String.valueOf(식용유A));
		assertThat(result).extracting(DailyConsumptionResponse::ingredientName)
				.doesNotContain("식용유");
	}

	@Test
	void 다른_가게_데이터는_섞이지_않는다() {
		assertThat(lines(consumptionService.daily(storeB, D1, D3))).containsExactly(
				"2026-10-01|등심|G|20000.000");
	}

	@Test
	void 판매가_없으면_빈_목록을_돌려준다() {
		assertThat(consumptionService.daily(storeA, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3)))
				.isEmpty();
	}

	@Test
	void 소수_셋째_자리로_반올림한다() {
		// 세트 2.5개 × 구성 1.5 × 레시피 0.3333 = 1.249875 + 다른 메뉴 10개 × 1.2 = 12 → 13.249875 → 13.250
		// 다른 메뉴 2.5개 × 0.0001 = 0.00025 → 0.000 → 줄 없음
		long store = insertStore(jdbc, "정밀도가게");
		long 소스 = insertIngredient(jdbc, store, "소스", "ML", "BOM");
		long 향신료 = insertIngredient(jdbc, store, "향신료", "G", "BOM");
		long 구성 = insertMenu(jdbc, store, "구성메뉴", "MAIN");
		long 세트 = insertMenu(jdbc, store, "세트", "SET");
		long 다른 = insertMenu(jdbc, store, "다른메뉴", "MAIN");
		long 조금 = insertMenu(jdbc, store, "조금메뉴", "MAIN");
		insertRecipe(jdbc, store, 구성, 소스, "0.3333");
		insertRecipe(jdbc, store, 다른, 소스, "1.2");
		insertRecipe(jdbc, store, 조금, 향신료, "0.0001");
		insertComponent(jdbc, store, 세트, 구성, "1.5");
		insertSale(jdbc, store, D1, 세트, "HALL", "2.5");
		insertSale(jdbc, store, D1, 다른, "HALL", "10");
		insertSale(jdbc, store, D1, 조금, "HALL", "2.5");

		List<DailyConsumptionResponse> result = consumptionService.daily(store, D1, D1);

		assertThat(lines(result)).containsExactly("2026-10-01|소스|ML|13.250");
		assertThat(new BigDecimal(qtyText(result.get(0)))).isEqualByComparingTo("13.250");
	}

	@Test
	void 아주_큰_값도_모든_자릿수를_유지한다() {
		// 99999999.999 × 999999999.999 = 99999999998900000.000001 → 소수 3자리로 반올림, double 로 잃지 않음
		long store = insertStore(jdbc, "큰값가게");
		long 재료 = insertIngredient(jdbc, store, "재료", "G", "BOM");
		long 메뉴 = insertMenu(jdbc, store, "메뉴", "MAIN");
		insertRecipe(jdbc, store, 메뉴, 재료, "99999999.999");
		insertSale(jdbc, store, D1, 메뉴, "HALL", "999999999.999");

		List<DailyConsumptionResponse> result = consumptionService.daily(store, D1, D1);

		String expected = new BigDecimal("99999999.999").multiply(new BigDecimal("999999999.999"))
				.setScale(3, RoundingMode.HALF_UP).toPlainString();
		assertThat(expected).isEqualTo("99999999998900000.000");
		assertThat(lines(result)).containsExactly("2026-10-01|재료|G|" + expected);
	}

	@Test
	void 마지막_실사_이후_소비량을_재료별로_합친다() {
		// 등심 실사 10-02 09:00(KST) → 10-02 · 10-03 만: 1050 + 600 = 1650. 나머지는 실사 없음 → 처음부터
		// 치즈 220 + 120 = 340 · 소스 123.450 + 86.415 + 49.380 = 259.245 · 콜라 3 + 3 + 5 = 11 · 포장용기 1 + 3 = 4
		// 식용유(CYCLE)는 없다. 가게 B 는 자기 등심 20000 만 (A 를 가리키는 오염 줄 무시)
		jdbc.update("INSERT INTO stock_count (store_id, ingredient_id, counted_at, qty) VALUES (?, ?, ?, 1)",
				storeA, 등심A, OffsetDateTime.of(2026, 10, 2, 9, 0, 0, 0, ZoneOffset.ofHours(9)));
		ZoneId seoul = ZoneId.of("Asia/Seoul");

		Map<Long, BigDecimal> a = consumptionService.totalSinceCountBefore(storeA, seoul, D3.plusDays(1));
		Map<Long, BigDecimal> b = consumptionService.totalSinceCountBefore(storeB, seoul, D3.plusDays(1));

		assertThat(a).hasSize(5);
		assertThat(a.get(등심A).toPlainString()).isEqualTo("1650.000");
		assertThat(a.values().stream().map(BigDecimal::toPlainString))
				.containsExactlyInAnyOrder("1650.000", "340.000", "259.245", "11.000", "4.000");
		assertThat(a).doesNotContainKey(식용유A);
		assertThat(b.values().stream().map(BigDecimal::toPlainString)).containsExactly("20000.000");
	}

	@Test
	void 경계일과_실사_이후_구간은_합계에서_뺀다() {
		// before = 10-03 이면 10-02 까지. 등심 실사가 10-03 이면 구간이 비어 줄이 없다
		jdbc.update("INSERT INTO stock_count (store_id, ingredient_id, counted_at, qty) VALUES (?, ?, ?, 1)",
				storeA, 등심A, OffsetDateTime.of(2026, 10, 3, 0, 30, 0, 0, ZoneOffset.ofHours(9)));

		Map<Long, BigDecimal> a = consumptionService.totalSinceCountBefore(storeA, ZoneId.of("Asia/Seoul"), D3);

		assertThat(a).doesNotContainKey(등심A);
		assertThat(a.values().stream().map(BigDecimal::toPlainString))
				.containsExactlyInAnyOrder("340.000", "209.865", "6.000", "4.000");
	}

	/** 반올림하지 않는다 — 서비스가 낸 qty 를 문자열 그대로 비교한다. */
	private static List<String> lines(List<DailyConsumptionResponse> rows) {
		return rows.stream()
				.map(r -> r.date() + "|" + r.ingredientName() + "|" + r.unit() + "|" + qtyText(r))
				.toList();
	}

	private static String qtyText(DailyConsumptionResponse r) {
		return r.qty().toPlainString();
	}
}
