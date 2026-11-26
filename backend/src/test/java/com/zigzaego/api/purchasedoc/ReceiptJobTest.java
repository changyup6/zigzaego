package com.zigzaego.api.purchasedoc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockingDetails;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.auth.AuthTestSupport;
import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.llm.GmsClient;
import com.zigzaego.api.llm.LlmErrorCode;
import com.zigzaego.api.llm.LlmException;
import com.zigzaego.api.llm.LlmRequest;
import com.zigzaego.api.purchasedoc.dto.ReceiptUpload;
import com.zigzaego.api.purchasedoc.exception.ReceiptJobErrorCode;
import com.zigzaego.api.purchasedoc.job.ReceiptAnalyzer;
import com.zigzaego.api.purchasedoc.job.ReceiptFileStorage;
import com.zigzaego.api.purchasedoc.ocr.FakeOcrClient;
import com.zigzaego.api.purchasedoc.ocr.OcrClient;
import com.zigzaego.api.purchasedoc.ocr.OcrErrorCode;
import com.zigzaego.api.purchasedoc.ocr.OcrException;
import com.zigzaego.api.purchasedoc.ocr.OcrImage;
import com.zigzaego.api.purchasedoc.service.ReceiptJobService;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.sql.Date;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 영수증 업로드 job (지라 S15P31A308-111). OCR · GMS 는 목, DB · job 실행기 · 파일 저장은 실제. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = "app.receipt.storage-dir=build/test-receipts")
class ReceiptJobTest {

	private static final String SHRIMP = "냉동 칵테일새우 1kg";

	@Autowired
	private ReceiptJobService receiptJobService;

	@Autowired
	private ReceiptFileStorage storage;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JsonMapper json;

	@MockitoBean
	private GmsClient gmsClient;

	@MockitoBean
	private OcrClient ocrClient;

	private final FakeOcrClient fakeOcr = new FakeOcrClient(JsonMapper.builder().build());
	private final List<LlmRequest> llmCalls = new CopyOnWriteArrayList<>();
	/** 정형화 n 번째 호출(0부터) → 응답. 기본은 가짜 영수증 4줄 · 2026-10-18. */
	private volatile Function<Integer, JsonNode> normalize;
	/** 매칭 요청의 lines → 응답. 기본은 이름 규칙대로. */
	private volatile Function<JsonNode, JsonNode> match;

	@BeforeEach
	void setUp() {
		normalize = n -> receipt("2026-10-18");
		match = this::ruleMatch;
		AtomicInteger normalizeCalls = new AtomicInteger();
		given(gmsClient.completeJson(any(LlmRequest.class))).willAnswer(invocation -> {
			LlmRequest request = invocation.getArgument(0);
			llmCalls.add(request);
			if (ReceiptAnalyzer.NORMALIZE_SCHEMA.equals(request.schemaName())) {
				return normalize.apply(normalizeCalls.getAndIncrement());
			}
			return match.apply(linesOf(request));
		});
		given(ocrClient.recognize(any(OcrImage.class))).willAnswer(inv -> fakeOcr.recognize(inv.getArgument(0)));
	}

	// ── 데이터 ────────────────────────────────────────────────

	private long insertStore(String label) {
		String name = label + "-" + System.nanoTime();
		return AuthTestSupport.insertStore(jdbc, name);
	}

	private long insertIngredient(long storeId, String name, String unit) {
		return jdbc.queryForObject("""
				INSERT INTO ingredient (store_id, name, unit, price_sensitive, sourcing_type, kind, storage_type, storage_location, category_id, category_source)
				VALUES (?, ?, ?, false, 'GENERIC', 'BOM', 'CHILLED', 'FRIDGE',
				        (SELECT id FROM ingredient_category WHERE store_id = ? AND code = 'OTHER'), 'AUTO') RETURNING id""", Long.class, storeId, name, unit, storeId);
	}

	private long insertSupplier(long storeId, String name) {
		return jdbc.queryForObject("INSERT INTO supplier (store_id, name) VALUES (?, ?) RETURNING id", Long.class,
				storeId, name);
	}

	private long insertProduct(long storeId, long supplierId, long ingredientId, String productName) {
		return jdbc.queryForObject("""
				INSERT INTO supplier_product (store_id, supplier_id, ingredient_id, product_name)
				VALUES (?, ?, ?, ?) RETURNING id""", Long.class, storeId, supplierId, ingredientId, productName);
	}

