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
import com.zigzaego.api.purchasedoc.dto.GroupConfirmResult;
import com.zigzaego.api.purchasedoc.dto.ReceiptUpload;
import com.zigzaego.api.purchasedoc.dto.ReviewBoard;
import com.zigzaego.api.purchasedoc.dto.ReviewMatchResponse;
import com.zigzaego.api.purchasedoc.dto.ReviewOptionResponse;
import com.zigzaego.api.purchasedoc.job.ReceiptAnalyzer;
import com.zigzaego.api.purchasedoc.ocr.FakeOcrClient;
import com.zigzaego.api.purchasedoc.ocr.OcrClient;
import com.zigzaego.api.purchasedoc.ocr.OcrImage;
import com.zigzaego.api.purchasedoc.service.ReceiptConfirmService;
import com.zigzaego.api.purchasedoc.service.ReceiptJobService;
import com.zigzaego.api.purchasedoc.service.ReceiptReview;
import com.zigzaego.api.purchasedoc.service.ReceiptReviewService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
 * 영수증 확인 목록 · 묶음 확정 (지라 S15P31A308-113): 상품명 단위 묶음 · 묶음 확정 1회로 그 상품명 줄 전부 확정 · 이유 코드 ·
 * 자동 확정 문서 · 경계. 픽스처는 DB 에 직접 넣는다(OCR · LLM 없음). 자동 확정 규칙 불변 확인만 111 job 을 목으로 돌린다.
 * 재고는 {@link InventoryEstimateService} 를 날짜 고정으로 직접 부른다(112 와 같은 방식).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = "app.receipt.storage-dir=build/test-receipts")
class ReceiptReviewTest {

	private static final String SHRIMP = "냉동 칵테일새우 1kg";
	private static final String TOWEL = "키친타올 6롤";
	/** 실사 2026-10-10 10:00 (가게 시간대 Asia/Seoul) 뒤, 소비 0 인 날. */
	private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
	private static final OffsetDateTime COUNTED_AT = OffsetDateTime.of(2026, 10, 10, 10, 0, 0, 0, ZoneOffset.ofHours(9));
	private static final String RECEIVE_DATE = "2026-10-12";
	private static final String PAST_DATE = "2026-10-05";

	@Autowired
	private ReceiptReviewService reviewService;

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

	@MockitoBean
	private GmsClient gmsClient;

	@MockitoBean
	private OcrClient ocrClient;

	private final FakeOcrClient fakeOcr = new FakeOcrClient(JsonMapper.builder().build());
	/** job 정형화 응답. */
	private volatile JsonNode normalized;
	/** job 매칭 응답: 요청의 lines → 응답. */
	private volatile Function<JsonNode, JsonNode> match;

