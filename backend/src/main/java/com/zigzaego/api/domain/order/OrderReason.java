package com.zigzaego.api.domain.order;

import com.zigzaego.api.domain.price.PriceIndexCalculator.PriceIndex;
import com.zigzaego.api.domain.price.PriceIndexCalculator.PriceLevel;
import com.zigzaego.api.domain.price.PriceIndexCalculator.PriceIndex;
import com.zigzaego.api.domain.price.PriceIndexCalculator.PriceLevel;
import com.zigzaego.api.ingredient.entity.IngredientUnit;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 추천 이유 문장 (3주 계획 4.6절). 템플릿 한 줄, LLM 없음. {@code recommendation.reason} 에 그대로 저장한다.
 *
 * <pre>
 * 현재 추정 재고 {est}{unit}, 입고 예정 {on_order}{unit}. 배송까지 {L}일간 예상 소비 {ltd}{unit}, 안전재고 {ss}{unit}.
 * {exhaust_date} 소진 예상. 오늘 주문하면 {arrival_date} 도착.
 * </pre>
 *
 * <p>단위는 재료 기준 단위 그대로(환산 없음, DECISIONS #33). 소진 예상일을 모르면 그 문장은 뺀다.
 *
 * <p>가격 연동(3주 계획 4.8절)이 걸린 재료는 {@link #priceLine} 한 줄을 뒤에 붙인다. 보통이면 붙이지 않는다.
 * 상한(유통기한 · max_order_qty)에 걸려 발주량을 잘랐으면 {@link #capLine} 한 줄을 맨 뒤에 붙인다 (3주 계획 8절 "자르고 경고").
 */
public final class OrderReason {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M월 d일");

	private OrderReason() {
	}

	/**
	 * @param unit        재료 기준 단위
	 * @param estStock    추정 재고
	 * @param onOrder     입고 예정
	 * @param leadTimeDays 리드타임 L(일)
	 * @param result      발주 계산 결과 (리드타임 수요 · 안전재고는 여기서 꺼낸다)
	 * @param safetyStock 안전재고 SS
	 * @param runOutDate  소진 예상일. 모르면 null
	 * @param arrivalDate 오늘 주문 시 도착일 (배송 요일 · 마감 반영, DeliverySchedule)
	 * @param afterCutoff 주문 마감이 지나 실제 주문이 내일인지
	 */
	public static String render(IngredientUnit unit, BigDecimal estStock, BigDecimal onOrder, int leadTimeDays,
			OrderCalculator.OrderResult result, BigDecimal safetyStock, LocalDate runOutDate, LocalDate arrivalDate,
			boolean afterCutoff) {
		String u = unitLabel(unit);
		StringBuilder sb = new StringBuilder();
		sb.append("현재 추정 재고 ").append(qty(estStock)).append(u)
				.append(", 입고 예정 ").append(qty(onOrder)).append(u).append(". ")
				.append("배송까지 ").append(leadTimeDays).append("일간 예상 소비 ").append(qty(result.leadTimeDemand())).append(u)
				.append(", 안전재고 ").append(qty(safetyStock)).append(u).append(". ");
		if (runOutDate != null) {
			sb.append(runOutDate.format(DATE)).append(" 소진 예상. ");
		}
		sb.append(afterCutoff ? "주문 마감이 지나 내일 주문하면 " : "오늘 주문하면 ")
				.append(arrivalDate.format(DATE)).append(" 도착.");
		return sb.toString();
	}

	/**
	 * 가격 연동 한 줄 (3주 계획 4.8절). 비쌈 · 쌈일 때만 부른다 — 보통(이력 부족 · 최근 이력 없음 포함)이면 줄을 넣지 않는다.
	 *
	 * <pre>
	 * 단가 {price}원/{unit}, 최근 8주보다 {pct}% 높음. {L+R'}일치만 주문.
	 * 단가 {price}원/{unit}, 최근 8주보다 {pct}% 낮음. {L+R'}일치 미리 주문.
	 * </pre>
	 *
	 * <p>단가는 단위 단가(원 / 기준 단위)라 소수 넷째 자리까지 쓰고 끝의 0 은 뗀다 (DECISIONS #57, 4.6원/g).
	 * pct 는 |지수 − 1| × 100 을 정수로 반올림.
	 *
	 * @param unit        재료 기준 단위
	 * @param index       가격 지수. level 이 HIGH 또는 LOW 여야 한다
	 * @param horizonDays L + R' (일). 목표 재고가 덮는 날수
	 * @throws IllegalArgumentException level 이 NORMAL 이면
	 */
	public static String priceLine(IngredientUnit unit, PriceIndex index, int horizonDays) {
		if (index.level() == PriceLevel.NORMAL) {
			throw new IllegalArgumentException("price line is only for HIGH or LOW");
		}
		boolean high = index.level() == PriceLevel.HIGH;
		BigDecimal pct = index.index().subtract(BigDecimal.ONE).abs().multiply(BigDecimal.valueOf(100))
				.setScale(0, RoundingMode.HALF_UP);
		return "단가 " + unitPrice(index.currentPrice()) + "원/" + unitLabel(unit)
				+ ", 최근 8주보다 " + pct.toPlainString() + "% " + (high ? "높음" : "낮음") + ". "
				+ horizonDays + (high ? "일치만 주문." : "일치 미리 주문.");
	}

	/**
	 * 상한 경고 한 줄 (3주 계획 4.5절 · 8절). 유통기한 상한 · 품목별 1회 최대 발주량(max_order_qty)에 걸려 발주량을 잘랐을 때만
	 * 부른다 — 안 잘렸으면 줄을 넣지 않는다.
	 *
	 * <pre>
	 * 1회 최대 발주량 {max}{unit}을 넘어 {qty}{unit}로 줄임.
	 * 유통기한 안에 쓸 수 있는 {qty}{unit}로 줄임.
	 * 유통기한 · 1회 최대 발주량에 맞춰 {qty}{unit}로 줄임.
	 * </pre>
	 *
	 * @param unit        재료 기준 단위
	 * @param result      발주 계산 결과. {@code capped()} 가 true 여야 한다
	 * @param maxOrderQty 품목별 1회 최대 발주량. MAX_ORDER_QTY_CAP 이 아니면 안 쓴다
	 * @throws IllegalArgumentException 상한에 안 걸린 결과면
	 */
	public static String capLine(IngredientUnit unit, OrderCalculator.OrderResult result, BigDecimal maxOrderQty) {
		if (!result.capped()) {
			throw new IllegalArgumentException("cap line is only for a capped result");
		}
		String u = unitLabel(unit);
		String qty = qty(result.recommendedQty()) + u;
		boolean shelf = result.warnings().contains(OrderCalculator.OrderWarning.SHELF_LIFE_CAP);
		boolean max = result.warnings().contains(OrderCalculator.OrderWarning.MAX_ORDER_QTY_CAP);
		if (shelf && max) {
			return "유통기한 · 1회 최대 발주량에 맞춰 " + qty + "로 줄임.";
		}
		if (shelf && result.cap() != null && result.recommendedQty().compareTo(result.cap()) > 0) {
			// 유통기한 안에 쓸 양이 최소 1회분(포장 · MOQ)보다 적다 — 줄이지 못하고 최소 1회분으로 (지라 301)
			return "유통기한 안에 쓸 양은 " + qty(result.cap()) + u + "이지만 최소 주문 단위 " + qty + "로 추천. 일부 남을 수 있음.";
		}
		if (shelf) {
			return "유통기한 안에 쓸 수 있는 " + qty + "로 줄임.";
		}
		return "1회 최대 발주량 " + qty(maxOrderQty) + u + "을 넘어 " + qty + "로 줄임.";
	}

	/**
	 * 반복 손실 한 줄 (지라 253). 실사 차이가 반복돼 안전재고에 평균 손실량을 더했을 때만 부른다.
	 *
	 * <pre>
	 * 실사 때마다 평균 {loss}{unit} 부족.
	 * </pre>
	 *
	 * @param averageLoss 최근 실사의 평균 손실량(기준 단위). 정수로 반올림해 쓴다
	 */
	public static String shortfallLine(IngredientUnit unit, BigDecimal averageLoss) {
		return "실사 때마다 평균 " + strip(averageLoss, 0) + unitLabel(unit) + " 부족.";
	}

	/** 소수 셋째 자리 반올림 뒤 끝의 0 은 뗀다 (6000.000 → 6000, 0.500 → 0.5). */
	static String qty(BigDecimal value) {
		return strip(value, 3);
	}

	/** 단위 단가: 소수 넷째 자리 반올림 뒤 끝의 0 은 뗀다 (4.6000 → 4.6, 12.0000 → 12). */
	static String unitPrice(BigDecimal value) {
		return strip(value, 4);
	}

	private static String strip(BigDecimal value, int scale) {
		BigDecimal rounded = value.setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros();
		if (rounded.scale() < 0) {
			rounded = rounded.setScale(0);
		}
		return rounded.toPlainString();
	}

	static String unitLabel(IngredientUnit unit) {
		return switch (unit) {
			case EA -> "개";
			case G -> "g";
			case ML -> "ml";
		};
	}
}
