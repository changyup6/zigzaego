package com.zigzaego.api.inventory;

import com.zigzaego.api.auth.AuthTestSupport;
import com.zigzaego.api.support.StoreFixtures;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;

/** 추정 재고 테스트 공용: V1 표에 NOT NULL 칸만 채워 한 줄씩 넣는다. 가게 시간대는 기본값(Asia/Seoul). */
final class InventoryTestData {

	private InventoryTestData() {
	}

	static long insertStore(JdbcTemplate jdbc, String name) {
		String unique = name + "-" + System.nanoTime();
		return AuthTestSupport.insertStore(jdbc, unique);
	}

	static long insertMenu(JdbcTemplate jdbc, long storeId, String name) {
		return jdbc.queryForObject(
				"INSERT INTO menu (store_id, name, kind, source) VALUES (?, ?, 'MAIN', 'POS') RETURNING id",
				Long.class, storeId, name);
	}

	/** kind = BOM · ONE_TO_ONE · CYCLE, unit = EA · G · ML */
	static long insertIngredient(JdbcTemplate jdbc, long storeId, String name, String unit, String kind) {
		return jdbc.queryForObject(
				"INSERT INTO ingredient (store_id, name, unit, price_sensitive, sourcing_type, kind, storage_type, storage_location,"
						+ " category_id, category_source)"
						+ " VALUES (?, ?, ?, false, 'GENERIC', ?, 'AMBIENT', 'SHELF', " + StoreFixtures.OTHER_CATEGORY_SQL
						+ ", 'AUTO') RETURNING id",
				Long.class, storeId, name, unit, kind, storeId);
	}

	static void insertRecipe(JdbcTemplate jdbc, long storeId, long menuId, long ingredientId, String qtyPerServing) {
		jdbc.update(
				"INSERT INTO recipe (store_id, menu_id, ingredient_id, qty_per_serving) VALUES (?, ?, ?, CAST(? AS NUMERIC))",
				storeId, menuId, ingredientId, qtyPerServing);
	}

	static void insertSale(JdbcTemplate jdbc, long storeId, LocalDate date, long menuId, String qty) {
		jdbc.update(
				"INSERT INTO pos_sale (store_id, sold_date, menu_id, qty, channel) VALUES (?, ?, ?, CAST(? AS NUMERIC), 'HALL')",
				storeId, date, menuId, qty);
	}

	static void insertCount(JdbcTemplate jdbc, long storeId, long ingredientId, OffsetDateTime countedAt, String qty) {
		jdbc.update(
				"INSERT INTO stock_count (store_id, ingredient_id, counted_at, qty) VALUES (?, ?, ?, CAST(? AS NUMERIC))",
				storeId, ingredientId, countedAt, qty);
	}

	static void insertClosedDay(JdbcTemplate jdbc, long storeId, LocalDate date) {
		jdbc.update("INSERT INTO store_closed_day (store_id, closed_date) VALUES (?, ?)", storeId, date);
	}

	static long insertSupplier(JdbcTemplate jdbc, long storeId, String name) {
		return jdbc.queryForObject(
				"INSERT INTO supplier (store_id, name) VALUES (?, ?) RETURNING id", Long.class, storeId,
				name + "-" + System.nanoTime());
	}

	/** basePerPack: 포장 1개 = 기준 단위 몇. 모르면 null */
	static long insertSupplierProduct(JdbcTemplate jdbc, long storeId, long supplierId, long ingredientId,
			String basePerPack) {
		return jdbc.queryForObject(
				"INSERT INTO supplier_product (store_id, supplier_id, ingredient_id, product_name, base_qty_per_pack)"
						+ " VALUES (?, ?, ?, ?, CAST(? AS NUMERIC)) RETURNING id",
				Long.class, storeId, supplierId, ingredientId, "상품-" + System.nanoTime(), basePerPack);
	}

	static long insertPurchaseOrder(JdbcTemplate jdbc, long storeId, long supplierId) {
		return jdbc.queryForObject(
				"INSERT INTO purchase_order (store_id, supplier_id) VALUES (?, ?) RETURNING id", Long.class, storeId,
				supplierId);
	}

	/** 입고 확인된 발주 줄. receivedAt 이 null 이면 아직 입고 전. */
	static void insertPoItem(JdbcTemplate jdbc, long storeId, long poId, long supplierProductId, long ingredientId,
			String receivedQty, OffsetDateTime receivedAt) {
		jdbc.update(
				"INSERT INTO purchase_order_item (store_id, po_id, supplier_product_id, ingredient_id, qty, received_qty, received_at)"
						+ " VALUES (?, ?, ?, ?, CAST(? AS NUMERIC), CAST(? AS NUMERIC), ?)",
				storeId, poId, supplierProductId, ingredientId, receivedQty, receivedQty, receivedAt);
	}

	/** kind = PAST · RECEIVE · null, parseStatus = CONFIRMED 등, poId = 발주서 연결(보통 null) */
	static long insertDoc(JdbcTemplate jdbc, long storeId, LocalDate docDate, String kind, String parseStatus, Long poId) {
		return jdbc.queryForObject(
				"INSERT INTO purchase_doc (store_id, doc_date, kind, parse_status, purchase_order_id)"
						+ " VALUES (?, ?, ?, ?, ?) RETURNING id",
				Long.class, storeId, docDate, kind, parseStatus, poId);
	}

	/** status = CONFIRMED · PENDING · IGNORED. supplierProductId 가 있으면 포장 단위 환산이 걸린다 */
	static void insertDocLine(JdbcTemplate jdbc, long storeId, long docId, int lineNo, Long ingredientId, String qty,
			String status, Long supplierProductId) {
		jdbc.update(
				"INSERT INTO purchase_doc_line (store_id, doc_id, line_no, raw_name, qty, status, ingredient_id, supplier_product_id)"
						+ " VALUES (?, ?, ?, ?, CAST(? AS NUMERIC), ?, ?, ?)",
				storeId, docId, lineNo, "줄" + lineNo, qty, status, ingredientId, supplierProductId);
	}
}
