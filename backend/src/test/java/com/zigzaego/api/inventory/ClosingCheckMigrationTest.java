package com.zigzaego.api.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zigzaego.api.TestcontainersConfiguration;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V17 의 손실 기록 · 요일별 마감 시각 · 푸시 구독 제약 (S15P31A308-246, DECISIONS #88).
 * 앱 스키마와 따로 빈 스키마에 V17 까지 올려 확인한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ClosingCheckMigrationTest {

	private static final String SCHEMA = "v17_closing_check";

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcTemplate jdbc;

	private long storeId;
	private long accountId;
	private long ingredientId;

	@BeforeEach
	void migrate() {
		jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
		Flyway.configure()
				.dataSource(dataSource)
				.schemas(SCHEMA)
				.locations("classpath:db/migration")
				.target("17")
				.load()
				.migrate();
		storeId = jdbc.queryForObject(
				"INSERT INTO " + SCHEMA + ".store (name, display_name) VALUES ('가게', '가게') RETURNING id", Long.class);
		accountId = jdbc.queryForObject("INSERT INTO " + SCHEMA + ".account (email, password_hash, name)"
				+ " VALUES ('a@b.c', 'x', '사장') RETURNING id", Long.class);
		long category = jdbc.queryForObject("INSERT INTO " + SCHEMA + ".ingredient_category"
				+ " (store_id, code, name, sort_order) VALUES (?, 'OTHER', '기타', 99) RETURNING id", Long.class, storeId);
		ingredientId = jdbc.queryForObject("INSERT INTO " + SCHEMA + ".ingredient (store_id, name, unit, price_sensitive,"
				+ " sourcing_type, kind, storage_type, storage_location, category_id, category_source)"
				+ " VALUES (?, '양파', 'G', false, 'GENERIC', 'BOM', 'CHILLED', 'FRIDGE', ?, 'AUTO') RETURNING id",
				Long.class, storeId, category);
	}

	@AfterEach
	void drop() {
		jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
	}

	private void insertAdjustment(String reason) {
		jdbc.update("INSERT INTO " + SCHEMA + ".stock_adjustment (store_id, ingredient_id, adjusted_at, qty, reason,"
				+ " before_qty, after_qty) VALUES (?, ?, now(), 500, ?, 1450, 950)", storeId, ingredientId, reason);
	}

	private void insertClosingTime(int dayOfWeek) {
		jdbc.update("INSERT INTO " + SCHEMA + ".store_closing_time (store_id, day_of_week, close_time)"
				+ " VALUES (?, ?, '22:00')", storeId, dayOfWeek);
	}

	private void insertSubscription(String endpoint) {
		jdbc.update("INSERT INTO " + SCHEMA + ".push_subscription (store_id, user_id, endpoint, p256dh, auth)"
				+ " VALUES (?, ?, ?, 'p', 'a')", storeId, accountId, endpoint);
	}

	@Test
	void 사유_5종만_빼기_기록에_들어간다() {
		for (String reason : new String[] {"DISCARD", "SPOILED", "DAMAGED", "SPILLED", "OTHER"}) {
			insertAdjustment(reason);
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM " + SCHEMA + ".stock_adjustment", Integer.class))
				.isEqualTo(5);
		assertThatThrownBy(() -> insertAdjustment("STOLEN")).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 같은_endpoint_는_두_번_구독되지_않는다() {
		insertSubscription("https://push.example/1");
		assertThatThrownBy(() -> insertSubscription("https://push.example/1"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 같은_가게_같은_요일_마감_시각은_한_줄이고_요일은_1에서_7() {
		insertClosingTime(1);
		insertClosingTime(7);
		assertThatThrownBy(() -> insertClosingTime(1)).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertClosingTime(0)).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertClosingTime(8)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 가게에_마지막_알림_날짜_칸이_생기고_비어_있다() {
		assertThat(jdbc.queryForObject("SELECT last_closing_push_date FROM " + SCHEMA + ".store WHERE id = ?",
				java.time.LocalDate.class, storeId)).isNull();
		assertThat(jdbc.queryForObject("SELECT column_default FROM information_schema.columns WHERE table_schema = ?"
				+ " AND table_name = 'stock_count' AND column_name = 'counted_after_close'", String.class, SCHEMA))
				.isEqualTo("false");
	}
}
