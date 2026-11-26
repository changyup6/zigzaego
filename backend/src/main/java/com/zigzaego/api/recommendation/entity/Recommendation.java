package com.zigzaego.api.recommendation.entity;

import com.zigzaego.api.domain.price.PriceIndexCalculator.PriceLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 하루 한 재료 한 건의 발주 추천. 표는 V1 {@code recommendation}, {@code (store_id, ingredient_id, run_date)} unique.
 * 생성은 {@code RecommendationService} 가 {@code INSERT ... ON CONFLICT DO NOTHING} 으로만 한다 — 같은 날 다시 돌려도 기존 건을 둔다.
 * 승인 · 수정 · 거절은 {@code RecommendationService.decide} 가 {@code UPDATE ... WHERE status = 'PENDING'} 으로 한 번만 바꾼다.
 */
@Entity
@Table(name = "recommendation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Recommendation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "store_id", nullable = false)
	private Long storeId;

	@Column(name = "ingredient_id", nullable = false)
	private Long ingredientId;

	@Column(name = "run_date", nullable = false)
	private LocalDate runDate;

	@Column(name = "est_stock", nullable = false, precision = 14, scale = 3)
	private BigDecimal estStock;

	@Column(name = "on_order", nullable = false, precision = 14, scale = 3)
	private BigDecimal onOrder;

	@Column(name = "lead_time_demand", nullable = false, precision = 14, scale = 3)
	private BigDecimal leadTimeDemand;

	@Column(name = "safety_stock", nullable = false, precision = 14, scale = 3)
	private BigDecimal safetyStock;

	@Column(name = "reorder_point", nullable = false, precision = 14, scale = 3)
	private BigDecimal reorderPoint;

	@Column(name = "recommended_qty", nullable = false, precision = 14, scale = 3)
	private BigDecimal recommendedQty;

	/** 가격 지수(현재 단가 ÷ 기준 단가). 가격 연동을 안 켰거나 대표 상품이 없으면 null. */
	@Column(name = "price_ratio", precision = 6, scale = 4)
	private BigDecimal priceRatio;

	@Enumerated(EnumType.STRING)
	@Column(name = "price_level", length = 10)
	private PriceLevel priceLevel;

	/** 실제로 쓴 발주 주기. 가격 연동이 없으면 R 그대로. */
	@Column(name = "cycle_days_used", precision = 6, scale = 2)
	private BigDecimal cycleDaysUsed;

	@Column(columnDefinition = "TEXT")
	private String reason;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private RecommendationStatus status;

	@Column(name = "final_qty", precision = 14, scale = 3)
	private BigDecimal finalQty;

	@Column(name = "decided_by")
	private Long decidedBy;

	@Column(name = "decided_at")
	private OffsetDateTime decidedAt;

	@Column(name = "reject_reason", columnDefinition = "TEXT")
	private String rejectReason;

	@Column(name = "runout_date")
	private LocalDate runoutDate;

	/** 이 추천이 사라고 한 상품. null 이면 재료에 대표 상품이 없었다. */
	@Column(name = "supplier_product_id")
	private Long supplierProductId;

	@Column(name = "next_order_date")
	private LocalDate nextOrderDate;

	/** {@code forecast.model} 값 (timesfm · dow_avg). */
	@Column(name = "forecast_model", nullable = false, length = 50)
	private String forecastModel;

	@Enumerated(EnumType.STRING)
	@Column(name = "ss_method", nullable = false, length = 10)
	private SafetyStockMethod ssMethod;
}