	@BeforeEach
	void setUp() {
		given(gmsClient.completeJson(any(LlmRequest.class))).willAnswer(invocation -> {
			LlmRequest request = invocation.getArgument(0);
			if (ReceiptAnalyzer.NORMALIZE_SCHEMA.equals(request.schemaName())) {
				return normalized;
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

	/** 영수증 문서 한 장. kind = PAST · RECEIVE · null (null 이면 날짜도 null) */
	private long insertDoc(long storeId, Long supplierId, String kind, String parseStatus) {
		String date = kind == null ? null : "PAST".equals(kind) ? PAST_DATE : RECEIVE_DATE;
		return jdbc.queryForObject("""
				INSERT INTO purchase_doc (store_id, supplier_id, doc_type, doc_date, parse_status, kind)
				VALUES (?, ?, 'RECEIPT', CAST(? AS DATE), ?, ?) RETURNING id""", Long.class, storeId, supplierId, date,
				parseStatus, kind);
	}

	private long insertLine(long storeId, long docId, int lineNo, String name, String qty, String price,
			Long ingredientId, Long productId, String confidence, String status, String raw) {
		return jdbc.queryForObject("""
				INSERT INTO purchase_doc_line (store_id, doc_id, line_no, raw_name, qty, unit_price, ingredient_id,
				                               supplier_product_id, confidence, status, raw)
				VALUES (?, ?, ?, ?, CAST(? AS NUMERIC), CAST(? AS NUMERIC), ?, ?, CAST(? AS NUMERIC), ?, CAST(? AS JSONB))
				RETURNING id""", Long.class, storeId, docId, lineNo, name, qty, price, ingredientId, productId,
				confidence, status, raw);
	}

	/** LLM 이 재료를 제안한(사전 밖) 확인 전 줄. */
	private long insertLlmLine(long storeId, long docId, int lineNo, String name, String qty, Long ingredientId,
			String confidence, Integer packQty, String packUnit) {
		return insertLine(storeId, docId, lineNo, name, qty, "15000", ingredientId, null, confidence, "PENDING",
				rawMatch(packQty, packUnit, false));
	}

	/** 111 이 남기는 원문 줄 모양. */
	private String rawMatch(Integer packQty, String packUnit, boolean autoConfirmed) {
		ObjectNode raw = json.createObjectNode();
		raw.putObject("item").put("name", "x");
		ObjectNode m = raw.putObject("match");
		if (packQty == null) {
			m.putNull("pack");
		} else {
			m.putObject("pack").put("qty", packQty).put("unit", packUnit);
		}
		raw.put("dictionary", autoConfirmed);
		if (autoConfirmed) {
			raw.put("autoConfirmed", true);
		}
		return json.writeValueAsString(raw);
	}

	// ── 조회 ────────────────────────────────────────────────

	private String lineStatus(long lineId) {
		return jdbc.queryForObject("SELECT status FROM purchase_doc_line WHERE id = ?", String.class, lineId);
	}

	private Long lineIngredient(long lineId) {
		return jdbc.queryForObject("SELECT ingredient_id FROM purchase_doc_line WHERE id = ?", Long.class, lineId);
	}

	private String parseStatus(long docId) {
		return jdbc.queryForObject("SELECT parse_status FROM purchase_doc WHERE id = ?", String.class, docId);
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

	private long pendingRows(long storeId) {
		return count("SELECT COUNT(*) FROM purchase_doc_line WHERE store_id = ? AND status = 'PENDING'", storeId);
	}

	private BigDecimal stock(long storeId, long ingredientId) {
		for (InventoryEstimateResponse row : inventoryService.estimate(storeId, TODAY)) {
			if (row.ingredientId().equals(String.valueOf(ingredientId))) {
				return row.estimatedQty();
			}
		}
		return fail("재료 %d 의 추정 재고가 없다", ingredientId);
	}

	private static List<ReviewMatchResponse> matchesNamed(ReviewBoard board, String id) {
		return board.matches().stream().filter(m -> m.id().equals(id)).toList();
	}

	private static ReviewMatchResponse onlyMatch(ReviewBoard board, String id) {
		List<ReviewMatchResponse> hits = matchesNamed(board, id);
		assertThat(hits).as("묶음 %s", id).hasSize(1);
		return hits.get(0);
	}

	private static List<String> ids(List<Long> ids) {
		return ids.stream().sorted().map(String::valueOf).toList();
	}

	private static void assertRejected(ThrowingCallable call, String code) {
		assertThatThrownBy(call).isInstanceOf(BusinessException.class)
				.satisfies(e -> assertThat(((BusinessException) e).getErrorCode().getCode()).isEqualTo(code));
	}

	// ── 50장 픽스처 ──────────────────────────────────────────

	/**
	 * PARSED 문서 50장: 12장에 새우 줄 1개씩(1장은 앞뒤 공백, 1~8번째 RECEIVE · 9~12번째 PAST, 수량 1~12, LLM 제안 새우 0.8,
	 * 포장 제안 1000 g), 나머지 38장은 다른 상품명. 그 38장 중 2장에 같은 상품명 자동 확정 줄 · IGNORED 줄. FAILED 문서에 같은
	 * 상품명 줄 1개. 다른 가게에 같은 상품명 PENDING 줄 3개.
	 */
	private record Fixture(long storeId, long otherStoreId, long shrimp, long otherShrimp, long supplier,
			List<Long> shrimpLines, List<Long> shrimpDocs, List<Long> otherStoreLines, long autoLine, long ignoredLine,
			long failedLine, BigDecimal qtySum, BigDecimal receiveQtySum) {
	}

	private Fixture fifty() {
		long storeId = insertStore("오십장");
		long shrimp = insertIngredient(storeId, "칵테일새우", "G", "LOCKED");
		insertCount(storeId, shrimp);
		long supplier = insertSupplier(storeId, "OO수산");
		long otherSupplier = insertSupplier(storeId, "다른수산");
		long otherProduct = insertProduct(storeId, otherSupplier, shrimp, SHRIMP, "1000", "15000");

		List<Long> shrimpLines = new ArrayList<>();
		List<Long> shrimpDocs = new ArrayList<>();
		BigDecimal qtySum = BigDecimal.ZERO;
		BigDecimal receiveSum = BigDecimal.ZERO;
		for (int i = 1; i <= 12; i++) {
			String kind = i <= 8 ? "RECEIVE" : "PAST";
			long doc = insertDoc(storeId, supplier, kind, "PARSED");
			String name = i == 5 ? " " + SHRIMP + " " : SHRIMP;
			shrimpLines.add(insertLlmLine(storeId, doc, 1, name, String.valueOf(i), shrimp, "0.800", 1000, "g"));
			shrimpDocs.add(doc);
			qtySum = qtySum.add(BigDecimal.valueOf(i));
			if (i <= 8) {
				receiveSum = receiveSum.add(BigDecimal.valueOf(i));
			}
		}
		long autoLine = 0;
		long ignoredLine = 0;
		for (int i = 1; i <= 38; i++) {
			// 자동 확정 줄은 다른 거래처 문서 — 새우 묶음의 사전 · 포장 기본값에 섞이지 않게
			long doc = insertDoc(storeId, i == 1 ? otherSupplier : supplier, "RECEIVE", "PARSED");
			insertLlmLine(storeId, doc, 1, "기타 상품 " + i, "1", null, null, null, null);
			if (i == 1) {
				autoLine = insertLine(storeId, doc, 2, SHRIMP, "3", "15000", shrimp, otherProduct, null, "CONFIRMED",
						rawMatch(null, null, true));
			} else if (i == 2) {
				ignoredLine = insertLine(storeId, doc, 2, SHRIMP, "4", "15000", shrimp, null, "0.800", "IGNORED",
						rawMatch(1000, "g", false));
			}
		}
		long failedDoc = insertDoc(storeId, supplier, "RECEIVE", "FAILED");
		long failedLine = insertLlmLine(storeId, failedDoc, 1, SHRIMP, "5", shrimp, "0.800", 1000, "g");

		long otherStoreId = insertStore("다른가게");
		long otherShrimp = insertIngredient(otherStoreId, "칵테일새우", "G", "LOCKED");
		long otherStoreSupplier = insertSupplier(otherStoreId, "OO수산");
		List<Long> otherStoreLines = new ArrayList<>();
		for (int i = 1; i <= 3; i++) {
			long doc = insertDoc(otherStoreId, otherStoreSupplier, "RECEIVE", "PARSED");
			otherStoreLines.add(insertLlmLine(otherStoreId, doc, 1, SHRIMP, "2", otherShrimp, "0.800", 1000, "g"));
		}
		return new Fixture(storeId, otherStoreId, shrimp, otherShrimp, supplier, shrimpLines, shrimpDocs,
				otherStoreLines, autoLine, ignoredLine, failedLine, qtySum, receiveSum);
	}

	// ── 확인 목록 ────────────────────────────────────────────

	@Test
	void board_50장에서_같은_상품명_12장은_묶음_1개_다른_가게_자동_확정_IGNORED_FAILED_문서_줄은_빠진다() {
		Fixture f = fifty();

		ReviewBoard board = reviewService.board(f.storeId());

		ReviewMatchResponse shrimp = onlyMatch(board, SHRIMP);
		assertThat(shrimp.name()).isEqualTo(SHRIMP);
		assertThat(shrimp.receipts()).isEqualTo(12);
		assertThat(shrimp.lineIds()).hasSize(12).isEqualTo(ids(f.shrimpLines()));
		assertThat(shrimp.lineIds()).doesNotContain(String.valueOf(f.autoLine()), String.valueOf(f.ignoredLine()),
				String.valueOf(f.failedLine()));
		assertThat(shrimp.lineIds()).doesNotContainAnyElementsOf(ids(f.otherStoreLines()));
		assertThat(shrimp.qty()).isEqualByComparingTo(f.qtySum()); // 1 + … + 12 = 78
		assertThat(shrimp.qty().scale()).isEqualTo(3);
		assertThat(shrimp.guess()).isEqualTo(String.valueOf(f.shrimp()));
		// 문서 거래처(OO수산)에 사전 상품이 없어 포장 환산을 물어야 한다 — 기본값은 LLM 제안 1000 g
		assertThat(shrimp.reasons()).containsExactly("UNIT_UNSET");
		assertThat(shrimp.unitNeeded()).isTrue();
		assertThat(shrimp.basePerPack()).isEqualByComparingTo("1000");
		assertThat(shrimp.baseUnit()).isEqualTo("G");
		assertThat(shrimp.past()).isFalse();
		assertThat(shrimp.needsReview()).isTrue();
		// 나머지 38장은 상품명마다 묶음 하나, 새우 줄이 마지막 묶음보다 먼저 들어가 기타 상품들이 위
		assertThat(board.matches()).hasSize(39);
		assertThat(board.matches().get(0).id()).isEqualTo("기타 상품 38");
		assertThat(board.options()).extracting(ReviewOptionResponse::id).containsExactly(String.valueOf(f.shrimp()));
		assertThat(board.readyDocs()).isEmpty();

		// 다른 가게 목록에는 그 가게 줄 3개만
		ReviewMatchResponse other = onlyMatch(reviewService.board(f.otherStoreId()), SHRIMP);
		assertThat(other.lineIds()).isEqualTo(ids(f.otherStoreLines()));
		assertThat(other.guess()).isEqualTo(String.valueOf(f.otherShrimp()));
	}

	@Test
	void board_guess_는_사전_매칭_재료_먼저_없으면_LLM_최빈값_같으면_작은_재료_id_past_는_모두_PAST_일_때만() {
		long storeId = insertStore("추정");
		long a = insertIngredient(storeId, "가", "G", "LOCKED");
		long b = insertIngredient(storeId, "나", "ML", "LOCKED");
		long c = insertIngredient(storeId, "다", "EA", "LOCKED");
		long supplier = insertSupplier(storeId, "OO상회");
		long product = insertProduct(storeId, supplier, c, "사전상품", "250", "1000");

		// 사전 매칭 줄(재료 다) 1개 + LLM 제안 가 2개 → 다
		long d1 = insertDoc(storeId, supplier, "PAST", "PARSED");
		insertLlmLine(storeId, d1, 1, "사전상품", "1", a, "0.900", null, null);
		insertLlmLine(storeId, d1, 2, "사전상품", "1", a, "0.900", null, null);
		insertLine(storeId, d1, 3, "사전상품", "1", "1000", c, product, null, "PENDING", rawMatch(null, null, false));
		// LLM 가 1 · 나 2 → 나, 포장 제안 500 ml
		long d2 = insertDoc(storeId, null, "PAST", "PARSED");
		long d3 = insertDoc(storeId, null, "RECEIVE", "PARSED");
		insertLlmLine(storeId, d2, 1, "최빈", "1", a, "0.900", null, null);
		insertLlmLine(storeId, d2, 2, "최빈", "1", b, "0.900", 500, "ml");
		insertLlmLine(storeId, d3, 1, "최빈", "1", b, "0.900", null, null);
		// 가 1 · 나 1 → 가(작은 id)
		insertLlmLine(storeId, d3, 2, "동점", "1", b, "0.900", null, null);
		insertLlmLine(storeId, d3, 3, "동점", "1", a, "0.900", null, null);

		ReviewBoard board = reviewService.board(storeId);

		ReviewMatchResponse dict = onlyMatch(board, "사전상품");
		assertThat(dict.guess()).isEqualTo(String.valueOf(c));
		assertThat(dict.basePerPack()).isEqualByComparingTo("250");
		assertThat(dict.baseUnit()).isEqualTo("EA");
		assertThat(dict.past()).isTrue();
		assertThat(dict.receipts()).isEqualTo(1);
		ReviewMatchResponse mode = onlyMatch(board, "최빈");
		assertThat(mode.guess()).isEqualTo(String.valueOf(b));
		assertThat(mode.basePerPack()).isEqualByComparingTo("500");
		assertThat(mode.baseUnit()).isEqualTo("ML");
		assertThat(mode.past()).isFalse();
		assertThat(mode.receipts()).isEqualTo(2);
		assertThat(onlyMatch(board, "동점").guess()).isEqualTo(String.valueOf(a));
		// 최근 줄이 있는 묶음 먼저
		assertThat(board.matches()).extracting(ReviewMatchResponse::id).containsExactly("동점", "최빈", "사전상품");
	}

	// ── 묶음 확정 ────────────────────────────────────────────

	@Test
	void group_묶음_확정_1회로_12줄_확정_RECEIVE_몫만_재고에_gain_과_같게_늘고_다른_가게_줄은_그대로() {
		Fixture f = fifty();
		BigDecimal before = stock(f.storeId(), f.shrimp());

		GroupConfirmResult result = reviewService.confirmGroup(f.storeId(), SHRIMP, String.valueOf(f.shrimp()),
				new BigDecimal("1000"));

		assertThat(result.confirm().confirmedLineIds()).hasSize(12).isEqualTo(ids(f.shrimpLines()));
		assertThat(result.confirm().confirmedDocIds()).isEqualTo(ids(f.shrimpDocs()));
		assertThat(result.skippedLineIds()).isEmpty();
		for (long lineId : f.shrimpLines()) {
			assertThat(lineStatus(lineId)).isEqualTo("CONFIRMED");
			assertThat(lineIngredient(lineId)).isEqualTo(f.shrimp());
		}
		for (long docId : f.shrimpDocs()) {
			assertThat(parseStatus(docId)).isEqualTo("CONFIRMED");
		}
		// RECEIVE 8장(수량 1~8 = 36) × 1000 g. PAST 4장은 안 늘린다
		BigDecimal expected = f.receiveQtySum().multiply(new BigDecimal("1000"));
		assertThat(result.gain()).isEqualByComparingTo(expected);
		assertThat(stock(f.storeId(), f.shrimp()).subtract(before)).isEqualByComparingTo(result.gain());
		assertThat(matchesNamed(result.board(), SHRIMP)).isEmpty();
		assertThat(result.board().matches()).hasSize(38);
		// 사전 1개(OO수산, 1000) · 단가 12행
		assertThat(count("SELECT COUNT(*) FROM supplier_product WHERE store_id = ? AND supplier_id = ?"
				+ " AND product_name = ? AND base_qty_per_pack = 1000", f.storeId(), f.supplier(), SHRIMP)).isEqualTo(1);
		assertThat(result.confirm().priceHistoryCount()).isEqualTo(12);
		// 다른 가게 · FAILED 문서 · 자동 확정 · IGNORED 줄은 그대로
		for (long lineId : f.otherStoreLines()) {
			assertThat(lineStatus(lineId)).isEqualTo("PENDING");
		}
		assertThat(lineStatus(f.failedLine())).isEqualTo("PENDING");
		assertThat(lineStatus(f.autoLine())).isEqualTo("CONFIRMED");
		assertThat(lineStatus(f.ignoredLine())).isEqualTo("IGNORED");
		assertThat(priceRows(f.otherStoreId())).isZero();
	}

	@Test
	void group_cycle_묶음은_주기형_재료_1개_gain_0_대소문자_무시() {
		long storeId = insertStore("주기형");
		long supplier = insertSupplier(storeId, "OO상회");
		List<Long> lines = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			long doc = insertDoc(storeId, supplier, "RECEIVE", "PARSED");
			lines.add(insertLlmLine(storeId, doc, 1, i == 0 ? TOWEL + "  " : TOWEL, "2", null, null, 6, "ea"));
		}
		long ingredientsBefore = ingredientRows(storeId);

		GroupConfirmResult result = reviewService.confirmGroup(storeId, TOWEL, "CYCLE", new BigDecimal("6"));

		assertThat(result.confirm().createdIngredientIds()).hasSize(1);
		assertThat(ingredientRows(storeId)).isEqualTo(ingredientsBefore + 1);
		long cycle = Long.parseLong(result.confirm().createdIngredientIds().get(0));
		assertThat(jdbc.queryForMap("SELECT name, kind, unit FROM ingredient WHERE id = ?", cycle))
				.containsEntry("name", TOWEL).containsEntry("kind", "CYCLE").containsEntry("unit", "EA");
		for (long lineId : lines) {
			assertThat(lineStatus(lineId)).isEqualTo("CONFIRMED");
			assertThat(lineIngredient(lineId)).isEqualTo(cycle);
		}
		assertThat(result.gain()).isEqualByComparingTo("0");
		assertThat(result.board().matches()).isEmpty();
		assertThat(result.board().options()).extracting(ReviewOptionResponse::id).contains(String.valueOf(cycle));
	}

	@Test
	void group_ignore_묶음은_줄_IGNORED_가격_이력_0행_gain_0() {
		long storeId = insertStore("무시");
		long onion = insertIngredient(storeId, "대파", "G", "LOCKED");
		long supplier = insertSupplier(storeId, "OO상회");
		insertProduct(storeId, supplier, onion, "비닐봉투", "1", "100");
		List<Long> lines = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			long doc = insertDoc(storeId, supplier, "RECEIVE", "PARSED");
			lines.add(insertLlmLine(storeId, doc, 1, "비닐봉투", "1", onion, "0.500", null, null));
		}

		GroupConfirmResult result = reviewService.confirmGroup(storeId, "비닐봉투", "Ignore", null);

		assertThat(result.confirm().ignoredLineIds()).isEqualTo(ids(lines));
		for (long lineId : lines) {
			assertThat(lineStatus(lineId)).isEqualTo("IGNORED");
		}
		assertThat(priceRows(storeId)).isZero();
		assertThat(result.confirm().priceHistoryCount()).isZero();
		assertThat(result.gain()).isEqualByComparingTo("0");
		assertThat(result.board().matches()).isEmpty();
	}

	@Test
	void group_문서에_PENDING_줄이_남으면_문서는_PARSED_gain_0_재고_그대로() {
		long storeId = insertStore("남은줄");
		long shrimp = insertIngredient(storeId, "칵테일새우", "G", "LOCKED");
		insertCount(storeId, shrimp);
		long supplier = insertSupplier(storeId, "OO수산");
		long doc = insertDoc(storeId, supplier, "RECEIVE", "PARSED");
		long line = insertLlmLine(storeId, doc, 1, SHRIMP, "2", shrimp, "0.900", 1000, "g");
		insertLlmLine(storeId, doc, 2, "다른 상품", "1", null, null, null, null);
		BigDecimal before = stock(storeId, shrimp);

		GroupConfirmResult result = reviewService.confirmGroup(storeId, SHRIMP, String.valueOf(shrimp),
				new BigDecimal("1000"));

		assertThat(lineStatus(line)).isEqualTo("CONFIRMED");
		assertThat(parseStatus(doc)).isEqualTo("PARSED");
		assertThat(result.confirm().confirmedDocIds()).isEmpty();
		assertThat(result.gain()).isEqualByComparingTo("0");
		assertThat(stock(storeId, shrimp)).isEqualByComparingTo(before);
	}

	@Test
	void group_분석_뒤_새_실사가_들어오면_실사일_전_RECEIVE_문서는_gain_에서_빠져_재고_증가와_같다() {
		long storeId = insertStore("재실사");
		long shrimp = insertIngredient(storeId, "칵테일새우", "G", "LOCKED");
		insertCount(storeId, shrimp); // 10/10 — 분석 때 이 실사로 10/12 문서가 RECEIVE 로 굳었다
		long supplier = insertSupplier(storeId, "OO수산");
		long before12 = insertDoc(storeId, supplier, "RECEIVE", "PARSED"); // 10/12
		insertLlmLine(storeId, before12, 1, SHRIMP, "3", shrimp, "0.900", 1000, "g");
		long after14 = jdbc.queryForObject("""
				INSERT INTO purchase_doc (store_id, supplier_id, doc_type, doc_date, parse_status, kind)
				VALUES (?, ?, 'RECEIPT', DATE '2026-10-14', 'PARSED', 'RECEIVE') RETURNING id""", Long.class, storeId,
				supplier);
		insertLlmLine(storeId, after14, 1, SHRIMP, "2", shrimp, "0.900", 1000, "g");
		// 분석 뒤 10/14 09:00(가게 시간) 에 다시 실사
		jdbc.update("INSERT INTO stock_count (store_id, ingredient_id, counted_at, qty) VALUES (?, ?, ?, 0)", storeId,
				shrimp, OffsetDateTime.of(2026, 10, 14, 9, 0, 0, 0, ZoneOffset.ofHours(9)));
		BigDecimal before = stock(storeId, shrimp);

		GroupConfirmResult result = reviewService.confirmGroup(storeId, SHRIMP, String.valueOf(shrimp),
				new BigDecimal("1000"));

		assertThat(result.confirm().confirmedDocIds()).hasSize(2);
		assertThat(result.gain()).isEqualByComparingTo("2000"); // 10/14 문서 2 × 1000 만
		assertThat(stock(storeId, shrimp).subtract(before)).isEqualByComparingTo(result.gain());
	}

	@Test
	void group_기존_주기형_재료를_id_로_골라도_gain_0_PAST_문서만_확정돼도_gain_0() {
		long storeId = insertStore("기존주기형");
		long towel = jdbc.queryForObject("""
				INSERT INTO ingredient (store_id, name, unit, unit_status, price_sensitive, sourcing_type, kind, storage_location, category_id, category_source)
				VALUES (?, '키친타올', 'EA', 'LOCKED', false, 'GENERIC', 'CYCLE', 'WAREHOUSE',
				        (SELECT id FROM ingredient_category WHERE store_id = ? AND code = 'OTHER'), 'AUTO') RETURNING id""", Long.class, storeId, storeId);
		long shrimp = insertIngredient(storeId, "칵테일새우", "G", "LOCKED");
		insertCount(storeId, shrimp);
		long supplier = insertSupplier(storeId, "OO상회");
		long receive = insertDoc(storeId, supplier, "RECEIVE", "PARSED");
		insertLlmLine(storeId, receive, 1, TOWEL, "2", null, null, 6, "ea");
		long past = insertDoc(storeId, supplier, "PAST", "PARSED");
		insertLlmLine(storeId, past, 1, SHRIMP, "4", shrimp, "0.900", 1000, "g");
		BigDecimal before = stock(storeId, shrimp);

		GroupConfirmResult cycle = reviewService.confirmGroup(storeId, TOWEL, String.valueOf(towel),
				new BigDecimal("6"));
		GroupConfirmResult pastOnly = reviewService.confirmGroup(storeId, SHRIMP, String.valueOf(shrimp),
				new BigDecimal("1000"));

		assertThat(cycle.confirm().confirmedDocIds()).containsExactly(String.valueOf(receive));
		assertThat(cycle.gain()).isEqualByComparingTo("0");
		assertThat(pastOnly.confirm().confirmedDocIds()).containsExactly(String.valueOf(past));
		assertThat(pastOnly.gain()).isEqualByComparingTo("0");
		assertThat(stock(storeId, shrimp)).isEqualByComparingTo(before);
	}

	@Test
	void reject_없는_가게는_404_COMMON_NOT_FOUND() {
		assertRejected(() -> reviewService.confirmGroup(Long.MAX_VALUE, SHRIMP, "1", new BigDecimal("1000")),
				"COMMON_NOT_FOUND");
	}

	// ── 이유 ────────────────────────────────────────────────

	@Test
	void reason_LLM_제안_줄_확신도_0_699_는_LOW_CONFIDENCE_0_700_은_없음_NULL_은_있음_사전_매칭_줄은_없음() {
		long storeId = insertStore("확신도");
		long onion = insertIngredient(storeId, "대파", "G", "LOCKED");
		long supplier = insertSupplier(storeId, "OO상회");
		long product = insertProduct(storeId, supplier, onion, "사전 대파", "1000", "3500");
		// 거래처 모르는 문서 — 사전에 안 들어가니 포장 환산을 묻지 않는다(이유는 확신도만)
		long doc = insertDoc(storeId, null, "RECEIVE", "PARSED");
		insertLlmLine(storeId, doc, 1, "대파 A", "1", onion, "0.699", null, null);
		insertLlmLine(storeId, doc, 2, "대파 B", "1", onion, "0.700", null, null);
		insertLlmLine(storeId, doc, 3, "대파 C", "1", onion, null, null, null);
		long dictDoc = insertDoc(storeId, supplier, "RECEIVE", "PARSED");
		insertLine(storeId, dictDoc, 1, "사전 대파", "1", "3500", onion, product, null, "PENDING",
				rawMatch(null, null, false));

		ReviewBoard board = reviewService.board(storeId);

		assertThat(onlyMatch(board, "대파 A").reasons()).containsExactly("LOW_CONFIDENCE");
		assertThat(onlyMatch(board, "대파 B").reasons()).isEmpty();
		assertThat(onlyMatch(board, "대파 C").reasons()).containsExactly("LOW_CONFIDENCE");
		ReviewMatchResponse dict = onlyMatch(board, "사전 대파");
		assertThat(dict.reasons()).isEmpty();
		assertThat(dict.unitNeeded()).isFalse();
		assertThat(board.matches()).allSatisfy(m -> assertThat(m.needsReview()).isTrue());
		assertThat(ReceiptReview.LOW_CONFIDENCE_BELOW).isEqualByComparingTo("0.7");
	}

	@Test
	void reason_재료_없는_줄은_UNMAPPED_와_UNIT_UNSET_unitNeeded_true_guess_빈_문자열_이유_순서_고정() {
		long storeId = insertStore("미매칭");
		long onion = insertIngredient(storeId, "대파", "G", "LOCKED");
		long supplier = insertSupplier(storeId, "OO상회");
		long doc = insertDoc(storeId, supplier, "RECEIVE", "PARSED");
		insertLlmLine(storeId, doc, 1, "이름모를상품", "1", null, null, 3, "ea");
		// 같은 묶음에 재료 있는 줄(확신도 낮음)과 없는 줄이 섞이면 guess 는 그 재료 — 이유는 줄마다의 합집합, 순서 고정
		insertLlmLine(storeId, doc, 2, "섞임", "1", null, null, null, null);
		insertLlmLine(storeId, doc, 3, "섞임", "1", onion, "0.300", null, null);

		ReviewBoard board = reviewService.board(storeId);

		ReviewMatchResponse unknown = onlyMatch(board, "이름모를상품");
		assertThat(unknown.reasons()).containsExactly("UNMAPPED", "UNIT_UNSET");
		assertThat(unknown.unitNeeded()).isTrue();
		assertThat(unknown.guess()).isEmpty();
		assertThat(unknown.basePerPack()).isNull();
		assertThat(unknown.baseUnit()).isEqualTo("EA"); // 재료가 없으면 LLM 포장 단위
		ReviewMatchResponse mixed = onlyMatch(board, "섞임");
		assertThat(mixed.guess()).isEqualTo(String.valueOf(onion));
		assertThat(mixed.reasons()).containsExactly("LOW_CONFIDENCE", "UNIT_UNSET");
	}

	// ── 자동 확정 문서 ────────────────────────────────────────

	@Test
	void ready_줄이_모두_자동_확정된_문서는_matches_에_없고_readyDocs_에_있으며_confirmDocuments_뒤_빠진다() {
		long storeId = insertStore("자동확정");
		long shrimp = insertIngredient(storeId, "칵테일새우", "G", "LOCKED");
		insertCount(storeId, shrimp);
		long supplier = insertSupplier(storeId, "OO수산");
		long product = insertProduct(storeId, supplier, shrimp, SHRIMP, "1000", "15000");
		long ready = insertDoc(storeId, supplier, "RECEIVE", "PARSED");
		insertLine(storeId, ready, 1, SHRIMP, "2", "15000", shrimp, product, null, "CONFIRMED",
				rawMatch(null, null, true));
		insertLine(storeId, ready, 2, SHRIMP, "1", "15000", shrimp, product, null, "CONFIRMED",
				rawMatch(null, null, true));
		// 확인 전 줄이 남은 문서 · 날짜 못 읽은 문서 · 줄 없는 문서는 readyDocs 가 아니다
		long pendingDoc = insertDoc(storeId, supplier, "RECEIVE", "PARSED");
		insertLlmLine(storeId, pendingDoc, 1, "기타", "1", null, null, null, null);
		insertLine(storeId, pendingDoc, 2, SHRIMP, "5", "15000", shrimp, product, null, "CONFIRMED",
				rawMatch(null, null, true));
		insertDoc(storeId, supplier, "RECEIVE", "PARSED");
		BigDecimal before = stock(storeId, shrimp);

		ReviewBoard board = reviewService.board(storeId);

		assertThat(matchesNamed(board, SHRIMP)).isEmpty();
		assertThat(board.matches()).extracting(ReviewMatchResponse::id).containsExactly("기타");
		assertThat(board.readyDocs()).singleElement().satisfies(d -> {
			assertThat(d.docId()).isEqualTo(String.valueOf(ready));
			assertThat(d.kind()).isEqualTo("RECEIVE");
			assertThat(d.date()).isEqualTo(RECEIVE_DATE);
			assertThat(d.lines()).isEqualTo(2);
		});
		assertThat(parseStatus(ready)).isEqualTo("PARSED");
		assertThat(stock(storeId, shrimp)).isEqualByComparingTo(before);

		confirmService.confirmDocuments(storeId, List.of(String.valueOf(ready)));

		assertThat(parseStatus(ready)).isEqualTo("CONFIRMED");
		assertThat(reviewService.board(storeId).readyDocs()).isEmpty();
		assertThat(stock(storeId, shrimp).subtract(before)).isEqualByComparingTo("3000");
	}

	@Test
	void ready_job_에서_사전_줄만_자동_확정되고_LLM_확신도_0_99_비사전_줄은_PENDING_으로_목록에() {
		long storeId = insertStore("자동확정불변");
		long onion = insertIngredient(storeId, "대파", "G", "LOCKED");
		insertIngredient(storeId, "양파", "G", "LOCKED");
		long supplier = insertSupplier(storeId, "OO상회");
		insertProduct(storeId, supplier, onion, "대파 1단", "1000", "3500");
		match = lines -> llmMatch(lines, "양파", 0.99);

		normalized = receipt("OO상회", "2026-10-12", item("대파 1단", 2, 3500));
		long dictDoc = upload(storeId);
		normalized = receipt("OO상회", "2026-10-12", item("양파 1망", 1, 5000));
		long llmDoc = upload(storeId);

		long llmLine = count("SELECT id FROM purchase_doc_line WHERE doc_id = ?", llmDoc);
		assertThat(lineStatus(llmLine)).isEqualTo("PENDING");
		assertThat(jdbc.queryForObject("SELECT confidence FROM purchase_doc_line WHERE id = ?", BigDecimal.class,
				llmLine)).isEqualByComparingTo("0.99");
		ReviewBoard board = reviewService.board(storeId);
		assertThat(board.matches()).extracting(ReviewMatchResponse::id).containsExactly("양파 1망");
		assertThat(onlyMatch(board, "양파 1망").reasons()).doesNotContain("LOW_CONFIDENCE");
		assertThat(board.readyDocs()).extracting(d -> d.docId()).containsExactly(String.valueOf(dictDoc));
		assertThat(parseStatus(dictDoc)).isEqualTo("PARSED");
	}

	// ── 경계 ────────────────────────────────────────────────

	@Test
	void reject_없는_matchId_null_다른_가게에만_있는_이름은_404_RECEIPT_MATCH_NOT_FOUND_다른_가게_줄은_그대로() {
		Fixture f = fifty();
		String shrimp = String.valueOf(f.shrimp());

		assertRejected(() -> reviewService.confirmGroup(f.storeId(), "없는 상품", shrimp, null),
				"RECEIPT_MATCH_NOT_FOUND");
		assertRejected(() -> reviewService.confirmGroup(f.storeId(), null, shrimp, null), "RECEIPT_MATCH_NOT_FOUND");
		// 공백 붙은 id 는 묶음 id 가 아니다
		assertRejected(() -> reviewService.confirmGroup(f.storeId(), " " + SHRIMP, shrimp, null),
				"RECEIPT_MATCH_NOT_FOUND");
		long lonely = insertStore("빈가게");
		long lonelyIngredient = insertIngredient(lonely, "칵테일새우", "G", "LOCKED");
		assertRejected(() -> reviewService.confirmGroup(lonely, SHRIMP, String.valueOf(lonelyIngredient),
				new BigDecimal("1000")), "RECEIPT_MATCH_NOT_FOUND");
		for (long lineId : f.otherStoreLines()) {
			assertThat(lineStatus(lineId)).isEqualTo("PENDING");
		}
		assertThat(pendingRows(f.storeId())).isEqualTo(12 + 38 + 1);
	}

	@Test
	void reject_같은_묶음_두번째_확정은_404_가격_이력_그대로() {
		Fixture f = fifty();
		reviewService.confirmGroup(f.storeId(), SHRIMP, String.valueOf(f.shrimp()), new BigDecimal("1000"));
		long prices = priceRows(f.storeId());

		assertRejected(() -> reviewService.confirmGroup(f.storeId(), SHRIMP, String.valueOf(f.shrimp()),
				new BigDecimal("1000")), "RECEIPT_MATCH_NOT_FOUND");

		assertThat(priceRows(f.storeId())).isEqualTo(prices);
	}

	@Test
	void reject_다른_가게_재료_숫자_아닌_choice_는_404_COMMON_NOT_FOUND_아무것도_저장하지_않음() {
		Fixture f = fifty();
		long products = productRows(f.storeId());
		long prices = priceRows(f.storeId());

		assertRejected(() -> reviewService.confirmGroup(f.storeId(), SHRIMP, String.valueOf(f.otherShrimp()),
				new BigDecimal("1000")), "COMMON_NOT_FOUND");
		assertRejected(() -> reviewService.confirmGroup(f.storeId(), SHRIMP, "새우", new BigDecimal("1000")),
				"COMMON_NOT_FOUND");
		assertRejected(() -> reviewService.confirmGroup(f.storeId(), SHRIMP, "99999999", new BigDecimal("1000")),
				"COMMON_NOT_FOUND");

		for (long lineId : f.shrimpLines()) {
			assertThat(lineStatus(lineId)).isEqualTo("PENDING");
		}
		assertThat(productRows(f.storeId())).isEqualTo(products);
		assertThat(priceRows(f.storeId())).isEqualTo(prices);
		assertThat(priceRows(f.otherStoreId())).isZero();
	}

	@Test
	void reject_choice_빈_문자열_null_은_400_RECEIPT_INGREDIENT_REQUIRED() {
		Fixture f = fifty();

		assertRejected(() -> reviewService.confirmGroup(f.storeId(), SHRIMP, "", new BigDecimal("1000")),
				"RECEIPT_INGREDIENT_REQUIRED");
		assertRejected(() -> reviewService.confirmGroup(f.storeId(), SHRIMP, "  ", new BigDecimal("1000")),
				"RECEIPT_INGREDIENT_REQUIRED");
		assertRejected(() -> reviewService.confirmGroup(f.storeId(), SHRIMP, null, new BigDecimal("1000")),
				"RECEIPT_INGREDIENT_REQUIRED");
		assertThat(pendingRows(f.storeId())).isEqualTo(12 + 38 + 1);
	}

	@Test
	void reject_unitNeeded_묶음에_basePerPack_없으면_400_RECEIPT_UNIT_REQUIRED_저장_0() {
		Fixture f = fifty();
		assertThat(onlyMatch(reviewService.board(f.storeId()), SHRIMP).unitNeeded()).isTrue();
		long products = productRows(f.storeId());
		long prices = priceRows(f.storeId());
		long ingredients = ingredientRows(f.storeId());

		assertRejected(() -> reviewService.confirmGroup(f.storeId(), SHRIMP, String.valueOf(f.shrimp()), null),
				"RECEIPT_UNIT_REQUIRED");
		assertRejected(() -> reviewService.confirmGroup(f.storeId(), SHRIMP, "cycle", null), "RECEIPT_UNIT_REQUIRED");

		for (long lineId : f.shrimpLines()) {
			assertThat(lineStatus(lineId)).isEqualTo("PENDING");
		}
		for (long docId : f.shrimpDocs()) {
			assertThat(parseStatus(docId)).isEqualTo("PARSED");
		}
		assertThat(productRows(f.storeId())).isEqualTo(products);
		assertThat(priceRows(f.storeId())).isEqualTo(prices);
		assertThat(ingredientRows(f.storeId())).isEqualTo(ingredients);
	}

	@Test
	void reject_basePerPack_0_이하_소수_넷째_자리_14자리_초과는_400_RECEIPT_UNIT_INVALID() {
		Fixture f = fifty();
		String shrimp = String.valueOf(f.shrimp());

		for (String bad : List.of("0", "-1", "0.0001", "123456789012.345")) {
			assertRejected(() -> reviewService.confirmGroup(f.storeId(), SHRIMP, shrimp, new BigDecimal(bad)),
					"RECEIPT_UNIT_INVALID");
		}
		assertThat(pendingRows(f.storeId())).isEqualTo(12 + 38 + 1);
	}

	@Test
	void reject_묶음_줄이_전부_날짜_못_읽은_문서면_409_RECEIPT_DATE_UNKNOWN() {
		long storeId = insertStore("날짜없음");
		long onion = insertIngredient(storeId, "대파", "G", "LOCKED");
		List<Long> lines = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			long doc = insertDoc(storeId, null, null, "PARSED");
			lines.add(insertLlmLine(storeId, doc, 1, "대파 1단", "1", onion, "0.900", null, null));
		}

		assertRejected(() -> reviewService.confirmGroup(storeId, "대파 1단", String.valueOf(onion), null),
				"RECEIPT_DATE_UNKNOWN");

		for (long lineId : lines) {
			assertThat(lineStatus(lineId)).isEqualTo("PENDING");
		}
		assertThat(onlyMatch(reviewService.board(storeId), "대파 1단").lineIds()).isEqualTo(ids(lines));
	}

	@Test
	void skip_날짜_못_읽은_문서_줄_1개_섞인_묶음은_나머지만_확정_skippedLineIds_1개_그_줄은_다음_목록에_남음() {
		long storeId = insertStore("건너뜀");
		long onion = insertIngredient(storeId, "대파", "G", "LOCKED");
		long supplier = insertSupplier(storeId, "OO상회");
		List<Long> dated = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			long doc = insertDoc(storeId, supplier, "RECEIVE", "PARSED");
			dated.add(insertLlmLine(storeId, doc, 1, "대파 1단", "1", onion, "0.900", 1000, "g"));
		}
		long noDateDoc = insertDoc(storeId, supplier, null, "PARSED");
		long noDateLine = insertLlmLine(storeId, noDateDoc, 1, "대파 1단", "1", onion, "0.900", 1000, "g");

		GroupConfirmResult result = reviewService.confirmGroup(storeId, "대파 1단", String.valueOf(onion),
				new BigDecimal("1000"));

		assertThat(result.confirm().confirmedLineIds()).isEqualTo(ids(dated));
		assertThat(result.skippedLineIds()).containsExactly(String.valueOf(noDateLine));
		assertThat(lineStatus(noDateLine)).isEqualTo("PENDING");
		assertThat(parseStatus(noDateDoc)).isEqualTo("PARSED");
		ReviewMatchResponse left = onlyMatch(result.board(), "대파 1단");
		assertThat(left.lineIds()).containsExactly(String.valueOf(noDateLine));
		assertThat(onlyMatch(reviewService.board(storeId), "대파 1단").lineIds())
				.containsExactly(String.valueOf(noDateLine));
	}

	@Test
	void empty_대상_줄_없는_가게는_matches_readyDocs_빈_목록_options_는_재료_그대로() {
		long storeId = insertStore("빈목록");
		long b = insertIngredient(storeId, "나", "G", "LOCKED");
		long a = insertIngredient(storeId, "가", "G", "LOCKED");
		// 확정 · 무시된 줄과 FAILED 문서 줄만 있다
		long confirmedDoc = insertDoc(storeId, null, "RECEIVE", "CONFIRMED");
		insertLine(storeId, confirmedDoc, 1, "다 끝난 줄", "1", "100", a, null, "0.900", "CONFIRMED",
				rawMatch(null, null, false));
		long failedDoc = insertDoc(storeId, null, null, "FAILED");
		insertLlmLine(storeId, failedDoc, 1, "실패 장 줄", "1", null, null, null, null);

		ReviewBoard board = reviewService.board(storeId);

		assertThat(board.matches()).isEmpty();
		assertThat(board.readyDocs()).isEmpty();
		assertThat(board.options()).extracting(ReviewOptionResponse::id).containsExactly(String.valueOf(b), String.valueOf(a));
		assertThat(board.options()).extracting(ReviewOptionResponse::name).containsExactly("나", "가");

		ReviewBoard none = reviewService.board(insertStore("재료없음"));
		assertThat(none.matches()).isEmpty();
		assertThat(none.readyDocs()).isEmpty();
		assertThat(none.options()).isEmpty();
	}

	// ── job 목 ────────────────────────────────────────────────

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
		out.put("supplier", supplier);
		out.put("date", date);
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

	/** 모든 줄을 한 재료로 제안. 포장 없음. */
	private JsonNode llmMatch(JsonNode lines, String ingredient, double confidence) {
		ObjectNode out = json.createObjectNode();
		ArrayNode items = out.putArray("items");
		for (JsonNode line : lines) {
			ObjectNode row = items.addObject();
			row.put("line", line.get("line").asInt());
			row.put("ingredient", ingredient).put("is_new_periodic", false);
			row.putNull("pack");
			row.put("confidence", confidence);
			row.put("reason", "테스트");
		}
		return out;
	}

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
}
