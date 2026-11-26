package com.zigzaego.api.price.repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 재료 하나의 단가 이력을 단위 단가로 읽는다. price_history · supplier_product 모두 가게 조건을 건다 (ADR-0002).
 *
 * <ul>
 *   <li>출처는 RECEIVE · DOC · MANUAL 만 (MARKET 제외, 가격 지수 정의 3절)</li>
 *   <li>0원 이하 단가는 뺀다 ({@code PriceIndexCalculator.isUsable} 과 같은 조건 — 관측 · 주별 · 지수가 같은 관측을 본다)</li>
 *   <li>base_qty_per_pack 이 없거나 0 인 상품의 이력은 뺀다 (단위를 모르면 비교할 수 없다)</li>
 *   <li>단위 단가 = price ÷ base_qty_per_pack. 응답 자릿수(4)보다 넉넉히 계산해 두고 응답 직전에 반올림한다</li>
 * </ul>
 */
@Repository
@RequiredArgsConstructor
public class PriceHistoryQueryRepository {

	/** 단위 단가 계산 자릿수. 응답은 4자리로 다시 반올림한다. */
	private static final int CALC_SCALE = 10;

	private static final String SQL = """
			SELECT ph.observed_at, ph.price, ph.source, sp.id AS supplier_product_id, sp.product_name,
			       sp.base_qty_per_pack
			FROM price_history ph
			JOIN supplier_product sp ON sp.id = ph.supplier_product_id AND sp.store_id = :storeId
			WHERE ph.store_id = :storeId
			  AND sp.ingredient_id = :ingredientId
			  AND ph.source IN ('RECEIVE', 'DOC', 'MANUAL')
			  -- PriceIndexCalculator.isUsable 과 같은 조건. 0원 행이 관측 · 주별 칸에만 남으면 지수와 어긋난다
			  AND ph.price > 0
			  AND sp.base_qty_per_pack IS NOT NULL
			  AND sp.base_qty_per_pack > 0
			  AND ph.observed_at >= :from
			  AND ph.observed_at < :to
			ORDER BY ph.observed_at DESC, ph.id DESC
			""";

	private final NamedParameterJdbcTemplate jdbc;

	/** 단위 단가 관측 한 줄. observedAt 은 시각 그대로(날짜는 부르는 쪽이 서비스 시간대로). */
	public record UnitPriceRow(Instant observedAt, BigDecimal unitPrice, String source, long supplierProductId,
			String productName) {
	}

	/** [from, to) 사이 관측. 최근 것이 앞. */
	public List<UnitPriceRow> findUnitPrices(long storeId, long ingredientId, OffsetDateTime from, OffsetDateTime to) {
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("storeId", storeId)
				.addValue("ingredientId", ingredientId)
				.addValue("from", from)
				.addValue("to", to);
		return jdbc.query(SQL, params, (rs, rowNum) -> new UnitPriceRow(
				rs.getObject("observed_at", OffsetDateTime.class).toInstant(),
				rs.getBigDecimal("price").divide(rs.getBigDecimal("base_qty_per_pack"), CALC_SCALE, RoundingMode.HALF_UP),
				rs.getString("source"),
				rs.getLong("supplier_product_id"),
				rs.getString("product_name")));
	}
}
