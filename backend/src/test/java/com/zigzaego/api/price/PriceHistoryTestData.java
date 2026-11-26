package com.zigzaego.api.price;

import com.zigzaego.api.support.FixedClockConfig;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.springframework.jdbc.core.JdbcTemplate;

/** 단가 이력 테스트 데이터. 가게 소속 행은 NOT NULL 칸만 채운다. */
final class PriceHistoryTestData {

	private PriceHistoryTestData() {
	}

	static long insertSupplier(JdbcTemplate jdbc, long storeId, String name) {
		return jdbc.queryForObject("INSERT INTO supplier (store_id, name) VALUES (?, ?) RETURNING id",
				Long.class, storeId, name);
	}

	/** baseQtyPerPack 이 null 이면 포장 환산을 모르는 상품. */
	static long insertProduct(JdbcTemplate jdbc, long storeId, long supplierId, long ingredientId, String name,
			Integer baseQtyPerPack) {
		return jdbc.queryForObject(
				"INSERT INTO supplier_product (store_id, supplier_id, ingredient_id, product_name, base_qty_per_pack)"
						+ " VALUES (?, ?, ?, ?, ?) RETURNING id",
				Long.class, storeId, supplierId, ingredientId, name, baseQtyPerPack);
	}

	/** 그날 서울 12:00 에 본 포장 1개 값. */
	static void insertPrice(JdbcTemplate jdbc, long storeId, long productId, int price, LocalDate day, String source) {
		insertPriceAt(jdbc, storeId, productId, price, day.atTime(LocalTime.NOON).atZone(FixedClockConfig.SEOUL).toInstant(),
				source);
	}

	/** 그 시각에 본 포장 1개 값 (날짜 경계 테스트용). */
	static void insertPriceAt(JdbcTemplate jdbc, long storeId, long productId, int price, Instant observedAt,
			String source) {
		jdbc.update("INSERT INTO price_history (store_id, supplier_product_id, price, unit, observed_at, source)"
						+ " VALUES (?, ?, ?, 'PACK', ?, ?)",
				storeId, productId, price, Timestamp.from(observedAt), source);
	}
}