	private static ReceiptUpload jpg(String name) {
		return new ReceiptUpload(name, "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, 1, 2});
	}

	private ObjectNode item(String name, String unit, Number qty, Number price, Number amount) {
		ObjectNode item = json.createObjectNode();
		item.put("name", name);
		if (unit == null) {
			item.putNull("unit");
		} else {
			item.put("unit", unit);
		}
		item.put("qty", qty == null ? null : new BigDecimal(qty.toString()));
		item.put("price", price == null ? null : new BigDecimal(price.toString()));
		item.put("amount", amount == null ? null : new BigDecimal(amount.toString()));
		return item;
	}

	/** 가짜 영수증 글자를 정형화한 결과 4줄. */
	private JsonNode receipt(String date) {
		ObjectNode out = json.createObjectNode();
		out.put("supplier", "위글리마트");
		if (date == null) {
			out.putNull("date");
		} else {
			out.put("date", date);
		}
		ArrayNode items = out.putArray("items");
		items.add(item("대파 1단", "단", 2, 3980, 7960));
		items.add(item(SHRIMP, null, 1, 12900, 12900));
		items.add(item("다진마늘 500g", null, 1, 5500, 5500));
		items.add(item("키친타올 6롤", null, 1, 4200, 4200));
		return out;
	}

	private JsonNode linesOf(LlmRequest request) {
		String user = request.user();
		int start = user.indexOf("영수증 줄:\n") + "영수증 줄:\n".length();
		int end = user.indexOf("\n\n위 영수증 줄마다");
		return json.readTree(user.substring(start, end));
	}

	/** 이름 규칙대로 매칭: 대파 → 대파 1000 g 0.9, 다진마늘 → 마늘 500 g 0.8, 키친타올 → 새 주기형. */
	private JsonNode ruleMatch(JsonNode lines) {
		ObjectNode out = json.createObjectNode();
		ArrayNode items = out.putArray("items");
		for (JsonNode line : lines) {
			String name = line.get("name").asString();
			ObjectNode row = items.addObject();
			row.put("line", line.get("line").asInt());
			if (name.contains("대파")) {
				row.put("ingredient", "대파").put("is_new_periodic", false);
				row.putObject("pack").put("qty", 1000).put("unit", "g");
				row.put("confidence", 0.9);
			} else if (name.contains("마늘")) {
				row.put("ingredient", "마늘").put("is_new_periodic", false);
				row.putObject("pack").put("qty", 500).put("unit", "g");
				row.put("confidence", 0.8);
			} else if (name.contains("키친타올")) {
				row.putNull("ingredient").put("is_new_periodic", true);
				row.putObject("pack").put("qty", 6).put("unit", "ea");
				row.put("confidence", 0.7);
			} else {
				row.putNull("ingredient").put("is_new_periodic", false);
				row.putNull("pack");
				row.put("confidence", 0.3);
			}
			row.put("reason", "테스트");
		}
		return out;
	}

	// ── job 기다리기 · 읽기 ─────────────────────────────────────

	private long start(long storeId, List<ReceiptUpload> uploads) {
		return Long.parseLong(receiptJobService.start(storeId, uploads).id());
	}

	private Map<String, Object> readJob(long jobId) {
		return jdbc.queryForMap("SELECT status, progress, result_json::text AS result_json, error FROM job WHERE id = ?",
				jobId);
	}

	private Map<String, Object> awaitFinished(long jobId) {
		Predicate<Map<String, Object>> finished = row -> Set.of("DONE", "FAILED").contains((String) row.get("status"));
		long deadline = System.currentTimeMillis() + 15_000;
		while (System.currentTimeMillis() < deadline) {
			Map<String, Object> row = readJob(jobId);
			if (finished.test(row)) {
				return row;
			}
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(e);
			}
		}
		return fail("job %d 이 15초 안에 끝나지 않았다: %s", jobId, readJob(jobId));
	}

	private JsonNode resultOf(Map<String, Object> row) {
		return json.readTree((String) row.get("result_json"));
	}

	private List<Map<String, Object>> docs(long jobId) {
		return jdbc.queryForList("""
				SELECT id, store_id, supplier_id, doc_type, doc_date, file_path, parse_status, ocr_json::text AS ocr_json,
				       kind, error
				FROM purchase_doc WHERE job_id = ? ORDER BY id""", jobId);
	}

	private List<Map<String, Object>> lines(long docId) {
		return jdbc.queryForList("""
				SELECT line_no, raw_name, qty, unit, unit_price, amount, supplier_product_id, ingredient_id, confidence,
				       status, raw::text AS raw
				FROM purchase_doc_line WHERE doc_id = ? ORDER BY line_no""", docId);
	}

	private Map<String, Object> line(long docId, String rawName) {
		return lines(docId).stream().filter(l -> rawName.equals(l.get("raw_name"))).findFirst()
				.orElseGet(() -> fail("줄 없음: " + rawName));
	}

	private long docId(Map<String, Object> doc) {
		return ((Number) doc.get("id")).longValue();
	}

	private long countLlm(String schema) {
		return llmCalls.stream().filter(r -> schema.equals(r.schemaName())).count();
	}

	// ── 정상 ─────────────────────────────────────────────────

