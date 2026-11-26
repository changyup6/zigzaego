package com.zigzaego.api.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zigzaego.api.domain.order.OrderCalculator.OrderInput;
import com.zigzaego.api.domain.order.OrderCalculator.OrderResult;
import com.zigzaego.api.domain.order.OrderCalculator.OrderWarning;
import com.zigzaego.api.domain.price.PriceIndexCalculator.PriceIndex;
import com.zigzaego.api.domain.price.PriceIndexCalculator.PriceIndexReason;
import com.zigzaego.api.domain.price.PriceIndexCalculator.PriceLevel;
import com.zigzaego.api.ingredient.entity.IngredientUnit;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** 설계 5.4절 식을 손으로 계산한 값과 맞춘다. 단위는 g (등심). */
class OrderCalculatorTest {

	private final OrderCalculator calculator = new OrderCalculator();

	private static BigDecimal d(String v) {
		return new BigDecimal(v);
	}

	/**
	 * 일반: 하루 6000g · L=2 · R=3 · SS=1500 · 추정 재고 8000 · 입고 예정 0 · 포장 5000g · MOQ 없음 · 유통기한 없음
	 *
	 * <pre>
	 * ROP = 6000 × 2 + 1500 = 13500   → 8000 ≤ 13500 발주
	 * S   = 6000 × (3 + 2) + 1500 = 31500
	 * raw = 31500 − 8000 − 0 = 23500  → 포장 5000 올림 = 25000
	 * </pre>
	 */
	@Test
	void 일반_발주점_아래면_목표재고까지_포장_올림() {
		OrderResult r = calculator.calculate(OrderInput.constant(
				d("6000"), 2, d("3"), d("1500"), d("8000"), d("0"), d("5000"), null, null, null));

		assertThat(r.leadTimeDemand()).isEqualByComparingTo("12000");
		assertThat(r.reorderPoint()).isEqualByComparingTo("13500");
		assertThat(r.targetStock()).isEqualByComparingTo("31500");
		assertThat(r.shouldOrder()).isTrue();
		assertThat(r.rawQty()).isEqualByComparingTo("23500");
		assertThat(r.recommendedQty()).isEqualByComparingTo("25000");
		assertThat(r.cap()).isNull();
		assertThat(r.warnings()).isEmpty();
	}

	/**
	 * MOQ: 하루 2000g · L=1 · R=1 · SS=500 · 추정 재고 1000 · 입고 예정 1000 · 포장 1000g · MOQ 10000g
	 *
	 * <pre>
	 * ROP = 2000 × 1 + 500 = 2500     → 1000 + 1000 = 2000 ≤ 2500 발주
	 * S   = 2000 × (1 + 1) + 500 = 4500
	 * raw = 4500 − 2000 = 2500        → 포장 1000 올림 = 3000 → MOQ 10000 미만 → 10000
	 * </pre>
	 */
	@Test
	void MOQ_미만이면_MOQ로_올린다() {
		OrderResult r = calculator.calculate(OrderInput.constant(
				d("2000"), 1, d("1"), d("500"), d("1000"), d("1000"), d("1000"), d("10000"), null, null));

		assertThat(r.reorderPoint()).isEqualByComparingTo("2500");
		assertThat(r.shouldOrder()).isTrue();
		assertThat(r.rawQty()).isEqualByComparingTo("2500");
		assertThat(r.recommendedQty()).isEqualByComparingTo("10000");
		assertThat(r.warnings()).isEmpty();
	}

