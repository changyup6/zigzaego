package com.zigzaego.api.forecast;

import com.zigzaego.api.auth.AuthTestSupport;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;

/** 예측 테스트 공용: V1 표에 NOT NULL 칸만 채워 한 줄씩 넣는다. 가게 시간대는 기본값(Asia/Seoul). */
final class ForecastTestData {

	private ForecastTestData() {
	}

	static long insertStore(JdbcTemplate jdbc, String name) {
		String unique = name + "-" + System.nanoTime();
		return AuthTestSupport.insertStore(jdbc, unique);
	}

	static long insertMenu(JdbcTemplate jdbc, long storeId, String name) {
		return insertMenu(jdbc, storeId, name, true);
	}

	static long insertMenu(JdbcTemplate jdbc, long storeId, String name, boolean active) {
		return jdbc.queryForObject(
				"INSERT INTO menu (store_id, name, kind, source, active) VALUES (?, ?, 'MAIN', 'POS', ?) RETURNING id",
				Long.class, storeId, name, active);
	}

	/** channel = DELIVERY · HALL · NONE */
	static void insertSale(JdbcTemplate jdbc, long storeId, LocalDate date, long menuId, String channel, String qty) {
		jdbc.update(
				"INSERT INTO pos_sale (store_id, sold_date, menu_id, qty, channel) VALUES (?, ?, ?, CAST(? AS NUMERIC), ?)",
				storeId, date, menuId, qty, channel);
	}

	static void insertClosedDay(JdbcTemplate jdbc, long storeId, LocalDate date) {
		jdbc.update("INSERT INTO store_closed_day (store_id, closed_date) VALUES (?, ?)", storeId, date);
	}

	/** model = timesfm · dow_avg, channel = DELIVERY · HALL · NONE */
	static void insertForecast(JdbcTemplate jdbc, long storeId, long menuId, LocalDate targetDate, String model,
			String channel, String qty) {
		jdbc.update(
				"INSERT INTO forecast (store_id, menu_id, target_date, predicted_qty, model, channel)"
						+ " VALUES (?, ?, ?, CAST(? AS NUMERIC), ?, ?)",
				storeId, menuId, targetDate, qty, model, channel);
	}
}
