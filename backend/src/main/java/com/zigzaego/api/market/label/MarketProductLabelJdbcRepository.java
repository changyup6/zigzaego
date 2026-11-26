package com.zigzaego.api.market.label;

import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

/**
 * 상품명 라벨 {@code market_product_label} 쓰기 (ADR-0012). 읽기는 시세 조회가 {@code market_product} 와 조인해서 한다.
 * 라벨의 키 = 상품명. 같은 상품명이 다시 오면 덮어쓴다(새 label_version · 다시 분류).
 */
@Repository
@RequiredArgsConstructor
public class MarketProductLabelJdbcRepository {

	private static final String UPSERT = """
			INSERT INTO market_product_label (product_name, item_group_code, form, qty, qty_unit, confidence, reason, label_version)
			VALUES (:productName, :itemGroupCode, :form, :qty, :qtyUnit, :confidence, :reason, :labelVersion)
			ON CONFLICT (product_name) DO UPDATE SET
			    item_group_code = EXCLUDED.item_group_code, form = EXCLUDED.form, qty = EXCLUDED.qty, qty_unit = EXCLUDED.qty_unit,
			    confidence = EXCLUDED.confidence, reason = EXCLUDED.reason, label_version = EXCLUDED.label_version, labeled_at = now()
			""";

	private final NamedParameterJdbcTemplate jdbc;

	/** 라벨 한 줄 (labels.csv 의 열 그대로). */
	public record LabelRow(String productName, String itemGroupCode, String form, BigDecimal qty, String qtyUnit,
			BigDecimal confidence, String reason, String labelVersion) {
	}

	public void upsertAll(List<LabelRow> rows) {
		SqlParameterSource[] batch = rows.stream().map(r -> (SqlParameterSource) new MapSqlParameterSource()
				.addValue("productName", r.productName())
				.addValue("itemGroupCode", r.itemGroupCode())
				.addValue("form", r.form())
				.addValue("qty", r.qty())
				.addValue("qtyUnit", r.qtyUnit())
				.addValue("confidence", r.confidence())
				.addValue("reason", r.reason())
				.addValue("labelVersion", r.labelVersion())).toArray(SqlParameterSource[]::new);
		jdbc.batchUpdate(UPSERT, batch);
	}

	/**
	 * {@code market_product} 의 고유 상품명 중 이 {@code label_version} 라벨이 없는 것 (증분 배치 대상, S15P31A308-229).
	 * 라벨이 없거나 다른 버전이면 포함. 이름순.
	 */
	public List<String> findUnlabeledNames(String labelVersion) {
		return jdbc.query("""
				SELECT DISTINCT p.product_name
				FROM market_product p
				LEFT JOIN market_product_label l ON l.product_name = p.product_name AND l.label_version = :version
				WHERE l.product_name IS NULL
				ORDER BY p.product_name
				""", new MapSqlParameterSource("version", labelVersion), (rs, i) -> rs.getString(1));
	}

	/** 라벨 표 전체, 상품명순 (CSV 백업용). */
	public List<LabelRow> findAll() {
		return jdbc.query("""
				SELECT product_name, item_group_code, form, qty, qty_unit, confidence, reason, label_version
				FROM market_product_label ORDER BY product_name
				""", (rs, i) -> new LabelRow(rs.getString("product_name"), rs.getString("item_group_code"), rs.getString("form"),
				rs.getBigDecimal("qty"), rs.getString("qty_unit"), rs.getBigDecimal("confidence"), rs.getString("reason"),
				rs.getString("label_version")));
	}

	public long count() {
		Long n = jdbc.getJdbcOperations().queryForObject("SELECT count(*) FROM market_product_label", Long.class);
		return n == null ? 0 : n;
	}
}
