package com.zigzaego.api.market.catalog;

import com.zigzaego.api.ingredient.entity.IngredientUnit;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code market_item_group} 전체 읽기 (수백 줄, 전역 참조 표). 쓰기는 마이그레이션으로만 한다 (ADR-0012). */
@Repository
@RequiredArgsConstructor
public class MarketItemGroupJdbcRepository {

	private static final String SELECT_ALL = """
			SELECT code, name, parent_code, category, comparable, base_unit, ea_weight_g, density_g_per_ml, aliases, note
			FROM market_item_group ORDER BY code
			""";

	private final JdbcTemplate jdbc;

	public List<MarketItemGroup> findAll() {
		return jdbc.query(SELECT_ALL, (rs, i) -> new MarketItemGroup(
				rs.getString("code"), rs.getString("name"), rs.getString("parent_code"), rs.getString("category"),
				rs.getBoolean("comparable"), IngredientUnit.valueOf(rs.getString("base_unit")),
				rs.getBigDecimal("ea_weight_g"), rs.getBigDecimal("density_g_per_ml"),
				Arrays.stream(rs.getString("aliases").split(";")).map(String::strip).filter(s -> !s.isEmpty()).toList(),
				rs.getString("note")));
	}
}
