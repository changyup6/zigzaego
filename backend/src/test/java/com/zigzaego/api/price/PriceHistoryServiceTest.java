package com.zigzaego.api.price;

import static com.zigzaego.api.price.PriceHistoryTestData.insertPrice;
import static com.zigzaego.api.price.PriceHistoryTestData.insertProduct;
import static com.zigzaego.api.price.PriceHistoryTestData.insertSupplier;
import static com.zigzaego.api.support.FixedClockConfig.TODAY;
import static com.zigzaego.api.support.StoreFixtures.insertIngredient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.auth.AuthTestSupport;
import com.zigzaego.api.domain.price.PriceIndexCalculator;
import com.zigzaego.api.domain.price.PriceIndexCalculator.PriceIndex;
import com.zigzaego.api.domain.price.PriceIndexCalculator.PriceIndexReason;
import com.zigzaego.api.domain.price.PriceIndexCalculator.PriceLevel;
import com.zigzaego.api.domain.price.PriceIndexCalculator.PriceObservation;
import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.exception.CommonErrorCode;
import com.zigzaego.api.ingredient.entity.IngredientUnit;
import com.zigzaego.api.price.dto.PriceHistoryResponse;
import com.zigzaego.api.price.dto.PriceObservationResponse;
import com.zigzaego.api.price.service.PriceHistoryService;
import com.zigzaego.api.support.FixedClockConfig;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 재료 단가 이력 · 가격 지수 조회 (S15P31A308-64). 오늘 = 2026-10-14 고정.
 * 주별 칸: [08-20~26] [08-27~09-02] [09-03~09] [09-10~16] [09-17~23] [09-24~30] [10-01~07] [10-08~14].
 */
@Import({TestcontainersConfiguration.class, FixedClockConfig.class})
@SpringBootTest
class PriceHistoryServiceTest {

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PriceHistoryService service;

	private long myStore;

	private long otherStore;

	private long greenOnion;

	private long supplier;

	/** 대파 1kg = 1000 g. */
	private long kg1;

	/** 대파 500g = 500 g. */
	private long g500;

	@BeforeEach
	void setUp() {
		myStore = AuthTestSupport.insertStore(jdbc, "단가내가게-" + System.nanoTime());
		otherStore = AuthTestSupport.insertStore(jdbc, "단가남의가게-" + System.nanoTime());
		greenOnion = insertIngredient(jdbc, myStore, "대파");
		supplier = insertSupplier(jdbc, myStore, "한빛농산");
		kg1 = insertProduct(jdbc, myStore, supplier, greenOnion, "대파 1kg", 1000);
		g500 = insertProduct(jdbc, myStore, supplier, greenOnion, "대파 500g", 500);
	}

