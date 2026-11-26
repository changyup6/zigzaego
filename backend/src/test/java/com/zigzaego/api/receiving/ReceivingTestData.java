package com.zigzaego.api.receiving;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 입고 확인 테스트 공용 행 넣기. 발주 70 코드가 아직 없어 purchase_order · purchase_order_item 을 표에 직접 넣는다.
 * 시각은 {@link com.zigzaego.api.support.FixedClockConfig} 의 오늘(서울 2026-10-14 12:00) 기준으로 준다.
 */
final class ReceivingTestData {

	private ReceivingTestData() {
	}

	static long insertSupplier(JdbcTemplate jdbc, long storeId, String name) {
		return jdbc.queryForObject("INSERT INTO supplier (store_id, name) VALUES (?, ?) RETURNING id",
				Long.class, storeId, name);
	}

	/** 거래처 상품. packUnit · baseQtyPerPack · price 는 null 이면 비운다. */
	static long insertProduct(JdbcTemplate jdbc, long storeId, long supplierId, long ingredientId, String name,
			String packUnit, BigDecimal baseQtyPerPack, BigDecimal price) {
		return jdbc.queryForObject("""
				INSERT INTO supplier_product (store_id, supplier_id, ingredient_id, product_name, pack_unit,
				                              base_qty_per_pack, price)
				VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
				""", Long.class, storeId, supplierId, ingredientId, name, packUnit, baseQtyPerPack, price);
	}

	/** 발주서 한 장. status = DRAFT · SENT · CANCELLED. */
	static long insertOrder(JdbcTemplate jdbc, long storeId, long supplierId, String status, OffsetDateTime createdAt) {
		return jdbc.queryForObject(
				"INSERT INTO purchase_order (store_id, supplier_id, status, created_at) VALUES (?, ?, ?, ?) RETURNING id",
				Long.class, storeId, supplierId, status, createdAt);
	}

	/** 아직 입고 확인 안 된 발주 줄. */
	static long insertItem(JdbcTemplate jdbc, long storeId, long orderId, long productId, long ingredientId,
			BigDecimal qty, LocalDate expectedDate) {
		return jdbc.queryForObject("""
				INSERT INTO purchase_order_item (store_id, po_id, supplier_product_id, ingredient_id, qty, expected_date)
				VALUES (?, ?, ?, ?, ?, ?) RETURNING id
				""", Long.class, storeId, orderId, productId, ingredientId, qty, expectedDate);
	}

	static void markReceived(JdbcTemplate jdbc, long itemId, BigDecimal qty, BigDecimal unitPrice,
			OffsetDateTime receivedAt) {
		jdbc.update("UPDATE purchase_order_item SET received_qty = ?, received_unit_price = ?, received_at = ? WHERE id = ?",
				qty, unitPrice, receivedAt, itemId);
	}

	static void markCancelled(JdbcTemplate jdbc, long itemId, OffsetDateTime cancelledAt) {
		jdbc.update("UPDATE purchase_order_item SET cancelled_at = ? WHERE id = ?", cancelledAt, itemId);
	}

	/** 승인된 추천 한 건(추천량은 재료 기준 단위)을 넣고 발주 줄에 잇는다. */
	static void linkRecommendation(JdbcTemplate jdbc, long storeId, long ingredientId, long itemId,
			LocalDate runDate, BigDecimal recommendedQty) {
		long recommendationId = jdbc.queryForObject("""
				INSERT INTO recommendation (store_id, ingredient_id, run_date, est_stock, on_order, lead_time_demand,
				                            safety_stock, reorder_point, recommended_qty, status, final_qty,
				                            forecast_model, ss_method)
				VALUES (?, ?, ?, 0, 0, 0, 0, 0, ?, 'APPROVED', ?, 'dow_avg', 'DAYS_N') RETURNING id
				""", Long.class, storeId, ingredientId, runDate, recommendedQty, recommendedQty);
		jdbc.update("UPDATE purchase_order_item SET recommendation_id = ? WHERE id = ?", recommendationId, itemId);
	}

	static int priceRowCount(JdbcTemplate jdbc, long productId) {
		return jdbc.queryForObject("SELECT count(*) FROM price_history WHERE supplier_product_id = ?",
				Integer.class, productId);
	}
}
