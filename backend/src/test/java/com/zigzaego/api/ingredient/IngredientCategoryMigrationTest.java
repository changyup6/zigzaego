package com.zigzaego.api.ingredient;

import static org.assertj.core.api.Assertions.assertThat;

import com.zigzaego.api.TestcontainersConfiguration;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V11 이 기존 가게마다 기본 카테고리 11종을 넣고, 기존 재료를 전부 그 가게의 '기타' 로 채우는지 (S15P31A308-273).
 * 앱 스키마와 따로 빈 스키마에 V9 까지 올리고 가게 2개 · 재료를 넣은 뒤 V11 까지 적용한다(V10 은 다른 일감의 번호라 이 브랜치에 없다).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class IngredientCategoryMigrationTest {

	private static final String SCHEMA = "v11_ingredient_category";

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
	void 기존_가게마다_기본_11종이_생기고_기존_재료는_그_가게의_기타가_된다() {
		jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
		try {
			migrateAndCheck();
		} finally {
			jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
		}
	}

	private void migrateAndCheck() {
		flyway("9").migrate();

		long storeA = insertStore("가게A");
		long storeB = insertStore("가게B");
		long onion = insertIngredient(storeA, "양파");
		long pork = insertIngredient(storeA, "삼겹살");
		long milk = insertIngredient(storeB, "우유");

		flyway("11").migrate();

		// 가게마다 11종, 기타는 1개
		for (long storeId : List.of(storeA, storeB)) {
			assertThat(jdbc.queryForList(
					"SELECT code FROM " + SCHEMA + ".ingredient_category WHERE store_id = ? ORDER BY sort_order, id",
					String.class, storeId))
					.containsExactly("MEAT", "SEAFOOD", "VEGETABLE", "FRUIT", "EGG_DAIRY", "GRAIN_NOODLE", "TOFU_BEAN",
							"SAUCE", "PROCESSED", "BEVERAGE", "OTHER");
		}
		assertThat(jdbc.queryForObject(
				"SELECT name FROM " + SCHEMA + ".ingredient_category WHERE store_id = ? AND code = 'OTHER'",
				String.class, storeA)).isEqualTo("기타");

		// 기존 재료는 전부 자기 가게의 기타. NULL 0건, NOT NULL 제약
		long otherA = otherCategoryId(storeA);
		long otherB = otherCategoryId(storeB);
		assertThat(otherA).isNotEqualTo(otherB);
		assertThat(categoryOf(onion)).isEqualTo(otherA);
		assertThat(categoryOf(pork)).isEqualTo(otherA);
		assertThat(categoryOf(milk)).isEqualTo(otherB);
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM " + SCHEMA + ".ingredient WHERE category_id IS NULL", Long.class)).isZero();
		assertThat(jdbc.queryForObject(
				"SELECT is_nullable FROM information_schema.columns"
						+ " WHERE table_schema = ? AND table_name = 'ingredient' AND column_name = 'category_id'",
				String.class, SCHEMA)).isEqualTo("NO");
	}

	private long insertStore(String name) {
		return jdbc.queryForObject(
				"INSERT INTO " + SCHEMA + ".store (name, display_name) VALUES (?, ?) RETURNING id", Long.class, name, name);
	}

	private long insertIngredient(long storeId, String name) {
		return jdbc.queryForObject("INSERT INTO " + SCHEMA + ".ingredient (store_id, name, unit, price_sensitive,"
						+ " sourcing_type, kind, storage_type, storage_location)"
						+ " VALUES (?, ?, 'G', false, 'GENERIC', 'BOM', 'CHILLED', 'FRIDGE') RETURNING id",
				Long.class, storeId, name);
	}

	private long otherCategoryId(long storeId) {
		return jdbc.queryForObject(
				"SELECT id FROM " + SCHEMA + ".ingredient_category WHERE store_id = ? AND code = 'OTHER'",
				Long.class, storeId);
	}

	private long categoryOf(long ingredientId) {
		return jdbc.queryForObject("SELECT category_id FROM " + SCHEMA + ".ingredient WHERE id = ?", Long.class,
				ingredientId);
	}
}
