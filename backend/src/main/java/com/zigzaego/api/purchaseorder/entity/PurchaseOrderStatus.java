package com.zigzaego.api.purchaseorder.entity;

/** 발주서 상태. DRAFT=메시지 생성됨 → SENT=사장님이 전송 표시(수동) / CANCELLED=취소 (V1 purchase_order.status). */
public enum PurchaseOrderStatus {
	DRAFT, SENT, CANCELLED
}