	/**
	 * 유통기한 상한: 하루 1000g · L=1 · R=7 · SS=300 · 추정 재고 500 · 입고 예정 0 · 포장 500g · 유통기한 4일 · max_order_qty 없음
	 *
	 * <pre>
	 * ROP = 1000 × 1 + 300 = 1300     → 500 ≤ 1300 발주
	 * S   = 1000 × (7 + 1) + 300 = 8300
	 * raw = 8300 − 500 = 7800         → 포장 500 올림 = 7800
	 * cap = 1000 × (4 − 1) − 500 = 2500 → 7800 > 2500 → 2500 (포장 500 배수) + SHELF_LIFE_CAP
	 * </pre>
	 */
	@Test
	void 유통기한_안에_못_쓰는_양은_자르고_경고() {
		OrderResult r = calculator.calculate(OrderInput.constant(
				d("1000"), 1, d("7"), d("300"), d("500"), d("0"), d("500"), null, 4, null));

		assertThat(r.rawQty()).isEqualByComparingTo("7800");
		assertThat(r.cap()).isEqualByComparingTo("2500");
		assertThat(r.recommendedQty()).isEqualByComparingTo("2500");
		assertThat(r.warnings()).containsExactly(OrderWarning.SHELF_LIFE_CAP);
		assertThat(r.capped()).isTrue();
	}

	/**
	 * max_order_qty: 일반 예와 같은데 1회 최대 20000g. 25000 → 20000 + MAX_ORDER_QTY_CAP.
	 * 상한이 포장 배수가 아니면(18000, 포장 5000) 포장 배수로 내린 15000.
	 */
	@Test
	void 최대_발주량_초과면_자르고_경고() {
		OrderResult r = calculator.calculate(OrderInput.constant(
				d("6000"), 2, d("3"), d("1500"), d("8000"), d("0"), d("5000"), null, null, d("20000")));
		assertThat(r.recommendedQty()).isEqualByComparingTo("20000");
		assertThat(r.warnings()).containsExactly(OrderWarning.MAX_ORDER_QTY_CAP);

		OrderResult floored = calculator.calculate(OrderInput.constant(
				d("6000"), 2, d("3"), d("1500"), d("8000"), d("0"), d("5000"), null, null, d("18000")));
		assertThat(floored.recommendedQty()).isEqualByComparingTo("15000");
	}

	/** 두 상한이 같은 값이면 경고 둘 다. */
	@Test
	void 유통기한_상한과_최대_발주량이_같으면_경고_둘_다() {
		// cap(유통기한) = 1000 × 3 − 500 = 2500, max_order_qty = 2500
		OrderResult r = calculator.calculate(OrderInput.constant(
				d("1000"), 1, d("7"), d("300"), d("500"), d("0"), null, null, 4, d("2500")));

		assertThat(r.recommendedQty()).isEqualByComparingTo("2500");
		assertThat(r.warnings()).containsExactly(OrderWarning.SHELF_LIFE_CAP, OrderWarning.MAX_ORDER_QTY_CAP);
	}

	/** 재고 포지션이 발주점보다 많으면 발주하지 않는다. 값은 그대로 돌려준다. */
	@Test
	void 발주점_위면_발주량_0() {
		OrderResult r = calculator.calculate(OrderInput.constant(
				d("6000"), 2, d("3"), d("1500"), d("20000"), d("0"), d("5000"), d("10000"), 3, d("1000")));

		assertThat(r.shouldOrder()).isFalse();
		assertThat(r.recommendedQty()).isEqualByComparingTo("0");
		assertThat(r.rawQty()).isEqualByComparingTo("11500");
		assertThat(r.warnings()).isEmpty();
	}

	/** 포지션 == 발주점이면 발주 (≤). */
	@Test
	void 발주점과_같으면_발주() {
		OrderResult r = calculator.calculate(OrderInput.constant(
				d("6000"), 2, d("3"), d("1500"), d("13500"), d("0"), null, null, null, null));

		assertThat(r.shouldOrder()).isTrue();
		assertThat(r.recommendedQty()).isEqualByComparingTo("18000");
	}

	/** 유통기한 1일 이하면 오늘 들어온 것도 못 쓴다 → 상한 0. 추정 재고가 상한을 넘어도 음수로 가지 않는다. */
	@Test
	void 유통기한_1일이면_상한_0() {
		OrderResult r = calculator.calculate(OrderInput.constant(
				d("1000"), 1, d("1"), d("0"), d("0"), d("0"), null, null, 1, null));

		assertThat(r.cap()).isEqualByComparingTo("0");
		assertThat(r.recommendedQty()).isEqualByComparingTo("0");
		assertThat(r.warnings()).containsExactly(OrderWarning.SHELF_LIFE_CAP);
	}

