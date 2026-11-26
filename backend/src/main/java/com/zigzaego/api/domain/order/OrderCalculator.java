package com.zigzaego.api.domain.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 재료 하나의 발주 판단과 발주량 (설계 5.4절, 3주 계획 4.4 · 4.5절).
 *
 * <pre>
 * 발주점     ROP = 리드타임 수요(Σ 예측 소비, 오늘+1 … 도착일) + SS
 * 발주 여부  추정 재고 + 입고 예정 ≤ ROP, 그리고 목표 재고 &gt; 0
 * 목표 재고  S   = 리드타임 수요 + 주기 수요(Σ 예측 소비, 도착일+1 … 다음 도착일) + SS
 * 발주량     S − 추정 재고 − 입고 예정
 *            → 포장 단위 올림 → MOQ 이상(올린 뒤 다시 포장 배수로)
 *            → 상한 min(하루 소비 × (유통기한 − 1) − 추정 재고 − 입고 예정, max_order_qty) 로 자르고 경고(포장 배수로 내림)
 *            → 유통기한 상한이 0 보다 큰데 내림이 0 이면 최소 1회분 max(포장 1개, MOQ) — max_order_qty 이하일 때만 (DECISIONS #87)
 * </pre>
 *
 * <p>수요를 날짜별 합으로 받는다(3주 계획 4.4 · 4.5절, DECISIONS #89) — 휴무일 · 요일 편차가 그대로 들어온다.
 * 판매가 일정하면 {@link OrderInput#constant} 가 하루 소비 × 날수로 같은 값을 만든다.
 *
 * <p>상태 없는 순수 계산이다. 하루 예상 소비 · 안전재고(5.3절) · 추정 재고 · 입고 예정은 호출하는 쪽이 재료 기준 단위로
 * 계산해 넘긴다. 포장 · MOQ 도 기준 단위다 (포장 1개 = {@code supplier_product.base_qty_per_pack}).
 * 가격 연동(3주 계획 4.8절)은 R 만 바꿔 넘기면 된다 — SS · ROP 는 R 과 무관하다.
 *
 * <p>수량은 소수 그대로 계산하고 응답 직전에 셋째 자리 반올림 (DECISIONS #42). 여기서는 반올림하지 않는다.
 */
public class OrderCalculator {

	/** 상한에 걸려 발주량을 잘랐을 때의 경고. */
	public enum OrderWarning {
		/** 하루 소비 × (유통기한 − 1) − 추정 재고 − 입고 예정 을 넘어 유통기한 안에 못 쓴다. */
		SHELF_LIFE_CAP,
		/** 품목별 1회 최대 발주량(max_order_qty)을 넘었다. */
		MAX_ORDER_QTY_CAP
	}

	/**
	 * 발주 계산 입력. 수량은 전부 재료 기준 단위.
	 *
	 * @param leadTimeDemand 리드타임 수요 = Σ 예측 소비(오늘+1 … 도착일). 0 이상
	 * @param cycleDemand    주기 수요 = Σ 예측 소비(도착일+1 … 다음 도착일). 가격 연동이면 R' 일치. 0 이상
	 * @param dailyDemand    하루 예상 소비(구간 평균) — 유통기한 상한에만 쓴다. 0 이상
	 * @param safetyStock   안전재고 SS. 0 이상
	 * @param estStock      추정 재고. 0 이상 (음수는 호출자가 0 으로 보정한다, DECISIONS #9)
	 * @param onOrder       입고 예정(승인됐지만 입고 확인 안 된 양). 0 이상
	 * @param packQty       포장 1개의 기준 단위 양. 모르면 null → 올림 없음
	 * @param moq           최소 주문량(기준 단위). 모르면 null
	 * @param shelfLifeDays 유통기한(일). 모르면 null → 유통기한 상한 없음. 1 이하면 상한 0
	 * @param maxOrderQty   1회 최대 발주량(기준 단위). 없으면 null
	 */
	public record OrderInput(BigDecimal leadTimeDemand, BigDecimal cycleDemand, BigDecimal dailyDemand,
			BigDecimal safetyStock, BigDecimal estStock, BigDecimal onOrder, BigDecimal packQty, BigDecimal moq,
			Integer shelfLifeDays, BigDecimal maxOrderQty) {

		/** 판매가 일정할 때: 리드타임 수요 = 하루 소비 × L, 주기 수요 = 하루 소비 × R. 손계산 · 테스트용. */
		public static OrderInput constant(BigDecimal dailyDemand, int leadTimeDays, BigDecimal cycleDays,
				BigDecimal safetyStock, BigDecimal estStock, BigDecimal onOrder, BigDecimal packQty, BigDecimal moq,
				Integer shelfLifeDays, BigDecimal maxOrderQty) {
			Objects.requireNonNull(dailyDemand, "dailyDemand");
			Objects.requireNonNull(cycleDays, "cycleDays");
			if (leadTimeDays < 0) {
				throw new IllegalArgumentException("leadTimeDays must be >= 0");
			}
			return new OrderInput(dailyDemand.multiply(BigDecimal.valueOf(leadTimeDays)), dailyDemand.multiply(cycleDays),
					dailyDemand, safetyStock, estStock, onOrder, packQty, moq, shelfLifeDays, maxOrderQty);
		}

		public OrderInput {
			Objects.requireNonNull(leadTimeDemand, "leadTimeDemand");
			Objects.requireNonNull(cycleDemand, "cycleDemand");
			Objects.requireNonNull(dailyDemand, "dailyDemand");
			Objects.requireNonNull(safetyStock, "safetyStock");
			Objects.requireNonNull(estStock, "estStock");
			Objects.requireNonNull(onOrder, "onOrder");
			requireNonNegative(leadTimeDemand, "leadTimeDemand");
			requireNonNegative(cycleDemand, "cycleDemand");
			requireNonNegative(dailyDemand, "dailyDemand");
			requireNonNegative(safetyStock, "safetyStock");
			requireNonNegative(estStock, "estStock");
			requireNonNegative(onOrder, "onOrder");
			if (packQty != null && packQty.signum() <= 0) {
				throw new IllegalArgumentException("packQty must be > 0");
			}
			if (moq != null && moq.signum() < 0) {
				throw new IllegalArgumentException("moq must be >= 0");
			}
			if (maxOrderQty != null && maxOrderQty.signum() < 0) {
				throw new IllegalArgumentException("maxOrderQty must be >= 0");
			}
		}

		private static void requireNonNegative(BigDecimal value, String name) {
			if (value.signum() < 0) {
				throw new IllegalArgumentException(name + " must be >= 0");
			}
		}
	}

	/**
	 * 발주 계산 결과. recommendation 표의 칸과 1:1 — lead_time_demand · safety_stock · reorder_point · recommended_qty.
	 *
	 * @param leadTimeDemand 리드타임 수요 (입력 그대로)
	 * @param reorderPoint   발주점 ROP
	 * @param targetStock    목표 재고 S
	 * @param shouldOrder    추정 재고 + 입고 예정 ≤ ROP 이고 S &gt; 0 (소비도 안전재고도 없으면 살 이유가 없다)
	 * @param rawQty         상한 · 포장 · MOQ 적용 전 S − 추정 재고 − 입고 예정. 음수일 수 있다
	 * @param recommendedQty 최종 발주량. 발주 불필요면 0. 유통기한 상한이 최소 1회분보다 작으면 cap 보다 클 수 있다
	 * @param cap            적용된 상한(min(유통기한 상한, max_order_qty)). 둘 다 없으면 null
	 * @param warnings       상한에 걸려 잘랐으면 그 이유. 발주 불필요면 빈 목록
	 */
	public record OrderResult(BigDecimal leadTimeDemand, BigDecimal reorderPoint, BigDecimal targetStock,
			boolean shouldOrder, BigDecimal rawQty, BigDecimal recommendedQty, BigDecimal cap,
			List<OrderWarning> warnings) {

		public boolean capped() {
			return !warnings.isEmpty();
		}
	}

	public OrderResult calculate(OrderInput in) {
		BigDecimal leadTimeDemand = in.leadTimeDemand();
		BigDecimal reorderPoint = leadTimeDemand.add(in.safetyStock());
		BigDecimal targetStock = leadTimeDemand.add(in.cycleDemand()).add(in.safetyStock());
		BigDecimal position = in.estStock().add(in.onOrder());
		// 목표 재고 0 = 소비도 안전재고도 없다. MOQ 가 있어도 채울 이유가 없다 (지라 263)
		boolean shouldOrder = position.compareTo(reorderPoint) <= 0 && targetStock.signum() > 0;
		BigDecimal rawQty = targetStock.subtract(position);
		BigDecimal cap = cap(in);

		if (!shouldOrder) {
			return new OrderResult(leadTimeDemand, reorderPoint, targetStock, false, rawQty, BigDecimal.ZERO, cap,
					List.of());
		}

		BigDecimal qty = rawQty.max(BigDecimal.ZERO);
		qty = ceilToPack(qty, in.packQty());
		if (in.moq() != null && qty.compareTo(in.moq()) < 0) {
			qty = ceilToPack(in.moq(), in.packQty());
		}

		List<OrderWarning> warnings = new ArrayList<>();
		if (cap != null && qty.compareTo(cap) > 0) {
			BigDecimal shelfLifeCap = shelfLifeCap(in);
			if (shelfLifeCap != null && shelfLifeCap.compareTo(cap) == 0) {
				warnings.add(OrderWarning.SHELF_LIFE_CAP);
			}
			if (in.maxOrderQty() != null && in.maxOrderQty().compareTo(cap) == 0) {
				warnings.add(OrderWarning.MAX_ORDER_QTY_CAP);
			}
			qty = floorToPack(cap.max(BigDecimal.ZERO), in.packQty());
			if (qty.signum() == 0 && shelfLifeCap != null && shelfLifeCap.compareTo(cap) == 0 && cap.signum() > 0) {
				// 유통기한 안에 쓸 양이 포장 1개(MOQ)보다 적어 내림하면 0 — 발주점 아래인데 추천이 사라진다 (지라 301).
				// 살 수 있는 최소 1회분을 추천하고 SHELF_LIFE_CAP 경고를 남긴다. max_order_qty 는 넘지 않는다
				BigDecimal minimum = minimumOrder(in);
				if (minimum.signum() > 0 && (in.maxOrderQty() == null || minimum.compareTo(in.maxOrderQty()) <= 0)) {
					qty = minimum;
				}
			}
		}
		return new OrderResult(leadTimeDemand, reorderPoint, targetStock, true, rawQty, qty, cap, List.copyOf(warnings));
	}

	/** 살 수 있는 최소 1회분: max(포장 1개, MOQ) 를 포장 배수로 올림. 포장을 모르면 MOQ, 둘 다 없으면 0. */
	private static BigDecimal minimumOrder(OrderInput in) {
		BigDecimal floor = in.packQty() != null ? in.packQty() : BigDecimal.ZERO;
		if (in.moq() != null) {
			floor = floor.max(in.moq());
		}
		return ceilToPack(floor, in.packQty());
	}

	/** min(유통기한 상한, max_order_qty). 둘 다 없으면 null. 0 아래로는 내려가지 않는다. */
	private static BigDecimal cap(OrderInput in) {
		BigDecimal shelf = shelfLifeCap(in);
		BigDecimal max = in.maxOrderQty();
		if (shelf == null && max == null) {
			return null;
		}
		if (shelf == null) {
			return max;
		}
		if (max == null) {
			return shelf;
		}
		return shelf.min(max);
	}

	/** 하루 소비 × (유통기한 − 1) − 추정 재고 − 입고 예정. 유통기한을 모르면 null. 음수면 0. 들어올 것도 그 안에 써야 한다. */
	private static BigDecimal shelfLifeCap(OrderInput in) {
		if (in.shelfLifeDays() == null) {
			return null;
		}
		int usableDays = Math.max(in.shelfLifeDays() - 1, 0);
		return in.dailyDemand().multiply(BigDecimal.valueOf(usableDays))
				.subtract(in.estStock()).subtract(in.onOrder()).max(BigDecimal.ZERO);
	}

	private static BigDecimal ceilToPack(BigDecimal qty, BigDecimal packQty) {
		if (packQty == null || qty.signum() <= 0) {
			return qty;
		}
		return qty.divide(packQty, 0, RoundingMode.CEILING).multiply(packQty);
	}

	private static BigDecimal floorToPack(BigDecimal qty, BigDecimal packQty) {
		if (packQty == null) {
			return qty;
		}
		return qty.divide(packQty, 0, RoundingMode.FLOOR).multiply(packQty);
	}
}
