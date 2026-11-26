package com.zigzaego.api.market.delivery;

import com.zigzaego.api.market.delivery.DeliveryCoverage.Area;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 판매처별 배송 가능 지역 표 {@code market_site_delivery_area} 읽기 (S15P31A308-230, ADR-0011).
 * 가게 소속이 아닌 전역 참조 표라 store 조건이 없다(ADR-0002 예외). 수십 줄이라 매번 전부 읽는다. JPA 엔티티를 두지 않는다.
 */
@Repository
@RequiredArgsConstructor
public class MarketSiteDeliveryAreaJdbcRepository {

	private final JdbcTemplate jdbc;

	public List<Area> findAll() {
		return jdbc.query("SELECT site, store_region, sigungu, dawn_delivery FROM market_site_delivery_area ORDER BY id",
				(rs, rowNum) -> new Area(rs.getString("site"), rs.getString("store_region"), rs.getString("sigungu"),
						rs.getObject("dawn_delivery", Boolean.class)));
	}
}
