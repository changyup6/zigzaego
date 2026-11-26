package com.zigzaego.api.purchasedoc.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 매입 문서 안의 품목 한 줄. 표는 V1 {@code purchase_doc_line} — 설계 문서의 receipt_line.
 * 수량 · 단가 · 금액은 문서에 없으면 null. 매칭(재료 · 사전 상품 · 확신도)은 영수증 job 이 채운다 (S15P31A308-111).
 */
@Entity
@Table(name = "purchase_doc_line")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PurchaseDocLine {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "store_id", nullable = false)
	private Long storeId;

	@Column(name = "doc_id", nullable = false)
	private Long docId;

	@Column(name = "line_no", nullable = false)
	private Integer lineNo;

	/** 문서에 적힌 품목명 그대로. */
	@Column(name = "raw_name", nullable = false, length = 200)
	private String rawName;

	@Column(precision = 14, scale = 3)
	private BigDecimal qty;

	@Column(length = 20)
	private String unit;

	@Column(name = "unit_price", precision = 12, scale = 2)
	private BigDecimal unitPrice;

	@Column(precision = 12, scale = 2)
	private BigDecimal amount;

	/** 사전(거래처 상품)과 맞춰지면 채운다. */
	@Column(name = "supplier_product_id")
	private Long supplierProductId;

	/** 재료와 맞춰지면 채운다. */
	@Column(name = "ingredient_id")
	private Long ingredientId;

	/** 매칭 확신도 0~1. 사전 즉시 매칭은 null. */
	@Column(precision = 4, scale = 3)
	private BigDecimal confidence;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private DocLineStatus status;

	/** OCR · LLM 이 낸 원문 줄 그대로(JSON). */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "raw")
	private String raw;

	@Column(name = "created_at", nullable = false)
	private OffsetDateTime createdAt;

	/** 영수증 job 이 읽어 낸 확인 전(PENDING) 줄. */
	public static PurchaseDocLine parsed(long storeId, long docId, int lineNo, String rawName, BigDecimal qty,
			String unit, BigDecimal unitPrice, BigDecimal amount, Long supplierProductId, Long ingredientId,
			BigDecimal confidence, String raw, OffsetDateTime now) {
		PurchaseDocLine line = new PurchaseDocLine();
		line.storeId = storeId;
		line.docId = docId;
		line.lineNo = lineNo;
		line.rawName = rawName;
		line.qty = qty;
		line.unit = unit;
		line.unitPrice = unitPrice;
		line.amount = amount;
		line.supplierProductId = supplierProductId;
		line.ingredientId = ingredientId;
		line.confidence = confidence;
		line.status = DocLineStatus.PENDING;
		line.raw = raw;
		line.createdAt = now;
		return line;
	}

	/**
	 * 서버가 사장님 확인 없이 확정한다(DECISIONS #41 자동 확정, S15P31A308-112). 문서는 PARSED 그대로라 재고에는 아직 안 들어간다.
	 *
	 * @param raw {@code "autoConfirmed": true} 를 덧붙인 원문 줄
	 */
	public void autoConfirm(String raw) {
		this.status = DocLineStatus.CONFIRMED;
		this.raw = raw;
	}

	/** 사장님이 확정했다. 확신도는 그대로 둔다. */
	public void confirm(long ingredientId, Long supplierProductId, String raw) {
		this.status = DocLineStatus.CONFIRMED;
		this.ingredientId = ingredientId;
		this.supplierProductId = supplierProductId;
		this.raw = raw;
	}

	/** 사장님이 무시했다 — 재고 · 가격에 안 쓴다. 재료 · 사전 연결은 손대지 않는다. */
	public void ignore(String raw) {
		this.status = DocLineStatus.IGNORED;
		this.raw = raw;
	}
}
