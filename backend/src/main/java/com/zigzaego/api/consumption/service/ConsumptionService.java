package com.zigzaego.api.consumption.service;

import com.zigzaego.api.consumption.dto.DailyConsumptionResponse;
import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.exception.CommonErrorCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 판매 기록(pos_sale) × 레시피(recipe)로 재료별 하루 소비량을 조회 때 계산한다. 저장 · 캐시하지 않는다.
 *
 * <pre>
 * 소비량(i, d) = Σ_메뉴 m [ 판매량(m, d) × recipe.qty_per_serving(m, i) ]
 *   판매량(m, d) = 그날 pos_sale.qty 채널 합산 + 세트 판매량 × menu_component.qty (한 단계만 풂)
 * </pre>
 * <ul>
 *   <li>세트 메뉴 자기 레시피 줄(포장재 등)도 센다. OPTION 메뉴도 일반 메뉴처럼 레시피로 센다.</li>
 *   <li>1:1 재료(ONE_TO_ONE)는 레시피 "메뉴 1개당 1" 줄로 연결돼 있다고 보고 같은 식으로 센다.</li>
 *   <li>주기형(CYCLE) 재료는 뺀다. 소비량은 소수 3자리로 반올림(HALF_UP)하고, 반올림 뒤 0 인 줄은 내지 않는다.</li>
 *   <li>모든 조인에 store_id 를 건다 — 다른 가게의 판매 · 레시피 · 세트 구성은 섞이지 않는다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ConsumptionService {

	private static final String DAILY_SQL = """
			WITH sold AS (
			    SELECT ps.sold_date, ps.menu_id, SUM(ps.qty) AS qty
			    FROM pos_sale ps
			    JOIN menu m ON m.id = ps.menu_id AND m.store_id = :storeId
			    WHERE ps.store_id = :storeId
			      AND ps.sold_date BETWEEN :from AND :to
			    GROUP BY ps.sold_date, ps.menu_id
			),
			servings AS (
			    SELECT s.sold_date, s.menu_id, s.qty
			    FROM sold s
			    UNION ALL
			    SELECT s.sold_date, mc.component_menu_id, s.qty * mc.qty
			    FROM sold s
			    JOIN menu_component mc ON mc.set_menu_id = s.menu_id AND mc.store_id = :storeId
			    JOIN menu cm ON cm.id = mc.component_menu_id AND cm.store_id = :storeId
			)
			SELECT sv.sold_date AS sold_date,
			       i.id AS ingredient_id,
			       i.name AS ingredient_name,
			       i.unit AS unit,
			       SUM(sv.qty * r.qty_per_serving) AS qty
			FROM servings sv
			JOIN recipe r ON r.menu_id = sv.menu_id AND r.store_id = :storeId
			JOIN ingredient i ON i.id = r.ingredient_id AND i.store_id = :storeId
			WHERE i.kind <> 'CYCLE'
			GROUP BY sv.sold_date, i.id, i.name, i.unit
			HAVING SUM(sv.qty * r.qty_per_serving) <> 0
			ORDER BY sv.sold_date, i.id
			""";

	/**
	 * 재료별 "마지막 실사일 ~ :before 전날" 소비량 합계. 날짜별 줄 없이 재료당 한 줄만 돌려준다 (지라 157).
	 * 실사가 없는 재료는 판매 기록 처음부터 센다. 구간이 비는 재료(실사일 ≥ :before)는 줄이 없다.
	 */
	private static final String TOTAL_SINCE_COUNT_SQL = """
			WITH last_count AS (
			    -- 마감 확인 실사(counted_after_close)는 그날 판매가 끝난 뒤 값이라 다음 날부터 센다 (DECISIONS #92)
			    SELECT DISTINCT ON (ingredient_id) ingredient_id,
			           (counted_at AT TIME ZONE :tz)::date + counted_after_close::int AS count_date
			    FROM stock_count
			    WHERE store_id = :storeId
			    ORDER BY ingredient_id, counted_at DESC, id DESC
			),
			sold AS (
			    SELECT ps.sold_date, ps.menu_id, SUM(ps.qty) AS qty
			    FROM pos_sale ps
			    JOIN menu m ON m.id = ps.menu_id AND m.store_id = :storeId
			    WHERE ps.store_id = :storeId
			      AND ps.sold_date < :before
			    GROUP BY ps.sold_date, ps.menu_id
			),
			servings AS (
			    SELECT s.sold_date, s.menu_id, s.qty
			    FROM sold s
			    UNION ALL
			    SELECT s.sold_date, mc.component_menu_id, s.qty * mc.qty
			    FROM sold s
			    JOIN menu_component mc ON mc.set_menu_id = s.menu_id AND mc.store_id = :storeId
			    JOIN menu cm ON cm.id = mc.component_menu_id AND cm.store_id = :storeId
			)
			SELECT i.id AS ingredient_id,
			       SUM(sv.qty * r.qty_per_serving) AS qty
			FROM servings sv
			JOIN recipe r ON r.menu_id = sv.menu_id AND r.store_id = :storeId
			JOIN ingredient i ON i.id = r.ingredient_id AND i.store_id = :storeId
			LEFT JOIN last_count lc ON lc.ingredient_id = i.id
			WHERE i.kind <> 'CYCLE'
			  AND (lc.count_date IS NULL OR sv.sold_date >= lc.count_date)
			GROUP BY i.id
			""";

	/** 저장 수량 칼럼(NUMERIC(14,3))과 같은 소수 자릿수. */
	private static final int QTY_SCALE = 3;

	private final NamedParameterJdbcTemplate jdbc;

	/**
	 * 가게 하나의 기간(from · to 포함) 재료별 하루 소비량. 날짜 오름차순 → 재료 id 오름차순.
	 * 판매가 없으면 빈 목록. 기간 길이 제한은 API 쪽에서 건다.
	 *
	 * @throws BusinessException from 또는 to 가 없거나 from &gt; to 이면 400 COMMON_INVALID_INPUT
	 */
	public List<DailyConsumptionResponse> daily(long storeId, LocalDate from, LocalDate to) {
		if (from == null || to == null || from.isAfter(to)) {
			throw new BusinessException(CommonErrorCode.INVALID_INPUT, "from 은 to 보다 늦을 수 없습니다.");
		}
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("storeId", storeId)
				.addValue("from", Date.valueOf(from))
				.addValue("to", Date.valueOf(to));
		return jdbc.query(DAILY_SQL, params, (rs, rowNum) -> new DailyConsumptionResponse(
						rs.getDate("sold_date").toLocalDate().toString(),
						String.valueOf(rs.getLong("ingredient_id")),
						rs.getString("ingredient_name"),
						rs.getString("unit"),
						rs.getBigDecimal("qty").setScale(QTY_SCALE, RoundingMode.HALF_UP)))
				.stream()
				.filter(row -> row.qty().signum() != 0)
				.toList();
	}

	/**
	 * 재료별 마지막 실사일부터 {@code before} 전날까지의 소비량 합계. 실사가 없는 재료는 판매 기록 처음부터 센다.
	 * 재고 추정이 날짜별 줄은 요일 평균에 필요한 최근 구간만 읽고, 그보다 앞선 구간은 이 합계 한 줄로 받는다.
	 *
	 * @param zone   가게 시간대. 실사 시각을 이 시간대의 날짜로 자른다
	 * @param before 이 날 전날까지 센다(제외 경계)
	 * @return 재료 id → 합계(소수 3자리). 구간에 소비가 없는 재료는 없다
	 */
	public Map<Long, BigDecimal> totalSinceCountBefore(long storeId, ZoneId zone, LocalDate before) {
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("storeId", storeId)
				.addValue("tz", zone.getId())
				.addValue("before", Date.valueOf(before));
		Map<Long, BigDecimal> result = new HashMap<>();
		jdbc.query(TOTAL_SINCE_COUNT_SQL, params, rs -> {
			BigDecimal qty = rs.getBigDecimal("qty").setScale(QTY_SCALE, RoundingMode.HALF_UP);
			if (qty.signum() != 0) {
				result.put(rs.getLong("ingredient_id"), qty);
			}
		});
		return result;
	}
}
