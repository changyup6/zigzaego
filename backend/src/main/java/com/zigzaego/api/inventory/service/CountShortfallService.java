package com.zigzaego.api.inventory.service;

import com.zigzaego.api.domain.order.CountShortfall;
import com.zigzaego.api.domain.order.CountShortfall.Sample;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재료별 반복 손실 — 최근 실사 {@value CountShortfall#WINDOW}회의 추정 대비 차이 (S15P31A308-253, {@link CountShortfall}).
 * 발주 추천의 안전재고 상향과 마감 확인 대상 품목 순위(지라 249)가 같은 값을 쓴다.
 */
@Service
@RequiredArgsConstructor
public class CountShortfallService {

	/** 재료별 추정치가 있는 최근 실사. 추정치가 없는 옛 실사(253 이전 FULL_COUNT)는 차이를 모르므로 건너뛴다. */
	private static final String RECENT_COUNTS_SQL = """
			SELECT ingredient_id, estimated_qty, qty
			FROM (
			    SELECT ingredient_id, estimated_qty, qty,
			           ROW_NUMBER() OVER (PARTITION BY ingredient_id ORDER BY counted_at DESC, id DESC) AS rn
			    FROM stock_count
			    WHERE store_id = :storeId AND estimated_qty IS NOT NULL
			) c
			WHERE rn <= :window
			ORDER BY ingredient_id, rn
			""";

	private final NamedParameterJdbcTemplate jdbc;

	/** @return 반복 손실인 재료만, 재료 id → 평균 손실량(기준 단위) */
	@Transactional(readOnly = true)
	public Map<Long, BigDecimal> averageLosses(long storeId) {
		Map<Long, List<Sample>> samples = new LinkedHashMap<>();
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("storeId", storeId)
				.addValue("window", CountShortfall.WINDOW);
		jdbc.query(RECENT_COUNTS_SQL, params, rs -> {
			samples.computeIfAbsent(rs.getLong("ingredient_id"), k -> new ArrayList<>())
					.add(new Sample(rs.getBigDecimal("estimated_qty"), rs.getBigDecimal("qty")));
		});
		Map<Long, BigDecimal> result = new HashMap<>();
		samples.forEach((id, list) -> CountShortfall.averageLoss(list).ifPresent(loss -> result.put(id, loss)));
		return result;
	}
}
