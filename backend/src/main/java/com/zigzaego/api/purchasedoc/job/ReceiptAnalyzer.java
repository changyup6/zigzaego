package com.zigzaego.api.purchasedoc.job;

import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.ingredient.entity.Ingredient;
import com.zigzaego.api.ingredient.repository.IngredientRepository;
import com.zigzaego.api.job.service.JobContext;
import com.zigzaego.api.job.service.JobFailedException;
import com.zigzaego.api.job.service.JobService;
import com.zigzaego.api.llm.GmsClient;
import com.zigzaego.api.llm.LlmRequest;
import com.zigzaego.api.onboarding.ai.IngredientCatalog;
import com.zigzaego.api.onboarding.ai.OnboardingPrompt;
import com.zigzaego.api.purchasedoc.entity.DocKind;
import com.zigzaego.api.purchasedoc.entity.DocType;
import com.zigzaego.api.purchasedoc.entity.PurchaseDoc;
import com.zigzaego.api.purchasedoc.entity.PurchaseDocLine;
import com.zigzaego.api.purchasedoc.ocr.OcrClient;
import com.zigzaego.api.purchasedoc.ocr.OcrImage;
import com.zigzaego.api.purchasedoc.ocr.OcrResult;
import com.zigzaego.api.purchasedoc.repository.PurchaseDocLineRepository;
import com.zigzaego.api.purchasedoc.repository.PurchaseDocRepository;
import com.zigzaego.api.purchasedoc.service.ReceiptReview;
import com.zigzaego.api.supplier.entity.Supplier;
import com.zigzaego.api.supplier.entity.SupplierProduct;
import com.zigzaego.api.supplier.repository.SupplierProductRepository;
import com.zigzaego.api.supplier.repository.SupplierRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 영수증 job 본문 (S15P31A308-111, 설계 4.2). 장마다 올린 순서대로:
 * CLOVA OCR → LLM 정형화(S15P31A308-110 {@code normalize}) → 줄마다 사전(supplier_product 상품명) 매칭 → 사전에 없는 줄만
 * LLM 매칭(S15P31A308-110 {@code match}: 재료표 재료 · 포장 · 새 주기형 · 확신도) → {@code purchase_doc_line} 저장.
 * 한 장이 실패해도 그 장만 FAILED 로 두고 다음 장으로 간다(설계 4.4). 모든 장이 실패해야 job 이 FAILED.
 * 줄은 PENDING — 단, 문서 거래처의 사전 상품으로 맞췄고 단위 미확정이 아닌 줄은 자동 확정(CONFIRMED, raw.autoConfirmed)으로
 * 저장한다(S15P31A308-112, {@link #autoConfirmable}). 문서는 PARSED — 사장님 확인 · 사전 저장은 {@code ReceiptConfirmService}.
 */
@Slf4j
@Component
public class ReceiptAnalyzer {

	public static final String NORMALIZE_SCHEMA = "receipt_normalize";
	public static final String MATCH_SCHEMA = "receipt_match";

	/** 정형화 결과에 품목이 한 줄도 없다. */
	public static final String NO_ITEMS = "RECEIPT_NO_ITEMS";
	/** 저장한 원본 파일이 없다. */
	public static final String FILE_MISSING = "RECEIPT_FILE_MISSING";
	/** 모든 장이 서로 다른 사유로 실패했다. */
	public static final String ALL_FAILED = "RECEIPT_ALL_FAILED";

	private static final double OCR_DONE = 0.4;
	private static final double NORMALIZE_DONE = 0.7;
	private static final double MATCH_DONE = 0.9;
	private static final int RAW_NAME_MAX = 200;
	private static final int UNIT_MAX = 20;
	/** NUMERIC(14,3) · NUMERIC(12,2) 에 들어가는 상한(정수부 11 · 10자리). */
	private static final BigDecimal QTY_LIMIT = new BigDecimal("1e11");
	private static final BigDecimal MONEY_LIMIT = new BigDecimal("1e10");

	private static final String LAST_COUNT_DATE_SQL = """
			SELECT (MAX(sc.counted_at) AT TIME ZONE s.timezone)::date
			FROM store s
			LEFT JOIN stock_count sc ON sc.store_id = s.id
			WHERE s.id = ?
			GROUP BY s.timezone
			""";

	private final OcrClient ocrClient;
	private final GmsClient gmsClient;
	private final JsonMapper jsonMapper;
	private final PurchaseDocRepository docRepository;
	private final PurchaseDocLineRepository lineRepository;
	private final SupplierRepository supplierRepository;
	private final SupplierProductRepository supplierProductRepository;
	private final IngredientRepository ingredientRepository;
	private final JdbcTemplate jdbc;
	private final ReceiptStorage storage;
	private final TransactionTemplate tx;
	private final OnboardingPrompt normalizePrompt;
	private final OnboardingPrompt matchPrompt;
	private final IngredientCatalog catalog;
	private final String catalogJson;

	public ReceiptAnalyzer(OcrClient ocrClient, GmsClient gmsClient, JsonMapper jsonMapper,
			PurchaseDocRepository docRepository, PurchaseDocLineRepository lineRepository,
			SupplierRepository supplierRepository, SupplierProductRepository supplierProductRepository,
			IngredientRepository ingredientRepository, JdbcTemplate jdbc, ReceiptStorage storage,
			PlatformTransactionManager transactionManager) {
		this.ocrClient = ocrClient;
		this.gmsClient = gmsClient;
		this.jsonMapper = jsonMapper;
		this.docRepository = docRepository;
		this.lineRepository = lineRepository;
		this.supplierRepository = supplierRepository;
		this.supplierProductRepository = supplierProductRepository;
		this.ingredientRepository = ingredientRepository;
		this.jdbc = jdbc;
		this.storage = storage;
		this.tx = new TransactionTemplate(transactionManager);
		this.normalizePrompt = OnboardingPrompt.load("receipt", "normalize", jsonMapper);
		this.matchPrompt = OnboardingPrompt.load("receipt", "match", jsonMapper);
		this.catalog = IngredientCatalog.loadDefault();
		this.catalogJson = catalogJson(catalog, jsonMapper);
	}

	/**
	 * 장 한 장의 결과 요약 (job result_json 의 docs[]).
	 *
	 * @param id                purchase_doc id (문자열)
	 * @param file              올린 파일 이름
	 * @param status            PENDING · PROCESSING · PARSED · FAILED
	 * @param kind              PAST · RECEIVE · null
	 * @param date              문서 날짜 YYYY-MM-DD · null
	 * @param supplier          정형화가 읽은 상호 · null
	 * @param lines             저장한 줄 수
	 * @param dictionaryMatched 사전으로 바로 맞춘 줄 수
	 * @param ingredientMatched 재료가 정해진 줄 수(사전 포함)
	 * @param error             실패 사유 코드 · null
	 */
	public record DocSummary(String id, String file, String status, String kind, String date, String supplier,
			int lines, int dictionaryMatched, int ingredientMatched, String error) {

		static DocSummary pending(long id, String file) {
			return new DocSummary(String.valueOf(id), file, "PENDING", null, null, null, 0, 0, 0, null);
		}

		static DocSummary failed(long id, String file, String error) {
			return new DocSummary(String.valueOf(id), file, "FAILED", null, null, null, 0, 0, 0, error);
		}
	}

	/** job 결과: {@code {"docs": [DocSummary]}}. */
	public record ReceiptJobResult(List<DocSummary> docs) {
	}

	/**
	 * @param fileNames purchase_doc id → 올린 파일 이름
	 */
	public ReceiptJobResult analyze(JobContext ctx, long storeId, Map<Long, String> fileNames) {
		List<Long> docIds = tx.execute(status -> docRepository.findAllByStoreIdAndJobIdOrderByIdAsc(storeId,
				ctx.jobId()).stream().map(PurchaseDoc::getId).toList());
		List<DocSummary> summaries = new ArrayList<>();
		for (Long id : docIds) {
			summaries.add(DocSummary.pending(id, fileNames.get(id)));
		}
		ctx.partialResult(new ReceiptJobResult(summaries));
		int n = docIds.size();
		for (int i = 0; i < n; i++) {
			long docId = docIds.get(i);
			summaries.set(i, process(ctx, storeId, docId, fileNames.get(docId), i, n));
			ctx.progress((i + 1) / (double) n);
			ctx.partialResult(new ReceiptJobResult(summaries));
		}
		if (n > 0 && summaries.stream().allMatch(s -> "FAILED".equals(s.status()))) {
			Set<String> errors = new HashSet<>();
			summaries.forEach(s -> errors.add(s.error()));
			throw new JobFailedException(errors.size() == 1 ? errors.iterator().next() : ALL_FAILED);
		}
		return new ReceiptJobResult(summaries);
	}

	private DocSummary process(JobContext ctx, long storeId, long docId, String fileName, int index, int total) {
		String ocrJson = null;
		try {
			String filePath = tx.execute(status -> {
				PurchaseDoc doc = docRepository.getInStore(docId, storeId);
				doc.markProcessing();
				docRepository.save(doc);
				return doc.getFilePath();
			});
			byte[] bytes = readFile(filePath);
			OcrResult ocr = ocrClient.recognize(new OcrImage(fileName == null ? "page" : fileName,
					ReceiptStorage.mimeTypeOf(filePath), bytes));
			ocrJson = jsonText(ocr.raw());
			ctx.progress((index + OCR_DONE) / total);

			JsonNode normalized = gmsClient.completeJson(new LlmRequest(normalizePrompt.system(),
					normalizePrompt.user("ocr_text", stripNul(ocr.text())), List.of(), NORMALIZE_SCHEMA,
					normalizePrompt.schema()));
			List<JsonNode> items = new ArrayList<>();
			for (JsonNode item : normalized.path("items")) {
				if (!text(item, "name").isEmpty()) {
					items.add(item);
				}
			}
			if (items.isEmpty()) {
				throw new StepFailed(NO_ITEMS);
			}
			ctx.progress((index + NORMALIZE_DONE) / total);

			LocalDate date = parseDate(text(normalized, "date"));
			String supplierName = text(normalized, "supplier");
			Long supplierId = findSupplier(storeId, supplierName);
			DocKind kind = kindOf(storeId, date);

			// 사전 매칭: 상품명이 똑같은 거래처 상품 (문서 거래처의 상품 우선)
			List<SupplierProduct> dictionary = new ArrayList<>();
			List<Integer> unmatched = new ArrayList<>();
			for (int k = 0; k < items.size(); k++) {
				SupplierProduct hit = lookupDictionary(storeId, supplierId, text(items.get(k), "name"));
				dictionary.add(hit);
				if (hit == null) {
					unmatched.add(k);
				}
			}
			Map<Integer, JsonNode> matches = unmatched.isEmpty() ? Map.of() : match(items, unmatched);
			ctx.progress((index + MATCH_DONE) / total);

			List<Ingredient> storeIngredients = ingredientRepository.findAllByStoreIdOrderByIdAsc(storeId);
			Map<String, Long> ingredientIds = ingredientIds(storeIngredients);
			Map<Long, Ingredient> ingredientsById = new HashMap<>();
			storeIngredients.forEach(i -> ingredientsById.put(i.getId(), i));
			OffsetDateTime now = OffsetDateTime.now();
			List<PurchaseDocLine> lines = new ArrayList<>();
			int dictionaryMatched = 0;
			int ingredientMatched = 0;
			for (int k = 0; k < items.size(); k++) {
				JsonNode item = items.get(k);
				SupplierProduct hit = dictionary.get(k);
				JsonNode match = hit == null ? matches.get(k) : null;
				Long ingredientId;
				BigDecimal confidence;
				if (hit != null) {
					ingredientId = hit.getIngredientId();
					confidence = null;
					dictionaryMatched++;
				} else {
					ingredientId = match == null ? null : ingredientIdOf(ingredientIds, text(match, "ingredient"));
					confidence = match == null ? null : confidence(match.get("confidence"));
				}
				if (ingredientId != null) {
					ingredientMatched++;
				}
				ObjectNode raw = jsonMapper.createObjectNode();
				raw.set("item", item);
				raw.set("match", match);
				raw.put("dictionary", hit != null);
				PurchaseDocLine line = PurchaseDocLine.parsed(storeId, docId, k + 1,
						truncate(text(item, "name"), RAW_NAME_MAX), number(item.get("qty"), 3, QTY_LIMIT),
						blankToNull(truncate(text(item, "unit"), UNIT_MAX)), number(item.get("price"), 2, MONEY_LIMIT),
						number(item.get("amount"), 2, MONEY_LIMIT), hit == null ? null : hit.getId(), ingredientId,
						confidence, jsonText(raw), now);
				if (autoConfirmable(line, hit, supplierId, kind, ingredientsById)) {
					raw.put("autoConfirmed", true);
					line.autoConfirm(jsonText(raw));
				}
				lines.add(line);
			}
			String savedOcr = ocrJson;
			tx.executeWithoutResult(status -> {
				lines.forEach(lineRepository::save);
				PurchaseDoc doc = docRepository.getInStore(docId, storeId);
				doc.markParsed(savedOcr, DocType.RECEIPT, date, supplierId, kind);
				docRepository.save(doc);
			});
			return new DocSummary(String.valueOf(docId), fileName, "PARSED", kind == null ? null : kind.name(),
					date == null ? null : date.toString(), blankToNull(supplierName), lines.size(), dictionaryMatched,
					ingredientMatched, null);
		} catch (StepFailed e) {
			return fail(storeId, docId, fileName, e.code, ocrJson);
		} catch (BusinessException e) {
			return fail(storeId, docId, fileName, e.getErrorCode().getCode(), ocrJson);
		} catch (RuntimeException | StackOverflowError | OutOfMemoryError e) {
			// 한 장의 이상한 응답이 남은 장 처리를 막지 않게 메모리 · 재귀 오류까지 그 장의 실패로 닫는다
			log.error("영수증 job {} 문서 {} 처리 실패", ctx.jobId(), docId, e);
			return fail(storeId, docId, fileName, JobService.INTERNAL_ERROR, ocrJson);
		}
	}

	/**
	 * 이 장만 FAILED 로 남긴다. OCR 원문을 같이 저장하다 실패하면 원문 없이 한 번 더, 그래도 실패하면 로그만 남기고
	 * 다음 장으로 간다 — 실패 기록의 실패가 남은 장 처리를 막지 않게.
	 */
	private DocSummary fail(long storeId, long docId, String fileName, String code, String ocrJson) {
		log.info("영수증 문서 {} 실패: {}", docId, code);
		try {
			markFailed(storeId, docId, code, ocrJson);
		} catch (RuntimeException first) {
			log.warn("영수증 문서 {} 실패 기록을 OCR 원문과 함께 저장하지 못해 원문 없이 다시 저장한다", docId, first);
			try {
				markFailed(storeId, docId, code, null);
			} catch (RuntimeException second) {
				log.error("영수증 문서 {} 실패 기록을 저장하지 못했다", docId, second);
			}
		}
		return DocSummary.failed(docId, fileName, code);
	}

	private void markFailed(long storeId, long docId, String code, String ocrJson) {
		tx.executeWithoutResult(status -> {
			PurchaseDoc doc = docRepository.getInStore(docId, storeId);
			doc.markFailed(code, ocrJson);
			docRepository.save(doc);
		});
	}

	/** JSONB · text 칸에 넣을 JSON 글. PostgreSQL 은 NUL(\u0000) 을 받지 않으므로 뺀다. */
	private String jsonText(JsonNode node) {
		return jsonMapper.writeValueAsString(withoutNul(node == null ? null : node.deepCopy()));
	}

	/** 문자열 값 속의 NUL 을 지운다(제자리). 칸 이름은 그대로 둔다. */
	private JsonNode withoutNul(JsonNode node) {
		if (node == null) {
			return null;
		}
		if (node.isString()) {
			return jsonMapper.getNodeFactory().stringNode(stripNul(node.asString()));
		}
		if (node instanceof ObjectNode object) {
			for (String name : new ArrayList<>(object.propertyNames())) {
				object.set(name, withoutNul(object.get(name)));
			}
		} else if (node instanceof ArrayNode array) {
			for (int i = 0; i < array.size(); i++) {
				array.set(i, withoutNul(array.get(i)));
			}
		}
		return node;
	}

	private static String stripNul(String value) {
		return value == null ? null : value.replace("\u0000", "");
	}

	private byte[] readFile(String filePath) {
		if (filePath == null) {
			throw new StepFailed(FILE_MISSING);
		}
		try {
			return storage.read(filePath);
		} catch (IOException e) {
			throw new StepFailed(FILE_MISSING);
		}
	}

	/** 사전에 없는 줄만 LLM 매칭 1번. 응답의 범위 밖 · 중복 line 은 버린다(먼저 온 것). @return 항목 순번(0부터) → match 항목 */
	private Map<Integer, JsonNode> match(List<JsonNode> items, List<Integer> unmatched) {
		ArrayNode lines = jsonMapper.createArrayNode();
		for (int j = 0; j < unmatched.size(); j++) {
			JsonNode item = items.get(unmatched.get(j));
			ObjectNode line = lines.addObject();
			line.put("line", j + 1);
			line.put("name", text(item, "name"));
			line.set("unit", item.get("unit"));
			line.set("qty", item.get("qty"));
			line.set("price", item.get("price"));
		}
		String user = matchPrompt.userTemplate()
				.replace("{{catalog_json}}", catalogJson)
				.replace("{{lines_json}}", jsonMapper.writeValueAsString(lines));
		JsonNode out = gmsClient.completeJson(new LlmRequest(matchPrompt.system(), user, List.of(), MATCH_SCHEMA,
				matchPrompt.schema()));
		Map<Integer, JsonNode> byItem = new HashMap<>();
		for (JsonNode row : out.path("items")) {
			JsonNode lineNode = row.get("line");
			if (lineNode == null || !lineNode.isIntegralNumber() || !lineNode.canConvertToInt()) {
				continue;
			}
			int line = lineNode.asInt();
			if (line < 1 || line > unmatched.size()) {
				continue;
			}
			byItem.putIfAbsent(unmatched.get(line - 1), row);
		}
		return byItem;
	}

	private SupplierProduct lookupDictionary(long storeId, Long supplierId, String name) {
		List<SupplierProduct> hits = supplierProductRepository.findAllByStoreIdAndProductNameOrderByIdAsc(storeId,
				name);
		if (hits.isEmpty()) {
			return null;
		}
		if (supplierId != null) {
			for (SupplierProduct hit : hits) {
				if (supplierId.equals(hit.getSupplierId())) {
					return hit;
				}
			}
		}
		return hits.get(0);
	}

	private Long findSupplier(long storeId, String name) {
		if (name.isEmpty()) {
			return null;
		}
		for (Supplier supplier : supplierRepository.findAllByStoreIdOrderByIdAsc(storeId)) {
			if (supplier.getName() != null && supplier.getName().strip().equals(name)) {
				return supplier.getId();
			}
		}
		return null;
	}

	/** 가게의 가장 최근 실사 날짜(가게 시간대)보다 이전이면 PAST, 같거나 이후 · 실사 없음이면 RECEIVE, 날짜 없으면 null. */
	private DocKind kindOf(long storeId, LocalDate date) {
		if (date == null) {
			return null;
		}
		List<LocalDate> rows = jdbc.queryForList(LAST_COUNT_DATE_SQL, LocalDate.class, storeId);
		LocalDate lastCount = rows.isEmpty() ? null : rows.get(0);
		return lastCount != null && date.isBefore(lastCount) ? DocKind.PAST : DocKind.RECEIVE;
	}

	/**
	 * 자동 확정 (DECISIONS #41 · 설계 4.4, S15P31A308-112): 문서 거래처의 사전 상품으로 맞췄고(다른 거래처 상품 ·
	 * 거래처 모르는 문서 제외) 포장 환산이 있고 재료 단위가 추정값이 아니고(= 단위 미확정 아님) 날짜를 읽은 줄만.
	 * LLM 제안만 있는 줄은 확신도와 상관없이 PENDING. 문서는 PARSED 그대로라 재고에는 사장님이 문서를 확정해야 들어간다.
	 */
	private static boolean autoConfirmable(PurchaseDocLine line, SupplierProduct hit, Long supplierId, DocKind kind,
			Map<Long, Ingredient> ingredientsById) {
		if (hit == null || supplierId == null || !supplierId.equals(hit.getSupplierId()) || kind == null) {
			return false;
		}
		Ingredient ingredient = ingredientsById.get(hit.getIngredientId());
		return ReceiptReview.reasons(line, supplierId, hit, ingredient).isEmpty();
	}

	/** 이 가게 재료 이름(공백 제거 · 소문자) → id. 같은 이름이면 먼저 만든 것. */
	private static Map<String, Long> ingredientIds(List<Ingredient> ingredients) {
		Map<String, Long> ids = new LinkedHashMap<>();
		for (Ingredient ingredient : ingredients) {
			ids.putIfAbsent(IngredientCatalog.normalize(ingredient.getName()), ingredient.getId());
		}
		return ids;
	}

	/**
	 * LLM 이 고른 재료표 이름 → 이 가게 재료. 이름이 같을 때만(공백 제거 · 소문자). 재료표 별칭으로 넓히지 않는다 —
	 * 별칭 묶음(펜네 · 스파게티 → 파스타면)이 다른 재료에 확신도를 붙여 잘못 묶기 때문.
	 */
	private static Long ingredientIdOf(Map<String, Long> ingredientIds, String suggested) {
		return suggested.isEmpty() ? null : ingredientIds.get(IngredientCatalog.normalize(suggested));
	}

	private static String catalogJson(IngredientCatalog catalog, JsonMapper jsonMapper) {
		ArrayNode rows = jsonMapper.createArrayNode();
		for (IngredientCatalog.Item item : catalog.items()) {
			ObjectNode row = rows.addObject();
			row.put("name", item.name());
			row.put("unit", item.unit().toLowerCase(Locale.ROOT));
			ArrayNode aliases = row.putArray("aliases");
			item.aliases().forEach(aliases::add);
		}
		return jsonMapper.writeValueAsString(rows);
	}

	private static LocalDate parseDate(String value) {
		if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) {
			return null;
		}
		try {
			return LocalDate.parse(value);
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	private static BigDecimal confidence(JsonNode node) {
		if (node == null || !node.isNumber()) {
			return null;
		}
		double value = node.asDouble();
		if (!(value >= 0 && value <= 1)) {
			return null;
		}
		return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP);
	}

	/** 숫자 칸. 숫자가 아니거나 칸 자릿수를 넘으면 null. */
	private static BigDecimal number(JsonNode node, int scale, BigDecimal limit) {
		if (node == null || !node.isNumber()) {
			return null;
		}
		if ((node.isDouble() || node.isFloat()) && !Double.isFinite(node.asDouble())) {
			return null; // 1e400 같은 값은 무한대로 읽힌다
		}
		BigDecimal value = node.decimalValue().setScale(scale, RoundingMode.HALF_UP);
		return value.abs().compareTo(limit) >= 0 ? null : value;
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node == null ? null : node.get(field);
		return value == null || !value.isString() ? "" : stripNul(value.asString()).strip();
	}

	/** 글자(코드 포인트) 수로 자른다 — 이모지 한가운데를 자르지 않게. */
	private static String truncate(String value, int max) {
		if (value.codePointCount(0, value.length()) <= max) {
			return value;
		}
		return value.substring(0, value.offsetByCodePoints(0, max));
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	/** 이 장만 실패시키는 사유. */
	private static final class StepFailed extends RuntimeException {
		private final String code;

		StepFailed(String code) {
			super(code);
			this.code = code;
		}
	}
}