	@Test
	void 두_장_업로드로_DONE_문서_2장_PARSED_줄_4개씩_사전_매칭_LLM_매칭_새_주기형() throws Exception {
		long storeId = insertStore("영수증");
		long greenOnion = insertIngredient(storeId, "대파", "G");
		long shrimp = insertIngredient(storeId, "새우", "EA");
		long supplier = insertSupplier(storeId, "식봄");
		long product = insertProduct(storeId, supplier, shrimp, SHRIMP);

		long jobId = start(storeId, List.of(jpg("IMG_2031.jpg"), jpg("IMG_2032.jpg")));
		Map<String, Object> job = awaitFinished(jobId);

		assertThat(job.get("status")).isEqualTo("DONE");
		assertThat(job.get("error")).isNull();
		assertThat((BigDecimal) job.get("progress")).isEqualByComparingTo("1");
		List<Map<String, Object>> docs = docs(jobId);
		assertThat(docs).hasSize(2);
		for (Map<String, Object> doc : docs) {
			assertThat(doc.get("parse_status")).isEqualTo("PARSED");
			assertThat(doc.get("doc_type")).isEqualTo("RECEIPT");
			assertThat(doc.get("doc_date")).isEqualTo(Date.valueOf("2026-10-18"));
			assertThat(doc.get("kind")).isEqualTo("RECEIVE"); // 실사 없음
			assertThat(doc.get("supplier_id")).isNull(); // "위글리마트" 는 등록 거래처가 아니다
			assertThat(doc.get("error")).isNull();
			assertThat(json.readTree((String) doc.get("ocr_json")).get("fake").asBoolean()).isTrue();
			String path = (String) doc.get("file_path");
			assertThat(path).startsWith(storeId + "/" + jobId + "/").endsWith(".jpg");
			assertThat(Files.readAllBytes(storage.resolve(path))).containsExactly(0xFF, 0xD8, 1, 2);

			long docId = docId(doc);
			List<Map<String, Object>> lines = lines(docId);
			assertThat(lines).extracting(l -> l.get("line_no")).containsExactly(1, 2, 3, 4);
			assertThat(lines).allSatisfy(l -> assertThat(l.get("status")).isEqualTo("PENDING"));

			Map<String, Object> shrimpLine = line(docId, SHRIMP);
			assertThat(shrimpLine.get("supplier_product_id")).isEqualTo(product);
			assertThat(shrimpLine.get("ingredient_id")).isEqualTo(shrimp);
			assertThat(shrimpLine.get("confidence")).isNull();
			assertThat(json.readTree((String) shrimpLine.get("raw")).get("dictionary").asBoolean()).isTrue();

			Map<String, Object> onionLine = line(docId, "대파 1단");
			assertThat(onionLine.get("ingredient_id")).isEqualTo(greenOnion);
			assertThat(onionLine.get("supplier_product_id")).isNull();
			assertThat((BigDecimal) onionLine.get("confidence")).isEqualByComparingTo("0.9");
			assertThat((BigDecimal) onionLine.get("qty")).isEqualByComparingTo("2");
			assertThat(onionLine.get("unit")).isEqualTo("단");
			assertThat((BigDecimal) onionLine.get("unit_price")).isEqualByComparingTo("3980");
			assertThat((BigDecimal) onionLine.get("amount")).isEqualByComparingTo("7960");
			JsonNode pack = json.readTree((String) onionLine.get("raw")).get("match").get("pack");
			assertThat(pack.get("qty").asInt()).isEqualTo(1000);
			assertThat(pack.get("unit").asString()).isEqualTo("g");

			Map<String, Object> garlicLine = line(docId, "다진마늘 500g");
			assertThat(garlicLine.get("ingredient_id")).isNull(); // 가게에 "마늘" 재료가 없다
			assertThat(json.readTree((String) garlicLine.get("raw")).get("match").get("ingredient").asString())
					.isEqualTo("마늘");

			Map<String, Object> towelLine = line(docId, "키친타올 6롤");
			assertThat(towelLine.get("ingredient_id")).isNull();
			assertThat(json.readTree((String) towelLine.get("raw")).get("match").get("is_new_periodic").asBoolean())
					.isTrue();
		}

		// 장마다 정형화 1 + 매칭 1. 사전에 있는 새우 줄은 매칭 요청에 없다
		assertThat(countLlm(ReceiptAnalyzer.NORMALIZE_SCHEMA)).isEqualTo(2);
		assertThat(countLlm(ReceiptAnalyzer.MATCH_SCHEMA)).isEqualTo(2);
		for (LlmRequest request : llmCalls) {
			if (ReceiptAnalyzer.MATCH_SCHEMA.equals(request.schemaName())) {
				JsonNode lines = linesOf(request);
				assertThat(lines).hasSize(3);
				List<String> names = new ArrayList<>();
				lines.forEach(l -> names.add(l.get("name").asString()));
				assertThat(names).containsExactly("대파 1단", "다진마늘 500g", "키친타올 6롤");
				assertThat(request.user()).contains("\"name\":\"대파\"").doesNotContain("{{");
			} else {
				assertThat(request.user()).contains("대파 1단").doesNotContain("{{");
			}
		}

		JsonNode result = resultOf(job);
		assertThat(result.get("docs")).hasSize(2);
		JsonNode first = result.get("docs").get(0);
		assertThat(first.get("id").asString()).isEqualTo(String.valueOf(docId(docs.get(0))));
		assertThat(first.get("file").asString()).isEqualTo("IMG_2031.jpg");
		assertThat(first.get("status").asString()).isEqualTo("PARSED");
		assertThat(first.get("kind").asString()).isEqualTo("RECEIVE");
		assertThat(first.get("date").asString()).isEqualTo("2026-10-18");
		assertThat(first.get("supplier").asString()).isEqualTo("위글리마트");
		assertThat(first.get("lines").asInt()).isEqualTo(4);
		assertThat(first.get("dictionaryMatched").asInt()).isEqualTo(1);
		assertThat(first.get("ingredientMatched").asInt()).isEqualTo(2);
		assertThat(first.get("error").isNull()).isTrue();
	}

