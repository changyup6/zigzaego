package com.zigzaego.api.ingredient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zigzaego.api.TestcontainersConfiguration;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V12 가 기존 재료의 카테고리 출처를 전부 AUTO 로 채우고 NOT NULL · 값 CHECK 를 거는지 (S15P31A308-274).
 * 앱 스키마와 따로 빈 스키마에 V11 까지 올리고 가게 · 카테고리 · 재료를 넣은 뒤 V12 를 적용한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class IngredientCategorySourceMigrationTest {

	private static final String SCHEMA = "v12_ingredient_category_source";

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
	void 기존_재료의_출처는_AUTO_가_되고_NOT_NULL_과_값_CHECK_가_걸린다() {
		jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
		try {
			migrateAndCheck();
		} finally {
			jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
		}
	}

	private void migrateAndCheck() {
		flyway("11").migrate();
		long storeId = jdbc.queryForObject(
				"INSERT INTO " + SCHEMA + ".store (name, display_name) VALUES ('가게', '가게') RETURNING id", Long.class);
		long other = jdbc.queryForObject("INSERT INTO " + SCHEMA + ".ingredient_category (store_id, code, name, sort_order)"
				+ " VALUES (?, 'OTHER', '기타', 99) RETURNING id", Long.class, storeId);
		long onion = insertIngredient(storeId, other, "양파");

		flyway("12").migrate();

		assertThat(jdbc.queryForObject("SELECT category_source FROM " + SCHEMA + ".ingredient WHERE id = ?",
				String.class, onion)).isEqualTo("AUTO");
		assertThat(jdbc.queryForObject(
				"SELECT is_nullable FROM information_schema.columns"
						+ " WHERE table_schema = ? AND table_name = 'ingredient' AND column_name = 'category_source'",
				String.class, SCHEMA)).isEqualTo("NO");
		assertThatThrownBy(() -> jdbc.update("UPDATE " + SCHEMA + ".ingredient SET category_source = 'ROBOT' WHERE id = ?",
				onion)).isInstanceOf(DataIntegrityViolationException.class);
		jdbc.update("UPDATE " + SCHEMA + ".ingredient SET category_source = 'OWNER' WHERE id = ?", onion);
	}

	private long insertIngredient(long storeId, long categoryId, String name) {
		return jdbc.queryForObject("INSERT INTO " + SCHEMA + ".ingredient (store_id, name, unit, price_sensitive,"
						+ " sourcing_type, kind, storage_type, storage_location, category_id)"
						+ " VALUES (?, ?, 'G', false, 'GENERIC', 'BOM', 'CHILLED', 'FRIDGE', ?) RETURNING id",
				Long.class, storeId, name, categoryId);
	}
}
