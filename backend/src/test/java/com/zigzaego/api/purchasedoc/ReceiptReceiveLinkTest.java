package com.zigzaego.api.purchasedoc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.auth.AuthTestSupport;
import com.zigzaego.api.inventory.dto.InventoryEstimateResponse;
import com.zigzaego.api.inventory.service.InventoryEstimateService;
import com.zigzaego.api.purchasedoc.dto.LineDecisionRequest;
import com.zigzaego.api.purchasedoc.dto.LineDecisionRequest.Action;
import com.zigzaego.api.purchasedoc.service.ReceiptConfirmService;
import com.zigzaego.api.receiving.service.AutoReceiveService;
import com.zigzaego.api.receiving.service.ReceivingService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 영수증(RECEIVE) 확정이 같은 입고 예정 줄을 {@link ReceivingService#recordReceipt} 로 채운다 (지라 S15P31A308-71 완료 조건
 * "탭 확인과 영수증 확정 각각 재고 반영" 의 영수증 쪽). 탭 쪽은 {@code ReceivingConfirmTest}.
 * 발주 70 코드 없이 발주서 · 발주 줄을 표에 직접 넣는다. 재고는 {@link InventoryEstimateService} 를 날짜 고정으로 직접 부른다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ReceiptReceiveLinkTest {

	private static final String ONION = "대파 1단";
	private static final String GARLIC = "깐마늘 1kg";
	/** 실사 2026-10-10 10:00 (Asia/Seoul) 뒤, 소비 0 인 날. */
	private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
	private static final OffsetDateTime COUNTED_AT = OffsetDateTime.of(2026, 10, 10, 10, 0, 0, 0, ZoneOffset.ofHours(9));
	/** 발주서를 만든 시각: 문서 날짜(10-12)보다 앞. */
	private static final OffsetDateTime ORDERED_AT = OffsetDateTime.of(2026, 10, 11, 9, 0, 0, 0, ZoneOffset.ofHours(9));
	private static final String AUTO_RAW = "{\"autoConfirmed\": true}";

	@Autowired
	private ReceiptConfirmService confirmService;

	@Autowired
	private ReceivingService receivingService;

	@Autowired
	private AutoReceiveService autoReceiveService;

	@Autowired
	private InventoryEstimateService inventoryService;

	@Autowired
	private JdbcTemplate jdbc;

	// ── 데이터 ────────────────────────────────────────────────

	/** 실사 2026-10-10 대파 0g, OO상회 사전 상품 "대파 1단" = 1000 g · 현재 단가 3500. 대파 단위는 CONFIRMED(아직 입고 없음). */
	private record Fixture(long storeId, long onion, long supplier, long product) {
	}

	private Fixture fixture(String label) {
		String name = label + "-" + System.nanoTime();
		long storeId = AuthTestSupport.insertStore(jdbc, name);
		long onion = insertIngredient(storeId, "대파");
		long supplier = insertSupplier(storeId, "OO상회");
		long product = insertProduct(storeId, supplier, onion, ONION, "1000", "3500");
		insertCount(storeId, onion);
		return new Fixture(storeId, onion, supplier, product);
	}

	private long insertIngredient(long storeId, String name) {
		return jdbc.queryForObject("""
				INSERT INTO ingredient (store_id, name, unit, unit_status, price_sensitive, sourcing_type, kind, storage_type, storage_location,
				                        category_id, category_source)
				VALUES (?, ?, 'G', 'CONFIRMED', false, 'GENERIC', 'BOM', 'CHILLED', 'FRIDGE',
				        (SELECT id FROM ingredient_category WHERE store_id = ? AND code = 'OTHER'), 'AUTO') RETURNING id""", Long.class, storeId,
				name, storeId);
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

	private long insertOrder(long storeId, long supplierId, String status, OffsetDateTime createdAt) {
		return jdbc.queryForObject(
				"INSERT INTO purchase_order (store_id, supplier_id, status, created_at) VALUES (?, ?, ?, ?) RETURNING id",
				Long.class, storeId, supplierId, status, createdAt);
	}

	private long insertItem(long storeId, long orderId, long productId, long ingredientId, String qty) {
		return jdbc.queryForObject("""
				INSERT INTO purchase_order_item (store_id, po_id, supplier_product_id, ingredient_id, qty, expected_date)
				VALUES (?, ?, ?, ?, CAST(? AS NUMERIC), DATE '2026-10-12') RETURNING id""", Long.class, storeId, orderId,
				productId, ingredientId, qty);
	}

	private long insertDoc(long storeId, Long supplierId, String date, String kind) {
		return jdbc.queryForObject("""
				INSERT INTO purchase_doc (store_id, supplier_id, doc_type, doc_date, parse_status, kind)
				VALUES (?, ?, 'RECEIPT', CAST(? AS DATE), 'PARSED', ?) RETURNING id""", Long.class, storeId, supplierId,
				date, kind);
	}

	private long insertLine(long storeId, long docId, int lineNo, String name, String qty, String price,
			long ingredientId, Long productId, String status, String raw) {
		return jdbc.queryForObject("""
				INSERT INTO purchase_doc_line (store_id, doc_id, line_no, raw_name, qty, unit_price, ingredient_id,
				                               supplier_product_id, status, raw)
				VALUES (?, ?, ?, ?, CAST(? AS NUMERIC), CAST(? AS NUMERIC), ?, ?, ?, CAST(? AS JSONB)) RETURNING id""",
				Long.class, storeId, docId, lineNo, name, qty, price, ingredientId, productId, status, raw);
	}

	private Map<String, Object> item(long itemId) {
		return jdbc.queryForMap(
				"SELECT received_qty, received_unit_price, received_at FROM purchase_order_item WHERE id = ?", itemId);
	}

	private Long linkedOrder(long docId) {
		return jdbc.queryForObject("SELECT purchase_order_id FROM purchase_doc WHERE id = ?", Long.class, docId);
	}

	private String docStatus(long docId) {
		return jdbc.queryForObject("SELECT parse_status FROM purchase_doc WHERE id = ?", String.class, docId);
	}

	private long priceRows(long productId, String source) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM price_history WHERE supplier_product_id = ? AND source = ?",
				Long.class, productId, source);
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

	private boolean incoming(long storeId, long itemId) {
		return receivingService.feed(storeId).incoming().stream()
				.anyMatch(line -> line.itemId().equals(String.valueOf(itemId)));
	}

	private static LineDecisionRequest ingredient(long lineId, long ingredientId) {
		return new LineDecisionRequest(String.valueOf(lineId), Action.INGREDIENT, String.valueOf(ingredientId), null);
	}

	/** 잇지 않았을 때의 기존 동작: 문서 확정 · 재고 ② 로 한 번(+2000) · 입고 예정 줄 그대로 · 문서 발주 연결 없음. */
	private void assertNotLinked(Fixture f, long docId, long itemId, BigDecimal before) {
		assertThat(docStatus(docId)).isEqualTo("CONFIRMED");
		assertThat(linkedOrder(docId)).isNull();
		assertThat(item(itemId).get("received_at")).isNull();
		assertThat(item(itemId).get("received_qty")).isNull();
		assertThat(stock(f.storeId(), f.onion()).subtract(before)).isEqualByComparingTo("2000.000");
	}

	// ── 연결 ────────────────────────────────────────────────

	@Test
	void link_RECEIVE_영수증_확정이_같은_입고_예정_줄을_채우고_재고는_한_번만_더한다() {
		Fixture f = fixture("연결");
		long orderId = insertOrder(f.storeId(), f.supplier(), "SENT", ORDERED_AT);
		long itemId = insertItem(f.storeId(), orderId, f.product(), f.onion(), "3");
		long docId = insertDoc(f.storeId(), f.supplier(), "2026-10-12", "RECEIVE");
		long lineId = insertLine(f.storeId(), docId, 1, ONION, "2", "3980", f.onion(), f.product(), "PENDING", "{}");
		BigDecimal before = stock(f.storeId(), f.onion());
		assertThat(incoming(f.storeId(), itemId)).isTrue();

		confirmService.confirm(f.storeId(), List.of(ingredient(lineId, f.onion())));

		Map<String, Object> received = item(itemId);
		assertThat((BigDecimal) received.get("received_qty")).isEqualByComparingTo("2");
		assertThat((BigDecimal) received.get("received_unit_price")).isEqualByComparingTo("3980");
		assertThat(received.get("received_at")).isNotNull();
		assertThat(linkedOrder(docId)).isEqualTo(orderId);
		assertThat(linkedItem(lineId)).isEqualTo(itemId);
		assertThat(docStatus(docId)).isEqualTo("CONFIRMED");
		// ① 발주 줄 2단 × 1000 g 만 — 문서 줄(②)은 발주서와 이어져 빠진다. 두 번 세면 4000
		assertThat(stock(f.storeId(), f.onion()).subtract(before)).isEqualByComparingTo("2000.000");
		assertThat(incoming(f.storeId(), itemId)).isFalse();
		assertThat(receivingService.feed(f.storeId()).firstReceiptDone()).isTrue();
	}

	@Test
	void lock_첫_입고면_영수증_연결도_재료_단위를_LOCKED_로() {
		Fixture f = fixture("잠금");
		long orderId = insertOrder(f.storeId(), f.supplier(), "SENT", ORDERED_AT);
		long itemId = insertItem(f.storeId(), orderId, f.product(), f.onion(), "2");
		long docId = insertDoc(f.storeId(), f.supplier(), "2026-10-12", "RECEIVE");
		long lineId = insertLine(f.storeId(), docId, 1, ONION, "2", "3980", f.onion(), f.product(), "PENDING", "{}");
		assertThat(unitStatus(f.onion())).isEqualTo("CONFIRMED");

		confirmService.confirm(f.storeId(), List.of(ingredient(lineId, f.onion())));

		assertThat(item(itemId).get("received_at")).isNotNull();
		assertThat(unitStatus(f.onion())).isEqualTo("LOCKED");
	}

	@Test
	void price_연결된_영수증_확정은_가격_이력_DOC_1행만_RECEIVE_행은_없다() {
		Fixture f = fixture("가격");
		long orderId = insertOrder(f.storeId(), f.supplier(), "SENT", ORDERED_AT);
		long itemId = insertItem(f.storeId(), orderId, f.product(), f.onion(), "2");
		long docId = insertDoc(f.storeId(), f.supplier(), "2026-10-12", "RECEIVE");
		long lineId = insertLine(f.storeId(), docId, 1, ONION, "2", "3980", f.onion(), f.product(), "PENDING", "{}");

		confirmService.confirm(f.storeId(), List.of(ingredient(lineId, f.onion())));

		assertThat(item(itemId).get("received_at")).isNotNull();
		assertThat(linkedOrder(docId)).isEqualTo(orderId);
		assertThat(priceRows(f.product(), "DOC")).isEqualTo(1);
		assertThat(priceRows(f.product(), "RECEIVE")).isZero();
		assertThat(jdbc.queryForObject("SELECT doc_line_id FROM price_history WHERE supplier_product_id = ?",
				Long.class, f.product())).isEqualTo(lineId);
	}

	@Test
	void auto_자동_확정_줄만_있는_문서를_confirmDocuments_로_확정해도_같은_연결() {
		Fixture f = fixture("자동");
		long orderId = insertOrder(f.storeId(), f.supplier(), "SENT", ORDERED_AT);
		long itemId = insertItem(f.storeId(), orderId, f.product(), f.onion(), "2");
		long docId = insertDoc(f.storeId(), f.supplier(), "2026-10-12", "RECEIVE");
		insertLine(f.storeId(), docId, 1, ONION, "2", "3980", f.onion(), f.product(), "CONFIRMED", AUTO_RAW);
		BigDecimal before = stock(f.storeId(), f.onion());

		confirmService.confirmDocuments(f.storeId(), List.of(String.valueOf(docId)));

		assertThat((BigDecimal) item(itemId).get("received_qty")).isEqualByComparingTo("2");
		assertThat(linkedOrder(docId)).isEqualTo(orderId);
		assertThat(stock(f.storeId(), f.onion()).subtract(before)).isEqualByComparingTo("2000.000");
		assertThat(priceRows(f.product(), "DOC")).isEqualTo(1);
		assertThat(priceRows(f.product(), "RECEIVE")).isZero();
		assertThat(unitStatus(f.onion())).isEqualTo("LOCKED");
	}

	@Test
	void link_발주서가_둘_맞으면_먼저_만든_발주서에_잇는다() {
		Fixture f = fixture("먼저");
		long older = insertOrder(f.storeId(), f.supplier(), "SENT", ORDERED_AT.minusDays(1));
		long olderItem = insertItem(f.storeId(), older, f.product(), f.onion(), "2");
		long newer = insertOrder(f.storeId(), f.supplier(), "SENT", ORDERED_AT);
		long newerItem = insertItem(f.storeId(), newer, f.product(), f.onion(), "2");
		long docId = insertDoc(f.storeId(), f.supplier(), "2026-10-12", "RECEIVE");
		long lineId = insertLine(f.storeId(), docId, 1, ONION, "2", "3980", f.onion(), f.product(), "PENDING", "{}");

		confirmService.confirm(f.storeId(), List.of(ingredient(lineId, f.onion())));

		assertThat(linkedOrder(docId)).isEqualTo(older);
		assertThat(item(olderItem).get("received_at")).isNotNull();
		assertThat(item(newerItem).get("received_at")).isNull();
	}

	// ── 입고 시각 = 문서 날짜 (잇기 전 ② 와 같은 실사 판정) ─────────────

	private void insertCountAt(long storeId, long ingredientId, OffsetDateTime countedAt) {
		jdbc.update("INSERT INTO stock_count (store_id, ingredient_id, counted_at, qty) VALUES (?, ?, ?, 0)", storeId,
				ingredientId, countedAt);
	}

	/** 발주서(10-11) 입고 예정 2단 + RECEIVE 영수증(문서 날짜) 대파 2단 줄. @return {itemId, lineId} */
	private long[] orderAndReceipt(Fixture f, String docDate) {
		long orderId = insertOrder(f.storeId(), f.supplier(), "SENT", ORDERED_AT);
		long itemId = insertItem(f.storeId(), orderId, f.product(), f.onion(), "2");
		long docId = insertDoc(f.storeId(), f.supplier(), docDate, "RECEIVE");
		long lineId = insertLine(f.storeId(), docId, 1, ONION, "2", "3980", f.onion(), f.product(), "PENDING", "{}");
		return new long[] {itemId, lineId};
	}

	private LocalDate receivedDate(long itemId) {
		return jdbc.queryForObject(
				"SELECT CAST(received_at AT TIME ZONE 'Asia/Seoul' AS date) FROM purchase_order_item WHERE id = ?",
				LocalDate.class, itemId);
	}

	@Test
	void count_실사가_문서_날짜와_확정_사이면_이어도_재고_증가_0() {
		Fixture f = fixture("사이실사");
		insertCountAt(f.storeId(), f.onion(), OffsetDateTime.of(2026, 10, 13, 10, 0, 0, 0, ZoneOffset.ofHours(9)));
		long[] ids = orderAndReceipt(f, "2026-10-12");
		BigDecimal before = stock(f.storeId(), f.onion());

		confirmService.confirm(f.storeId(), List.of(ingredient(ids[1], f.onion())));

		assertThat(item(ids[0]).get("received_at")).isNotNull();
		// 10-13 실사가 10-12 입고분을 이미 셌다 — 잇기 전 ②(doc_date ≥ 실사일)도 빠졌다
		assertThat(stock(f.storeId(), f.onion()).subtract(before)).isEqualByComparingTo("0");
	}

	@Test
	void count_같은_날_실사면_문서_날짜_포함이라_재고_증가() {
		Fixture f = fixture("같은날실사");
		insertCountAt(f.storeId(), f.onion(), OffsetDateTime.of(2026, 10, 12, 22, 0, 0, 0, ZoneOffset.ofHours(9)));
		long[] ids = orderAndReceipt(f, "2026-10-12");
		BigDecimal before = stock(f.storeId(), f.onion());

		confirmService.confirm(f.storeId(), List.of(ingredient(ids[1], f.onion())));

		assertThat(item(ids[0]).get("received_at")).isNotNull();
		assertThat(stock(f.storeId(), f.onion()).subtract(before)).isEqualByComparingTo("2000.000");
	}

	@Test
	void count_입고일은_문서_날짜_가게_시간대() {
		Fixture f = fixture("입고일");
		long[] ids = orderAndReceipt(f, "2026-10-12");

		confirmService.confirm(f.storeId(), List.of(ingredient(ids[1], f.onion())));

		// 주기형 소진 속도(CYCLE_RECEIPTS_SQL)가 쓰는 날짜 = (received_at AT TIME ZONE tz)::date
		assertThat(receivedDate(ids[0])).isEqualTo(LocalDate.of(2026, 10, 12));
	}

	@Test
	void count_문서_날짜가_오늘이면_입고_시각은_미래가_아니다() {
		Fixture f = fixture("오늘");
		LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
		long[] ids = orderAndReceipt(f, today.toString());

		confirmService.confirm(f.storeId(), List.of(ingredient(ids[1], f.onion())));

		assertThat(jdbc.queryForObject("SELECT received_at <= now() FROM purchase_order_item WHERE id = ?",
				Boolean.class, ids[0])).isTrue();
		assertThat(receivedDate(ids[0])).isEqualTo(today);
	}

	// ── 잇지 않는 경우 = 기존 동작 ─────────────────────────────

	@Test
	void edge_맞는_입고_예정_줄이_없으면_기존_동작() {
		Fixture f = fixture("없음");
		long other = insertProduct(f.storeId(), f.supplier(), f.onion(), "대파 2단", "2000", "7000");
		long orderId = insertOrder(f.storeId(), f.supplier(), "SENT", ORDERED_AT);
		long itemId = insertItem(f.storeId(), orderId, other, f.onion(), "1"); // 다른 상품
		long docId = insertDoc(f.storeId(), f.supplier(), "2026-10-12", "RECEIVE");
		long lineId = insertLine(f.storeId(), docId, 1, ONION, "2", "3980", f.onion(), f.product(), "PENDING", "{}");
		BigDecimal before = stock(f.storeId(), f.onion());

		confirmService.confirm(f.storeId(), List.of(ingredient(lineId, f.onion())));

		assertNotLinked(f, docId, itemId, before);
		assertThat(priceRows(f.product(), "DOC")).isEqualTo(1);
		assertThat(unitStatus(f.onion())).isEqualTo("LOCKED"); // 112 의 확정 경로가 잠근다
	}

	private Long linkedItem(long lineId) {
		return jdbc.queryForObject("SELECT purchase_order_item_id FROM purchase_doc_line WHERE id = ?", Long.class,
				lineId);
	}

	/**
	 * 문서 줄 일부만 발주에 맞으면 줄 단위로 잇는다 (S15P31A308-301, DECISIONS #86). 예전에는 문서 단위로만 이어 잇지 않았고,
	 * 대파 줄이 영수증(②)으로 +2000 · 입고 예정 줄은 남아 자동 입고가 +2000 을 한 번 더 더했다.
	 */
	@Test
	void partial_문서_줄_둘_중_하나만_발주에_있으면_그_줄만_발주_줄에_잇고_재고는_한_번씩만() {
		Fixture f = fixture("일부");
		long garlic = insertIngredient(f.storeId(), "마늘");
		insertCount(f.storeId(), garlic);
		long garlicProduct = insertProduct(f.storeId(), f.supplier(), garlic, GARLIC, "1000", "9000");
		long orderId = insertOrder(f.storeId(), f.supplier(), "SENT", ORDERED_AT);
		long itemId = insertItem(f.storeId(), orderId, f.product(), f.onion(), "2");
		long docId = insertDoc(f.storeId(), f.supplier(), "2026-10-12", "RECEIVE");
		long onionLine = insertLine(f.storeId(), docId, 1, ONION, "2", "3980", f.onion(), f.product(), "PENDING",
				"{}");
		long garlicLine = insertLine(f.storeId(), docId, 2, GARLIC, "1", "9000", garlic, garlicProduct, "PENDING",
				"{}");
		BigDecimal before = stock(f.storeId(), f.onion());
		BigDecimal garlicBefore = stock(f.storeId(), garlic);

		confirmService.confirm(f.storeId(), List.of(ingredient(onionLine, f.onion()), ingredient(garlicLine, garlic)));

		assertThat(docStatus(docId)).isEqualTo("CONFIRMED");
		assertThat(linkedOrder(docId)).isNull(); // 문서 전체가 한 발주서에 맞지는 않는다
		assertThat(linkedItem(onionLine)).isEqualTo(itemId);
		assertThat(linkedItem(garlicLine)).isNull();
		assertThat((BigDecimal) item(itemId).get("received_qty")).isEqualByComparingTo("2");
		assertThat(incoming(f.storeId(), itemId)).isFalse();
		assertThat(stock(f.storeId(), f.onion()).subtract(before)).isEqualByComparingTo("2000.000");
		assertThat(stock(f.storeId(), garlic).subtract(garlicBefore)).isEqualByComparingTo("1000.000");

		// 그 뒤 자동 입고(203)가 돌아도 이어진 줄은 이미 입고라 다시 더하지 않는다
		autoReceiveService.receiveDue(f.storeId());
		assertThat(stock(f.storeId(), f.onion()).subtract(before)).isEqualByComparingTo("2000.000");
	}

	/** 영수증 한 장이 두 발주서의 줄을 함께 받으면(문서 단위로는 못 잇는다) 줄마다 각 발주 줄에 잇는다. */
	@Test
	void partial_두_발주서에_걸친_영수증은_줄마다_각_발주_줄에_잇는다() {
		Fixture f = fixture("두발주");
		long garlic = insertIngredient(f.storeId(), "마늘");
		insertCount(f.storeId(), garlic);
		long garlicProduct = insertProduct(f.storeId(), f.supplier(), garlic, GARLIC, "1000", "9000");
		long onionOrder = insertOrder(f.storeId(), f.supplier(), "SENT", ORDERED_AT);
		long onionItem = insertItem(f.storeId(), onionOrder, f.product(), f.onion(), "2");
		long garlicOrder = insertOrder(f.storeId(), f.supplier(), "SENT", ORDERED_AT.plusHours(1));
		long garlicItem = insertItem(f.storeId(), garlicOrder, garlicProduct, garlic, "1");
		long docId = insertDoc(f.storeId(), f.supplier(), "2026-10-12", "RECEIVE");
		long onionLine = insertLine(f.storeId(), docId, 1, ONION, "2", "3980", f.onion(), f.product(), "PENDING",
				"{}");
		long garlicLine = insertLine(f.storeId(), docId, 2, GARLIC, "1", "9000", garlic, garlicProduct, "PENDING",
				"{}");
		BigDecimal before = stock(f.storeId(), f.onion());
		BigDecimal garlicBefore = stock(f.storeId(), garlic);

		confirmService.confirm(f.storeId(), List.of(ingredient(onionLine, f.onion()), ingredient(garlicLine, garlic)));

		assertThat(linkedOrder(docId)).isNull();
		assertThat(linkedItem(onionLine)).isEqualTo(onionItem);
		assertThat(linkedItem(garlicLine)).isEqualTo(garlicItem);
		autoReceiveService.receiveDue(f.storeId());
		assertThat(stock(f.storeId(), f.onion()).subtract(before)).isEqualByComparingTo("2000.000");
		assertThat(stock(f.storeId(), garlic).subtract(garlicBefore)).isEqualByComparingTo("1000.000");
	}

	@Test
	void edge_PAST_문서는_입고_예정을_건드리지_않는다() {
		Fixture f = fixture("과거");
		long orderId = insertOrder(f.storeId(), f.supplier(), "SENT", ORDERED_AT.minusDays(5));
		long itemId = insertItem(f.storeId(), orderId, f.product(), f.onion(), "2");
		long docId = insertDoc(f.storeId(), f.supplier(), "2026-10-09", "PAST");
		long lineId = insertLine(f.storeId(), docId, 1, ONION, "2", "3980", f.onion(), f.product(), "PENDING", "{}");
		BigDecimal before = stock(f.storeId(), f.onion());

		confirmService.confirm(f.storeId(), List.of(ingredient(lineId, f.onion())));

		assertThat(linkedOrder(docId)).isNull();
		assertThat(item(itemId).get("received_at")).isNull();
		assertThat(stock(f.storeId(), f.onion())).isEqualByComparingTo(before);
		assertThat(priceRows(f.product(), "RECEIVE")).isZero();
	}

	@Test
	void edge_다른_거래처_발주와_문서_날짜보다_뒤에_만든_발주서는_잇지_않는다() {
		Fixture f = fixture("거래처날짜");
		long otherSupplier = insertSupplier(f.storeId(), "XX유통");
		long otherOrder = insertOrder(f.storeId(), otherSupplier, "SENT", ORDERED_AT);
		long otherItem = insertItem(f.storeId(), otherOrder, f.product(), f.onion(), "2"); // 거래처가 다름
		long lateOrder = insertOrder(f.storeId(), f.supplier(), "SENT",
				OffsetDateTime.of(2026, 10, 13, 9, 0, 0, 0, ZoneOffset.ofHours(9))); // 문서(10-12) 뒤
		long lateItem = insertItem(f.storeId(), lateOrder, f.product(), f.onion(), "2");
		long docId = insertDoc(f.storeId(), f.supplier(), "2026-10-12", "RECEIVE");
		long lineId = insertLine(f.storeId(), docId, 1, ONION, "2", "3980", f.onion(), f.product(), "PENDING", "{}");
		BigDecimal before = stock(f.storeId(), f.onion());

		confirmService.confirm(f.storeId(), List.of(ingredient(lineId, f.onion())));

		assertNotLinked(f, docId, lateItem, before);
		assertThat(item(otherItem).get("received_at")).isNull();
	}

	@Test
	void edge_이미_입고_확인된_줄_취소된_줄_취소된_발주서는_잇지_않는다() {
		Fixture f = fixture("취소");
		long receivedOrder = insertOrder(f.storeId(), f.supplier(), "SENT", ORDERED_AT);
		long receivedItem = insertItem(f.storeId(), receivedOrder, f.product(), f.onion(), "2");
		jdbc.update("UPDATE purchase_order_item SET received_qty = 1, received_unit_price = 3500, received_at = ? "
				+ "WHERE id = ?", COUNTED_AT.plusDays(1), receivedItem);
		long cancelledItemOrder = insertOrder(f.storeId(), f.supplier(), "SENT", ORDERED_AT);
		long cancelledItem = insertItem(f.storeId(), cancelledItemOrder, f.product(), f.onion(), "2");
		jdbc.update("UPDATE purchase_order_item SET cancelled_at = ? WHERE id = ?", COUNTED_AT.plusDays(1),
				cancelledItem);
		long cancelledOrder = insertOrder(f.storeId(), f.supplier(), "CANCELLED", ORDERED_AT);
		long cancelledOrderItem = insertItem(f.storeId(), cancelledOrder, f.product(), f.onion(), "2");
		long docId = insertDoc(f.storeId(), f.supplier(), "2026-10-12", "RECEIVE");
		long lineId = insertLine(f.storeId(), docId, 1, ONION, "2", "3980", f.onion(), f.product(), "PENDING", "{}");
		BigDecimal before = stock(f.storeId(), f.onion());

		confirmService.confirm(f.storeId(), List.of(ingredient(lineId, f.onion())));

		assertThat(linkedOrder(docId)).isNull();
		assertThat((BigDecimal) item(receivedItem).get("received_qty")).isEqualByComparingTo("1"); // 덮어쓰지 않음
		assertThat(item(cancelledItem).get("received_at")).isNull();
		assertThat(item(cancelledOrderItem).get("received_at")).isNull();
		assertThat(stock(f.storeId(), f.onion()).subtract(before)).isEqualByComparingTo("2000.000");
	}

	@Test
	void edge_다른_가게의_발주는_잇지_않는다() {
		Fixture f = fixture("내가게");
		Fixture other = fixture("남가게");
		long otherOrder = insertOrder(other.storeId(), other.supplier(), "SENT", ORDERED_AT);
		long otherItem = insertItem(other.storeId(), otherOrder, other.product(), other.onion(), "2");
		long docId = insertDoc(f.storeId(), f.supplier(), "2026-10-12", "RECEIVE");
		long lineId = insertLine(f.storeId(), docId, 1, ONION, "2", "3980", f.onion(), f.product(), "PENDING", "{}");
		BigDecimal before = stock(f.storeId(), f.onion());

		confirmService.confirm(f.storeId(), List.of(ingredient(lineId, f.onion())));

		assertThat(linkedOrder(docId)).isNull();
		assertThat(item(otherItem).get("received_at")).isNull();
		assertThat(stock(f.storeId(), f.onion()).subtract(before)).isEqualByComparingTo("2000.000");
	}

	@Test
	void edge_거래처_모르는_문서는_잇지_않는다() {
		Fixture f = fixture("마트");
		long orderId = insertOrder(f.storeId(), f.supplier(), "SENT", ORDERED_AT);
		long itemId = insertItem(f.storeId(), orderId, f.product(), f.onion(), "2");
		long docId = insertDoc(f.storeId(), null, "2026-10-12", "RECEIVE");
		long lineId = insertLine(f.storeId(), docId, 1, "대파", "2", "3980", f.onion(), null, "PENDING", "{}");

		confirmService.confirm(f.storeId(), List.of(ingredient(lineId, f.onion())));

		assertThat(linkedOrder(docId)).isNull();
		assertThat(item(itemId).get("received_at")).isNull();
	}
}