	@Test
	void 등록된_거래처_이름이면_supplier_id_를_채운다() {
		long storeId = insertStore("거래처");
		long supplier = insertSupplier(storeId, "위글리마트");

		long jobId = start(storeId, List.of(jpg("a.jpg")));
		awaitFinished(jobId);

		assertThat(docs(jobId).get(0).get("supplier_id")).isEqualTo(supplier);
	}

	// ── 실패 ─────────────────────────────────────────────────

	@Test
	void failed_두번째_장_정형화가_LLM_FAILED_면_그_장만_FAILED_job_DONE() {
		long storeId = insertStore("실패");
		normalize = n -> {
			if (n == 1) {
				throw new LlmException(LlmErrorCode.FAILED);
			}
			return receipt("2026-10-18");
		};

		long jobId = start(storeId, List.of(jpg("a.jpg"), jpg("b.jpg")));
		Map<String, Object> job = awaitFinished(jobId);

		assertThat(job.get("status")).isEqualTo("DONE");
		List<Map<String, Object>> docs = docs(jobId);
		assertThat(docs.get(0).get("parse_status")).isEqualTo("PARSED");
		assertThat(docs.get(1).get("parse_status")).isEqualTo("FAILED");
		assertThat(docs.get(1).get("error")).isEqualTo("LLM_FAILED");
		assertThat(docs.get(1).get("ocr_json")).isNotNull(); // OCR 원문은 남겨 정형화만 다시 돌릴 수 있게
		assertThat(lines(docId(docs.get(1)))).isEmpty();
		JsonNode result = resultOf(job);
		assertThat(result.get("docs").get(1).get("status").asString()).isEqualTo("FAILED");
		assertThat(result.get("docs").get(1).get("error").asString()).isEqualTo("LLM_FAILED");
	}

	@Test
	void empty_OCR_글자가_없으면_그_장만_FAILED_OCR_EMPTY() {
		long storeId = insertStore("빈글자");
		AtomicInteger calls = new AtomicInteger();
		given(ocrClient.recognize(any(OcrImage.class))).willAnswer(inv -> {
			if (calls.getAndIncrement() == 0) {
				throw new OcrException(OcrErrorCode.EMPTY);
			}
			return fakeOcr.recognize(inv.getArgument(0));
		});

		long jobId = start(storeId, List.of(jpg("a.jpg"), jpg("b.jpg")));
		Map<String, Object> job = awaitFinished(jobId);

		assertThat(job.get("status")).isEqualTo("DONE");
		List<Map<String, Object>> docs = docs(jobId);
		assertThat(docs.get(0).get("parse_status")).isEqualTo("FAILED");
		assertThat(docs.get(0).get("error")).isEqualTo("OCR_EMPTY");
		assertThat(docs.get(0).get("ocr_json")).isNull();
		assertThat(docs.get(1).get("parse_status")).isEqualTo("PARSED");
		assertThat(countLlm(ReceiptAnalyzer.NORMALIZE_SCHEMA)).isEqualTo(1); // 빈 글자는 LLM 에 넘기지 않는다
	}

	@Test
	void noItems_정형화_품목이_0줄이면_RECEIPT_NO_ITEMS() {
		long storeId = insertStore("품목없음");
		normalize = n -> {
			ObjectNode out = json.createObjectNode();
			out.putNull("supplier");
			out.putNull("date");
			out.putArray("items").add(item("  ", null, 1, 100, 100)); // 이름이 빈 줄은 품목이 아니다
			return out;
		};

		long jobId = start(storeId, List.of(jpg("a.jpg")));
		Map<String, Object> job = awaitFinished(jobId);

		assertThat(docs(jobId).get(0).get("parse_status")).isEqualTo("FAILED");
		assertThat(docs(jobId).get(0).get("error")).isEqualTo(ReceiptAnalyzer.NO_ITEMS);
		// 한 장뿐이라 모든 장 실패 → job FAILED, 사유 = 그 장의 사유
		assertThat(job.get("status")).isEqualTo("FAILED");
		assertThat(job.get("error")).isEqualTo(ReceiptAnalyzer.NO_ITEMS);
		assertThat(countLlm(ReceiptAnalyzer.MATCH_SCHEMA)).isZero();
	}