	@Test
	void 두_상품_단위_단가_주별_중앙값_지수() {
		insertPrice(jdbc, myStore, kg1, 4200, day(-55), "MANUAL"); // 08-20 칸0 4.2
		insertPrice(jdbc, myStore, kg1, 4200, LocalDate.of(2026, 9, 5), "RECEIVE"); // 칸2 4.2
		insertPrice(jdbc, myStore, g500, 2250, LocalDate.of(2026, 9, 6), "DOC"); // 칸2 4.5
		insertPrice(jdbc, myStore, kg1, 4400, LocalDate.of(2026, 9, 20), "MANUAL"); // 칸4 4.4
		insertPrice(jdbc, myStore, g500, 2200, LocalDate.of(2026, 9, 28), "MANUAL"); // 칸5 4.4
		insertPrice(jdbc, myStore, kg1, 4600, LocalDate.of(2026, 10, 3), "MANUAL"); // 칸6 4.6
		insertPrice(jdbc, myStore, kg1, 4500, LocalDate.of(2026, 10, 5), "MANUAL"); // 칸6 4.5
		insertPrice(jdbc, myStore, g500, 2400, LocalDate.of(2026, 10, 6), "MANUAL"); // 칸6 4.8
		insertPrice(jdbc, myStore, g500, 2400, TODAY, "MANUAL"); // 칸7 4.8

		// 남의 가게 행: 가게 조건이 빠지면 섞인다 (재료 id 는 내 대파를 가리키게 일부러 넣었다)
		long otherSupplier = insertSupplier(jdbc, otherStore, "남의거래처");
		long otherProduct = insertProduct(jdbc, otherStore, otherSupplier, greenOnion, "남의 대파", 1000);
		insertPrice(jdbc, otherStore, otherProduct, 99000, TODAY, "MANUAL");

		PriceHistoryResponse res = service.get(myStore, String.valueOf(greenOnion));

		assertThat(res.ingredientId()).isEqualTo(String.valueOf(greenOnion));
		assertThat(res.unit()).isEqualTo(IngredientUnit.G);
		assertThat(res.observations()).hasSize(9);
		PriceObservationResponse latest = res.observations().get(0);
		assertThat(latest.observedOn()).isEqualTo("2026-10-14");
		assertThat(latest.unitPrice()).isEqualByComparingTo("4.8");
		assertThat(latest.unitPrice().scale()).isEqualTo(4);
		assertThat(latest.source()).isEqualTo("MANUAL");
		assertThat(latest.supplierProductId()).isEqualTo(String.valueOf(g500));
		assertThat(latest.productName()).isEqualTo("대파 500g");
		PriceObservationResponse oldest = res.observations().get(8);
		assertThat(oldest.observedOn()).isEqualTo("2026-08-20");
		assertThat(oldest.unitPrice()).isEqualByComparingTo("4.2");
		assertThat(oldest.supplierProductId()).isEqualTo(String.valueOf(kg1));
		assertThat(res.observations()).extracting(PriceObservationResponse::observedOn)
				.isSortedAccordingTo(Collections.reverseOrder());

		assertWeekly(res, "4.2", null, "4.35", null, "4.4", "4.4", "4.6", "4.8");

		PriceIndex expected = new PriceIndexCalculator().calculate(TODAY, List.of(
				obs(day(-55), "4.2", "MANUAL"), obs(LocalDate.of(2026, 9, 5), "4.2", "RECEIVE"),
				obs(LocalDate.of(2026, 9, 6), "4.5", "DOC"), obs(LocalDate.of(2026, 9, 20), "4.4", "MANUAL"),
				obs(LocalDate.of(2026, 9, 28), "4.4", "MANUAL"), obs(LocalDate.of(2026, 10, 3), "4.6", "MANUAL"),
				obs(LocalDate.of(2026, 10, 5), "4.5", "MANUAL"), obs(LocalDate.of(2026, 10, 6), "4.8", "MANUAL"),
				obs(TODAY, "4.8", "MANUAL")));
		assertSameIndex(res, expected);
		assertThat(res.index().reason()).isEqualTo(PriceIndexReason.OK);
		assertThat(res.index().sampleCount()).isEqualTo(8);
		assertThat(res.index().basePrice()).isEqualByComparingTo("4.45");
		assertThat(res.index().currentPrice()).isEqualByComparingTo("4.8");
		assertThat(res.index().index()).isEqualByComparingTo("1.0787");
		assertThat(res.index().level()).isEqualTo(PriceLevel.NORMAL);
	}

	@Test
	void 비싸지면_HIGH_지수는_계산기와_같다() {
		insertPrice(jdbc, myStore, kg1, 4200, day(-30), "MANUAL");
		insertPrice(jdbc, myStore, kg1, 4200, day(-20), "MANUAL");
		insertPrice(jdbc, myStore, g500, 2100, day(-10), "MANUAL"); // 4.2
		insertPrice(jdbc, myStore, g500, 2400, day(-1), "MANUAL"); // 4.8

		PriceHistoryResponse res = service.get(myStore, String.valueOf(greenOnion));

		PriceIndex expected = new PriceIndexCalculator().calculate(TODAY, List.of(
				obs(day(-30), "4.2", "MANUAL"), obs(day(-20), "4.2", "MANUAL"),
				obs(day(-10), "4.2", "MANUAL"), obs(day(-1), "4.8", "MANUAL")));
		assertSameIndex(res, expected);
		assertThat(res.index().level()).isEqualTo(PriceLevel.HIGH);
		assertThat(res.index().index()).isEqualByComparingTo("1.1429");
		assertThat(res.index().basePrice()).isEqualByComparingTo("4.2");
		assertThat(res.index().currentPrice()).isEqualByComparingTo("4.8");
	}

