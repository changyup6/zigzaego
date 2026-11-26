package com.zigzaego.api.purchasedoc.service;

import com.zigzaego.api.account.entity.Store;
import com.zigzaego.api.account.repository.StoreRepository;
import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.exception.CommonErrorCode;
import com.zigzaego.api.global.response.Amounts;
import com.zigzaego.api.global.store.ResourceIds;
import com.zigzaego.api.job.entity.Job;
import com.zigzaego.api.job.entity.JobType;
import com.zigzaego.api.job.repository.JobRepository;
import com.zigzaego.api.purchasedoc.dto.ReceiptBoardResponse;
import com.zigzaego.api.purchasedoc.dto.ReceiptDetailResponse;
import com.zigzaego.api.purchasedoc.dto.ReceiptFileResponse;
import com.zigzaego.api.purchasedoc.dto.ReceiptLineResponse;
import com.zigzaego.api.purchasedoc.dto.ReviewBoard;
import com.zigzaego.api.purchasedoc.entity.DocLineStatus;
import com.zigzaego.api.purchasedoc.entity.ParseStatus;
import com.zigzaego.api.purchasedoc.entity.PurchaseDoc;
import com.zigzaego.api.purchasedoc.entity.PurchaseDocLine;
import com.zigzaego.api.purchasedoc.exception.ReceiptJobErrorCode;
import com.zigzaego.api.purchasedoc.repository.PurchaseDocLineRepository;
import com.zigzaego.api.purchasedoc.repository.PurchaseDocRepository;
import com.zigzaego.api.supplier.repository.SupplierRepository;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 영수증 화면(S15P31A308-113): 올린 장 목록 · 한 장 상세 · 한 장 지우기, 그리고 확인 목록과 합친 board.
 * 장 목록은 {@code files}, 상세는 한 장 단위. 확인 묶음 · 재료 선택지 · 확정만 남은 영수증은
 * {@link ReceiptReviewService#board} 결과 그대로다.
 */
@Service
@RequiredArgsConstructor
public class ReceiptBoardService {

	/** CONFIRMED 장을 목록에 남기는 기간 — 할 일이 남은 장은 날짜와 무관하게 다 보인다(S15P31A308-113). */
	static final long CONFIRMED_VISIBLE_DAYS = 7;

	private static final String LOCK_STORE_SQL = "SELECT id FROM store WHERE id = :storeId FOR NO KEY UPDATE";

	private final PurchaseDocRepository docRepository;
	private final PurchaseDocLineRepository lineRepository;
	private final JobRepository jobRepository;
	private final StoreRepository storeRepository;
	private final SupplierRepository supplierRepository;
	private final ReceiptReviewService reviewService;
	private final NamedParameterJdbcTemplate jdbc;
	private final JsonMapper jsonMapper;

	// ---------------------------------------------------------------- 목록

	/** 이 가게의 영수증 화면. 묶음 id 는 S15P31A308-113 묶음 키(상품명) 그대로 — URL 에 넣을 모양은 컨트롤러가 만든다. */
	@Transactional(readOnly = true)
	public ReceiptBoardResponse board(long storeId) {
		return board(storeId, reviewService.board(storeId));
	}

	/** 이미 계산한 S15P31A308-113 확인 목록(묶음 확정 결과 등)에 장 목록을 붙인다. */
	@Transactional(readOnly = true)
	public ReceiptBoardResponse board(long storeId, ReviewBoard review) {
		ZoneId zone = zoneOf(storeId);
		List<PurchaseDoc> docs = docRepository.findReceiptFiles(storeId,
				OffsetDateTime.now().minusDays(CONFIRMED_VISIBLE_DAYS));
		Map<Long, Job> jobs = jobsOf(storeId, docs);
		Map<Long, Integer> lineCounts = lineCounts(storeId, docs);
		Map<Long, Map<String, JsonNode>> summaries = new HashMap<>();
		List<ReceiptFileResponse> files = docs.stream().map(doc -> {
			Job job = doc.getJobId() == null ? null : jobs.get(doc.getJobId());
			JsonNode summary = job == null ? null
					: summaries.computeIfAbsent(job.getId(), id -> ReceiptFileState.summaries(jsonMapper, job))
							.get(String.valueOf(doc.getId()));
			return file(doc, job, summary, lineCounts.getOrDefault(doc.getId(), 0), zone);
		}).toList();
		String jobId = jobRepository.findFirstByStoreIdAndTypeOrderByIdDesc(storeId, JobType.RECEIPT)
				.map(j -> String.valueOf(j.getId())).orElse(null);
		return new ReceiptBoardResponse(jobId, files, review.matches(), review.options(), review.readyDocs());
	}

	// ---------------------------------------------------------------- 상세

	/**
	 * 한 장과 줄(줄 번호 오름차순).
	 *
	 * @throws BusinessException 숫자 아님 · 없음 · 다른 가게 문서면 404 COMMON_NOT_FOUND
	 */
	@Transactional(readOnly = true)
	public ReceiptDetailResponse detail(long storeId, String rawDocId) {
		PurchaseDoc doc = docRepository.getInStore(ResourceIds.parse(rawDocId), storeId);
		Job job = jobOf(storeId, doc);
		JsonNode summary = ReceiptFileState.summary(jsonMapper, job, doc.getId());
		List<PurchaseDocLine> lines = lineRepository.findAllByStoreIdAndDocIdOrderByLineNoAsc(storeId, doc.getId());
		ReceiptFileResponse file = file(doc, job, summary, lines.size(), zoneOf(storeId));
		String supplierName = doc.getSupplierId() == null ? null
				: supplierRepository.findByIdAndStoreId(doc.getSupplierId(), storeId).map(s -> s.getName()).orElse(null);
		if (supplierName == null) {
			supplierName = blankToNull(ReceiptFileState.text(summary, "supplier"));
		}
		return ReceiptDetailResponse.of(file, supplierName, lines.stream().map(ReceiptBoardService::line).toList());
	}

	// ---------------------------------------------------------------- 지우기

	/**
	 * 확인 전 장을 지운다 — 줄 · 문서 행만. 원본 파일은 디스크에 남긴다(DECISIONS #34). 가게 잠금으로 확정 · 다시 읽기와 줄 선다.
	 *
	 * @throws BusinessException 숫자 아님 · 없음 · 다른 가게 문서면 404 COMMON_NOT_FOUND, 읽는 중이면 409 RECEIPT_IN_PROGRESS,
	 *         문서가 CONFIRMED 이거나 사장님이 정한 줄(자동 확정 아닌 CONFIRMED · IGNORED)이 있으면 409 RECEIPT_ALREADY_CONFIRMED
	 */
	@Transactional
	public void delete(long storeId, String rawDocId) {
		long docId = ResourceIds.parse(rawDocId);
		lockStore(storeId);
		PurchaseDoc doc = docRepository.getInStore(docId, storeId);
		if (ReceiptFileState.inProgress(doc, jobOf(storeId, doc))) {
			throw new BusinessException(ReceiptJobErrorCode.IN_PROGRESS);
		}
		if (doc.getParseStatus() == ParseStatus.CONFIRMED) {
			throw new BusinessException(ReceiptJobErrorCode.ALREADY_CONFIRMED);
		}
		for (PurchaseDocLine line : lineRepository.findAllByStoreIdAndDocIdOrderByLineNoAsc(storeId, docId)) {
			if (decidedByOwner(line)) {
				throw new BusinessException(ReceiptJobErrorCode.ALREADY_CONFIRMED);
			}
		}
		lineRepository.deleteAllByStoreIdAndDocId(storeId, docId);
		docRepository.delete(doc);
	}

	/** 사장님이 정한 줄 — 무시했거나, 자동 확정이 아닌 확정. 이런 줄은 사전 · 주기형 재료 · 가격 이력을 이미 만들었다. */
	private boolean decidedByOwner(PurchaseDocLine line) {
		if (line.getStatus() == DocLineStatus.IGNORED) {
			return true;
		}
		return line.getStatus() == DocLineStatus.CONFIRMED && !autoConfirmed(line);
	}

	private boolean autoConfirmed(PurchaseDocLine line) {
		if (line.getRaw() == null) {
			return false;
		}
		try {
			JsonNode flag = jsonMapper.readTree(line.getRaw()).get("autoConfirmed");
			return flag != null && flag.isBoolean() && flag.asBoolean();
		} catch (JacksonException e) {
			return false;
		}
	}

	// ---------------------------------------------------------------- 공통

	private static ReceiptFileResponse file(PurchaseDoc doc, Job job, JsonNode summary, int lineCount, ZoneId zone) {
		return new ReceiptFileResponse(
				String.valueOf(doc.getId()),
				ReceiptFileState.text(summary, "file"),
				doc.getCreatedAt().atZoneSameInstant(zone).truncatedTo(ChronoUnit.SECONDS)
						.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
				ReceiptFileState.st(doc, job),
				doc.getKind() == null ? null : doc.getKind().name(),
				doc.getDocDate() == null ? null : doc.getDocDate().toString(),
				lineCount,
				ReceiptFileState.error(doc, job));
	}

	private static ReceiptLineResponse line(PurchaseDocLine line) {
		return new ReceiptLineResponse(
				String.valueOf(line.getId()),
				line.getLineNo(),
				line.getRawName(),
				Amounts.qty(line.getQty()),
				line.getUnit(),
				Amounts.won(line.getUnitPrice()),
				Amounts.won(line.getAmount()),
				line.getIngredientId() == null ? null : String.valueOf(line.getIngredientId()),
				line.getConfidence(),
				line.getStatus().name());
	}

	private Job jobOf(long storeId, PurchaseDoc doc) {
		return doc.getJobId() == null ? null : jobRepository.findByIdAndStoreId(doc.getJobId(), storeId).orElse(null);
	}

	private Map<Long, Job> jobsOf(long storeId, List<PurchaseDoc> docs) {
		Set<Long> ids = docs.stream().map(PurchaseDoc::getJobId).collect(Collectors.toSet());
		if (ids.isEmpty()) {
			return Map.of();
		}
		return jobRepository.findAllByStoreIdAndIdIn(storeId, ids).stream()
				.collect(Collectors.toMap(Job::getId, Function.identity()));
	}

	private Map<Long, Integer> lineCounts(long storeId, List<PurchaseDoc> docs) {
		if (docs.isEmpty()) {
			return Map.of();
		}
		Map<Long, Integer> counts = new HashMap<>();
		for (Object[] row : lineRepository.countLinesByDoc(storeId, docs.stream().map(PurchaseDoc::getId).toList())) {
			counts.put(((Number) row[0]).longValue(), ((Number) row[1]).intValue());
		}
		return counts;
	}

	private ZoneId zoneOf(long storeId) {
		Store store = storeRepository.findById(storeId)
				.orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
		return ZoneId.of(store.getTimezone());
	}

	private void lockStore(long storeId) {
		List<Long> rows = jdbc.queryForList(LOCK_STORE_SQL, new MapSqlParameterSource("storeId", storeId), Long.class);
		if (rows.isEmpty()) {
			throw new BusinessException(CommonErrorCode.NOT_FOUND);
		}
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}
}