	@Test
	void allFailed_모든_장이_같은_사유면_job_FAILED_그_사유_다르면_RECEIPT_ALL_FAILED() {
		long storeId = insertStore("전부실패");
		given(ocrClient.recognize(any(OcrImage.class))).willThrow(new OcrException(OcrErrorCode.UNAVAILABLE));

		Map<String, Object> same = awaitFinished(start(storeId, List.of(jpg("a.jpg"), jpg("b.jpg"))));

		assertThat(same.get("status")).isEqualTo("FAILED");
		assertThat(same.get("error")).isEqualTo("OCR_UNAVAILABLE");

		AtomicInteger calls = new AtomicInteger();
		given(ocrClient.recognize(any(OcrImage.class))).willAnswer(inv -> {
			if (calls.getAndIncrement() == 0) {
				throw new OcrException(OcrErrorCode.FAILED);
			}
			throw new OcrException(OcrErrorCode.EMPTY);
		});

		Map<String, Object> mixed = awaitFinished(start(storeId, List.of(jpg("a.jpg"), jpg("b.jpg"))));

		assertThat(mixed.get("status")).isEqualTo("FAILED");
		assertThat(mixed.get("error")).isEqualTo(ReceiptAnalyzer.ALL_FAILED);
	}

	@Test
	void badMatch_범위_밖_중복_line_확신도_범위_밖은_버리고_줄은_모두_저장() {
		long storeId = insertStore("이상한매칭");
		long greenOnion = insertIngredient(storeId, "대파", "G");
		match = lines -> {
			ObjectNode out = json.createObjectNode();
			ArrayNode items = out.putArray("items");
			items.addObject().put("line", 0).put("ingredient", "대파").put("is_new_periodic", false).putNull("pack")
					.put("confidence", 0.9).put("reason", "범위 밖");
			items.addObject().put("line", 99).put("ingredient", "대파").put("is_new_periodic", false).putNull("pack")
					.put("confidence", 0.9).put("reason", "범위 밖");
			items.addObject().put("line", 1).put("ingredient", "대파").put("is_new_periodic", false).putNull("pack")
					.put("confidence", 0.95).put("reason", "먼저 온 것");
			items.addObject().put("line", 1).putNull("ingredient").put("is_new_periodic", true).putNull("pack")
					.put("confidence", 0.1).put("reason", "중복");
			items.addObject().put("line", 2).putNull("ingredient").put("is_new_periodic", false).putNull("pack")
					.put("confidence", 1.5).put("reason", "확신도 범위 밖");
			return out; // line 3 · 4 는 응답에 없다
		};

		long jobId = start(storeId, List.of(jpg("a.jpg")));
		Map<String, Object> job = awaitFinished(jobId);

		assertThat(job.get("status")).isEqualTo("DONE");
		long docId = docId(docs(jobId).get(0));
		assertThat(lines(docId)).hasSize(4);
		Map<String, Object> onion = line(docId, "대파 1단");
		assertThat(onion.get("ingredient_id")).isEqualTo(greenOnion);
		assertThat((BigDecimal) onion.get("confidence")).isEqualByComparingTo("0.95");
		assertThat(line(docId, SHRIMP).get("confidence")).isNull(); // 1.5 → NULL
		assertThat(json.readTree((String) line(docId, "다진마늘 500g").get("raw")).get("match").isNull()).isTrue();
		assertThat(line(docId, "키친타올 6롤").get("ingredient_id")).isNull();
	}

	// ── 감사 1라운드에서 찾은 깨짐 (2026-10-19) ───────────────────

	@Test
	void nul_OCR_원문과_품목명의_NUL_글자는_지우고_저장한다() {
		long storeId = insertStore("NUL");
		given(ocrClient.recognize(any(OcrImage.class))).willAnswer(inv -> {
			ObjectNode raw = json.createObjectNode();
			raw.putArray("images").addObject().putArray("fields").addObject().put("inferText", "대파\u0000");
			return new com.zigzaego.api.purchasedoc.ocr.OcrResult("대파\u0000 1단 3,980", raw);
		});
		normalize = n -> {
			ObjectNode out = (ObjectNode) receipt("2026-10-18");
			((ObjectNode) out.get("items").get(0)).put("name", "대\u0000파 1단");
			return out;
		};

		long jobId = start(storeId, List.of(jpg("a.jpg"), jpg("b.jpg"), jpg("c.jpg")));
		Map<String, Object> job = awaitFinished(jobId);

		assertThat(job.get("status")).isEqualTo("DONE");
		assertThat(docs(jobId)).allSatisfy(d -> {
			assertThat(d.get("parse_status")).isEqualTo("PARSED");
			assertThat((String) d.get("ocr_json")).contains("대파").doesNotContain("\\u0000");
			assertThat(lines(docId(d)).get(0).get("raw_name")).isEqualTo("대파 1단");
		});
		assertThat(llmCalls.get(0).user()).doesNotContain("\u0000");
	}