	/**
	 * 유통기한 상한이 포장 1개보다 작으면 최소 1회분 (지라 301, DECISIONS #87): 하루 100g · L=1 · R=1 · SS=50 · 재고 0 · 포장 1000g · 유통기한 3일
	 *
	 * <pre>
	 * ROP = 100 + 50 = 150 → 0 ≤ 150 발주,  S = 100 × 2 + 50 = 250 → 포장 올림 1000
	 * cap = 100 × (3 − 1) − 0 = 200 → 포장 내림 0 → 예전에는 추천 0. 이제 포장 1개 1000 + SHELF_LIFE_CAP
	 * </pre>
	 */
	@Test
	void 유통기한_상한이_포장_1개보다_작으면_최소_1회분() {
		OrderResult r = calculator.calculate(OrderInput.constant(
				d("100"), 1, d("1"), d("50"), d("0"), d("0"), d("1000"), null, 3, null));

		assertThat(r.shouldOrder()).isTrue();
		assertThat(r.cap()).isEqualByComparingTo("200");
		assertThat(r.recommendedQty()).isEqualByComparingTo("1000");
		assertThat(r.warnings()).containsExactly(OrderWarning.SHELF_LIFE_CAP);
		assertThat(OrderReason.capLine(IngredientUnit.G, r, null))
				.isEqualTo("유통기한 안에 쓸 양은 200g이지만 최소 주문 단위 1000g로 추천. 일부 남을 수 있음.");
	}

	/** 최소 1회분은 MOQ 도 따른다: MOQ 1500 → 포장 1000 배수로 올려 2000. */
	@Test
	void 유통기한_상한이_작을_때_최소_1회분은_MOQ_를_포장_배수로() {
		OrderResult r = calculator.calculate(OrderInput.constant(
				d("100"), 1, d("1"), d("50"), d("0"), d("0"), d("1000"), d("1500"), 3, null));

		assertThat(r.recommendedQty()).isEqualByComparingTo("2000");
		assertThat(r.warnings()).containsExactly(OrderWarning.SHELF_LIFE_CAP);
	}

	/** max_order_qty(500)가 포장 1개(1000)보다 작으면 사장님 상한을 넘지 않는다 → 0 그대로. */
	@Test
	void 유통기한_상한이_작아도_최대_발주량이_포장보다_작으면_0() {
		OrderResult r = calculator.calculate(OrderInput.constant(
				d("100"), 1, d("1"), d("50"), d("0"), d("0"), d("1000"), null, 3, d("500")));

		assertThat(r.cap()).isEqualByComparingTo("200");
		assertThat(r.recommendedQty()).isEqualByComparingTo("0");
		assertThat(r.warnings()).containsExactly(OrderWarning.SHELF_LIFE_CAP);
	}

	/** 상한이 max_order_qty 쪽(150)이면 최소 1회분으로 올리지 않는다 → 0. */
	@Test
	void 최대_발주량_상한이_포장보다_작으면_0() {
		OrderResult r = calculator.calculate(OrderInput.constant(
				d("100"), 1, d("1"), d("50"), d("0"), d("0"), d("1000"), null, 3, d("150")));

		assertThat(r.recommendedQty()).isEqualByComparingTo("0");
		assertThat(r.warnings()).containsExactly(OrderWarning.MAX_ORDER_QTY_CAP);
	}

	/** 유통기한 1일이면 상한 0 — 쓸 수 있는 양이 없으니 포장이 있어도 0 그대로. */
	@Test
	void 유통기한_상한_0이면_포장이_있어도_0() {
		OrderResult r = calculator.calculate(OrderInput.constant(
				d("100"), 1, d("1"), d("50"), d("0"), d("0"), d("1000"), null, 1, null));

		assertThat(r.recommendedQty()).isEqualByComparingTo("0");
	}

