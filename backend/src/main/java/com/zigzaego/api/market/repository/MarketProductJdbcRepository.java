package com.zigzaego.api.market.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

/**
 * 식자재몰 시세 표 {@code market_product} 읽기 · 쓰기 (S15P31A308-232 · 229, ADR-0010 · ADR-0012).
 * 이 표는 가게 소속이 아닌 전역 참조 데이터라 store 조건이 없다 — ADR-0002 의 예외이고, 가게 스코프는 부르는 쪽(재료 조회)이 건다.
 * JPA 엔티티를 두지 않는다: 48만 줄을 한 번에 넣고 읽기만 하므로 JDBC 가 맞다.
 * 시세는 수집일마다 쌓이므로(ADR-0012) "지금 최저가" 는 사이트마다 가장 최근 수집일의 줄만, 추이는 날짜 범위로 읽는다.
 */
@Repository
@RequiredArgsConstructor
public class MarketProductJdbcRepository {

	private static final String INSERT = """
			INSERT INTO market_product (site, store_region, seller, product_no, product_name, brand, spec, price, original_price,
			                            total_qty, unit, unit_price_100, category, url, collected_on, group_no)
			VALUES (:site, :storeRegion, :seller, :productNo, :productName, :brand, :spec, :price, :originalPrice,
			        :totalQty, :unit, :unitPrice100, :category, :url, :collectedOn, :groupNo)
			""";

	private static final String COLUMNS = """
			SELECT p.site, p.store_region, p.product_no, p.product_name, p.spec, p.price, p.total_qty, p.unit, p.unit_price_100, p.url,
			       p.collected_on, l.form, l.qty AS label_qty, l.qty_unit AS label_unit, l.confidence AS label_confidence
			""";

	/** 사이트마다 가장 최근 수집일의 줄만 — "지금 최저가". */
	private static final String LATEST = " p.collected_on = (SELECT max(x.collected_on) FROM market_product x WHERE x.site = p.site) ";

	/** 상품명이 후보 정규식에 맞는 줄(라벨은 있으면 붙인다). 정확 판정은 Java(ItemMatcher 또는 라벨)가 한다. */
	private static final String BY_REGEX = COLUMNS + """
			FROM market_product p LEFT JOIN market_product_label l ON l.product_name = p.product_name
			WHERE p.product_name ~* :regex AND %s
			""";

	/** 라벨이 이 품목군의 원물 · 손질품이라고 한 줄. */
	private static final String BY_GROUP = COLUMNS + """
			FROM market_product_label l JOIN market_product p ON p.product_name = l.product_name
			WHERE l.item_group_code = :code AND l.form IN ('RAW', 'PREPARED') AND l.confidence >= :minConfidence AND %s
			""";

	private final NamedParameterJdbcTemplate jdbc;

	/** 데이터셋 한 줄. 열 뜻은 데이터셋 README 와 같다. */
	public record MarketProductRow(String site, String storeRegion, String seller, String productNo, String productName,
			String brand, String spec, long price, Long originalPrice, BigDecimal totalQty, String unit,
			BigDecimal unitPrice100, String category, String url, LocalDate collectedOn, Long groupNo) {
	}

	/**
	 * 최저가 후보 한 줄. 라벨 칸(form · labelQty · labelUnit · labelConfidence)은 라벨이 없으면 null.
	 *
	 * @param unit 데이터셋 단위 g · ml · 개 (규격 해석 실패면 null)
	 * @param unitPrice100 원/100g · 원/100ml · 원/개 (해석 실패면 null)
	 */
	public record Candidate(String site, String storeRegion, String productNo, String productName, String spec, long price,
			BigDecimal totalQty, String unit, BigDecimal unitPrice100, String url, LocalDate collectedOn,
			String form, BigDecimal labelQty, String labelUnit, BigDecimal labelConfidence) {

		public boolean labeled() {
			return form != null;
		}
	}

	private static final RowMapper<Candidate> CANDIDATE = (rs, rowNum) -> new Candidate(
			rs.getString("site"), rs.getString("store_region"), rs.getString("product_no"), rs.getString("product_name"),
			rs.getString("spec"), rs.getLong("price"), rs.getBigDecimal("total_qty"), rs.getString("unit"),
			rs.getBigDecimal("unit_price_100"), rs.getString("url"), rs.getObject("collected_on", LocalDate.class),
			rs.getString("form"), rs.getBigDecimal("label_qty"), rs.getString("label_unit"), rs.getBigDecimal("label_confidence"));

	public void deleteAll() {
		jdbc.getJdbcOperations().update("DELETE FROM market_product");
	}

	/** 같은 수집일 줄만 지운다 — 새 스냅샷이 그 날짜를 다시 채운다 (ADR-0012). */
	public int deleteByCollectedOn(Collection<LocalDate> dates) {
		if (dates.isEmpty()) {
			return 0;
		}
		return jdbc.update("DELETE FROM market_product WHERE collected_on IN (:dates)", new MapSqlParameterSource("dates", dates));
	}

	public void insertAll(List<MarketProductRow> rows) {
		SqlParameterSource[] batch = rows.stream().map(r -> (SqlParameterSource) new MapSqlParameterSource()
				.addValue("site", r.site())
				.addValue("storeRegion", r.storeRegion())
				.addValue("seller", r.seller())
				.addValue("productNo", r.productNo())
				.addValue("productName", r.productName())
				.addValue("brand", r.brand())
				.addValue("spec", r.spec())
				.addValue("price", r.price())
				.addValue("originalPrice", r.originalPrice())
				.addValue("totalQty", r.totalQty())
				.addValue("unit", r.unit())
				.addValue("unitPrice100", r.unitPrice100())
				.addValue("category", r.category())
				.addValue("url", r.url())
				.addValue("collectedOn", r.collectedOn())
				.addValue("groupNo", r.groupNo())).toArray(SqlParameterSource[]::new);
		jdbc.batchUpdate(INSERT, batch);
	}

	public long count() {
		Long n = jdbc.getJdbcOperations().queryForObject("SELECT count(*) FROM market_product", Long.class);
		return n == null ? 0 : n;
	}

	/** 정규식({@code ItemMatcher.candidateRegex})에 맞는 줄 중 사이트별 최신 수집일. 순서 없음 — 부르는 쪽이 정렬한다. */
	public List<Candidate> findLatestByRegex(String regex) {
		return jdbc.query(BY_REGEX.formatted(LATEST), new MapSqlParameterSource("regex", regex), CANDIDATE);
	}

	/** 정규식에 맞는 줄 중 수집일이 {@code since} 이후(포함)인 것 전부 — 추이용. */
	public List<Candidate> findByRegexSince(String regex, LocalDate since) {
		return jdbc.query(BY_REGEX.formatted(" p.collected_on >= :since "),
				new MapSqlParameterSource("regex", regex).addValue("since", since), CANDIDATE);
	}

	/** 라벨이 이 품목군(원물 · 손질품, 확신 {@code minConfidence} 이상)이라고 한 줄 중 사이트별 최신 수집일. */
	public List<Candidate> findLatestByGroup(String code, BigDecimal minConfidence) {
		return jdbc.query(BY_GROUP.formatted(LATEST),
				new MapSqlParameterSource("code", code).addValue("minConfidence", minConfidence), CANDIDATE);
	}

	public List<Candidate> findByGroupSince(String code, BigDecimal minConfidence, LocalDate since) {
		return jdbc.query(BY_GROUP.formatted(" p.collected_on >= :since "),
				new MapSqlParameterSource("code", code).addValue("minConfidence", minConfidence).addValue("since", since), CANDIDATE);
	}
}