	@Test
	void fileMissing_원본_파일이_없으면_그_장만_RECEIPT_FILE_MISSING() throws Exception {
		long storeId = insertStore("파일없음");
		java.util.concurrent.CountDownLatch firstOcr = new java.util.concurrent.CountDownLatch(1);
		java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
		given(ocrClient.recognize(any(OcrImage.class))).willAnswer(inv -> {
			if (firstOcr.getCount() > 0) {
				firstOcr.countDown();
				release.await(10, java.util.concurrent.TimeUnit.SECONDS); // 첫 장을 붙잡은 사이 둘째 장 파일을 지운다
			}
			return fakeOcr.recognize(inv.getArgument(0));
		});

		long jobId = start(storeId, List.of(jpg("a.jpg"), jpg("b.jpg")));
		assertThat(firstOcr.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
		Files.delete(storage.resolve((String) docs(jobId).get(1).get("file_path")));
		release.countDown();
		Map<String, Object> job = awaitFinished(jobId);

		assertThat(job.get("status")).isEqualTo("DONE");
		assertThat(docs(jobId).get(0).get("parse_status")).isEqualTo("PARSED");
		assertThat(docs(jobId).get(1).get("parse_status")).isEqualTo("FAILED");
		assertThat(docs(jobId).get(1).get("error")).isEqualTo(ReceiptAnalyzer.FILE_MISSING);
	}

	@Test
	void dictionary_같은_상품명이_여러_거래처에_있으면_문서_거래처의_상품을_고른다() {
		long storeId = insertStore("사전우선");
		long shrimpA = insertIngredient(storeId, "새우", "EA");
		long shrimpB = insertIngredient(storeId, "칵테일새우", "EA");
		insertProduct(storeId, insertSupplier(storeId, "식봄"), shrimpA, SHRIMP);
		long mart = insertSupplier(storeId, "위글리마트");
		long martProduct = insertProduct(storeId, mart, shrimpB, SHRIMP);

		long jobId = start(storeId, List.of(jpg("a.jpg")));
		awaitFinished(jobId);

		Map<String, Object> shrimpLine = line(docId(docs(jobId).get(0)), SHRIMP);
		assertThat(shrimpLine.get("supplier_product_id")).isEqualTo(martProduct);
		assertThat(shrimpLine.get("ingredient_id")).isEqualTo(shrimpB);
	}

	@Test
	void alias_재료표_별칭이_같은_묶음이어도_가게_재료_이름이_다르면_매칭하지_않는다() {
		long storeId = insertStore("별칭");
		insertIngredient(storeId, "스파게티면", "G");
		match = lines -> {
			ObjectNode out = json.createObjectNode();
			ArrayNode items = out.putArray("items");
			for (JsonNode l : lines) {
				items.addObject().put("line", l.get("line").asInt()).put("ingredient", "펜네")
						.put("is_new_periodic", false).putNull("pack").put("confidence", 0.9).put("reason", "별칭");
			}
			return out;
		};

		long jobId = start(storeId, List.of(jpg("a.jpg")));
		awaitFinished(jobId);

		assertThat(lines(docId(docs(jobId).get(0)))).allSatisfy(l -> assertThat(l.get("ingredient_id")).isNull());
	}

	@Test
	void infinity_수량이_무한대로_읽히면_그_칸만_NULL_장은_PARSED() {
		long storeId = insertStore("무한대");
		normalize = n -> json.readTree("""
				{"supplier":null,"date":"2026-10-18","items":[
				  {"name":"대파 1단","unit":null,"qty":1e400,"price":-1e400,"amount":12345678901234}]}""");

		long jobId = start(storeId, List.of(jpg("a.jpg")));
		awaitFinished(jobId);

		Map<String, Object> doc = docs(jobId).get(0);
		assertThat(doc.get("parse_status")).isEqualTo("PARSED");
		Map<String, Object> onion = lines(docId(doc)).get(0);
		assertThat(onion.get("qty")).isNull();
		assertThat(onion.get("unit_price")).isNull();
		assertThat(onion.get("amount")).isNull(); // NUMERIC(12,2) 자릿수를 넘는다
	}

	// ── 감사 2라운드에서 찾은 깨짐 (2026-10-19) ───────────────────

	@Test
	void nulFileName_올린_파일_이름의_NUL_은_지우고_모든_장을_처리한다() {
		long storeId = insertStore("NUL이름");

		long jobId = start(storeId, List.of(new ReceiptUpload("a\u0000.jpg", "image/jpeg", new byte[] {1, 2}),
				jpg("b.jpg")));
		Map<String, Object> job = awaitFinished(jobId);

		assertThat(job.get("status")).isEqualTo("DONE");
		assertThat(docs(jobId)).allSatisfy(d -> assertThat(d.get("parse_status")).isEqualTo("PARSED"));
		assertThat(resultOf(job).get("docs").get(0).get("file").asString()).isEqualTo("a.jpg");
	}

	@Test
	void hugeLine_매칭_응답_line_이_int_범위_밖이면_그_항목만_버린다() {
		long storeId = insertStore("큰line");
		long greenOnion = insertIngredient(storeId, "대파", "G");
		match = lines -> {
			ObjectNode out = (ObjectNode) ruleMatch(lines);
			((ArrayNode) out.get("items")).insertObject(0).put("line", 4294967297L).put("ingredient", "대파")
					.put("is_new_periodic", false).putNull("pack").put("confidence", 0.1).put("reason", "범위 밖");
			return out;
		};

		long jobId = start(storeId, List.of(jpg("a.jpg")));
		awaitFinished(jobId);

		Map<String, Object> doc = docs(jobId).get(0);
		assertThat(doc.get("parse_status")).isEqualTo("PARSED");
		Map<String, Object> onion = line(docId(doc), "대파 1단");
		assertThat(onion.get("ingredient_id")).isEqualTo(greenOnion);
		assertThat((BigDecimal) onion.get("confidence")).isEqualByComparingTo("0.9");
	}

	@Test
	void emoji_200자_자르기는_이모지_한가운데를_자르지_않는다() {
		long storeId = insertStore("이모지");
		String name = "a".repeat(199) + "😀" + "b";
		normalize = n -> {
			ObjectNode out = (ObjectNode) receipt("2026-10-18");
			((ObjectNode) out.get("items").get(0)).put("name", name);
			return out;
		};

		long jobId = start(storeId, List.of(jpg("a.jpg")));
		awaitFinished(jobId);

		assertThat(lines(docId(docs(jobId).get(0))).get(0).get("raw_name")).isEqualTo("a".repeat(199) + "😀");
	}

	@Test
	void docGone_처리_중_문서_행이_사라져도_실패_기록의_실패를_넘기고_다음_장을_처리한다() throws Exception {
		long storeId = insertStore("문서삭제");
		java.util.concurrent.CountDownLatch firstOcr = new java.util.concurrent.CountDownLatch(1);
		java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
		given(ocrClient.recognize(any(OcrImage.class))).willAnswer(inv -> {
			if (firstOcr.getCount() > 0) {
				firstOcr.countDown();
				release.await(10, java.util.concurrent.TimeUnit.SECONDS);
			}
			return fakeOcr.recognize(inv.getArgument(0));
		});

		long jobId = start(storeId, List.of(jpg("a.jpg"), jpg("b.jpg"), jpg("c.jpg")));
		assertThat(firstOcr.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
		long gone = docId(docs(jobId).get(1));
		jdbc.update("DELETE FROM purchase_doc WHERE id = ?", gone); // 둘째 장은 PROCESSING 표시도 FAILED 기록도 못 한다
		release.countDown();
		Map<String, Object> job = awaitFinished(jobId);

		assertThat(job.get("status")).isEqualTo("DONE");
		List<Map<String, Object>> docs = docs(jobId);
		assertThat(docs).hasSize(2);
		assertThat(docs).allSatisfy(d -> assertThat(d.get("parse_status")).isEqualTo("PARSED"));
		JsonNode gonePart = resultOf(job).get("docs").get(1);
		assertThat(gonePart.get("status").asString()).isEqualTo("FAILED");
		assertThat(gonePart.get("error").asString()).isEqualTo("COMMON_NOT_FOUND");
	}

	// ── 입력 검사 (job 을 만들지 않는다) ─────────────────────────

	private long jobCount(long storeId) {
		return jdbc.queryForObject("SELECT count(*) FROM job WHERE store_id = ?", Long.class, storeId);
	}

	private void assertRejected(long storeId, List<ReceiptUpload> uploads, ReceiptJobErrorCode code) {
		assertThatThrownBy(() -> receiptJobService.start(storeId, uploads))
				.isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
	}

	@Test
	void upload_0장_51장_10MB_초과_빈_파일_형식_밖은_400_job_없음() {
		long storeId = insertStore("입력");

		assertRejected(storeId, List.of(), ReceiptJobErrorCode.FILE_REQUIRED);
		assertRejected(storeId, null, ReceiptJobErrorCode.FILE_REQUIRED);
		assertRejected(storeId, Collections.nCopies(51, jpg("a.jpg")), ReceiptJobErrorCode.TOO_MANY_FILES);
		assertRejected(storeId,
				List.of(new ReceiptUpload("big.jpg", "image/jpeg", new byte[(int) ReceiptJobService.MAX_FILE_BYTES + 1])),
				ReceiptJobErrorCode.FILE_TOO_LARGE);
		assertRejected(storeId, List.of(new ReceiptUpload("empty.jpg", "image/jpeg", new byte[0])),
				ReceiptJobErrorCode.FILE_REQUIRED);
		assertRejected(storeId, List.of(jpg("a.jpg"), new ReceiptUpload("a.txt", "text/plain", new byte[] {1})),
				ReceiptJobErrorCode.FILE_NOT_SUPPORTED);

		assertThat(jobCount(storeId)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_doc WHERE store_id = ?", Long.class, storeId))
				.isZero();
	}

	@Test
	void upload_50장_딱_10MB_PNG_PDF_image_jpg_는_받는다() {
		long storeId = insertStore("경계");

		long jobId = start(storeId, List.of(
				new ReceiptUpload("full.png", "image/png", new byte[(int) ReceiptJobService.MAX_FILE_BYTES]),
				new ReceiptUpload("doc.pdf", "application/pdf", new byte[] {1}),
				new ReceiptUpload("x.jpg", "IMAGE/JPG", new byte[] {1})));
		awaitFinished(jobId);

		assertThat(docs(jobId)).extracting(d -> ((String) d.get("file_path")).replaceAll(".*\\.", ""))
				.containsExactly("png", "pdf", "jpg");
		var fifty = receiptJobService.start(storeId, Collections.nCopies(50, jpg("a.jpg")));
		assertThat(fifty.status().name()).isEqualTo("QUEUED");
		// 다음 테스트의 목 호출 수에 섞이지 않게 끝까지 기다린다
		assertThat(awaitFinished(Long.parseLong(fifty.id())).get("status")).isEqualTo("DONE");
		assertThat(docs(Long.parseLong(fifty.id()))).hasSize(50);
	}

	// ── PAST / RECEIVE ───────────────────────────────────────

	private String kindAfter(long storeId, String date) {
		normalize = n -> receipt(date);
		long jobId = start(storeId, List.of(jpg("a.jpg")));
		awaitFinished(jobId);
		return (String) docs(jobId).get(0).get("kind");
	}

	@Test
	void kind_실사일_이전은_PAST_같은날_이후는_RECEIVE_날짜_없으면_NULL() {
		long storeId = insertStore("실사");
		long ingredient = insertIngredient(storeId, "양파", "G");
		// 2026-10-10 00:30 (Asia/Seoul) = 2026-10-09 15:30Z. 가게 시간대 날짜는 10-10
		jdbc.update("INSERT INTO stock_count (store_id, ingredient_id, counted_at, qty) VALUES (?, ?, ?::timestamptz, 1)",
				storeId, ingredient, "2026-10-09T15:30:00Z");
		jdbc.update("INSERT INTO stock_count (store_id, ingredient_id, counted_at, qty) VALUES (?, ?, ?::timestamptz, 1)",
				storeId, ingredient, "2026-10-01T03:00:00Z"); // 더 이른 실사는 기준이 아니다

		assertThat(kindAfter(storeId, "2026-10-09")).isEqualTo("PAST");
		assertThat(kindAfter(storeId, "2026-10-10")).isEqualTo("RECEIVE");
		assertThat(kindAfter(storeId, "2026-10-11")).isEqualTo("RECEIVE");
		assertThat(kindAfter(storeId, null)).isNull();
		assertThat(kindAfter(storeId, "2026/10/11")).isNull(); // YYYY-MM-DD 가 아니면 날짜 없음

		long other = insertStore("실사없음");
		assertThat(kindAfter(other, "2020-01-01")).isEqualTo("RECEIVE");
	}

	// ── 가게 2개 ─────────────────────────────────────────────

	@Test
	void store_다른_가게의_사전_상품_재료_거래처와는_매칭되지_않는다() {
		long storeA = insertStore("가게A");
		long storeB = insertStore("가게B");
		long shrimpB = insertIngredient(storeB, "새우", "EA");
		insertIngredient(storeB, "대파", "G");
		insertProduct(storeB, insertSupplier(storeB, "식봄"), shrimpB, SHRIMP);
		insertSupplier(storeB, "위글리마트");
		long onionA = insertIngredient(storeA, "대파", "G");

		long jobId = start(storeA, List.of(jpg("a.jpg")));
		Map<String, Object> job = awaitFinished(jobId);

		assertThat(job.get("status")).isEqualTo("DONE");
		Map<String, Object> doc = docs(jobId).get(0);
		assertThat(doc.get("store_id")).isEqualTo(storeA);
		assertThat(doc.get("supplier_id")).isNull();
		long docId = docId(doc);
		for (Map<String, Object> line : lines(docId)) {
			assertThat(line.get("supplier_product_id")).isNull();
		}
		assertThat(line(docId, SHRIMP).get("ingredient_id")).isNull();
		assertThat(line(docId, "대파 1단").get("ingredient_id")).isEqualTo(onionA);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_doc_line WHERE doc_id = ? AND store_id <> ?",
				Long.class, docId, storeA)).isZero();
		// 사전에 없으므로 새우 줄도 LLM 매칭으로 갔다
		assertThat(mockingDetails(gmsClient).getInvocations()).hasSize(2);
	}
}
