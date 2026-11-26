package com.zigzaego.api.recommendation.dto;

import com.zigzaego.api.domain.price.PriceIndexCalculator.PriceLevel;
import com.zigzaego.api.global.config.ClockConfig;
import com.zigzaego.api.global.response.Amounts;
import com.zigzaego.api.global.review.ReviewReason;
import com.zigzaego.api.ingredient.entity.Ingredient;
import com.zigzaego.api.ingredient.entity.IngredientUnit;
import com.zigzaego.api.recommendation.entity.Recommendation;
import com.zigzaego.api.recommendation.entity.RecommendationStatus;
import com.zigzaego.api.recommendation.entity.SafetyStockMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** 발주 추천 한 건. 수량은 재료 기준 단위, 소수 3자리 (DECISIONS #42). */
@Schema(description = "발주 추천 한 건")
public record RecommendationResponse(
		@Schema(description = "추천 id (문자열)", example = "12")
		String id,

		@Schema(description = "재료 id (문자열)", example = "3")
		String ingredientId,

		@Schema(description = "재료 이름", example = "등심")
		String ingredientName,

		@Schema(description = "재료 기준 단위", example = "G")
		IngredientUnit unit,

		@Schema(description = "추천 생성일 (YYYY-MM-DD)", example = "2026-10-10", type = "string", format = "date")
		String runDate,

		@Schema(description = "추정 재고", example = "2500.000")
		BigDecimal estStock,

		@Schema(description = "입고 예정(승인됐지만 입고 확인 안 된 양)", example = "0.000")
		BigDecimal onOrder,

		@Schema(description = "리드타임 수요 = 하루 예상 소비 × L", example = "1500.000")
		BigDecimal leadTimeDemand,

		@Schema(description = "안전재고", example = "3000.000")
		BigDecimal safetyStock,

		@Schema(description = "발주점 = 리드타임 수요 + 안전재고", example = "4500.000")
		BigDecimal reorderPoint,

		@Schema(description = "추천 발주량(포장 올림 · MOQ · 상한 적용 뒤)", example = "4000.000")
		BigDecimal recommendedQty,

		@Schema(description = "가격 지수(현재 단가 ÷ 최근 8주 기준 단가). 가격 연동이 꺼져 있으면 null",
				example = "1.0000", nullable = true)
		BigDecimal priceRatio,

		@Schema(description = "가격 수준. 가격 연동이 꺼져 있으면 null", example = "NORMAL", nullable = true)
		PriceLevel priceLevel,

		@Schema(description = "실제로 쓴 발주 주기(일). 가격 연동이면 조정된 값", example = "1.00", nullable = true)
		BigDecimal cycleDaysUsed,

		@Schema(description = "추천 이유 문장 (3주 계획 4.6절)", nullable = true)
		String reason,

		@Schema(description = "상태. PENDING → APPROVED | REJECTED 한 번만", example = "PENDING")
		RecommendationStatus status,

		@Schema(description = "사장님이 정한 최종 수량. 아직 결정 전이면 null", nullable = true)
		BigDecimal finalQty,

		@Schema(description = "결정 시각 (ISO-8601, +09:00). 결정 전이면 null", example = "2026-10-14T12:00:00+09:00",
				nullable = true)
		String decidedAt,

		@Schema(description = "거절 사유. REJECTED 가 아니거나 사유를 안 적었으면 null", example = "재고가 충분함", nullable = true)
		String rejectReason,

		@Schema(description = "소진 예상일 (YYYY-MM-DD). 계산 못 하면 null", example = "2026-10-12",
				type = "string", format = "date", nullable = true)
		String runoutDate,

		@Schema(description = "이 추천이 사라고 한 거래처 상품 id (문자열). 대표 상품이 없으면 null", example = "7", nullable = true)
		String supplierProductId,

		@Schema(description = "다음 주문할 날 (YYYY-MM-DD)", example = "2026-10-10", type = "string", format = "date",
				nullable = true)
		String nextOrderDate,

		@Schema(description = "계산에 쓴 예측 모델 (forecast.model 값). timesfm 또는 dow_avg", example = "dow_avg")
		String forecastModel,

		@Schema(description = "안전재고 식. STAT=z×σ×√(R+L) / DAYS_N=최근 7일 평균×N", example = "DAYS_N")
		SafetyStockMethod ssMethod,

		@Schema(description = "확인 필요 — 추정값이 섞인 추천 (설계 6절)", example = "false")
		boolean needsReview,

		@Schema(description = "확인 필요 이유. UNANSWERED=AI 추정 레시피 / NEEDS_COUNT=실사 기록 없음 / "
				+ "SUPPLIER_UNSET=거래처 리드타임 · 발주 주기 없음", example = "[]")
		List<ReviewReason> reviewReasons
) {

	public static RecommendationResponse of(Recommendation r, Ingredient ingredient, List<ReviewReason> reasons) {
		return new RecommendationResponse(
				String.valueOf(r.getId()),
				String.valueOf(r.getIngredientId()),
				ingredient.getName(),
				ingredient.getUnit(),
				r.getRunDate().toString(),
				Amounts.qty(r.getEstStock()),
				Amounts.qty(r.getOnOrder()),
				Amounts.qty(r.getLeadTimeDemand()),
				Amounts.qty(r.getSafetyStock()),
				Amounts.qty(r.getReorderPoint()),
				Amounts.qty(r.getRecommendedQty()),
				r.getPriceRatio() == null ? null : r.getPriceRatio().setScale(4, RoundingMode.HALF_UP),
				r.getPriceLevel(),
				r.getCycleDaysUsed() == null ? null : r.getCycleDaysUsed().setScale(2, RoundingMode.HALF_UP),
				r.getReason(),
				r.getStatus(),
				Amounts.qty(r.getFinalQty()),
				r.getDecidedAt() == null ? null
						: r.getDecidedAt().atZoneSameInstant(ClockConfig.SERVICE_ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
				r.getRejectReason(),
				r.getRunoutDate() == null ? null : r.getRunoutDate().toString(),
				r.getSupplierProductId() == null ? null : String.valueOf(r.getSupplierProductId()),
				r.getNextOrderDate() == null ? null : r.getNextOrderDate().toString(),
				r.getForecastModel(),
				r.getSsMethod(),
				!reasons.isEmpty(),
				reasons);
	}
}
