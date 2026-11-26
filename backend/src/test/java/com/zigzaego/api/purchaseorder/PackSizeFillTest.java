package com.zigzaego.api.purchaseorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.auth.AuthTestSupport;
import com.zigzaego.api.inventory.dto.InventoryEstimateResponse;
import com.zigzaego.api.inventory.service.InventoryEstimateService;
import com.zigzaego.api.purchasedoc.dto.LineDecisionRequest;
import com.zigzaego.api.purchasedoc.dto.LineDecisionRequest.Action;
import com.zigzaego.api.purchasedoc.service.ReceiptConfirmService;
import com.zigzaego.api.receiving.dto.ReceiveRequest;
import com.zigzaego.api.receiving.dto.ReceivingFeedResponse.IncomingLine;
import com.zigzaego.api.receiving.service.ReceivingService;
import com.zigzaego.api.supplier.dto.SupplierProductUpdateRequest;
import com.zigzaego.api.supplier.service.SupplierProductService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 포장 환산을 모를 때 만든 발주 줄은 포장 환산이 나중에 채워져도 기준 단위 양이 그대로다 (S15P31A308-301, DECISIONS #85).
 * 예전에는 qty(기준 단위 양 2000)가 × 포장 크기(1000)로 읽혀 입고 예정이 2,000,000 g 이 됐다.
 * 채우는 세 경로(입고 확인 포장 크기 입력 · 거래처 상품 수정 · 영수증 확정)를 각각 본다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PackSizeFillTest {

	private static final String ONION = "대파 1단";
	private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
	private static final OffsetDateTime COUNTED_AT = OffsetDateTime.of(2026, 10, 10, 10, 0, 0, 0, ZoneOffset.ofHours(9));
	private static final OffsetDateTime ORDERED_AT = OffsetDateTime.of(2026, 10, 11, 9, 0, 0, 0, ZoneOffset.ofHours(9));

	@Autowired
	private ReceivingService receivingService;

	@Autowired
	private SupplierProductService productService;

	@Autowired
	private ReceiptConfirmService confirmService;

	@Autowired
	private InventoryEstimateService inventoryService;

	@Autowired
	private JdbcTemplate jdbc;

	/** 실사 0 g 의 대파, OO상회 "대파 1단" 상품 — 포장 환산(packSize 인자) · 단가 3500. */
	private record Fixture(long storeId, long onion, long supplier, long product, long orderId) {
	}

	private Fixture fixture(String label, String packSize) {
		long storeId = AuthTestSupport.insertStore(jdbc, label + "-" + System.nanoTime());
		long onion = jdbc.queryForObject("""
				INSERT INTO ingredient (store_id, name, unit, unit_status, price_sensitive, sourcing_type, kind, storage_type,
				                        storage_location, category_id, category_source)
				VALUES (?, '대파', 'G', 'CONFIRMED', false, 'GENERIC', 'BOM', 'CHILLED', 'FRIDGE',
				        (SELECT id FROM ingredient_category WHERE store_id = ? AND code = 'OTHER'), 'AUTO') RETURNING id""",
				Long.class, storeId, storeId);
		long supplier = jdbc.queryForObject("INSERT INTO supplier (store_id, name) VALUES (?, 'OO상회') RETURNING id",
				Long.class, storeId);
		long product = jdbc.queryForObject("""
				INSERT INTO supplier_product (store_id, supplier_id, ingredient_id, product_name, base_qty_per_pack, price)
				VALUES (?, ?, ?, ?, CAST(? AS NUMERIC), 3500) RETURNING id""", Long.class, storeId, supplier, onion, ONION,
				packSize);
		jdbc.update("INSERT INTO stock_count (store_id, ingredient_id, counted_at, qty) VALUES (?, ?, ?, 0)", storeId,
				onion, COUNTED_AT);
		long orderId = jdbc.queryForObject(
				"INSERT INTO purchase_order (store_id, supplier_id, status, created_at) VALUES (?, ?, 'SENT', ?) RETURNING id",
				Long.class, storeId, supplier, ORDERED_AT);
		return new Fixture(storeId, onion, supplier, product, orderId);
	}

	/** 포장 환산을 모를 때 PurchaseOrderService.toPackQty 가 쓰는 값 = 기준 단위 양 그대로. */
	private long insertItem(Fixture f, String qty) {
		return jdbc.queryForObject("""
				INSERT INTO purchase_order_item (store_id, po_id, supplier_product_id, ingredient_id, qty, expected_date)
				VALUES (?, ?, ?, ?, CAST(? AS NUMERIC), DATE '2026-10-12') RETURNING id""", Long.class, f.storeId(),
				f.orderId(), f.product(), f.onion(), qty);
	}

	private BigDecimal itemQty(long itemId) {
		return jdbc.queryForObject("SELECT qty FROM purchase_order_item WHERE id = ?", BigDecimal.class, itemId);
	}

	/** 입고 탭이 보여주는 입고 예정량(기준 단위). */
	private BigDecimal incomingQty(Fixture f, long itemId) {
		return receivingService.feed(f.storeId()).incoming().stream()
				.filter(line -> line.itemId().equals(String.valueOf(itemId)))
				.map(IncomingLine::qty)
				.findFirst().orElseGet(() -> fail("입고 예정에 줄 %d 이 없다", itemId));
	}

	/** 추천 엔진이 읽는 입고 예정량과 같은 식 (RecommendationService ON_ORDER_SQL). */
	private BigDecimal onOrder(Fixture f) {
		return jdbc.queryForObject("""
				SELECT COALESCE(SUM(poi.qty * COALESCE(sp.base_qty_per_pack, 1)), 0)
				FROM purchase_order_item poi JOIN supplier_product sp ON sp.id = poi.supplier_product_id
				WHERE poi.store_id = ? AND poi.received_at IS NULL AND poi.cancelled_at IS NULL""", BigDecimal.class,
				f.storeId());
	}

	private BigDecimal stock(Fixture f) {
		for (InventoryEstimateResponse row : inventoryService.estimate(f.storeId(), TODAY)) {
			if (row.ingredientId().equals(String.valueOf(f.onion()))) {
				return row.estimatedQty();
			}
		}
		return fail("재료 %d 의 추정 재고가 없다", f.onion());
	}

	@Test
	void receive_입고_확인에서_포장_크기를_채워도_다른_입고_예정_줄의_기준_단위_양은_그대로() {
		Fixture f = fixture("입고", null);
		long first = insertItem(f, "2000");
		long second = insertItem(f, "500");
		assertThat(onOrder(f)).isEqualByComparingTo("2500");

		receivingService.receive(f.storeId(), String.valueOf(f.orderId()), String.valueOf(first),
				new ReceiveRequest(new BigDecimal("2000"), null, new BigDecimal("1000")));

		// 예전: 두 번째 줄이 500 × 1000 = 500,000 g 으로 읽혔다
		assertThat(itemQty(second)).isEqualByComparingTo("0.5");
		assertThat(incomingQty(f, second)).isEqualByComparingTo("500");
		assertThat(onOrder(f)).isEqualByComparingTo("500");
		assertThat(itemQty(first)).isEqualByComparingTo("2");
		assertThat(jdbc.queryForObject("SELECT received_qty FROM purchase_order_item WHERE id = ?", BigDecimal.class,
				first)).isEqualByComparingTo("2");
		assertThat(stock(f)).isEqualByComparingTo("2000.000");
	}

	@Test
	void update_거래처_상품_수정으로_포장_환산을_채우면_줄을_포장_개수로() {
		Fixture f = fixture("수정", null);
		long open = insertItem(f, "3000");
		long received = insertItem(f, "1500");
		jdbc.update("""
				UPDATE purchase_order_item SET received_qty = 1500, received_unit_price = 4, received_at = ?
				WHERE id = ?""", ORDERED_AT.plusDays(1), received);
		BigDecimal stockBefore = stock(f);
		assertThat(stockBefore).isEqualByComparingTo("1500.000");

		productService.update(f.storeId(), String.valueOf(f.product()),
				new SupplierProductUpdateRequest(null, null, null, null, null, new BigDecimal("1000"), null));

		assertThat(itemQty(open)).isEqualByComparingTo("3");
		assertThat(incomingQty(f, open)).isEqualByComparingTo("3000");
		assertThat(onOrder(f)).isEqualByComparingTo("3000");
		// 받은 양도 같은 기준 단위 양, 단가는 기준 단위 1 값(4원) → 포장 1개 값(4000원)
		assertThat(stock(f)).isEqualByComparingTo(stockBefore);
		assertThat(jdbc.queryForObject("SELECT received_unit_price FROM purchase_order_item WHERE id = ?",
				BigDecimal.class, received)).isEqualByComparingTo("4000");
	}

	@Test
	void update_이미_있던_포장_환산을_고치면_줄은_그대로() {
		Fixture f = fixture("정정", "1000");
		long item = insertItem(f, "2"); // 대파 2단으로 주문했다

		productService.update(f.storeId(), String.valueOf(f.product()),
				new SupplierProductUpdateRequest(null, null, null, null, null, new BigDecimal("500"), null));

		assertThat(itemQty(item)).isEqualByComparingTo("2");
		assertThat(incomingQty(f, item)).isEqualByComparingTo("1000");
	}

	@Test
	void confirm_영수증_확정으로_포장_환산을_채워도_입고는_기준_단위_양_그대로_한_번만() {
		Fixture f = fixture("영수증", null);
		long item = insertItem(f, "2000");
		long docId = jdbc.queryForObject("""
				INSERT INTO purchase_doc (store_id, supplier_id, doc_type, doc_date, parse_status, kind)
				VALUES (?, ?, 'RECEIPT', DATE '2026-10-12', 'PARSED', 'RECEIVE') RETURNING id""", Long.class,
				f.storeId(), f.supplier());
		long lineId = jdbc.queryForObject("""
				INSERT INTO purchase_doc_line (store_id, doc_id, line_no, raw_name, qty, unit_price, ingredient_id,
				                               supplier_product_id, status, raw)
				VALUES (?, ?, 1, ?, 2, 3980, ?, ?, 'PENDING', CAST('{}' AS JSONB)) RETURNING id""", Long.class,
				f.storeId(), docId, ONION, f.onion(), f.product());

		confirmService.confirm(f.storeId(), List.of(new LineDecisionRequest(String.valueOf(lineId), Action.INGREDIENT,
				String.valueOf(f.onion()), new BigDecimal("1000"))));

		assertThat(itemQty(item)).isEqualByComparingTo("2");
		assertThat(jdbc.queryForObject("SELECT received_qty FROM purchase_order_item WHERE id = ?", BigDecimal.class,
				item)).isEqualByComparingTo("2");
		assertThat(stock(f)).isEqualByComparingTo("2000.000");
	}
}
