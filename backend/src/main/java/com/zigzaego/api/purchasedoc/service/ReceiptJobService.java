package com.zigzaego.api.purchasedoc.service;

import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.exception.CommonErrorCode;
import com.zigzaego.api.global.store.ResourceIds;
import com.zigzaego.api.job.dto.JobResponse;
import com.zigzaego.api.job.entity.Job;
import com.zigzaego.api.job.entity.JobType;
import com.zigzaego.api.job.repository.JobRepository;
import com.zigzaego.api.job.service.JobService;
import com.zigzaego.api.purchasedoc.dto.ReceiptUpload;
import com.zigzaego.api.purchasedoc.entity.PurchaseDoc;
import com.zigzaego.api.purchasedoc.exception.ReceiptJobErrorCode;
import com.zigzaego.api.purchasedoc.job.ReceiptAnalyzer;
import com.zigzaego.api.purchasedoc.job.ReceiptStorage;
import com.zigzaego.api.purchasedoc.ocr.ClovaOcrProperties;
import com.zigzaego.api.purchasedoc.repository.PurchaseDocLineRepository;
import com.zigzaego.api.purchasedoc.repository.PurchaseDocRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * 영수증 업로드 job 을 만든다 (S15P31A308-111, 설계 4.2 — 1장이든 50장이든 같은 경로).
 * 원본을 서버 디스크에 두고 장마다 {@code purchase_doc}(PENDING) 을 만든 뒤, 커밋되면 {@link ReceiptAnalyzer} 를 job 실행기에 넘긴다.
 * 실패한 한 장 다시 읽기({@link #retry})도 같은 job 경로다(DECISIONS #56 "다시 시도는 새 job").
 * HTTP 주소는 {@code ReceiptController}(S15P31A308-113). 화면은 {@code GET /receipts} 로 장마다 상태를 본다.
 *
 * <p>CLOVA 키가 없어 가짜 글자 인식이 골라졌으면 {@code app.receipt.allow-fake-ocr=true} 일 때만 연다 — 기본은 닫힘(503)이라
 * 스테이징 · 운영 화면에 가짜 품목이 뜨지 않는다(S15P31A308-113).
 */
@Service
public class ReceiptJobService {

	/** 한 번에 올릴 수 있는 장 수. */
	public static final int MAX_FILES = 50;
	/** 한 장 상한 10MB. */
	public static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

	private static final Set<String> SUPPORTED = Set.of("image/jpeg", "image/png", "application/pdf");

	/** S15P31A308-112 · S15P31A308-113 확정과 같은 가게 잠금 — 같은 장 다시 읽기 · 지우기 · 확정을 한 줄로 세운다. */
	private static final String LOCK_STORE_SQL = "SELECT id FROM store WHERE id = :storeId FOR NO KEY UPDATE";

	private final JobService jobService;
	private final JobRepository jobRepository;
	private final ReceiptStorage storage;
	private final PurchaseDocRepository docRepository;
	private final PurchaseDocLineRepository lineRepository;
	private final ReceiptAnalyzer analyzer;
	private final JsonMapper jsonMapper;
	private final NamedParameterJdbcTemplate jdbc;
	private final ClovaOcrProperties clovaProperties;
	/** 가짜 글자 인식 상태에서도 올리기 · 다시 읽기를 연다. 테스트가 끈 상태를 보려고 final 이 아니다. */
	private boolean allowFakeOcr;

	public ReceiptJobService(JobService jobService, JobRepository jobRepository, ReceiptStorage storage,
			PurchaseDocRepository docRepository, PurchaseDocLineRepository lineRepository, ReceiptAnalyzer analyzer,
			JsonMapper jsonMapper, NamedParameterJdbcTemplate jdbc, ClovaOcrProperties clovaProperties,
			@Value("${app.receipt.allow-fake-ocr:false}") boolean allowFakeOcr) {
		this.jobService = jobService;
		this.jobRepository = jobRepository;
		this.storage = storage;
		this.docRepository = docRepository;
		this.lineRepository = lineRepository;
		this.analyzer = analyzer;
		this.jsonMapper = jsonMapper;
		this.jdbc = jdbc;
		this.clovaProperties = clovaProperties;
		this.allowFakeOcr = allowFakeOcr;
	}

	/**
	 * @throws BusinessException 가짜 글자 인식이고 허용 설정이 꺼졌으면 503 RECEIPT_OCR_NOT_CONFIGURED, 입력이 틀리면
	 *         {@link ReceiptJobErrorCode}. 어느 쪽이든 job · 문서 · 파일을 만들지 않는다
	 */
	@Transactional
	public JobResponse start(long storeId, List<ReceiptUpload> uploads) {
		requireOcr();
		List<String> mimeTypes = validate(uploads);
		Job job = jobService.create(storeId, JobType.RECEIPT);

		List<String> saved = new ArrayList<>();
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				if (status != STATUS_COMMITTED) {
					saved.forEach(storage::deleteQuietly);
				}
			}
		});
		Map<Long, String> fileNames = new LinkedHashMap<>();
		OffsetDateTime now = OffsetDateTime.now();
		for (int i = 0; i < uploads.size(); i++) {
			ReceiptUpload upload = uploads.get(i);
			String path = storage.save(storeId, job.getId(), mimeTypes.get(i), upload.bytes());
			saved.add(path);
			PurchaseDoc doc = docRepository.save(PurchaseDoc.uploaded(storeId, job.getId(), path, now));
			// 화면 표시용 이름. PostgreSQL JSONB 는 NUL(\u0000) 을 받지 않으므로 뺀다
			fileNames.put(doc.getId(), upload.fileName() == null ? null : upload.fileName().replace("\u0000", ""));
		}
		initResult(job.getId(), fileNames);
		jobService.submit(job.getId(), ctx -> analyzer.analyze(ctx, storeId, fileNames));
		return JobResponse.from(job, jsonMapper);
	}

	/**
	 * 화면에 실패로 보이는 한 장(FAILED, 또는 job 이 끝났는데 PENDING · PROCESSING 에 남은 장)을 새 job 으로 다시 읽는다.
	 * 한 트랜잭션: 가게 잠금 → 새 job → 그 장 줄 삭제 → 문서를 PENDING 으로 되돌림(job_id = 새 job) → 커밋 뒤 실행.
	 * 원본 파일은 그대로 다시 읽는다(없으면 S15P31A308-111 규칙대로 그 장 FAILED RECEIPT_FILE_MISSING).
	 *
	 * @throws BusinessException 가짜 글자 인식이고 허용 설정이 꺼졌으면 503 RECEIPT_OCR_NOT_CONFIGURED, 숫자 아님 · 없음 ·
	 *         다른 가게 문서면 404 COMMON_NOT_FOUND, 실패한 장이 아니면 409 RECEIPT_NOT_FAILED
	 */
	@Transactional
	public JobResponse retry(long storeId, String rawDocId) {
		requireOcr();
		long docId = ResourceIds.parse(rawDocId);
		lockStore(storeId);
		PurchaseDoc doc = docRepository.getInStore(docId, storeId);
		Job previous = doc.getJobId() == null ? null
				: jobRepository.findByIdAndStoreId(doc.getJobId(), storeId).orElse(null);
		if (!ReceiptFileState.failed(doc, previous)) {
			throw new BusinessException(ReceiptJobErrorCode.NOT_FAILED);
		}
		String fileName = ReceiptFileState.text(ReceiptFileState.summary(jsonMapper, previous, docId), "file");

		Job job = jobService.create(storeId, JobType.RECEIPT);
		lineRepository.deleteAllByStoreIdAndDocId(storeId, docId);
		doc.resetForRetry(job.getId());
		docRepository.save(doc);
		Map<Long, String> fileNames = new HashMap<>();
		fileNames.put(docId, fileName);
		initResult(job.getId(), fileNames);
		jobService.submit(job.getId(), ctx -> analyzer.analyze(ctx, storeId, fileNames));
		return JobResponse.from(job, jsonMapper);
	}

	/** 실행 전에도 목록이 올린 파일 이름을 보이게 장마다 PENDING 요약을 결과에 넣어 둔다(S15P31A308-111 결과 모양). */
	private void initResult(long jobId, Map<Long, String> fileNames) {
		List<ReceiptAnalyzer.DocSummary> docs = new ArrayList<>();
		fileNames.forEach((id, name) -> docs.add(new ReceiptAnalyzer.DocSummary(String.valueOf(id), name, "PENDING",
				null, null, null, 0, 0, 0, null)));
		jobService.initResult(jobId, new ReceiptAnalyzer.ReceiptJobResult(docs));
	}

	/** CLOVA 키가 없어 가짜 글자 인식을 쓰는 상태면 허용 설정이 켜져 있어야 한다. */
	private void requireOcr() {
		if (!clovaProperties.configured() && !allowFakeOcr) {
			throw new BusinessException(ReceiptJobErrorCode.OCR_NOT_CONFIGURED);
		}
	}

	private void lockStore(long storeId) {
		List<Long> rows = jdbc.queryForList(LOCK_STORE_SQL, new MapSqlParameterSource("storeId", storeId), Long.class);
		if (rows.isEmpty()) {
			throw new BusinessException(CommonErrorCode.NOT_FOUND);
		}
	}

	/** @return 장마다 정리한 형식(소문자, image/jpg → image/jpeg) */
	private static List<String> validate(List<ReceiptUpload> uploads) {
		if (uploads == null || uploads.isEmpty()) {
			throw new BusinessException(ReceiptJobErrorCode.FILE_REQUIRED);
		}
		if (uploads.size() > MAX_FILES) {
			throw new BusinessException(ReceiptJobErrorCode.TOO_MANY_FILES);
		}
		List<String> mimeTypes = new ArrayList<>();
		for (ReceiptUpload upload : uploads) {
			if (upload == null || upload.bytes() == null || upload.bytes().length == 0) {
				throw new BusinessException(ReceiptJobErrorCode.FILE_REQUIRED);
			}
			if (upload.bytes().length > MAX_FILE_BYTES) {
				throw new BusinessException(ReceiptJobErrorCode.FILE_TOO_LARGE);
			}
			String mimeType = upload.contentType() == null ? "" : upload.contentType().strip().toLowerCase(Locale.ROOT);
			if ("image/jpg".equals(mimeType)) {
				mimeType = "image/jpeg";
			}
			if (!SUPPORTED.contains(mimeType)) {
				throw new BusinessException(ReceiptJobErrorCode.FILE_NOT_SUPPORTED);
			}
			mimeTypes.add(mimeType);
		}
		return mimeTypes;
	}
}