	/** 소수 수량(ml 소스 12.345)도 포장 올림이 맞는다: raw 37.035 → 포장 10 → 40. */
	@Test
	void 소수_수량_포장_올림() {
		OrderResult r = calculator.calculate(OrderInput.constant(
				d("12.345"), 1, d("2"), d("0"), d("0"), d("0"), d("10"), null, null, null));

		assertThat(r.rawQty()).isEqualByComparingTo("37.035");
		assertThat(r.recommendedQty()).isEqualByComparingTo("40");
	}

	/**
	 * 유통기한 상한은 입고 예정도 뺀다 (지라 263): 하루 1000 · 유통기한 4 → 쓸 수 있는 양 3000, 재고 500 + 입고 예정 1000 → 상한 1500.
	 * L=1 · R=7 · SS 300: ROP 1300 → 1500 ≤ 1300 아님… 재고를 200 으로 두면 ROP 1300 ≥ 1200 발주, 상한 = 3000 − 200 − 1000 = 1800.
	 */
	@Test
	void 유통기한_상한은_입고_예정도_뺀다() {
		OrderResult withOnOrder = calculator.calculate(OrderInput.constant(
				d("1000"), 1, d("7"), d("300"), d("200"), d("1000"), null, null, 4, null));
		OrderResult without = calculator.calculate(OrderInput.constant(
				d("1000"), 1, d("7"), d("300"), d("200"), d("0"), null, null, 4, null));

		assertThat(withOnOrder.shouldOrder()).isTrue();
		assertThat(withOnOrder.cap()).isEqualByComparingTo("1800");
		assertThat(withOnOrder.recommendedQty()).isEqualByComparingTo("1800");
		assertThat(withOnOrder.warnings()).containsExactly(OrderWarning.SHELF_LIFE_CAP);
		assertThat(without.cap()).isEqualByComparingTo("2800");
	}

	/** 소비도 안전재고도 없으면(목표 재고 0) MOQ 가 있어도 발주하지 않는다 (지라 263 — 219 의 수량 0 거르기를 MOQ 앞에서). */
	@Test
	void 목표_재고_0이면_MOQ가_있어도_발주하지_않는다() {
		OrderResult r = calculator.calculate(OrderInput.constant(
				d("0"), 1, d("1"), d("0"), d("0"), d("0"), d("1000"), d("10000"), null, null));

		assertThat(r.shouldOrder()).isFalse();
		assertThat(r.recommendedQty()).isZero();
	}

	/** 날짜별 합으로 직접 넣으면 요일 편차가 그대로 반영된다: 리드타임 2일 수요 1000+3000, 주기 수요 2000. */
	@Test
	void 날짜별_수요_합을_그대로_쓴다() {
		OrderResult r = calculator.calculate(new OrderInput(
				d("4000"), d("2000"), d("2000"), d("500"), d("1000"), d("0"), null, null, null, null));

		assertThat(r.leadTimeDemand()).isEqualByComparingTo("4000");
		assertThat(r.reorderPoint()).isEqualByComparingTo("4500");
		assertThat(r.targetStock()).isEqualByComparingTo("6500");
		assertThat(r.recommendedQty()).isEqualByComparingTo("5500");
	}

