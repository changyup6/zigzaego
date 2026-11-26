package com.zigzaego.api.purchasedoc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.auth.AuthTestSupport;
import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.inventory.dto.InventoryEstimateResponse;
import com.zigzaego.api.inventory.service.InventoryEstimateService;
import com.zigzaego.api.llm.GmsClient;
import com.zigzaego.api.llm.LlmRequest;
import com.zigzaego.api.purchasedoc.dto.ConfirmResultResponse;
import com.zigzaego.api.purchasedoc.dto.LineDecisionRequest;
import com.zigzaego.api.purchasedoc.dto.LineDecisionRequest.Action;
import com.zigzaego.api.purchasedoc.dto.ReceiptUpload;
import com.zigzaego.api.purchasedoc.job.ReceiptAnalyzer;
import com.zigzaego.api.purchasedoc.ocr.FakeOcrClient;
import com.zigzaego.api.purchasedoc.ocr.OcrClient;
import com.zigzaego.api.purchasedoc.ocr.OcrImage;
import com.zigzaego.api.purchasedoc.service.ReceiptConfirmService;
import com.zigzaego.api.purchasedoc.service.ReceiptJobService;
import com.zigzaego.api.support.LlmFakes;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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

/**
 * 영수증 줄 확정 (지라 S15P31A308-112): 사전 저장 · 주기형 생성 · PAST/RECEIVE 반영 · 자동 확정 · 경계.
 * GMS · OCR 은 목, DB · job 실행기는 실제. 재고는 {@link InventoryEstimateService} 를 날짜 고정으로 직접 부른다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = "app.receipt.storage-dir=build/test-receipts")
class ReceiptConfirmTest {

	private static final String ONION = "대파 1단";
	private static final String TOWEL = "키친타올 6롤";
	/** 실사 2026-10-10 10:00 (가게 시간대 Asia/Seoul) 뒤, 소비 0 인 날. */
	private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
	private static final OffsetDateTime COUNTED_AT = OffsetDateTime.of(2026, 10, 10, 10, 0, 0, 0, ZoneOffset.ofHours(9));

	@Autowired
	private ReceiptConfirmService confirmService;

	@Autowired
	private ReceiptJobService receiptJobService;

	@Autowired
	private InventoryEstimateService inventoryService;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JsonMapper json;

	@Autowired
	private org.springframework.transaction.PlatformTransactionManager txManager;

	@MockitoBean
	private GmsClient gmsClient;

	@MockitoBean
	private OcrClient ocrClient;

	private final FakeOcrClient fakeOcr = new FakeOcrClient(JsonMapper.builder().build());
	private final List<LlmRequest> llmCalls = new CopyOnWriteArrayList<>();
	/** 정형화 응답. 기본은 거래처 OO상회 · 2026-10-12 · 대파 1단 2개 3980원. */
	private volatile JsonNode normalized;
	/** 매칭 요청의 lines → 응답. */
	private volatile Function<JsonNode, JsonNode> match;

	@BeforeEach
	void setUp() {
		normalized = receipt("OO상회", "2026-10-12", item(ONION, 2, 3980));
		match = lines -> ruleMatch(lines, 0.9);
		given(gmsClient.completeJson(any(LlmRequest.class))).willAnswer(invocation -> {
			LlmRequest request = invocation.getArgument(0);
			llmCalls.add(request);
			if (ReceiptAnalyzer.NORMALIZE_SCHEMA.equals(request.schemaName())) {
				return normalized;
			}
			return match.apply(linesOf(request));
		});
		given(ocrClient.recognize(any(OcrImage.class))).willAnswer(inv -> fakeOcr.recognize(inv.getArgument(0)));
		// 재료 카테고리 자동 분류(S15P31A308-274): 새 주기형 재료 이름을 묻는다 — 여기서는 전부 기타
		given(gmsClient.complete(any(LlmRequest.class))).willAnswer(invocation -> {
			LlmRequest request = invocation.getArgument(0);
			llmCalls.add(request);
			return LlmFakes.answerAll(request, "기타");
		});
	}

	// ── 데이터 ────────────────────────────────────────────────

	private long insertStore(String label) {
		String name = label + "-" + System.nanoTime();
		return AuthTestSupport.insertStore(jdbc, name);
	}

	/** BOM · 냉장 재료. unitStatus = ESTIMATED · CONFIRMED · LOCKED */
	private long insertIngredient(long storeId, String name, String unit, String unitStatus) {
		return jdbc.queryForObject("""
				INSERT INTO ingredient (store_id, name, unit, unit_status, price_sensitive, sourcing_type, kind, storage_type, storage_location,
				                        category_id, category_source)
				VALUES (?, ?, ?, ?, false, 'GENERIC', 'BOM', 'CHILLED', 'FRIDGE',
				        (SELECT id FROM ingredient_category WHERE store_id = ? AND code = 'OTHER'), 'AUTO') RETURNING id""", Long.class, storeId, name, unit,
				unitStatus, storeId);
	}

	private long insertSupplier(long storeId, String name) {
		return jdbc.queryForObject("INSERT INTO supplier (store_id, name) VALUES (?, ?) RETURNING id", Long.class,
				storeId, name);
	}

	private long insertProduct(long storeId, long supplierId, long ingredientId, String name, String baseQty,
			String price) {
		return jdbc.queryForObject("""
				INSERT INTO supplier_product (store_id, supplier_id, ingredient_id, product_name, base_qty_per_pack, price)
				VALUES (?, ?, ?, ?, CAST(? AS NUMERIC), CAST(? AS NUMERIC)) RETURNING id""", Long.class, storeId,
				supplierId, ingredientId, name, baseQty, price);
	}

	private void insertCount(long storeId, long ingredientId) {
		jdbc.update("INSERT INTO stock_count (store_id, ingredient_id, counted_at, qty) VALUES (?, ?, ?, 0)", storeId,
				ingredientId, COUNTED_AT);
	}

	/** 영수증 문서 한 장. kind = PAST · RECEIVE · null */
	private long insertDoc(long storeId, Long supplierId, String date, String kind, String parseStatus) {
		return jdbc.queryForObject("""
				INSERT INTO purchase_doc (store_id, supplier_id, doc_type, doc_date, parse_status, kind)
				VALUES (?, ?, 'RECEIPT', CAST(? AS DATE), ?, ?) RETURNING id""", Long.class, storeId, supplierId, date,
				parseStatus, kind);
	}

	private long insertLine(long storeId, long docId, int lineNo, String name, String qty, String price,
			Long ingredientId, Long productId, String status, String raw) {
		return jdbc.queryForObject("""
				INSERT INTO purchase_doc_line (store_id, doc_id, line_no, raw_name, qty, unit_price, ingredient_id,
				                               supplier_product_id, status, raw)
				VALUES (?, ?, ?, ?, CAST(? AS NUMERIC), CAST(? AS NUMERIC), ?, ?, ?, CAST(? AS JSONB)) RETURNING id""",
				Long.class, storeId, docId, lineNo, name, qty, price, ingredientId, productId, status, raw);
	}

	/** 111 이 남기는 원문 줄 모양: LLM 매칭 결과. */
	private String rawMatch(String ingredient, boolean periodic, Integer packQty, String packUnit) {
		ObjectNode raw = json.createObjectNode();
		raw.putObject("item").put("name", "x");
		ObjectNode match = raw.putObject("match");
		if (ingredient == null) {
			match.putNull("ingredient");
		} else {
			match.put("ingredient", ingredient);
		}
		match.put("is_new_periodic", periodic);
		if (packQty == null) {
			match.putNull("pack");
		} else {
			match.putObject("pack").put("qty", packQty).put("unit", packUnit);
		}
		match.put("confidence", 0.9);
		raw.put("dictionary", false);
		return json.writeValueAsString(raw);
	}

	private ObjectNode item(String name, Number qty, Number price) {
		ObjectNode item = json.createObjectNode();
		item.put("name", name);
		item.putNull("unit");
		item.put("qty", new BigDecimal(qty.toString()));
		item.put("price", new BigDecimal(price.toString()));
		item.put("amount", new BigDecimal(qty.toString()).multiply(new BigDecimal(price.toString())));
		return item;
	}

	private JsonNode receipt(String supplier, String date, ObjectNode... items) {
		ObjectNode out = json.createObjectNode();
		if (supplier == null) {
			out.putNull("supplier");
		} else {
			out.put("supplier", supplier);
		}
		if (date == null) {
			out.putNull("date");
		} else {
			out.put("date", date);
		}
		ArrayNode array = out.putArray("items");
		for (ObjectNode item : items) {
			array.add(item);
		}
		return out;
	}

	private JsonNode linesOf(LlmRequest request) {
		String user = request.user();
		int start = user.indexOf("영수증 줄:\n") + "영수증 줄:\n".length();
		int end = user.indexOf("\n\n위 영수증 줄마다");
		return json.readTree(user.substring(start, end));
	}

	/** 대파 → 대파 1000 g, 키친타올 → 새 주기형 6 ea, 그 밖 → 매칭 없음. */
	private JsonNode ruleMatch(JsonNode lines, double confidence) {
		ObjectNode out = json.createObjectNode();
		ArrayNode items = out.putArray("items");
		for (JsonNode line : lines) {
			String name = line.get("name").asString();
			ObjectNode row = items.addObject();
			row.put("line", line.get("line").asInt());
			if (name.contains("대파")) {
				row.put("ingredient", "대파").put("is_new_periodic", false);
				row.putObject("pack").put("qty", 1000).put("unit", "g");
			} else if (name.contains("키친타올")) {
				row.putNull("ingredient").put("is_new_periodic", true);
				row.putObject("pack").put("qty", 6).put("unit", "ea");
			} else {
				row.putNull("ingredient").put("is_new_periodic", false);
				row.putNull("pack");
			}
			row.put("confidence", confidence);
			row.put("reason", "테스트");
		}
		return out;
	}

	// ── job · 조회 ────────────────────────────────────────────

	/** 영수증 1장을 올리고 끝날 때까지 기다린다. @return 문서 id */
	private long upload(long storeId) {
		long jobId = Long.parseLong(receiptJobService.start(storeId,
				List.of(new ReceiptUpload("a.jpg", "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, 1}))).id());
		long deadline = System.currentTimeMillis() + 15_000;
		while (System.currentTimeMillis() < deadline) {
			String status = jdbc.queryForObject("SELECT status FROM job WHERE id = ?", String.class, jobId);
			if (Set.of("DONE", "FAILED").contains(status)) {
				assertThat(status).isEqualTo("DONE");
				return jdbc.queryForObject("SELECT id FROM purchase_doc WHERE job_id = ?", Long.class, jobId);
			}
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(e);
			}
		}
		return fail("job %d 이 15초 안에 끝나지 않았다", jobId);
	}

	private Map<String, Object> doc(long docId) {
		return jdbc.queryForMap("SELECT supplier_id, parse_status, kind FROM purchase_doc WHERE id = ?", docId);
	}

	private Map<String, Object> line(long lineId) {
		return jdbc.queryForMap("""
				SELECT status, ingredient_id, supplier_product_id, confidence, raw::text AS raw
				FROM purchase_doc_line WHERE id = ?""", lineId);
	}

	private long lineIdOf(long docId, String rawName) {
		return jdbc.queryForObject("SELECT id FROM purchase_doc_line WHERE doc_id = ? AND raw_name = ?", Long.class,
				docId, rawName);
	}

	private JsonNode rawOf(long lineId) {
		return json.readTree((String) line(lineId).get("raw"));
	}

	private long count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Long.class, args);
	}

	private long priceRows(long storeId) {
		return count("SELECT COUNT(*) FROM price_history WHERE store_id = ?", storeId);
	}

	private long productRows(long storeId) {
		return count("SELECT COUNT(*) FROM supplier_product WHERE store_id = ?", storeId);
	}

	private long ingredientRows(long storeId) {
		return count("SELECT COUNT(*) FROM ingredient WHERE store_id = ?", storeId);
	}

	private String unitStatus(long ingredientId) {
		return jdbc.queryForObject("SELECT unit_status FROM ingredient WHERE id = ?", String.class, ingredientId);
	}

	private BigDecimal stock(long storeId, long ingredientId) {
		for (InventoryEstimateResponse row : inventoryService.estimate(storeId, TODAY)) {
			if (row.ingredientId().equals(String.valueOf(ingredientId))) {
				return row.estimatedQty();
			}
		}
		return fail("재료 %d 의 추정 재고가 없다", ingredientId);
	}

	private long matchCallsSince(int from) {
		return llmCalls.subList(from, llmCalls.size()).stream()
				.filter(r -> ReceiptAnalyzer.MATCH_SCHEMA.equals(r.schemaName())).count();
	}

	private static LineDecisionRequest ingredient(long lineId, long ingredientId, String baseQty) {
		return new LineDecisionRequest(String.valueOf(lineId), Action.INGREDIENT, String.valueOf(ingredientId),
				baseQty == null ? null : new BigDecimal(baseQty));
	}

	private static LineDecisionRequest cycle(long lineId, String baseQty) {
		return new LineDecisionRequest(String.valueOf(lineId), Action.CYCLE, null,
				baseQty == null ? null : new BigDecimal(baseQty));
	}

	private static LineDecisionRequest ignore(long lineId) {
		return new LineDecisionRequest(String.valueOf(lineId), Action.IGNORE, null, null);
	}

	private static void assertRejected(ThrowingCallable call, String code) {
		assertThatThrownBy(call).isInstanceOf(BusinessException.class)
				.satisfies(e -> assertThat(((BusinessException) e).getErrorCode().getCode()).isEqualTo(code));
	}

	// ── 사전 저장 → 두 번째 LLM 0회 ────────────────────────────

	@Test
	void dictionary_확정하면_사전에_저장되고_같은_거래처_같은_상품명_다음_영수증은_LLM_매칭_0회_자동_확정() {
		long storeId = insertStore("사전");
		long onion = insertIngredient(storeId, "대파", "G", "CONFIRMED");
		long supplier = insertSupplier(storeId, "OO상회");

		long firstDoc = upload(storeId);
		long firstLine = lineIdOf(firstDoc, ONION);
		assertThat(line(firstLine).get("status")).isEqualTo("PENDING");
		assertThat(line(firstLine).get("ingredient_id")).isEqualTo(onion); // LLM 매칭
		assertThat(doc(firstDoc).get("supplier_id")).isEqualTo(supplier);

		ConfirmResultResponse result = confirmService.confirm(storeId, List.of(ingredient(firstLine, onion, "1000")));

		List<Map<String, Object>> products = jdbc.queryForList(
				"SELECT id, supplier_id, ingredient_id, product_name, base_qty_per_pack, price FROM supplier_product WHERE store_id = ?",
				storeId);
		assertThat(products).hasSize(1);
		Map<String, Object> product = products.get(0);
		assertThat(product.get("supplier_id")).isEqualTo(supplier);
		assertThat(product.get("ingredient_id")).isEqualTo(onion);
		assertThat(product.get("product_name")).isEqualTo(ONION);
		assertThat((BigDecimal) product.get("base_qty_per_pack")).isEqualByComparingTo("1000");
		assertThat((BigDecimal) product.get("price")).isEqualByComparingTo("3980");
		Map<String, Object> confirmed = line(firstLine);
		assertThat(confirmed.get("status")).isEqualTo("CONFIRMED");
		assertThat(confirmed.get("supplier_product_id")).isEqualTo(product.get("id"));
		assertThat((BigDecimal) confirmed.get("confidence")).isEqualByComparingTo("0.9"); // 확신도는 그대로
		JsonNode decision = rawOf(firstLine).get("decision");
		assertThat(decision.get("action").asString()).isEqualTo("INGREDIENT");
		assertThat(decision.get("by").asString()).isEqualTo("OWNER");
		assertThat(doc(firstDoc).get("parse_status")).isEqualTo("CONFIRMED");
		assertThat(unitStatus(onion)).isEqualTo("LOCKED");
		assertThat(result.confirmedLineIds()).containsExactly(String.valueOf(firstLine));
		assertThat(result.confirmedDocIds()).containsExactly(String.valueOf(firstDoc));
		assertThat(result.createdProductIds()).containsExactly(String.valueOf(product.get("id")));
		assertThat(result.priceHistoryCount()).isEqualTo(1);

		int before = llmCalls.size();
		long secondDoc = upload(storeId);

		assertThat(matchCallsSince(before)).isZero(); // 다른 줄이 없으니 매칭 호출 자체가 없다
		long secondLine = lineIdOf(secondDoc, ONION);
		Map<String, Object> auto = line(secondLine);
		assertThat(auto.get("status")).isEqualTo("CONFIRMED");
		assertThat(auto.get("supplier_product_id")).isEqualTo(product.get("id"));
		assertThat(auto.get("confidence")).isNull();
		assertThat(rawOf(secondLine).get("autoConfirmed").asBoolean()).isTrue();
		assertThat(doc(secondDoc).get("parse_status")).isEqualTo("PARSED"); // 자동 확정만으로 문서는 확정되지 않는다
	}

	@Test
	void dictionary_같은_요청의_같은_상품명_줄은_한_상품을_같이_쓰고_포장_환산은_한_번만_받는다() {
		long storeId = insertStore("묶음");
		long onion = insertIngredient(storeId, "대파", "G", "CONFIRMED");
		long supplier = insertSupplier(storeId, "OO상회");
		long docA = insertDoc(storeId, supplier, "2026-10-12", "RECEIVE", "PARSED");
		long docB = insertDoc(storeId, supplier, "2026-10-13", "RECEIVE", "PARSED");
		long lineA = insertLine(storeId, docA, 1, ONION, "2", "3980", onion, null, "PENDING",
				rawMatch("대파", false, 1000, "g"));
		long lineB = insertLine(storeId, docB, 1, "  " + ONION + " ", "1", "4100", onion, null, "PENDING",
				rawMatch("대파", false, 1000, "g"));

		ConfirmResultResponse result = confirmService.confirm(storeId,
				List.of(ingredient(lineB, onion, null), ingredient(lineA, onion, "1000")));

		assertThat(productRows(storeId)).isEqualTo(1);
		long productId = count("SELECT id FROM supplier_product WHERE store_id = ?", storeId);
		assertThat(line(lineA).get("supplier_product_id")).isEqualTo(productId);
		assertThat(line(lineB).get("supplier_product_id")).isEqualTo(productId);
		assertThat(result.createdProductIds()).hasSize(1);
		assertThat(result.priceHistoryCount()).isEqualTo(2);
		// 줄 id 순서로 반영 — 뒤 줄(RECEIVE)의 단가가 현재 단가
		assertThat(jdbc.queryForObject("SELECT price FROM supplier_product WHERE id = ?", BigDecimal.class, productId))
				.isEqualByComparingTo("4100");
	}

	@Test
	void dictionary_포장_환산을_뒤_줄에만_줘도_같은_상품명_앞_줄과_같은_추정_단위_재료_줄이_통과한다() {
		long storeId = insertStore("순서");
		long onion = insertIngredient(storeId, "대파", "G", "CONFIRMED");
		long garlic = insertIngredient(storeId, "마늘", "G", "ESTIMATED");
		long supplier = insertSupplier(storeId, "OO상회");
		long garlicProduct = insertProduct(storeId, supplier, garlic, "다진마늘 500g", "500", null);
		long docId = insertDoc(storeId, supplier, "2026-10-12", "RECEIVE", "PARSED");
		long firstOnion = insertLine(storeId, docId, 1, ONION, "1", "3980", onion, null, "PENDING", null);
		long firstGarlic = insertLine(storeId, docId, 2, "다진마늘 500g", "1", "5500", garlic, garlicProduct, "PENDING",
				null);
		long secondOnion = insertLine(storeId, docId, 3, ONION, "1", "3980", onion, null, "PENDING", null);
		long secondGarlic = insertLine(storeId, docId, 4, "깐마늘", "1", "6000", garlic, null, "PENDING", null);

		confirmService.confirm(storeId, List.of(ingredient(firstOnion, onion, null), ingredient(firstGarlic, garlic, null),
				ingredient(secondOnion, onion, "1000"), ingredient(secondGarlic, garlic, "1000")));

		assertThat(jdbc.queryForObject("SELECT base_qty_per_pack FROM supplier_product WHERE store_id = ? AND product_name = ?",
				BigDecimal.class, storeId, ONION)).isEqualByComparingTo("1000");
		assertThat(line(firstOnion).get("supplier_product_id")).isEqualTo(line(secondOnion).get("supplier_product_id"));
		assertThat(unitStatus(garlic)).isEqualTo("LOCKED");
		assertThat(doc(docId).get("parse_status")).isEqualTo("CONFIRMED");
	}

	// ── 주기형 생성 ──────────────────────────────────────────

	/** 다른 트랜잭션이 SQL 을 넣고 커밋하기 전에 멈춰 있다가, 확정이 그 행을 기다리기 시작한 뒤 커밋한다. */
	private Thread holdUncommitted(String sql, Object[] args, java.util.concurrent.CountDownLatch inserted) {
		Thread thread = new Thread(() -> new org.springframework.transaction.support.TransactionTemplate(txManager)
				.executeWithoutResult(status -> {
					jdbc.update(sql, args);
					inserted.countDown();
					try {
						Thread.sleep(1500);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}));
		thread.start();
		return thread;
	}

	@Test
	void cycle_다른_경로가_같은_이름_재료와_같은_사전_상품을_동시에_만들면_충돌_없이_다시_찾아_쓴다() throws Exception {
		long storeId = insertStore("동시");
		long supplier = insertSupplier(storeId, "OO상회");
		long seed = insertIngredient(storeId, "씨앗", "EA", "CONFIRMED");
		long docId = insertDoc(storeId, supplier, "2026-10-12", "RECEIVE", "PARSED");
		long lineId = insertLine(storeId, docId, 1, TOWEL, "1", "4200", null, null, "PENDING",
				rawMatch(null, true, 6, "ea"));
		java.util.concurrent.CountDownLatch inserted = new java.util.concurrent.CountDownLatch(2);
		Thread ingredientApi = holdUncommitted("""
				INSERT INTO ingredient (store_id, name, unit, unit_status, price_sensitive, sourcing_type, kind, storage_location, category_id, category_source)
				VALUES (?, ?, 'EA', 'CONFIRMED', false, 'GENERIC', 'CYCLE', 'WAREHOUSE',
				        (SELECT id FROM ingredient_category WHERE store_id = ? AND code = 'OTHER'), 'AUTO')""", new Object[] {storeId, TOWEL, storeId}, inserted);
		Thread productApi = holdUncommitted("""
				INSERT INTO supplier_product (store_id, supplier_id, ingredient_id, product_name, price)
				VALUES (?, ?, ?, ?, 3000)""", new Object[] {storeId, supplier, seed, TOWEL}, inserted);
		assertThat(inserted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

		ConfirmResultResponse result = confirmService.confirm(storeId, List.of(cycle(lineId, "6")));
		ingredientApi.join();
		productApi.join();

		assertThat(result.createdIngredientIds()).isEmpty();
		assertThat(result.createdProductIds()).isEmpty();
		long towel = jdbc.queryForObject("SELECT id FROM ingredient WHERE store_id = ? AND name = ?", Long.class,
				storeId, TOWEL);
		Map<String, Object> product = jdbc.queryForMap(
				"SELECT id, ingredient_id, base_qty_per_pack, price FROM supplier_product WHERE store_id = ?", storeId);
		assertThat(product.get("ingredient_id")).isEqualTo(towel); // 다시 찾은 상품을 이번 재료로 잇는다
		assertThat((BigDecimal) product.get("base_qty_per_pack")).isEqualByComparingTo("6");
		assertThat((BigDecimal) product.get("price")).isEqualByComparingTo("4200");
		assertThat(line(lineId).get("ingredient_id")).isEqualTo(towel);
		assertThat(line(lineId).get("supplier_product_id")).isEqualTo(product.get("id"));
		assertThat(unitStatus(towel)).isEqualTo("LOCKED");
		assertThat(priceRows(storeId)).isEqualTo(1);
	}

	@Test
	void cycle_매칭_없는_줄을_주기형으로_확정하면_새_재료가_생기고_다른_장의_같은_이름은_같은_재료() {
		long storeId = insertStore("주기형");
		long supplier = insertSupplier(storeId, "OO상회");
		long docA = insertDoc(storeId, supplier, "2026-10-12", "RECEIVE", "PARSED");
		long docB = insertDoc(storeId, null, "2026-10-13", "PAST", "PARSED");
		long lineA = insertLine(storeId, docA, 1, TOWEL, "1", "4200", null, null, "PENDING",
				rawMatch(null, true, 6, "ea"));
		long lineB = insertLine(storeId, docB, 1, TOWEL, "2", "4300", null, null, "PENDING",
				rawMatch(null, true, 6, "ea"));
		long ingredientsBefore = ingredientRows(storeId);

		ConfirmResultResponse result = confirmService.confirm(storeId, List.of(cycle(lineA, "6")));

		assertThat(ingredientRows(storeId)).isEqualTo(ingredientsBefore + 1);
		Map<String, Object> created = jdbc.queryForMap("""
				SELECT id, kind, name, unit, unit_status, sourcing_type, price_sensitive, storage_type, storage_location,
				       safety_days, shelf_life_days, category_id, category_source
				FROM ingredient WHERE store_id = ?""", storeId);
		long towel = ((Number) created.get("id")).longValue();
		assertThat(created.get("kind")).isEqualTo("CYCLE");
		// 카테고리는 자동 분류(S15P31A308-274): 표에 없는 이름이라 LLM 에 1번 묻고, 응답이 기타 → 그 가게의 기타 · 출처 AUTO
		assertThat(created.get("category_id")).isEqualTo(jdbc.queryForObject(
				"SELECT id FROM ingredient_category WHERE store_id = ? AND code = 'OTHER'", Long.class, storeId));
		assertThat(created.get("category_source")).isEqualTo("AUTO");
		assertThat(llmCalls.stream().filter(r -> "category".equals(r.schemaName())).toList())
				.singleElement().satisfies(r -> assertThat(LlmFakes.namesOf(r)).containsExactly(TOWEL));
		assertThat(created.get("name")).isEqualTo(TOWEL);
		assertThat(created.get("unit")).isEqualTo("EA");
		assertThat(created.get("unit_status")).isEqualTo("LOCKED");
		assertThat(created.get("sourcing_type")).isEqualTo("GENERIC");
		assertThat(created.get("price_sensitive")).isEqualTo(false);
		assertThat(created.get("storage_type")).isNull();
		assertThat(created.get("storage_location")).isEqualTo("WAREHOUSE");
		assertThat(created.get("shelf_life_days")).isNull();
		assertThat((BigDecimal) created.get("safety_days")).isEqualByComparingTo("1");
		assertThat(count("SELECT COUNT(*) FROM recipe WHERE ingredient_id = ?", towel)).isZero();
		Map<String, Object> product = jdbc.queryForMap(
				"SELECT ingredient_id, base_qty_per_pack FROM supplier_product WHERE store_id = ?", storeId);
		assertThat(product.get("ingredient_id")).isEqualTo(towel);
		assertThat((BigDecimal) product.get("base_qty_per_pack")).isEqualByComparingTo("6");
		assertThat(line(lineA).get("ingredient_id")).isEqualTo(towel);
		assertThat(result.createdIngredientIds()).containsExactly(String.valueOf(towel));

		ConfirmResultResponse again = confirmService.confirm(storeId, List.of(cycle(lineB, null))); // 거래처 모르는 장

		assertThat(ingredientRows(storeId)).isEqualTo(ingredientsBefore + 1);
		assertThat(line(lineB).get("ingredient_id")).isEqualTo(towel);
		assertThat(line(lineB).get("supplier_product_id")).isNull();
		assertThat(again.createdIngredientIds()).isEmpty();
	}

	@Test
	void cycle_같은_요청의_같은_이름_두_줄은_재료_하나_포장_단위_g_면_G_긴_이름은_100자에서_이모지를_깨지_않고_자른다() {
		long storeId = insertStore("주기형2");
		String longName = "🍅".repeat(99) + "소스원액"; // 103 코드 포인트
		long docA = insertDoc(storeId, null, "2026-10-12", "RECEIVE", "PARSED");
		long lineA = insertLine(storeId, docA, 1, longName, "1", "9000", null, null, "PENDING",
				rawMatch(null, true, 500, "g"));
		long lineB = insertLine(storeId, docA, 2, longName, "1", "9000", null, null, "PENDING",
				rawMatch(null, true, 500, "g"));

		ConfirmResultResponse result = confirmService.confirm(storeId, List.of(cycle(lineA, null), cycle(lineB, null)));

		assertThat(result.createdIngredientIds()).hasSize(1);
		Map<String, Object> created = jdbc.queryForMap("SELECT name, unit FROM ingredient WHERE store_id = ?", storeId);
		String name = (String) created.get("name");
		assertThat(name.codePointCount(0, name.length())).isEqualTo(100);
		assertThat(name).isEqualTo("🍅".repeat(99) + "소");
		assertThat(created.get("unit")).isEqualTo("G");
		assertThat(doc(docA).get("parse_status")).isEqualTo("CONFIRMED");
	}

	// ── PAST / RECEIVE 반영 ─────────────────────────────────

	/** 실사 2026-10-10 대파 0g, 사전 상품 OO상회 "대파 1단" = 1000 g · 현재 단가 3500. */
	private record Fixture(long storeId, long onion, long supplier, long product) {
	}

	private Fixture fixture(String label) {
		long storeId = insertStore(label);
		long onion = insertIngredient(storeId, "대파", "G", "CONFIRMED");
		long supplier = insertSupplier(storeId, "OO상회");
		long product = insertProduct(storeId, supplier, onion, ONION, "1000", "3500");
		insertCount(storeId, onion);
		return new Fixture(storeId, onion, supplier, product);
	}

	@Test
	void past_실사_전_문서를_확정하면_재고는_그대로이고_가격_이력_1행_현재_단가는_안_덮는다() {
		Fixture f = fixture("과거");
		long docId = insertDoc(f.storeId(), f.supplier(), "2026-10-09", "PAST", "PARSED");
		long lineId = insertLine(f.storeId(), docId, 1, ONION, "2", "3980.4", f.onion(), f.product(), "PENDING",
				rawMatch("대파", false, 1000, "g"));
		BigDecimal before = stock(f.storeId(), f.onion());

		confirmService.confirm(f.storeId(), List.of(ingredient(lineId, f.onion(), null)));

		assertThat(doc(docId).get("parse_status")).isEqualTo("CONFIRMED");
		assertThat(stock(f.storeId(), f.onion())).isEqualByComparingTo(before);
		List<Map<String, Object>> prices = jdbc.queryForList("""
				SELECT supplier_product_id, price, unit, source, doc_line_id, observed_at
				FROM price_history WHERE store_id = ?""", f.storeId());
		assertThat(prices).hasSize(1);
		Map<String, Object> price = prices.get(0);
		assertThat(price.get("supplier_product_id")).isEqualTo(f.product());
		assertThat((BigDecimal) price.get("price")).isEqualByComparingTo("3980"); // 원 정수
		assertThat(price.get("unit")).isEqualTo("PACK");
		assertThat(price.get("source")).isEqualTo("DOC");
		assertThat(price.get("doc_line_id")).isEqualTo(lineId);
		assertThat(jdbc.queryForObject("""
				SELECT observed_at = TIMESTAMPTZ '2026-10-09 00:00:00+09' FROM price_history WHERE doc_line_id = ?""",
				Boolean.class, lineId)).isTrue();
		assertThat(jdbc.queryForObject("SELECT price FROM supplier_product WHERE id = ?", BigDecimal.class,
				f.product())).isEqualByComparingTo("3500");
		assertThat(unitStatus(f.onion())).isEqualTo("LOCKED");
	}

	@Test
	void receive_실사_뒤_문서를_확정하면_재고_2단_x_1000g_더하고_가격_이력_1행_현재_단가_갱신() {
		Fixture f = fixture("입고");
		jdbc.update("UPDATE supplier_product SET pack_unit = '단' WHERE id = ?", f.product());
		long docId = insertDoc(f.storeId(), f.supplier(), "2026-10-12", "RECEIVE", "PARSED");
		long lineId = insertLine(f.storeId(), docId, 1, ONION, "2", "3980", f.onion(), f.product(), "PENDING",
				rawMatch("대파", false, 1000, "g"));
		BigDecimal before = stock(f.storeId(), f.onion());

		confirmService.confirm(f.storeId(), List.of(ingredient(lineId, f.onion(), null)));

		assertThat(stock(f.storeId(), f.onion()).subtract(before)).isEqualByComparingTo("2000.000");
		assertThat(priceRows(f.storeId())).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT unit FROM price_history WHERE doc_line_id = ?", String.class, lineId))
				.isEqualTo("단");
		assertThat(jdbc.queryForObject("SELECT price FROM supplier_product WHERE id = ?", BigDecimal.class,
				f.product())).isEqualByComparingTo("3980");
	}

	@Test
	void ignore_무시한_줄은_가격_이력_0행_재고_변화_0_재료_연결은_그대로() {
		Fixture f = fixture("무시");
		long docId = insertDoc(f.storeId(), f.supplier(), "2026-10-12", "RECEIVE", "PARSED");
		long lineId = insertLine(f.storeId(), docId, 1, ONION, "2", "3980", f.onion(), f.product(), "PENDING",
				rawMatch("대파", false, 1000, "g"));
		BigDecimal before = stock(f.storeId(), f.onion());

		ConfirmResultResponse result = confirmService.confirm(f.storeId(), List.of(ignore(lineId)));

		assertThat(line(lineId).get("status")).isEqualTo("IGNORED");
		assertThat(line(lineId).get("ingredient_id")).isEqualTo(f.onion());
		assertThat(rawOf(lineId).get("decision").get("action").asString()).isEqualTo("IGNORE");
		assertThat(doc(docId).get("parse_status")).isEqualTo("CONFIRMED"); // 남은 PENDING 0
		assertThat(priceRows(f.storeId())).isZero();
		assertThat(stock(f.storeId(), f.onion())).isEqualByComparingTo(before);
		assertThat(unitStatus(f.onion())).isEqualTo("CONFIRMED"); // 입고 기록이 아니다
		assertThat(result.ignoredLineIds()).containsExactly(String.valueOf(lineId));
		assertThat(result.priceHistoryCount()).isZero();
	}

	@Test
	void partial_PENDING_줄이_남은_RECEIVE_문서는_재고_변화_0_마지막_줄을_정하면_반영() {
		Fixture f = fixture("부분");
		long docId = insertDoc(f.storeId(), f.supplier(), "2026-10-12", "RECEIVE", "PARSED");
		long first = insertLine(f.storeId(), docId, 1, ONION, "2", "3980", f.onion(), f.product(), "PENDING",
				rawMatch("대파", false, 1000, "g"));
		long second = insertLine(f.storeId(), docId, 2, TOWEL, "1", "4200", null, null, "PENDING",
				rawMatch(null, true, 6, "ea"));
		BigDecimal before = stock(f.storeId(), f.onion());

		ConfirmResultResponse result = confirmService.confirm(f.storeId(), List.of(ingredient(first, f.onion(), null)));

		assertThat(line(first).get("status")).isEqualTo("CONFIRMED");
		assertThat(doc(docId).get("parse_status")).isEqualTo("PARSED");
		assertThat(result.confirmedDocIds()).isEmpty();
		assertThat(stock(f.storeId(), f.onion())).isEqualByComparingTo(before);

		confirmService.confirm(f.storeId(), List.of(ignore(second)));

		assertThat(doc(docId).get("parse_status")).isEqualTo("CONFIRMED");
		assertThat(stock(f.storeId(), f.onion()).subtract(before)).isEqualByComparingTo("2000.000");
	}

	// ── 경계: 입력 · 가게 ─────────────────────────────────────

	@Test
	void reject_빈_목록은_RECEIPT_LINE_REQUIRED() {
		long storeId = insertStore("빈");
		assertRejected(() -> confirmService.confirm(storeId, List.of()), "RECEIPT_LINE_REQUIRED");
		assertRejected(() -> confirmService.confirm(storeId, null), "RECEIPT_LINE_REQUIRED");
	}

	@Test
	void reject_같은_줄_id_두_번은_RECEIPT_LINE_DUPLICATE() {
		Fixture f = fixture("중복");
		long docId = insertDoc(f.storeId(), f.supplier(), "2026-10-12", "RECEIVE", "PARSED");
		long lineId = insertLine(f.storeId(), docId, 1, ONION, "2", "3980", f.onion(), f.product(), "PENDING", null);

		assertRejected(() -> confirmService.confirm(f.storeId(),
				List.of(ingredient(lineId, f.onion(), null), ignore(lineId))), "RECEIPT_LINE_DUPLICATE");
		assertRejected(() -> confirmService.confirm(f.storeId(), List.of(ingredient(lineId, f.onion(), null),
				new LineDecisionRequest("0" + lineId, Action.IGNORE, null, null))), "RECEIPT_LINE_DUPLICATE");
		assertThat(line(lineId).get("status")).isEqualTo("PENDING");
	}

	@Test
	void reject_다른_가게_줄_id_형식_오류_없는_줄은_404() {
		Fixture a = fixture("가게A");
		Fixture b = fixture("가게B");
		long docB = insertDoc(b.storeId(), b.supplier(), "2026-10-12", "RECEIVE", "PARSED");
		long lineB = insertLine(b.storeId(), docB, 1, ONION, "2", "3980", b.onion(), b.product(), "PENDING", null);

		assertRejected(() -> confirmService.confirm(a.storeId(), List.of(ingredient(lineB, a.onion(), null))),
				"COMMON_NOT_FOUND");
		assertRejected(() -> confirmService.confirm(a.storeId(),
				List.of(new LineDecisionRequest("abc", Action.IGNORE, null, null))), "COMMON_NOT_FOUND");
		assertRejected(() -> confirmService.confirm(a.storeId(),
				List.of(new LineDecisionRequest(null, Action.IGNORE, null, null))), "COMMON_NOT_FOUND");
		assertRejected(() -> confirmService.confirm(a.storeId(),
				List.of(new LineDecisionRequest("999999999999", Action.IGNORE, null, null))), "COMMON_NOT_FOUND");
		assertThat(line(lineB).get("status")).isEqualTo("PENDING");
	}

	@Test
	void reject_다른_가게_재료_id_는_404_재료_id_없음은_RECEIPT_INGREDIENT_REQUIRED() {
		Fixture a = fixture("재료A");
		Fixture b = fixture("재료B");
		long docA = insertDoc(a.storeId(), a.supplier(), "2026-10-12", "RECEIVE", "PARSED");
		long lineA = insertLine(a.storeId(), docA, 1, ONION, "2", "3980", a.onion(), a.product(), "PENDING", null);

		assertRejected(() -> confirmService.confirm(a.storeId(), List.of(ingredient(lineA, b.onion(), null))),
				"COMMON_NOT_FOUND");
		assertRejected(() -> confirmService.confirm(a.storeId(),
				List.of(new LineDecisionRequest(String.valueOf(lineA), Action.INGREDIENT, null, null))),
				"RECEIPT_INGREDIENT_REQUIRED");
		assertRejected(() -> confirmService.confirm(a.storeId(),
				List.of(new LineDecisionRequest(String.valueOf(lineA), Action.INGREDIENT, " ", null))),
				"RECEIPT_INGREDIENT_REQUIRED");
		assertThat(line(lineA).get("status")).isEqualTo("PENDING");
		assertThat(jdbc.queryForObject("SELECT ingredient_id FROM supplier_product WHERE id = ?", Long.class,
				a.product())).isEqualTo(a.onion());
	}

	@Test
	void reject_사장님이_이미_확정하거나_무시한_줄은_409_가격_이력_그대로() {
		Fixture f = fixture("이미");
		long docId = insertDoc(f.storeId(), f.supplier(), "2026-10-12", "RECEIVE", "PARSED");
		long lineId = insertLine(f.storeId(), docId, 1, ONION, "2", "3980", f.onion(), f.product(), "PENDING", null);
		long other = insertLine(f.storeId(), docId, 2, TOWEL, "1", "4200", null, null, "PENDING", null);
		confirmService.confirm(f.storeId(), List.of(ingredient(lineId, f.onion(), null), ignore(other)));
		long prices = priceRows(f.storeId());

		assertRejected(() -> confirmService.confirm(f.storeId(), List.of(ingredient(lineId, f.onion(), null))),
				"RECEIPT_LINE_ALREADY_DECIDED");
		assertRejected(() -> confirmService.confirm(f.storeId(), List.of(cycle(other, "6"))),
				"RECEIPT_LINE_ALREADY_DECIDED");
		assertThat(priceRows(f.storeId())).isEqualTo(prices);
	}

	@Test
	void reject_날짜_없는_문서는_RECEIPT_DATE_UNKNOWN_읽는_중인_문서는_RECEIPT_DOC_NOT_PARSED() {
		Fixture f = fixture("날짜");
		long noDate = insertDoc(f.storeId(), f.supplier(), null, null, "PARSED");
		long lineA = insertLine(f.storeId(), noDate, 1, ONION, "2", "3980", f.onion(), f.product(), "PENDING", null);
		long processing = insertDoc(f.storeId(), f.supplier(), "2026-10-12", "RECEIVE", "PROCESSING");
		long lineB = insertLine(f.storeId(), processing, 1, ONION, "2", "3980", f.onion(), f.product(), "PENDING",
				null);

		assertRejected(() -> confirmService.confirm(f.storeId(), List.of(ingredient(lineA, f.onion(), null))),
				"RECEIPT_DATE_UNKNOWN");
		assertRejected(() -> confirmService.confirm(f.storeId(), List.of(ignore(lineA))), "RECEIPT_DATE_UNKNOWN");
		assertRejected(() -> confirmService.confirm(f.storeId(), List.of(ingredient(lineB, f.onion(), null))),
				"RECEIPT_DOC_NOT_PARSED");
		assertThat(line(lineA).get("status")).isEqualTo("PENDING");
	}

	@Test
	void reject_단위_미확정인데_포장_환산이_없으면_RECEIPT_UNIT_REQUIRED() {
		long storeId = insertStore("단위");
		long onion = insertIngredient(storeId, "대파", "G", "CONFIRMED");
		long garlic = insertIngredient(storeId, "마늘", "G", "ESTIMATED");
		long supplier = insertSupplier(storeId, "OO상회");
		long noBase = insertProduct(storeId, supplier, onion, ONION, null, null);
		long docId = insertDoc(storeId, supplier, "2026-10-12", "RECEIVE", "PARSED");
		long onionLine = insertLine(storeId, docId, 1, ONION, "2", "3980", onion, noBase, "PENDING",
				rawMatch("대파", false, 1000, "g")); // LLM 포장만으로는 풀리지 않는다
		long newName = insertLine(storeId, docId, 2, "대파 한단", "1", "3980", onion, null, "PENDING", null);
		long noSupplierDoc = insertDoc(storeId, null, "2026-10-12", "RECEIVE", "PARSED");
		long garlicLine = insertLine(storeId, noSupplierDoc, 1, "다진마늘", "1", "5500", garlic, null, "PENDING", null);

		assertRejected(() -> confirmService.confirm(storeId, List.of(ingredient(onionLine, onion, null))),
				"RECEIPT_UNIT_REQUIRED");
		assertRejected(() -> confirmService.confirm(storeId, List.of(ingredient(newName, onion, null))),
				"RECEIPT_UNIT_REQUIRED");
		assertRejected(() -> confirmService.confirm(storeId, List.of(ingredient(garlicLine, garlic, null))),
				"RECEIPT_UNIT_REQUIRED"); // 거래처 모르는 문서여도 재료 단위가 추정값이면
		assertRejected(() -> confirmService.confirm(storeId, List.of(cycle(newName, null))), "RECEIPT_UNIT_REQUIRED");
		// 무시는 단위를 묻지 않는다 · 거래처 모르는 문서에 확정 단위 재료는 묻지 않는다
		confirmService.confirm(storeId, List.of(ignore(onionLine)));
		long onionNoSupplier = insertLine(storeId, noSupplierDoc, 2, ONION, "1", "3980", onion, null, "PENDING",
				null);
		confirmService.confirm(storeId, List.of(ingredient(onionNoSupplier, onion, null)));
		assertThat(line(onionNoSupplier).get("status")).isEqualTo("CONFIRMED");
		// 포장 환산을 주면 추정 단위 재료도 확정되고 LOCKED
		confirmService.confirm(storeId, List.of(ingredient(garlicLine, garlic, "500")));
		assertThat(unitStatus(garlic)).isEqualTo("LOCKED");
	}

	@Test
	void reject_포장_환산이_0_이하_소수_넷째_자리_자릿수_초과면_RECEIPT_UNIT_INVALID() {
		Fixture f = fixture("환산");
		long docId = insertDoc(f.storeId(), f.supplier(), "2026-10-12", "RECEIVE", "PARSED");
		long lineId = insertLine(f.storeId(), docId, 1, ONION, "2", "3980", f.onion(), f.product(), "PENDING", null);

		for (String bad : List.of("0", "-1", "0.0001", "100000000000", "1E+12")) {
			assertRejected(() -> confirmService.confirm(f.storeId(), List.of(ingredient(lineId, f.onion(), bad))),
					"RECEIPT_UNIT_INVALID");
		}
		confirmService.confirm(f.storeId(), List.of(ingredient(lineId, f.onion(), "99999999999.999")));
		assertThat(jdbc.queryForObject("SELECT base_qty_per_pack FROM supplier_product WHERE id = ?", BigDecimal.class,
				f.product())).isEqualByComparingTo("99999999999.999");
	}

	@Test
	void reject_세_줄_중_세번째만_잘못되면_앞_두_줄도_저장되지_않는다() {
		long storeId = insertStore("원자성");
		long onion = insertIngredient(storeId, "대파", "G", "CONFIRMED");
		long supplier = insertSupplier(storeId, "OO상회");
		long docId = insertDoc(storeId, supplier, "2026-10-12", "RECEIVE", "PARSED");
		long first = insertLine(storeId, docId, 1, ONION, "2", "3980", onion, null, "PENDING", null);
		long second = insertLine(storeId, docId, 2, TOWEL, "1", "4200", null, null, "PENDING",
				rawMatch(null, true, 6, "ea"));
		long third = insertLine(storeId, docId, 3, "봉투", "1", "100", null, null, "PENDING", null);
		long ingredients = ingredientRows(storeId);

		assertRejected(() -> confirmService.confirm(storeId, List.of(ingredient(first, onion, "1000"),
				cycle(second, "6"), ingredient(third, onion, "0"))), "RECEIPT_UNIT_INVALID");

		for (long lineId : List.of(first, second, third)) {
			assertThat(line(lineId).get("status")).isEqualTo("PENDING");
		}
		assertThat(productRows(storeId)).isZero();
		assertThat(ingredientRows(storeId)).isEqualTo(ingredients);
		assertThat(priceRows(storeId)).isZero();
		assertThat(unitStatus(onion)).isEqualTo("CONFIRMED");
		assertThat(doc(docId).get("parse_status")).isEqualTo("PARSED");
	}

	@Test
	void reject_pack_conflict_같은_상품명_두_줄에_1000_과_500_이면_RECEIPT_PACK_CONFLICT_저장_0행_같은_값은_통과() {
		Fixture f = fixture("포장충돌");
		long docA = insertDoc(f.storeId(), f.supplier(), "2026-10-12", "RECEIVE", "PARSED");
		long docB = insertDoc(f.storeId(), f.supplier(), "2026-10-13", "RECEIVE", "PARSED");
		long lineA = insertLine(f.storeId(), docA, 1, ONION, "2", "3980", f.onion(), null, "PENDING", null);
		long lineB = insertLine(f.storeId(), docB, 1, "  " + ONION + " ", "1", "4100", f.onion(), null, "PENDING", null);

		assertRejected(() -> confirmService.confirm(f.storeId(),
				List.of(ingredient(lineA, f.onion(), "1000"), ingredient(lineB, f.onion(), "500"))),
				"RECEIPT_PACK_CONFLICT");
		assertRejected(() -> confirmService.confirm(f.storeId(),
				List.of(ingredient(lineB, f.onion(), "500"), ingredient(lineA, f.onion(), "1000"))),
				"RECEIPT_PACK_CONFLICT");

		for (long lineId : List.of(lineA, lineB)) {
			assertThat(line(lineId).get("status")).isEqualTo("PENDING");
			assertThat(line(lineId).get("supplier_product_id")).isNull();
		}
		assertThat(productRows(f.storeId())).isEqualTo(1);
		Map<String, Object> product = jdbc.queryForMap(
				"SELECT base_qty_per_pack, price FROM supplier_product WHERE id = ?", f.product());
		assertThat((BigDecimal) product.get("base_qty_per_pack")).isEqualByComparingTo("1000");
		assertThat((BigDecimal) product.get("price")).isEqualByComparingTo("3500");
		assertThat(priceRows(f.storeId())).isZero();
		assertThat(unitStatus(f.onion())).isEqualTo("CONFIRMED");
		assertThat(doc(docA).get("parse_status")).isEqualTo("PARSED");
		assertThat(doc(docB).get("parse_status")).isEqualTo("PARSED");

		// 같은 값(자릿수만 다름)은 충돌이 아니다
		ConfirmResultResponse result = confirmService.confirm(f.storeId(),
				List.of(ingredient(lineA, f.onion(), "1000"), ingredient(lineB, f.onion(), "1000.0")));
		assertThat(result.confirmedLineIds()).containsExactly(String.valueOf(lineA), String.valueOf(lineB));
		assertThat(priceRows(f.storeId())).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT base_qty_per_pack FROM supplier_product WHERE id = ?", BigDecimal.class,
				f.product())).isEqualByComparingTo("1000");
	}

	@Test
	void reject_confirmDocuments_빈_목록_PENDING_남은_문서_다른_가게_문서() {
		Fixture f = fixture("문서확정");
		Fixture other = fixture("문서확정B");
		long docId = insertDoc(f.storeId(), f.supplier(), "2026-10-12", "RECEIVE", "PARSED");
		insertLine(f.storeId(), docId, 1, ONION, "2", "3980", f.onion(), f.product(), "PENDING", null);
		long otherDoc = insertDoc(other.storeId(), other.supplier(), "2026-10-12", "RECEIVE", "PARSED");

		assertRejected(() -> confirmService.confirmDocuments(f.storeId(), List.of()), "RECEIPT_DOC_REQUIRED");
		assertRejected(() -> confirmService.confirmDocuments(f.storeId(), List.of(String.valueOf(docId))),
				"RECEIPT_DOC_HAS_PENDING");
		assertRejected(() -> confirmService.confirmDocuments(f.storeId(), List.of(String.valueOf(otherDoc))),
				"COMMON_NOT_FOUND");
		assertThat(doc(docId).get("parse_status")).isEqualTo("PARSED");
	}

	// ── 경계: 자동 확정 ─────────────────────────────────────

	@Test
	void auto_사전_상품의_거래처가_문서_거래처와_다르면_PENDING() {
		long storeId = insertStore("다른거래처");
		long onion = insertIngredient(storeId, "대파", "G", "CONFIRMED");
		insertSupplier(storeId, "OO상회");
		long otherSupplier = insertSupplier(storeId, "다른상회");
		long otherProduct = insertProduct(storeId, otherSupplier, onion, ONION, "1000", "3500");

		long docId = upload(storeId);

		long lineId = lineIdOf(docId, ONION);
		assertThat(line(lineId).get("supplier_product_id")).isEqualTo(otherProduct); // 사전 매칭은 됐지만
		assertThat(line(lineId).get("status")).isEqualTo("PENDING");
		assertThat(rawOf(lineId).has("autoConfirmed")).isFalse();
	}

	@Test
	void auto_거래처_모르는_문서의_사전_매칭은_PENDING() {
		long storeId = insertStore("모르는거래처");
		long onion = insertIngredient(storeId, "대파", "G", "CONFIRMED");
		long supplier = insertSupplier(storeId, "OO상회");
		insertProduct(storeId, supplier, onion, ONION, "1000", "3500");
		normalized = receipt("OO마트", "2026-10-12", item(ONION, 2, 3980));

		long docId = upload(storeId);

		assertThat(doc(docId).get("supplier_id")).isNull();
		assertThat(line(lineIdOf(docId, ONION)).get("status")).isEqualTo("PENDING");
	}

	@Test
	void auto_사전_상품_포장_환산이_NULL_이거나_재료_단위가_추정값이거나_날짜가_없으면_PENDING() {
		long storeId = insertStore("환산없음");
		long onion = insertIngredient(storeId, "대파", "G", "CONFIRMED");
		long garlic = insertIngredient(storeId, "마늘", "G", "ESTIMATED");
		long supplier = insertSupplier(storeId, "OO상회");
		insertProduct(storeId, supplier, onion, ONION, null, "3500");
		insertProduct(storeId, supplier, garlic, "다진마늘 500g", "500", "5500");
		insertProduct(storeId, supplier, onion, "대파 2단", "2000", "7000");
		normalized = receipt("OO상회", "2026-10-12", item(ONION, 2, 3980), item("다진마늘 500g", 1, 5500));

		long docId = upload(storeId);

		assertThat(line(lineIdOf(docId, ONION)).get("status")).isEqualTo("PENDING");
		assertThat(line(lineIdOf(docId, "다진마늘 500g")).get("status")).isEqualTo("PENDING");

		normalized = receipt("OO상회", null, item("대파 2단", 1, 7000));
		long noDate = upload(storeId);
		assertThat(doc(noDate).get("kind")).isNull();
		assertThat(line(lineIdOf(noDate, "대파 2단")).get("status")).isEqualTo("PENDING");
	}

	@Test
	void auto_LLM_확신도_0_99_인_사전_밖_줄은_PENDING() {
		long storeId = insertStore("확신도");
		long onion = insertIngredient(storeId, "대파", "G", "CONFIRMED");
		insertSupplier(storeId, "OO상회");
		match = lines -> ruleMatch(lines, 0.99);

		long docId = upload(storeId);

		Map<String, Object> row = line(lineIdOf(docId, ONION));
		assertThat(row.get("ingredient_id")).isEqualTo(onion);
		assertThat((BigDecimal) row.get("confidence")).isEqualByComparingTo("0.99");
		assertThat(row.get("status")).isEqualTo("PENDING");
	}

	@Test
	void auto_자동_확정_줄만_있는_문서는_PARSED_재고_변화_0_confirmDocuments_뒤_CONFIRMED_재고_가격_이력_반영() {
		Fixture f = fixture("자동만");
		long docId = upload(f.storeId()); // 2026-10-12 RECEIVE, 대파 1단 2개 3980원
		long lineId = lineIdOf(docId, ONION);
		assertThat(line(lineId).get("status")).isEqualTo("CONFIRMED");
		assertThat(doc(docId).get("parse_status")).isEqualTo("PARSED");
		assertThat(doc(docId).get("kind")).isEqualTo("RECEIVE");
		BigDecimal before = stock(f.storeId(), f.onion());
		assertThat(priceRows(f.storeId())).isZero();
		assertThat(unitStatus(f.onion())).isEqualTo("CONFIRMED");

		ConfirmResultResponse result = confirmService.confirmDocuments(f.storeId(),
				List.of(String.valueOf(docId), String.valueOf(docId)));

		assertThat(doc(docId).get("parse_status")).isEqualTo("CONFIRMED");
		assertThat(stock(f.storeId(), f.onion()).subtract(before)).isEqualByComparingTo("2000.000");
		assertThat(priceRows(f.storeId())).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT doc_line_id FROM price_history WHERE store_id = ?", Long.class,
				f.storeId())).isEqualTo(lineId);
		assertThat(unitStatus(f.onion())).isEqualTo("LOCKED");
		assertThat(result.confirmedDocIds()).containsExactly(String.valueOf(docId));
		assertThat(result.priceHistoryCount()).isEqualTo(1);
		// 확정된 문서의 자동 확정 줄은 다시 정할 수 없다
		assertRejected(() -> confirmService.confirm(f.storeId(), List.of(ignore(lineId))), "RECEIPT_DOC_NOT_PARSED");
	}

	@Test
	void auto_자동_확정_줄을_사장님이_다른_재료로_다시_확정하면_허용되고_사전_상품의_재료가_바뀐다() {
		Fixture f = fixture("재확정");
		long chive = insertIngredient(f.storeId(), "쪽파", "G", "CONFIRMED");
		long docId = upload(f.storeId());
		long lineId = lineIdOf(docId, ONION);
		assertThat(rawOf(lineId).get("autoConfirmed").asBoolean()).isTrue();

		ConfirmResultResponse result = confirmService.confirm(f.storeId(), List.of(ingredient(lineId, chive, null)));

		assertThat(line(lineId).get("ingredient_id")).isEqualTo(chive);
		assertThat(line(lineId).get("supplier_product_id")).isEqualTo(f.product());
		assertThat(rawOf(lineId).has("autoConfirmed")).isFalse();
		assertThat(rawOf(lineId).get("decision").get("by").asString()).isEqualTo("OWNER");
		assertThat(jdbc.queryForObject("SELECT ingredient_id FROM supplier_product WHERE id = ?", Long.class,
				f.product())).isEqualTo(chive);
		assertThat(doc(docId).get("parse_status")).isEqualTo("CONFIRMED");
		assertThat(result.priceHistoryCount()).isEqualTo(1); // 다시 정한 줄 몫 한 번만
		assertThat(priceRows(f.storeId())).isEqualTo(1);
	}

	// ── 경계: 거래처 모르는 문서 ──────────────────────────────

	@Test
	void supplier_거래처_모르는_문서를_확정하면_거래처_사전_가격_이력_0행_줄만_CONFIRMED() {
		long storeId = insertStore("마트");
		long onion = insertIngredient(storeId, "대파", "G", "CONFIRMED");
		normalized = receipt("OO마트", "2026-10-12", item(ONION, 2, 3980));
		long docId = upload(storeId);
		long suppliers = count("SELECT COUNT(*) FROM supplier WHERE store_id = ?", storeId);

		ConfirmResultResponse result = confirmService.confirm(storeId, List.of(ingredient(lineIdOf(docId, ONION), onion,
				"1000")));

		assertThat(count("SELECT COUNT(*) FROM supplier WHERE store_id = ?", storeId)).isEqualTo(suppliers);
		assertThat(doc(docId).get("supplier_id")).isNull();
		assertThat(productRows(storeId)).isZero();
		assertThat(priceRows(storeId)).isZero();
		Map<String, Object> row = line(lineIdOf(docId, ONION));
		assertThat(row.get("status")).isEqualTo("CONFIRMED");
		assertThat(row.get("ingredient_id")).isEqualTo(onion);
		assertThat(row.get("supplier_product_id")).isNull();
		assertThat(doc(docId).get("parse_status")).isEqualTo("CONFIRMED");
		assertThat(result.createdProductIds()).isEmpty();
	}

	@Test
	void supplier_거래처_모르는_문서의_같은_상품명은_다음_영수증에서_다시_LLM_매칭() {
		long storeId = insertStore("마트2");
		long onion = insertIngredient(storeId, "대파", "G", "CONFIRMED");
		normalized = receipt("OO마트", "2026-10-12", item(ONION, 2, 3980));
		long firstDoc = upload(storeId);
		confirmService.confirm(storeId, List.of(ingredient(lineIdOf(firstDoc, ONION), onion, null)));

		int before = llmCalls.size();
		long secondDoc = upload(storeId);

		assertThat(matchCallsSince(before)).isEqualTo(1);
		List<String> names = new ArrayList<>();
		llmCalls.subList(before, llmCalls.size()).stream()
				.filter(r -> ReceiptAnalyzer.MATCH_SCHEMA.equals(r.schemaName()))
				.forEach(r -> linesOf(r).forEach(l -> names.add(l.get("name").asString())));
		assertThat(names).containsExactly(ONION);
		assertThat(line(lineIdOf(secondDoc, ONION)).get("status")).isEqualTo("PENDING");
	}
}
