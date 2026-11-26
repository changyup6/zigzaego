package com.zigzaego.api.consumption;

import com.zigzaego.api.auth.AuthTestSupport;
import com.zigzaego.api.support.StoreFixtures;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;

/** 소비량 테스트 공용: V1 표에 NOT NULL 칸만 채워 한 줄씩 넣는다. */
final class ConsumptionTestData {

	private ConsumptionTestData() {
	}

	static long insertStore(JdbcTemplate jdbc, String name) {
		String unique = name + "-" + System.nanoTime();
		return AuthTestSupport.insertStore(jdbc, unique);
	}

	/** kind = MAIN · OPTION · SET */
	static long insertMenu(JdbcTemplate jdbc, long storeId, String name, String kind) {
		return jdbc.queryForObject(
				"INSERT INTO menu (store_id, name, kind, source) VALUES (?, ?, ?, 'POS') RETURNING id",
				Long.class, storeId, name, kind);
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

	static void insertComponent(JdbcTemplate jdbc, long storeId, long setMenuId, long componentMenuId, String qty) {
		jdbc.update(
				"INSERT INTO menu_component (store_id, set_menu_id, component_menu_id, qty) VALUES (?, ?, ?, CAST(? AS NUMERIC))",
				storeId, setMenuId, componentMenuId, qty);
	}

	/** channel = DELIVERY · HALL · NONE */
	static void insertSale(JdbcTemplate jdbc, long storeId, LocalDate date, long menuId, String channel, String qty) {
		jdbc.update(
				"INSERT INTO pos_sale (store_id, sold_date, menu_id, qty, channel) VALUES (?, ?, ?, CAST(? AS NUMERIC), ?)",
				storeId, date, menuId, qty, channel);
	}
}
