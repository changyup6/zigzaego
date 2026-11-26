package com.zigzaego.api.receiving;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.auth.AuthTestSupport;
import com.zigzaego.api.global.config.StoreClock;
import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.inventory.dto.InventoryEstimateResponse;
import com.zigzaego.api.inventory.service.InventoryEstimateService;
import com.zigzaego.api.purchasedoc.dto.LineDecisionRequest;
import com.zigzaego.api.purchasedoc.dto.LineDecisionRequest.Action;
import com.zigzaego.api.purchasedoc.service.ReceiptConfirmService;
import com.zigzaego.api.receiving.service.AutoReceiveService;
import com.zigzaego.api.receiving.service.ReceivingService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 자동 입고(203)된 발주 줄에 영수증을 확정해도 재고가 두 번 더해지지 않는다 (S15P31A308-238, DECISIONS #41 ① ②).
 * 영수증 연결이 자동 입고 줄(auto_received)도 후보로 받아 값(받은 양 · 단가 · 시각)을 영수증으로 덮는다.
 * 자동 입고 줄은 recordReceipt(…, autoReceived=true) 로 만든다 — 배치가 부르는 것과 같은 경로.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AutoReceiveReceiptLinkTest {

	private static final String ONION = "대파 1단";
	/** 실사 2026-10-10 10:00 (Asia/Seoul) 뒤, 소비 0 인 날. */
	private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
	private static final OffsetDateTime COUNTED_AT = OffsetDateTime.of(2026, 10, 10, 10, 0, 0, 0, ZoneOffset.ofHours(9));
	private static final OffsetDateTime ORDERED_AT = OffsetDateTime.of(2026, 10, 11, 9, 0, 0, 0, ZoneOffset.ofHours(9));
	private static final LocalDate EXPECTED = LocalDate.of(2026, 10, 12);

	@Autowired
	private ReceiptConfirmService confirmService;

	@Autowired
	private ReceivingService receivingService;

	@Autowired
	private InventoryEstimateService inventoryService;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private NamedParameterJdbcTemplate namedJdbc;

	@Autowired
	private PlatformTransactionManager txManager;

	private record Fixture(long storeId, long onion, long supplier, long product) {
	}

	private Fixture fixture(String label) {
		String name = label + "-" + System.nanoTime();
		long storeId = AuthTestSupport.insertStore(jdbc, name);
		long onion = jdbc.queryForObject("""
				INSERT INTO ingredient (store_id, name, unit, unit_status, price_sensitive, sourcing_type, kind, storage_type,
				                        storage_location, category_id, category_source)
				VALUES (?, '대파', 'G', 'CONFIRMED', false, 'GENERIC', 'BOM', 'CHILLED', 'FRIDGE',
				        (SELECT id FROM ingredient_category WHERE store_id = ? AND code = 'OTHER'), 'AUTO') RETURNING id""",
				Long.class, storeId, storeId);
		long supplier = insertSupplier(storeId, "OO상회");
		long product = insertProduct(storeId, supplier, onion, "3500");
		jdbc.update("INSERT INTO stock_count (store_id, ingredient_id, counted_at, qty) VALUES (?, ?, ?, 0)", storeId,
				onion, COUNTED_AT);
		return new Fixture(storeId, onion, supplier, product);
	}

	private long insertSupplier(long storeId, String name) {
		return jdbc.queryForObject("INSERT INTO supplier (store_id, name) VALUES (?, ?) RETURNING id", Long.class,
				storeId, name);
	}

	private long insertProduct(long storeId, long supplierId, long ingredientId, String price) {
		return jdbc.queryForObject("""
				INSERT INTO supplier_product (store_id, supplier_id, ingredient_id, product_name, base_qty_per_pack, price)
				VALUES (?, ?, ?, ?, 1000, CAST(? AS NUMERIC)) RETURNING id""", Long.class, storeId, supplierId,
				ingredientId, ONION, price);
	}

	private long insertOrder(long storeId, long supplierId, String status, OffsetDateTime createdAt) {
		return jdbc.queryForObject(
				"INSERT INTO purchase_order (store_id, supplier_id, status, created_at) VALUES (?, ?, ?, ?) RETURNING id",
				Long.class, storeId, supplierId, status, createdAt);
	}

	private long insertItem(Fixture f, long orderId, String qty, LocalDate expected) {
		return jdbc.queryForObject("""
				INSERT INTO purchase_order_item (store_id, po_id, supplier_product_id, ingredient_id, qty, expected_date)
				VALUES (?, ?, ?, ?, CAST(? AS NUMERIC), ?) RETURNING id""", Long.class, f.storeId(), orderId,
				f.product(), f.onion(), qty, expected);
	}

	/** 203 배치와 같은 경로: 주문 수량 · 단가 null · price_history 안 씀 · 받은 날 = 예정일 · 자동 입고 표시. */
	private long autoItem(Fixture f, long orderId, String qty, LocalDate expected) {
		long id = insertItem(f, orderId, qty, expected);
		receivingService.recordReceipt(f.storeId(), id, new BigDecimal(qty), null, false, expected, true);
		return id;
	}

	private long insertDoc(long storeId, Long supplierId, String date) {
		return jdbc.queryForObject("""
				INSERT INTO purchase_doc (store_id, supplier_id, doc_type, doc_date, parse_status, kind)
				VALUES (?, ?, 'RECEIPT', CAST(? AS DATE), 'PARSED', 'RECEIVE') RETURNING id""", Long.class, storeId,
				supplierId, date);
	}

	private long insertLine(Fixture f, long docId, String qty, String price) {
		return insertLine(f, docId, qty, price, f.product());
	}

	private long insertLine(Fixture f, long docId, String qty, String price, long productId) {
		return jdbc.queryForObject("""
				INSERT INTO purchase_doc_line (store_id, doc_id, line_no, raw_name, qty, unit_price, ingredient_id,
				                               supplier_product_id, status, raw)
				VALUES (?, ?, 1, ?, CAST(? AS NUMERIC), CAST(? AS NUMERIC), ?, ?, 'PENDING', CAST('{}' AS JSONB))
				RETURNING id""", Long.class, f.storeId(), docId, ONION, qty, price, f.onion(), productId);
	}

	private void confirm(Fixture f, long lineId) {
		confirmService.confirm(f.storeId(), List.of(
				new LineDecisionRequest(String.valueOf(lineId), Action.INGREDIENT, String.valueOf(f.onion()), null)));
	}

	private Map<String, Object> item(long itemId) {
		return jdbc.queryForMap("""
				SELECT received_qty, received_unit_price, received_at, auto_received FROM purchase_order_item
				WHERE id = ?""", itemId);
	}

	private Long linkedOrder(long docId) {
		return jdbc.queryForObject("SELECT purchase_order_id FROM purchase_doc WHERE id = ?", Long.class, docId);
	}

	private long priceRows(long productId, String source) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM price_history WHERE supplier_product_id = ? AND source = ?",
				Long.class, productId, source);
	}

	private BigDecimal stock(Fixture f) {
		return stock(f, TODAY);
	}

	private BigDecimal stock(Fixture f, LocalDate asOf) {
		for (InventoryEstimateResponse row : inventoryService.estimate(f.storeId(), asOf)) {
			if (row.ingredientId().equals(String.valueOf(f.onion()))) {
				return row.estimatedQty();
			}
		}
		return fail("재료 %d 의 추정 재고가 없다", f.onion());
	}

	/** 후보가 아니면: 줄은 그대로 · 문서 발주 연결 없음 · 영수증은 ② 로 +2000 (수량 2 포장). */
	private void assertUntouched(Fixture f, long docId, long itemId, Map<String, Object> itemBefore,
			BigDecimal stockBefore) {
		assertThat(linkedOrder(docId)).isNull();
		assertThat(item(itemId)).isEqualTo(itemBefore);
		assertThat(stock(f).subtract(stockBefore)).isEqualByComparingTo("2000.000");
	}

	// ── 연결 · 값 덮기 ────────────────────────────────────────

	@Test
	void link_자동_입고된_줄에_영수증을_확정하면_줄을_잇고_재고는_한_번만() {
		Fixture f = fixture("연결");
		long orderId = insertOrder(f.storeId(), f.supplier(), "SENT", ORDERED_AT);
		long itemId = autoItem(f, orderId, "2", EXPECTED);
		assertThat(item(itemId).get("auto_received")).isEqualTo(true);
		BigDecimal before = stock(f);
		assertThat(before).isEqualByComparingTo("2000.000");
		long docId = insertDoc(f.storeId(), f.supplier(), "2026-10-12");
		long lineId = insertLine(f, docId, "2", "3980");

		confirm(f, lineId);

		Map<String, Object> row = item(itemId);
		assertThat(row.get("auto_received")).isEqualTo(false);
		assertThat((BigDecimal) row.get("received_qty")).isEqualByComparingTo("2");
		assertThat(linkedOrder(docId)).isEqualTo(orderId);
		// 두 번 세면 4000
		assertThat(stock(f)).isEqualByComparingTo("2000.000");
	}

	@Test
	void overwrite_영수증_수량과_단가로_덮고_받은_시각은_문서_날짜_그날_끝_가격_이력은_DOC_1행만() {
		Fixture f = fixture("덮기");
		long orderId = insertOrder(f.storeId(), f.supplier(), "SENT", ORDERED_AT);
		long itemId = autoItem(f, orderId, "10", EXPECTED);
		long recvBefore = priceRows(f.product(), "RECEIVE");
		long docBefore = priceRows(f.product(), "DOC");
		long docId = insertDoc(f.storeId(), f.supplier(), "2026-10-13");
		long lineId = insertLine(f, docId, "8", "11700");

		confirm(f, lineId);

		Map<String, Object> row = item(itemId);
		assertThat((BigDecimal) row.get("received_qty")).isEqualByComparingTo("8");
		assertThat((BigDecimal) row.get("received_unit_price")).isEqualByComparingTo("11700");
		assertThat(((java.sql.Timestamp) row.get("received_at")).toInstant())
				.isEqualTo(OffsetDateTime.of(2026, 10, 13, 23, 59, 59, 999_999_000, ZoneOffset.ofHours(9)).toInstant());
		assertThat(row.get("auto_received")).isEqualTo(false);
		assertThat(linkedOrder(docId)).isEqualTo(orderId);
		assertThat(stock(f)).isEqualByComparingTo("8000.000");
		assertThat(priceRows(f.product(), "DOC") - docBefore).isEqualTo(1);
		assertThat(priceRows(f.product(), "RECEIVE") - recvBefore).isZero();
	}

	// ── 경계: 후보 아님 ───────────────────────────────────────

	@Test
	void boundary_사람이_입고한_줄은_후보가_아니다() {
		Fixture f = fixture("사람");
		long orderId = insertOrder(f.storeId(), f.supplier(), "SENT", ORDERED_AT);
		long itemId = insertItem(f, orderId, "3", EXPECTED);
		receivingService.recordReceipt(f.storeId(), itemId, new BigDecimal("3"), null);
		assertThat(item(itemId).get("auto_received")).isEqualTo(false);
		Map<String, Object> snapshot = item(itemId);
		BigDecimal before = stock(f);
		long docId = insertDoc(f.storeId(), f.supplier(), "2026-10-12");

		confirm(f, insertLine(f, docId, "2", "3980"));

		assertUntouched(f, docId, itemId, snapshot, before);
	}

	@Test
	void boundary_취소한_줄은_후보가_아니다() {
		Fixture f = fixture("취소줄");
		long orderId = insertOrder(f.storeId(), f.supplier(), "SENT", ORDERED_AT);
		long itemId = autoItem(f, orderId, "3", EXPECTED);
		jdbc.update("UPDATE purchase_order_item SET cancelled_at = now() WHERE id = ?", itemId);
		Map<String, Object> snapshot = item(itemId);
		BigDecimal before = stock(f);
		long docId = insertDoc(f.storeId(), f.supplier(), "2026-10-12");

		confirm(f, insertLine(f, docId, "2", "3980"));

		assertUntouched(f, docId, itemId, snapshot, before);
	}

	@Test
	void boundary_CANCELLED_발주서의_줄은_후보가_아니다() {
		Fixture f = fixture("취소서");
		long orderId = insertOrder(f.storeId(), f.supplier(), "SENT", ORDERED_AT);
		long itemId = autoItem(f, orderId, "3", EXPECTED);
		jdbc.update("UPDATE purchase_order SET status = 'CANCELLED' WHERE id = ?", orderId);
		Map<String, Object> snapshot = item(itemId);
		BigDecimal before = stock(f);
		long docId = insertDoc(f.storeId(), f.supplier(), "2026-10-12");

		confirm(f, insertLine(f, docId, "2", "3980"));

		assertUntouched(f, docId, itemId, snapshot, before);
	}

	@Test
	void boundary_다른_거래처_영수증은_잇지_않는다() {
		Fixture f = fixture("거래처");
		long orderId = insertOrder(f.storeId(), f.supplier(), "SENT", ORDERED_AT);
		long itemId = autoItem(f, orderId, "3", EXPECTED);
		long otherSupplier = insertSupplier(f.storeId(), "다른상회");
		long otherProduct = insertProduct(f.storeId(), otherSupplier, f.onion(), "3500");
		Map<String, Object> snapshot = item(itemId);
		BigDecimal before = stock(f);
		long docId = insertDoc(f.storeId(), otherSupplier, "2026-10-12");

		confirm(f, insertLine(f, docId, "2", "3980", otherProduct));

		assertUntouched(f, docId, itemId, snapshot, before);
	}

	@Test
	void boundary_문서_날짜가_예정일_3일_밖이면_잇지_않고_3일_안쪽_끝은_잇는다() {
		Fixture f = fixture("창");
		long orderId = insertOrder(f.storeId(), f.supplier(), "SENT", ORDERED_AT);
		long itemId = autoItem(f, orderId, "3", EXPECTED);
		Map<String, Object> snapshot = item(itemId);
		BigDecimal before = stock(f);
		long farDoc = insertDoc(f.storeId(), f.supplier(), "2026-10-16");

		confirm(f, insertLine(f, farDoc, "2", "3980"));

		assertUntouched(f, farDoc, itemId, snapshot, before);

		long edgeDoc = insertDoc(f.storeId(), f.supplier(), "2026-10-15");
		confirm(f, insertLine(f, edgeDoc, "2", "3980"));

		assertThat(linkedOrder(edgeDoc)).isEqualTo(orderId);
		assertThat(item(itemId).get("auto_received")).isEqualTo(false);
	}

	// ── 경계: 순서 · 재확정 ───────────────────────────────────

	@Test
	void order_이번_주_열린_줄이_먼저_이어지고_지난_주_자동_입고_줄은_그대로_재확정은_409() {
		Fixture f = fixture("순서");
		long older = insertOrder(f.storeId(), f.supplier(), "SENT", ORDERED_AT.minusDays(7));
		long autoId = autoItem(f, older, "3", LocalDate.of(2026, 10, 13));
		long newer = insertOrder(f.storeId(), f.supplier(), "SENT", ORDERED_AT);
		long openId = insertItem(f, newer, "2", EXPECTED);
		Map<String, Object> autoBefore = item(autoId);
		long docId = insertDoc(f.storeId(), f.supplier(), "2026-10-12");
		long lineId = insertLine(f, docId, "2", "3980");

		confirm(f, lineId);

		assertThat(linkedOrder(docId)).isEqualTo(newer);
		assertThat(item(openId).get("received_at")).isNotNull();
		assertThat(item(autoId)).isEqualTo(autoBefore);

		assertThatThrownBy(() -> confirm(f, lineId)).isInstanceOf(BusinessException.class)
				.satisfies(e -> assertThat(((BusinessException) e).getErrorCode().getCode())
						.isEqualTo("RECEIPT_LINE_ALREADY_DECIDED"));
		assertThat(item(autoId)).isEqualTo(autoBefore);
		assertThat((BigDecimal) item(openId).get("received_qty")).isEqualByComparingTo("2");
	}

	@Test
	void order_자동_입고_줄이_둘이면_예정일이_문서_날짜에_가까운_쪽() {
		Fixture f = fixture("가까운");
		long first = insertOrder(f.storeId(), f.supplier(), "SENT", ORDERED_AT.minusDays(1));
		long farItem = autoItem(f, first, "3", LocalDate.of(2026, 10, 14));
		long second = insertOrder(f.storeId(), f.supplier(), "SENT", ORDERED_AT);
		long nearItem = autoItem(f, second, "3", EXPECTED);
		Map<String, Object> farBefore = item(farItem);
		long docId = insertDoc(f.storeId(), f.supplier(), "2026-10-12");

		confirm(f, insertLine(f, docId, "2", "3980"));

		assertThat(linkedOrder(docId)).isEqualTo(second);
		assertThat(item(nearItem).get("auto_received")).isEqualTo(false);
		assertThat(item(farItem)).isEqualTo(farBefore);
	}

	// ── 동시성 ────────────────────────────────────────────────

	@Test
	void race_다른_수동_덮어쓰기가_먼저_끝난_줄은_overwriteAutoReceipt_가_건너뛴다() {
		Fixture f = fixture("경쟁");
		long orderId = insertOrder(f.storeId(), f.supplier(), "SENT", ORDERED_AT);
		long itemId = autoItem(f, orderId, "3", EXPECTED);
		// 먼저 온 쪽이 이 줄을 덮은 상태(auto_received 가 false 로 바뀜)
		jdbc.update("UPDATE purchase_order_item SET auto_received = false, received_qty = 1 WHERE id = ?", itemId);
		Map<String, Object> snapshot = item(itemId);

		boolean applied = receivingService.overwriteAutoReceipt(f.storeId(), itemId, new BigDecimal("2"),
				new BigDecimal("3980"), EXPECTED);

		assertThat(applied).isFalse();
		assertThat(item(itemId)).isEqualTo(snapshot);
	}


	@Test
	void order_자동_입고가_없으면_예정일이_멀어도_먼저_만든_발주서의_열린_줄에_잇는다() {
		Fixture f = fixture("평소순서");
		// 먼저 만든 발주서는 예정일이 멀고(10/25), 나중 발주서는 문서 날짜에 가깝다(10/12) — 옛 순서(만든 순)가 이긴다
		long first = insertOrder(f.storeId(), f.supplier(), "SENT", OffsetDateTime.of(2026, 10, 1, 9, 0, 0, 0, ZoneOffset.ofHours(9)));
		long firstItem = insertItem(f, first, "2", LocalDate.of(2026, 10, 25));
		long second = insertOrder(f.storeId(), f.supplier(), "SENT", OffsetDateTime.of(2026, 10, 5, 9, 0, 0, 0, ZoneOffset.ofHours(9)));
		long secondItem = insertItem(f, second, "2", LocalDate.of(2026, 10, 12));
		long docId = insertDoc(f.storeId(), f.supplier(), "2026-10-12");

		confirm(f, insertLine(f, docId, "2", "3980"));

		assertThat(linkedOrder(docId)).isEqualTo(first);
		assertThat(item(firstItem).get("received_at")).isNotNull();
		assertThat(item(secondItem).get("received_at")).isNull();
	}

	// ── 배치 경로 · 가게 경계 ─────────────────────────────────

	@Test
	void batch_배치가_입고한_줄에_영수증을_확정하면_줄을_잇고_재고는_한_번만() {
		Fixture f = fixture("배치");
		LocalDate expected = LocalDate.of(2026, 10, 20);
		long orderId = insertOrder(f.storeId(), f.supplier(), "SENT", ORDERED_AT);
		long itemId = insertItem(f, orderId, "10", expected);
		Clock clock = Clock.fixed(OffsetDateTime.of(2026, 10, 21, 0, 10, 0, 0, ZoneOffset.ofHours(9)).toInstant(),
				ZoneId.of("Asia/Seoul"));

		AutoReceiveService.Result result = new AutoReceiveService(namedJdbc, new StoreClock(clock), receivingService)
				.receiveDue(f.storeId());

		assertThat(result.received()).isEqualTo(1);
		assertThat(item(itemId).get("auto_received")).isEqualTo(true);
		LocalDate asOf = LocalDate.of(2026, 10, 21);
		assertThat(stock(f, asOf)).isEqualByComparingTo("10000.000");
		long docId = insertDoc(f.storeId(), f.supplier(), "2026-10-20");
		long lineId = insertLine(f, docId, "10", "3980");

		confirm(f, lineId);

		Map<String, Object> row = item(itemId);
		assertThat(row.get("auto_received")).isEqualTo(false);
		assertThat((BigDecimal) row.get("received_qty")).isEqualByComparingTo("10");
		assertThat(linkedOrder(docId)).isEqualTo(orderId);
		assertThat(stock(f, asOf)).isEqualByComparingTo("10000.000");
	}

	@Test
	void store_다른_가게의_같은_조건_자동_입고_줄은_이_가게_영수증이_건드리지_않는다() {
		Fixture mine = fixture("내가게");
		Fixture other = fixture("남가게");
		long otherOrder = insertOrder(other.storeId(), other.supplier(), "SENT", ORDERED_AT);
		long otherItem = autoItem(other, otherOrder, "3", EXPECTED);
		Map<String, Object> otherBefore = item(otherItem);
		BigDecimal otherStock = stock(other);
		BigDecimal before = stock(mine);
		long docId = insertDoc(mine.storeId(), mine.supplier(), "2026-10-12");

		confirm(mine, insertLine(mine, docId, "2", "3980"));

		assertThat(linkedOrder(docId)).isNull();
		assertThat(item(otherItem)).isEqualTo(otherBefore);
		assertThat(stock(other)).isEqualByComparingTo(otherStock);
		assertThat(stock(mine).subtract(before)).isEqualByComparingTo("2000.000");
	}

	// ── 동시성 ────────────────────────────────────────────────

	@Test
	void race_confirm_첫_영수증이_줄을_잠근_채면_두_번째_confirm_은_기다렸다가_그_줄을_건너뛰고_영수증은_2로_센다() throws Exception {
		Fixture f = fixture("경쟁확정");
		long orderId = insertOrder(f.storeId(), f.supplier(), "SENT", ORDERED_AT);
		long itemId = autoItem(f, orderId, "3", EXPECTED);
		long docId = insertDoc(f.storeId(), f.supplier(), "2026-10-12");
		long lineId = insertLine(f, docId, "2", "3980");
		TransactionTemplate tx = new TransactionTemplate(txManager);
		java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
		java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
		// 첫 영수증 확정이 줄을 잠그고 덮은 채 아직 커밋하지 않은 상태를 만든다
		CompletableFuture<Void> first = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
			jdbc.queryForObject("SELECT id FROM purchase_order_item WHERE id = ? FOR UPDATE", Long.class, itemId);
			jdbc.update("""
					UPDATE purchase_order_item SET auto_received = false, received_qty = 1, received_unit_price = 4000,
					received_at = now() WHERE id = ?""", itemId);
			locked.countDown();
			try {
				if (!release.await(30, TimeUnit.SECONDS)) {
					throw new IllegalStateException("해제 신호 없음");
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}));
		assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
		CompletableFuture<Void> second = CompletableFuture.runAsync(() -> confirm(f, lineId));
		// 두 번째 confirm 이 줄 잠금 대기에 들어갈 때까지 기다린다
		long deadline = System.currentTimeMillis() + 20_000;
		while (jdbc.queryForObject("SELECT COUNT(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'", Long.class) == 0
				&& System.currentTimeMillis() < deadline) {
			Thread.sleep(50);
		}
		assertThat(second.isDone()).isFalse();
		release.countDown();
		first.get(30, TimeUnit.SECONDS);
		second.get(30, TimeUnit.SECONDS);

		Map<String, Object> row = item(itemId);
		assertThat((BigDecimal) row.get("received_qty")).isEqualByComparingTo("1");
		assertThat((BigDecimal) row.get("received_unit_price")).isEqualByComparingTo("4000");
		assertThat(row.get("auto_received")).isEqualTo(false);
		assertThat(linkedOrder(docId)).isNull();
		// 줄 ①(1 포장 = 1000)은 첫 영수증이 덮은 값, 두 번째 영수증은 ② 로 +2000
		assertThat(stock(f)).isEqualByComparingTo("3000.000");
	}

	// ── 마이그레이션 ──────────────────────────────────────────

	@Test
	void migration_auto_received_기본값은_false_이고_NOT_NULL() {
		Map<String, Object> column = jdbc.queryForMap("""
				SELECT column_default, is_nullable FROM information_schema.columns
				WHERE table_name = 'purchase_order_item' AND column_name = 'auto_received'""");
		assertThat((String) column.get("column_default")).contains("false");
		assertThat(column.get("is_nullable")).isEqualTo("NO");
		Fixture f = fixture("기본값");
		long orderId = insertOrder(f.storeId(), f.supplier(), "SENT", ORDERED_AT);
		long itemId = insertItem(f, orderId, "3", EXPECTED);
		assertThat(item(itemId).get("auto_received")).isEqualTo(false);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order_item WHERE auto_received IS NULL",
				Long.class)).isZero();
	}
}
