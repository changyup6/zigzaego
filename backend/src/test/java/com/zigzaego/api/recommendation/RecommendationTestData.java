package com.zigzaego.api.recommendation;

import com.zigzaego.api.auth.AuthTestSupport;
import com.zigzaego.api.ingredient.entity.IngredientKind;
import com.zigzaego.api.ingredient.entity.StorageLocation;
import com.zigzaego.api.ingredient.entity.StorageType;
import com.zigzaego.api.support.StoreFixtures;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;

/** 추천 테스트 공용: V1 표에 NOT NULL 칸만 채워 한 줄씩 넣는다. 가게 시간대는 기본값(Asia/Seoul). */
final class RecommendationTestData {

	private RecommendationTestData() {
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

	/** kind = BOM · ONE_TO_ONE · CYCLE, storage = FROZEN · AMBIENT · CHILLED · FRESH */
	static long insertIngredient(JdbcTemplate jdbc, long storeId, String name, String unit, String kind, String storage,
			String safetyDays, boolean priceSensitive) {
		return jdbc.queryForObject(
				"INSERT INTO ingredient (store_id, name, unit, price_sensitive, sourcing_type, kind, storage_type,"
						+ " storage_location, safety_days, category_id, category_source)"
						+ " VALUES (?, ?, ?, ?, 'GENERIC', ?, ?, ?, CAST(? AS NUMERIC), " + StoreFixtures.OTHER_CATEGORY_SQL
						+ ", 'AUTO') RETURNING id",
				Long.class, storeId, name, unit, priceSensitive, kind, storage,
				StorageLocation.defaultFor(IngredientKind.valueOf(kind), storage == null ? null : StorageType.valueOf(storage))
						.name(),
				safetyDays, storeId);
	}

	/** source = MANUAL · AI 등 (AI = 추정값) */
	static void insertRecipe(JdbcTemplate jdbc, long storeId, long menuId, long ingredientId, String qtyPerServing,
			String source) {
		jdbc.update(
				"INSERT INTO recipe (store_id, menu_id, ingredient_id, qty_per_serving, source)"
						+ " VALUES (?, ?, ?, CAST(? AS NUMERIC), ?)",
				storeId, menuId, ingredientId, qtyPerServing, source);
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

	/** leadTime · cycle 은 null 허용 (영수증으로 생긴 거래처). */
	static long insertSupplier(JdbcTemplate jdbc, long storeId, String name, Integer leadTimeDays, Integer cycleDays) {
		return jdbc.queryForObject(
				"INSERT INTO supplier (store_id, name, lead_time_days, order_cycle_days) VALUES (?, ?, ?, ?) RETURNING id",
				Long.class, storeId, name + "-" + System.nanoTime(), leadTimeDays, cycleDays);
	}

	/** deliveryDays: Postgres 배열 리터럴 (예 "{MON,WED,FRI}"), 모르면 null. cutoff: 주문 마감 "HH:mm", 모르면 null */
	static long insertSupplier(JdbcTemplate jdbc, long storeId, String name, Integer leadTimeDays, Integer cycleDays,
			String deliveryDays, String cutoff) {
		return jdbc.queryForObject(
				"INSERT INTO supplier (store_id, name, lead_time_days, order_cycle_days, delivery_days, cutoff_time)"
						+ " VALUES (?, ?, ?, ?, CAST(? AS text[]), CAST(? AS time)) RETURNING id",
				Long.class, storeId, name + "-" + System.nanoTime(), leadTimeDays, cycleDays, deliveryDays, cutoff);
	}

	static void insertClosedDay(JdbcTemplate jdbc, long storeId, LocalDate date) {
		jdbc.update("INSERT INTO store_closed_day (store_id, closed_date) VALUES (?, ?)", storeId, date);
	}

	/** basePerPack: 포장 1개 = 기준 단위 몇. moq 는 포장 단위. 둘 다 모르면 null */
	static long insertSupplierProduct(JdbcTemplate jdbc, long storeId, long supplierId, long ingredientId,
			String basePerPack, String moq) {
		return jdbc.queryForObject(
				"INSERT INTO supplier_product (store_id, supplier_id, ingredient_id, product_name, base_qty_per_pack, moq)"
						+ " VALUES (?, ?, ?, ?, CAST(? AS NUMERIC), CAST(? AS NUMERIC)) RETURNING id",
				Long.class, storeId, supplierId, ingredientId, "상품-" + System.nanoTime(), basePerPack, moq);
	}

	static void setPrimaryProduct(JdbcTemplate jdbc, long storeId, long ingredientId, long productId) {
		jdbc.update("UPDATE ingredient SET primary_supplier_product_id = ? WHERE id = ? AND store_id = ?",
				productId, ingredientId, storeId);
	}

	/** 보낸(SENT) 발주서 — 입고 예정(onOrder)은 보낸 발주서만 센다 (S15P31A308-200). */
	static long insertPurchaseOrder(JdbcTemplate jdbc, long storeId, long supplierId) {
		return jdbc.queryForObject(
				"INSERT INTO purchase_order (store_id, supplier_id, status, sent_at) VALUES (?, ?, 'SENT', now()) RETURNING id",
				Long.class, storeId, supplierId);
	}

	/** 아직 입고 확인 안 된 발주 줄(입고 예정). qty 는 포장 단위. */
	static void insertPendingPoItem(JdbcTemplate jdbc, long storeId, long poId, long supplierProductId,
			long ingredientId, String qty) {
		jdbc.update(
				"INSERT INTO purchase_order_item (store_id, po_id, supplier_product_id, ingredient_id, qty)"
						+ " VALUES (?, ?, ?, ?, CAST(? AS NUMERIC))",
				storeId, poId, supplierProductId, ingredientId, qty);
	}

	/** 그날 서울 12:00 에 본 포장 1개 값(원). 가격 지수 입력 (RECEIVE · DOC · MANUAL 만 센다). */
	static void insertPriceHistory(JdbcTemplate jdbc, long storeId, long supplierProductId, LocalDate day, int price,
			String source) {
		jdbc.update(
				"INSERT INTO price_history (store_id, supplier_product_id, price, unit, observed_at, source)"
						+ " VALUES (?, ?, ?, 'PACK', ?, ?)",
				storeId, supplierProductId, price, day.atTime(12, 0).atOffset(ZoneOffset.ofHours(9)), source);
	}

	static void insertForecast(JdbcTemplate jdbc, long storeId, long menuId, LocalDate targetDate, String qty) {
		jdbc.update(
				"INSERT INTO forecast (store_id, menu_id, target_date, predicted_qty, model, channel)"
						+ " VALUES (?, ?, ?, CAST(? AS NUMERIC), 'timesfm', 'HALL')",
				storeId, menuId, targetDate, qty);
	}

	/** PENDING 추천 한 건. 계산 근거 숫자는 NOT NULL 만 채운다. */
	static long insertRecommendation(JdbcTemplate jdbc, long storeId, long ingredientId, LocalDate runDate,
			String recommendedQty) {
		return jdbc.queryForObject(
				"INSERT INTO recommendation (store_id, ingredient_id, run_date, est_stock, on_order, lead_time_demand,"
						+ " safety_stock, reorder_point, recommended_qty, forecast_model, ss_method)"
						+ " VALUES (?, ?, ?, 2500, 0, 1500, 3000, 4500, CAST(? AS NUMERIC), 'dow_avg', 'DAYS_N') RETURNING id",
				Long.class, storeId, ingredientId, runDate, recommendedQty);
	}

	static int countRecommendations(JdbcTemplate jdbc, long storeId, LocalDate runDate) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM recommendation WHERE store_id = ? AND run_date = ?",
				Integer.class, storeId, runDate);
	}
}
