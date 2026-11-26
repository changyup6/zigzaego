package com.zigzaego.api.ingredient;

import static org.assertj.core.api.Assertions.assertThat;

import com.zigzaego.api.TestcontainersConfiguration;
import java.util.Map;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V9 가 기존 재료 행의 storage_location 을 규칙대로 채우는지 (S15P31A308-268).
 * 앱 스키마와 따로 빈 스키마에 V8 까지 올리고 행을 넣은 뒤 V9 를 적용한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class StorageLocationMigrationTest {

	private static final String SCHEMA = "v9_storage_location";

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcTemplate jdbc;

	private Flyway flyway(String target) {
		return Flyway.configure()
				.dataSource(dataSource)
				.schemas(SCHEMA)
				.locations("classpath:db/migration")
				.target(target)
				.load();
	}

	@Test
	void 기존_행은_규칙대로_채워지고_NULL_은_0건() {
		jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
		try {
			migrateAndCheck();
		} finally {
			jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
		}
	}

	private void migrateAndCheck() {
		flyway("8").migrate();

		long storeId = jdbc.queryForObject(
				"INSERT INTO " + SCHEMA + ".store (name, display_name) VALUES ('마이그', '마이그') RETURNING id", Long.class);
		insert(storeId, "냉동새우", "BOM", "'FROZEN'");
		insert(storeId, "우유", "ONE_TO_ONE", "'CHILLED'");
		insert(storeId, "양상추", "BOM", "'FRESH'");
		insert(storeId, "파스타면", "BOM", "'AMBIENT'");
		insert(storeId, "키친타올", "CYCLE", "NULL");
		insert(storeId, "쌀", "CYCLE", "'AMBIENT'");

		flyway("9").migrate();

		Map<String, String> locations = jdbc.queryForList(
						"SELECT name, storage_location FROM " + SCHEMA + ".ingredient WHERE store_id = ?", storeId)
				.stream()
				.collect(Collectors.toMap(row -> (String) row.get("name"), row -> (String) row.get("storage_location")));
		assertThat(locations).containsExactlyInAnyOrderEntriesOf(Map.of(
				"냉동새우", "FREEZER",
				"우유", "FRIDGE",
				"양상추", "FRIDGE",
				"파스타면", "SHELF",
				"키친타올", "WAREHOUSE",
				"쌀", "WAREHOUSE"));
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM " + SCHEMA + ".ingredient WHERE storage_location IS NULL", Long.class)).isZero();
		assertThat(jdbc.queryForObject(
				"SELECT is_nullable FROM information_schema.columns"
						+ " WHERE table_schema = ? AND table_name = 'ingredient' AND column_name = 'storage_location'",
				String.class, SCHEMA)).isEqualTo("NO");
	}

	private void insert(long storeId, String name, String kind, String storageTypeSql) {
		jdbc.update("INSERT INTO " + SCHEMA + ".ingredient (store_id, name, unit, price_sensitive, sourcing_type, kind,"
				+ " storage_type) VALUES (?, ?, 'G', false, 'GENERIC', ?, " + storageTypeSql + ")", storeId, name, kind);
	}
}
