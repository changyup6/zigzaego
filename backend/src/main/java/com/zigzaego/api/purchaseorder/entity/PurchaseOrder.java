package com.zigzaego.api.purchaseorder.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 거래처 하나에 보내는 발주서 한 장. 표는 V1 {@code purchase_order}.
 * 승인 건을 거래처별로 묶어 만들고(지라 70), 완성된 메시지({@code message_text})를 사장님이 카톡 · 문자로 공유한다 (DECISIONS #49).
 */
@Entity
@Table(name = "purchase_order")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PurchaseOrder {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "store_id", nullable = false)
	private Long storeId;

	/** 거래처 발주서만 값이 있다. 식봄 · 쿠팡 발주서는 null (DB CHECK ck_purchase_order_source_supplier). */
	@Column(name = "supplier_id")
	private Long supplierId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private PurchaseOrderSource source;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private PurchaseOrderStatus status;

	/** 거래처 템플릿으로 렌더링한 주문 메시지. */
	@Column(name = "message_text", columnDefinition = "TEXT")
	private String messageText;

	@Column(name = "created_at", nullable = false)
	private OffsetDateTime createdAt;

	/** 사장님이 보냄 표시한 시각. DRAFT 면 null. */
	@Column(name = "sent_at")
	private OffsetDateTime sentAt;

	/** @param supplierId 거래처 발주서면 그 거래처, 식봄 · 쿠팡이면 null */
	public static PurchaseOrder draft(long storeId, PurchaseOrderSource source, Long supplierId, String messageText,
			OffsetDateTime now) {
		PurchaseOrder order = new PurchaseOrder();
		order.storeId = storeId;
		order.source = source;
		order.supplierId = supplierId;
		order.status = PurchaseOrderStatus.DRAFT;
		order.messageText = messageText;
		order.createdAt = now;
		return order;
	}

	/**
	 * 사장님이 보냄을 눌렀다 (S15P31A308-200, DECISIONS #63). DRAFT → SENT, 보낸 시각을 남긴다.
	 * 이미 SENT 면 아무것도 바꾸지 않는다(첫 보낸 시각 유지 · 멱등).
	 *
	 * @throws IllegalStateException CANCELLED — 부르는 쪽이 먼저 {@link #isCancelled()} 로 걸러 409 로 답한다
	 */
	public void markSent(OffsetDateTime now) {
		if (status == PurchaseOrderStatus.CANCELLED) {
			throw new IllegalStateException("취소한 발주서는 보낼 수 없다: " + id);
		}
		if (status == PurchaseOrderStatus.SENT) {
			return;
		}
		status = PurchaseOrderStatus.SENT;
		sentAt = now;
	}

	public boolean isCancelled() {
		return status == PurchaseOrderStatus.CANCELLED;
	}
}