	@Test
	void 음수_입력은_거부() {
		assertThatThrownBy(() -> OrderInput.constant(d("-1"), 1, d("1"), d("0"), d("0"), d("0"), null, null, null, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> OrderInput.constant(d("1"), 1, d("1"), d("0"), d("0"), d("0"), d("0"), null, null, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 이유_문장_템플릿() {
		OrderResult r = calculator.calculate(OrderInput.constant(
				d("6000"), 2, d("3"), d("1500"), d("8000"), d("0"), d("5000"), null, null, null));

		String reason = OrderReason.render(IngredientUnit.G, d("8000.000"), d("0"), 2, r, d("1500"),
				LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 21), false);

		assertThat(reason).isEqualTo(
				"현재 추정 재고 8000g, 입고 예정 0g. 배송까지 2일간 예상 소비 12000g, 안전재고 1500g. "
						+ "10월 21일 소진 예상. 오늘 주문하면 10월 21일 도착.");
	}

	/** 가격 한 줄 (3주 계획 4.8절). 단위 단가는 소수 넷째 자리까지, 끝의 0 은 뗀다 (DECISIONS #57). */
	@Test
	void 가격_한_줄_비쌈_쌈() {
		PriceIndex high = new PriceIndex(PriceLevel.HIGH, d("1.1500"), d("4.0000"), d("4.6000"), 3, PriceIndexReason.OK);
		assertThat(OrderReason.priceLine(IngredientUnit.G, high, 2))
				.isEqualTo("단가 4.6원/g, 최근 8주보다 15% 높음. 2일치만 주문.");

		PriceIndex low = new PriceIndex(PriceLevel.LOW, d("0.8571"), d("1400"), d("1200"), 4, PriceIndexReason.OK);
		assertThat(OrderReason.priceLine(IngredientUnit.EA, low, 5))
				.isEqualTo("단가 1200원/개, 최근 8주보다 14% 낮음. 5일치 미리 주문.");

		PriceIndex tiny = new PriceIndex(PriceLevel.HIGH, d("1.2500"), d("0.0100"), d("0.012345"), 3, PriceIndexReason.OK);
		assertThat(OrderReason.priceLine(IngredientUnit.ML, tiny, 1)).startsWith("단가 0.0123원/ml,");
	}

	/** 상한 한 줄 (3주 계획 8절 "자르고 경고"). 어느 상한에 걸렸는지에 따라 문장이 다르다. */
	@Test
	void 상한_한_줄_최대_발주량_유통기한_둘_다() {
		OrderResult max = calculator.calculate(OrderInput.constant(
				d("6000"), 2, d("3"), d("1500"), d("8000"), d("0"), d("5000"), null, null, d("20000")));
		assertThat(OrderReason.capLine(IngredientUnit.G, max, d("20000.000")))
				.isEqualTo("1회 최대 발주량 20000g을 넘어 20000g로 줄임.");

		// cap(유통기한) = 1000 × 3 − 500 = 2500
		OrderResult shelf = calculator.calculate(OrderInput.constant(
				d("1000"), 1, d("7"), d("300"), d("500"), d("0"), null, null, 4, null));
		assertThat(OrderReason.capLine(IngredientUnit.EA, shelf, null))
				.isEqualTo("유통기한 안에 쓸 수 있는 2500개로 줄임.");

		OrderResult both = calculator.calculate(OrderInput.constant(
				d("1000"), 1, d("7"), d("300"), d("500"), d("0"), null, null, 4, d("2500")));
		assertThat(OrderReason.capLine(IngredientUnit.ML, both, d("2500")))
				.isEqualTo("유통기한 · 1회 최대 발주량에 맞춰 2500ml로 줄임.");
	}

	@Test
	void 상한_한_줄_안_잘렸으면_거부() {
		OrderResult r = calculator.calculate(OrderInput.constant(
				d("6000"), 2, d("3"), d("1500"), d("8000"), d("0"), d("5000"), null, null, null));
		assertThatThrownBy(() -> OrderReason.capLine(IngredientUnit.G, r, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 가격_한_줄_보통이면_거부() {
		PriceIndex normal = new PriceIndex(PriceLevel.NORMAL, d("1"), null, d("4"), 2, PriceIndexReason.TOO_FEW);
		assertThatThrownBy(() -> OrderReason.priceLine(IngredientUnit.G, normal, 2))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 이유_문장_소진일_모르면_그_문장을_뺀다() {
		OrderResult r = calculator.calculate(OrderInput.constant(
				d("0.5"), 1, d("1"), d("0.25"), d("1.5"), d("0"), null, null, null, null));

		String reason = OrderReason.render(IngredientUnit.EA, d("1.5"), d("0"), 1, r, d("0.25"), null,
				LocalDate.of(2026, 11, 2), false);

		assertThat(reason).isEqualTo(
				"현재 추정 재고 1.5개, 입고 예정 0개. 배송까지 1일간 예상 소비 0.5개, 안전재고 0.25개. "
						+ "오늘 주문하면 11월 2일 도착.");
	}
}
