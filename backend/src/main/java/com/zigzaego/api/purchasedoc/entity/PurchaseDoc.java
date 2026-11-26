package com.zigzaego.api.purchasedoc.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 매입 문서(영수증 · 거래명세서 등) 한 장. 표는 V1 {@code purchase_doc} — 설계 문서의 receipt.
 * 영수증 업로드 job(S15P31A308-111)이 장마다 처리 상태 · OCR 원문 · 날짜 · 과거/입고 구분을 여기 남긴다.
 * 금액({@code total_amount})은 정형화 결과에 합계가 없어 아직 매핑하지 않는다.
 */
@Entity
@Table(name = "purchase_doc")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PurchaseDoc {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "store_id", nullable = false)
	private Long storeId;

	/** 보낸 거래처. 마트 영수증처럼 등록 안 된 곳이면 null. */
	@Column(name = "supplier_id")
	private Long supplierId;

	/** 문서 종류. 인식 전엔 모른다. */
	@Enumerated(EnumType.STRING)
	@Column(name = "doc_type", length = 20)
	private DocType docType;

	/** 이 문서를 처리한 영수증 job. 직접 넣은 문서는 null. */
	@Column(name = "job_id")
	private Long jobId;

	/** 인식 전엔 모른다. */
	@Column(name = "doc_date")
	private LocalDate docDate;

	/** 원본 파일 위치 — {@code app.receipt.storage-dir} 기준 상대 경로. */
	@Column(name = "file_path", length = 500)
	private String filePath;

	@Enumerated(EnumType.STRING)
	@Column(name = "parse_status", nullable = false, length = 10)
	private ParseStatus parseStatus;

	/** OCR 원문 JSON 그대로. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "ocr_json")
	private String ocrJson;

	/** 재고 실사일 이전(PAST) / 이후(RECEIVE). 날짜를 읽기 전엔 null. */
	@Enumerated(EnumType.STRING)
	@Column(name = "kind", length = 10)
	private DocKind kind;

	/** 이 장의 실패 사유 코드. */
	@Column(columnDefinition = "text")
	private String error;

	@Column(name = "created_at", nullable = false)
	private OffsetDateTime createdAt;

	/** 영수증 job 이 올린 직후의 한 장(PENDING). */
	public static PurchaseDoc uploaded(long storeId, long jobId, String filePath, OffsetDateTime now) {
		PurchaseDoc doc = new PurchaseDoc();
		doc.storeId = storeId;
		doc.jobId = jobId;
		doc.filePath = filePath;
		doc.parseStatus = ParseStatus.PENDING;
		doc.createdAt = now;
		return doc;
	}

	/**
	 * 실패한 장을 새 job 으로 다시 읽게 되돌린다(S15P31A308-113 {@code POST /receipts/{docId}/retry}). 읽은 결과는 모두 비우고 원본 경로는
	 * 그대로 둔다 — 같은 파일을 다시 읽는다.
	 */
	public void resetForRetry(long newJobId) {
		this.jobId = newJobId;
		this.parseStatus = ParseStatus.PENDING;
		this.error = null;
		this.ocrJson = null;
		this.docType = null;
		this.docDate = null;
		this.kind = null;
		this.supplierId = null;
	}

	public void markProcessing() {
		this.parseStatus = ParseStatus.PROCESSING;
		this.error = null;
	}

	/** 글자 인식 · 정형화가 끝났다. 줄은 따로 저장한다. */
	public void markParsed(String ocrJson, DocType docType, LocalDate docDate, Long supplierId, DocKind kind) {
		this.ocrJson = ocrJson;
		this.docType = docType;
		this.docDate = docDate;
		this.supplierId = supplierId;
		this.kind = kind;
		this.parseStatus = ParseStatus.PARSED;
		this.error = null;
	}

	/** 사장님이 모든 줄을 확인했다(남은 PENDING 0). RECEIVE 문서는 이때부터 추정 재고에 들어간다(DECISIONS #41). */
	public void confirm() {
		this.parseStatus = ParseStatus.CONFIRMED;
	}

	/** 이 장만 실패. OCR 원문이 있으면 남긴다(정형화만 다시 돌릴 수 있게). */
	public void markFailed(String error, String ocrJson) {
		this.parseStatus = ParseStatus.FAILED;
		this.error = error;
		if (ocrJson != null) {
			this.ocrJson = ocrJson;
		}
	}
}