	@Test
	void market_출처는_관측_주별_지수에서_빠진다() {
		insertPrice(jdbc, myStore, kg1, 4200, day(-30), "MANUAL");
		insertPrice(jdbc, myStore, kg1, 4200, day(-20), "MANUAL");
		insertPrice(jdbc, myStore, kg1, 4200, day(-10), "MANUAL");
		insertPrice(jdbc, myStore, kg1, 9000, TODAY, "MARKET");

		PriceHistoryResponse res = service.get(myStore, String.valueOf(greenOnion));

		assertThat(res.observations()).extracting(PriceObservationResponse::source).doesNotContain("MARKET");
		assertThat(res.observations()).hasSize(3);
		assertThat(res.weekly().get(7)).isNull();
		// MARKET 이 들어갔다면 현재 단가 9.0 · HIGH
		assertThat(res.index().currentPrice()).isEqualByComparingTo("4.2");
		assertThat(res.index().reason()).isEqualTo(PriceIndexReason.TOO_FEW);
	}

	@Test
	void baseQty_없는_상품의_이력은_빠진다() {
		long unknownPack = insertProduct(jdbc, myStore, supplier, greenOnion, "대파 한단", null);
		insertPrice(jdbc, myStore, unknownPack, 3500, TODAY, "MANUAL");
		insertPrice(jdbc, myStore, kg1, 4200, day(-3), "MANUAL");

		PriceHistoryResponse res = service.get(myStore, String.valueOf(greenOnion));

		assertThat(res.observations()).singleElement()
				.satisfies(o -> assertThat(o.supplierProductId()).isEqualTo(String.valueOf(kg1)));
		assertThat(res.index().currentPrice()).isEqualByComparingTo("4.2");
		assertWeekly(res, null, null, null, null, null, null, null, "4.2");
	}

	@Test
	void empty_이력_0건이면_주별_8칸_null_지수는_NO_RECENT() {
		PriceHistoryResponse res = service.get(myStore, String.valueOf(greenOnion));

		assertThat(res.observations()).isEmpty();
		assertThat(res.weekly()).hasSize(8).containsOnlyNulls();
		assertThat(res.index().reason()).isEqualTo(PriceIndexReason.NO_RECENT);
		assertThat(res.index().level()).isEqualTo(PriceLevel.NORMAL);
		assertThat(res.index().index()).isEqualByComparingTo("1");
		assertThat(res.index().basePrice()).isNull();
		assertThat(res.index().currentPrice()).isNull();
		assertThat(res.index().sampleCount()).isZero();
	}

	@Test
	void tooFew_이력_2건이면_지수_1() {
		insertPrice(jdbc, myStore, kg1, 4200, day(-10), "MANUAL");
		insertPrice(jdbc, myStore, g500, 2400, TODAY, "MANUAL");

		PriceHistoryResponse res = service.get(myStore, String.valueOf(greenOnion));

		assertThat(res.index().reason()).isEqualTo(PriceIndexReason.TOO_FEW);
		assertThat(res.index().index()).isEqualByComparingTo("1");
		assertThat(res.index().level()).isEqualTo(PriceLevel.NORMAL);
		assertThat(res.index().sampleCount()).isEqualTo(1);
		assertThat(res.index().basePrice()).isEqualByComparingTo("4.2");
		assertThat(res.index().currentPrice()).isEqualByComparingTo("4.8");
		assertThat(res.observations()).hasSize(2);
	}

	@Test
	void window_56일_안만_관측으로_57일전과_내일은_빠진다() {
		insertPrice(jdbc, myStore, kg1, 4000, day(-57), "MANUAL");
		insertPrice(jdbc, myStore, kg1, 4100, day(-56), "MANUAL");
		insertPrice(jdbc, myStore, kg1, 4200, day(-55), "MANUAL");
		insertPrice(jdbc, myStore, kg1, 4300, day(1), "MANUAL");

		PriceHistoryResponse res = service.get(myStore, String.valueOf(greenOnion));

		assertThat(res.observations()).singleElement()
				.satisfies(o -> assertThat(o.observedOn()).isEqualTo("2026-08-20"));
		assertWeekly(res, "4.2", null, null, null, null, null, null, null);
		// 지수는 계산기에 관측 전부를 넘긴 것과 같다 (계산기 창은 오늘 − 56일부터, 내일은 버림)
		PriceIndex expected = new PriceIndexCalculator().calculate(TODAY, List.of(
				obs(day(-57), "4.0", "MANUAL"), obs(day(-56), "4.1", "MANUAL"),
				obs(day(-55), "4.2", "MANUAL"), obs(day(1), "4.3", "MANUAL")));
		assertSameIndex(res, expected);
	}

