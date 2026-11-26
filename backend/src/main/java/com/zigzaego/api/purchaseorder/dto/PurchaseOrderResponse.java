package com.zigzaego.api.purchaseorder.dto;

import com.zigzaego.api.global.config.ClockConfig;
import com.zigzaego.api.purchaseorder.entity.PurchaseOrder;
import com.zigzaego.api.purchaseorder.entity.PurchaseOrderSource;
import com.zigzaego.api.purchaseorder.entity.PurchaseOrderStatus;
import com.zigzaego.api.supplier.entity.Supplier;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** 주문처 하나의 발주서 한 장과 렌더링된 주문 메시지. */
@Schema(description = "주문처별 발주서 한 장")
public record PurchaseOrderResponse(
		@Schema(description = "발주서 id (문자열)", example = "5")
		String id,

		@Schema(description = "주문처. SUPPLIER = 등록 거래처, SIKBOM = 식봄, COUPANG = 쿠팡, MARKETBOM = 마켓봄, ORDERLINK = 오더링크", example = "SUPPLIER")
		PurchaseOrderSource source,

		@Schema(description = "거래처 id (문자열). 식봄 · 쿠팡 발주서는 null", example = "4", nullable = true)
		String supplierId,

		@Schema(description = "거래처 이름. 식봄 · 쿠팡 발주서는 null (이름은 source 로 표시)", example = "한빛농산", nullable = true)
		String supplierName,

		@Schema(description = "상태. 만들면 DRAFT, 보냄 기록(POST /purchase-orders/{id}/send) 뒤 SENT", example = "DRAFT")
		PurchaseOrderStatus status,

		@Schema(description = "거래처 템플릿으로 만든 주문 메시지. 사장님이 그대로 카톡 · 문자로 공유한다",
				example = "안녕하세요, 위글리입니다.\n10/15(목) 배송으로 아래 부탁드립니다.\n- 대파 1단 2단\n감사합니다.")
		String messageText,

		@Schema(description = "만든 시각 (ISO-8601, +09:00)", example = "2026-10-14T12:00:00+09:00")
		String createdAt,

		@Schema(description = "사장님이 보냄을 누른 시각 (ISO-8601, +09:00). 보낸(SENT) 발주서만 입고 예정에 들어간다. DRAFT 면 null",
				example = "2026-10-14T12:05:00+09:00", nullable = true)
		String sentAt,

		@Schema(description = "품목 줄. 추천 id 순")
		List<PurchaseOrderItemResponse> items
) {

	/** @param supplier 식봄 · 쿠팡 발주서면 null */
	public static PurchaseOrderResponse of(PurchaseOrder order, Supplier supplier, List<PurchaseOrderItemResponse> items) {
		return new PurchaseOrderResponse(
				String.valueOf(order.getId()),
				order.getSource(),
				order.getSupplierId() == null ? null : String.valueOf(order.getSupplierId()),
				supplier == null ? null : supplier.getName(),
				order.getStatus(),
				order.getMessageText(),
				format(order.getCreatedAt()),
				order.getSentAt() == null ? null : format(order.getSentAt()),
				items);
	}

	private static String format(OffsetDateTime time) {
		return time.atZoneSameInstant(ClockConfig.SERVICE_ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
	}
}
