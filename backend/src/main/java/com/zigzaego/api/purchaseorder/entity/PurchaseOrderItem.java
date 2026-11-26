package com.zigzaego.api.purchaseorder.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * 발주서 안의 품목 한 줄. 표는 V1 {@code purchase_order_item}.
 *
 * <p>{@code qty} 는 <b>포장 단위</b>(대파 2단 → 2)다. 입고 예정량 · 입고량은 {@code supplier_product.base_qty_per_pack} 을 곱해
 * 기준 단위로 바꿔 센다 (RecommendationService.ON_ORDER_SQL · InventoryEstimateService). 환산을 모르는 상품은 포장 1개 = 기준 단위 1.
 * {@code recommendation_id} 는 unique(uq_po_item_recommendation) — 같은 추천을 두 번 주문으로 만들 수 없다.
 */
@Entity
@Table(name = "purchase_order_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PurchaseOrderItem {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "store_id", nullable = false)
	private Long storeId;

	@Column(name = "po_id", nullable = false)
	private Long poId;

	@Column(name = "supplier_product_id", nullable = false)
	private Long supplierProductId;

	@Column(name = "ingredient_id", nullable = false)
	private Long ingredientId;

	/** 주문 수량(포장 단위). */
	@Column(nullable = false, precision = 14, scale = 3)
	private BigDecimal qty;

	@Column(name = "expected_date")
	private LocalDate expectedDate;

	/** 실제 입고 수량(포장 단위). 입고 확인 전엔 null. */
	@Column(name = "received_qty", precision = 14, scale = 3)
	private BigDecimal receivedQty;

	@Column(name = "received_at")
	private OffsetDateTime receivedAt;

	@Column(name = "received_unit_price", precision = 12, scale = 2)
	private BigDecimal receivedUnitPrice;

	/** 줄 단위 "안 옴" 처리 시각. */
	@Column(name = "cancelled_at")
	private OffsetDateTime cancelledAt;

	/** 발주서를 만들 때 저장한 포장 1개 상당 원 단가. 모르면 null (S15P31A308-201). */
	@Column(name = "unit_price", precision = 12, scale = 2)
	private BigDecimal unitPrice;

	/** 식봄 · 쿠팡 줄의 상품 링크. */
	@Column(name = "product_url", length = 500)
	private String productUrl;

	/** 이 줄을 만든 추천. 수동 주문은 null. */
	@Column(name = "recommendation_id")
	private Long recommendationId;
}