	@Test
	void zeroPrice_0원_이력은_관측_주별_지수_모두에서_빠진다() {
		insertPrice(jdbc, myStore, kg1, 4200, day(-30), "MANUAL");
		insertPrice(jdbc, myStore, kg1, 4200, day(-20), "MANUAL");
		insertPrice(jdbc, myStore, kg1, 4200, day(-10), "MANUAL"); // 10-04 칸6
		insertPrice(jdbc, myStore, g500, 0, day(-10), "MANUAL"); // 같은 칸의 0원: 들어가면 중앙값 2.1
		insertPrice(jdbc, myStore, kg1, 0, TODAY, "MANUAL"); // 칸7 의 유일한 행: 들어가면 0.0000

		PriceHistoryResponse res = service.get(myStore, String.valueOf(greenOnion));

		assertThat(res.observations()).hasSize(3);
		assertThat(res.observations()).allSatisfy(o -> assertThat(o.unitPrice().signum()).isPositive());
		assertWeekly(res, null, null, null, "4.2", null, "4.2", "4.2", null); // 09-14 칸3, 09-24 칸5, 10-04 칸6
		PriceIndex expected = new PriceIndexCalculator().calculate(TODAY, List.of(
				obs(day(-30), "4.2", "MANUAL"), obs(day(-20), "4.2", "MANUAL"), obs(day(-10), "4.2", "MANUAL")));
		assertSameIndex(res, expected);
		assertThat(res.index().currentPrice()).isEqualByComparingTo("4.2");
	}

	@Test
	void otherStoreRow_내_상품을_가리키는_남의_가게_이력은_빠진다() {
		insertPrice(jdbc, myStore, kg1, 4200, day(-3), "MANUAL");
		// store_id 는 남의 가게인데 supplier_product_id 는 내 상품
		insertPrice(jdbc, otherStore, kg1, 99000, TODAY, "MANUAL");

		PriceHistoryResponse res = service.get(myStore, String.valueOf(greenOnion));

		assertThat(res.observations()).singleElement().satisfies(o -> {
			assertThat(o.observedOn()).isEqualTo(day(-3).toString());
			assertThat(o.unitPrice()).isEqualByComparingTo("4.2");
		});
		assertWeekly(res, null, null, null, null, null, null, null, "4.2");
		assertThat(res.index().currentPrice()).isEqualByComparingTo("4.2");
	}

	@Test
	void 남의_가게_재료와_없는_id와_숫자_아닌_id는_404() {
		long othersIngredient = insertIngredient(jdbc, otherStore, "대파");

		for (String rawId : List.of(String.valueOf(othersIngredient), "999999999", "abc")) {
			assertThatThrownBy(() -> service.get(myStore, rawId))
					.isInstanceOf(BusinessException.class)
					.satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND));
		}
	}

	private static LocalDate day(int offset) {
		return TODAY.plusDays(offset);
	}

	private static PriceObservation obs(LocalDate day, String unitPrice, String source) {
		return new PriceObservation(day, new BigDecimal(unitPrice), source);
	}

	private static void assertSameIndex(PriceHistoryResponse res, PriceIndex expected) {
		assertThat(res.index().level()).isEqualTo(expected.level());
		assertThat(res.index().reason()).isEqualTo(expected.reason());
		assertThat(res.index().sampleCount()).isEqualTo(expected.sampleCount());
		assertThat(res.index().index()).isEqualByComparingTo(round4(expected.index()));
		assertThat(round4OrNull(res.index().basePrice())).isEqualTo(round4OrNull(expected.basePrice()));
		assertThat(round4OrNull(res.index().currentPrice())).isEqualTo(round4OrNull(expected.currentPrice()));
	}

	private static BigDecimal round4(BigDecimal v) {
		return v.setScale(4, RoundingMode.HALF_UP);
	}

	private static BigDecimal round4OrNull(BigDecimal v) {
		return v == null ? null : round4(v);
	}

	private static void assertWeekly(PriceHistoryResponse res, String... expected) {
		assertThat(res.weekly()).hasSize(8);
		List<String> actual = new ArrayList<>();
		for (BigDecimal v : res.weekly()) {
			actual.add(v == null ? null : v.stripTrailingZeros().toPlainString());
		}
		List<String> want = new ArrayList<>();
		for (String e : expected) {
			want.add(e == null ? null : new BigDecimal(e).stripTrailingZeros().toPlainString());
		}
		assertThat(actual).containsExactlyElementsOf(want);
	}
}
