package com.zigzaego.api.inventory.service;

import com.zigzaego.api.account.entity.Store;
import com.zigzaego.api.account.repository.StoreRepository;
import com.zigzaego.api.consumption.dto.DailyConsumptionResponse;
import com.zigzaego.api.consumption.service.ConsumptionService;
import com.zigzaego.api.global.config.StoreClock;
import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.exception.CommonErrorCode;
import com.zigzaego.api.ingredient.entity.Ingredient;
import com.zigzaego.api.ingredient.entity.IngredientKind;
import com.zigzaego.api.ingredient.repository.IngredientRepository;
import com.zigzaego.api.inventory.dto.EstimateBasis;
import com.zigzaego.api.inventory.dto.InventoryEstimateResponse;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재료별 추정 재고와 소진 예상일. 조회 때 계산하고 저장하지 않는다.
 *
 * <pre>
 * BOM · 1:1 (3주 계획 4.2절, DECISIONS #41)
 *   추정 재고 = 마지막 실사량
 *             + Σ 입고 (실사 뒤):  ① purchase_order_item.received_qty (received_at &gt; 실사 시각)
 *                                 ② 매입 문서 줄: 문서 CONFIRMED · kind=RECEIVE · 발주서 미연결, 줄 CONFIRMED · 재료 연결 ·
 *                                    발주 줄 미연결(purchase_order_item_id, 지라 301)
 *                                    (doc_date ≥ 실사일). PAST 문서는 더하지 않는다
 *                                 둘 다 × supplier_product.base_qty_per_pack (포장 단위 → 기준 단위, 모르면 1)
 *             − Σ 일 소비량 (실사일 ~ 오늘, ConsumptionService 와 같은 식. 날짜별 줄은 최근 28일만 읽고
 *                            그 앞은 재료별 합계 한 줄로 받는다)
 *             − Σ 빼기 기록 (stock_adjustment, adjusted_at &gt; 실사 시각. 실사가 들어오면 그 앞 빼기는 기준점에 흡수된다, 지라 252)
 *   마감 확인 실사(counted_after_close, DECISIONS #92)는 그날 판매가 끝난 뒤 값이라 "실사일" 을 다음 날로 본다
 *   — 소비는 다음 날부터, 매입 문서는 doc_date ≥ 다음 날만 더한다
 *   실사 기록이 없으면 0 을 기준점으로 전체 입고 − 전체 소비, needsCount=true (NO_COUNT)
 *   음수면 0 으로 보정하고 needsCount=true (DECISIONS #9)
 *
 * 주기형 (설계 5.1절) — 입고 기록 = 확인된 매입 문서 줄(PAST 포함) + 입고 확인된 발주 줄, 같은 날은 한 건으로
 *   입고 ≤ 1건                 → CYCLE_COLLECTING (둘 다 null)
 *   ≥ 2건 + 그 사이 판매 있음  → 그릇당 소비량 = (마지막 전 입고량 합) ÷ (첫 입고일 ~ 마지막 입고일 전날 전체 판매 그릇 수)
 *                                추정 재고 = 마지막 입고량 − 그릇당 소비량 × 마지막 입고일 이후 판매 그릇 수
 *                                소진일 = 하루 예상 판매 그릇 수(요일 평균) × 그릇당 소비량으로 하루씩 뺀다
 *   ≥ 2건 + 판매 없음          → CYCLE_INTERVAL. 소진일 = 마지막 입고일 + 평균 입고 간격, 추정 재고 null
 *
 * 소진 예상일 (3주 계획 4.3절): 최근 4주 같은 요일 평균, 휴무일 제외 — {@link DailyRate}
 * </pre>
 *
 * 모든 SQL 에 store_id 를 건다 (ADR-0002). 날짜 경계는 가게 시간대({@code store.timezone})로 자른다.
 */
@Service
@RequiredArgsConstructor
public class InventoryEstimateService {

	private static final int QTY_SCALE = 3;
	private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
	/** 요일 평균 이력 길이(일). 4주. */
	private static final int HISTORY_DAYS = 28;

	private static final String LAST_COUNT_SQL = """
			SELECT DISTINCT ON (ingredient_id) ingredient_id, qty, counted_at, counted_after_close
			FROM stock_count
			WHERE store_id = :storeId
			ORDER BY ingredient_id, counted_at DESC, id DESC
			""";

	private static final String RECEIVED_SINCE_COUNT_SQL = """
			WITH last_count AS (
			    SELECT DISTINCT ON (ingredient_id) ingredient_id, counted_at, counted_after_close
			    FROM stock_count
			    WHERE store_id = :storeId
			    ORDER BY ingredient_id, counted_at DESC, id DESC
			),
			po AS (
			    SELECT poi.ingredient_id, SUM(poi.received_qty * COALESCE(sp.base_qty_per_pack, 1)) AS qty
			    FROM purchase_order_item poi
			    JOIN supplier_product sp ON sp.id = poi.supplier_product_id AND sp.store_id = :storeId
			    LEFT JOIN last_count lc ON lc.ingredient_id = poi.ingredient_id
			    WHERE poi.store_id = :storeId
			      AND poi.received_qty IS NOT NULL AND poi.received_at IS NOT NULL
			      AND (lc.counted_at IS NULL OR poi.received_at > lc.counted_at)
			    GROUP BY poi.ingredient_id
			),
			doc AS (
			    SELECT l.ingredient_id, SUM(l.qty * COALESCE(sp.base_qty_per_pack, 1)) AS qty
			    FROM purchase_doc_line l
			    JOIN purchase_doc d ON d.id = l.doc_id AND d.store_id = :storeId
			    LEFT JOIN supplier_product sp ON sp.id = l.supplier_product_id AND sp.store_id = :storeId
			    LEFT JOIN last_count lc ON lc.ingredient_id = l.ingredient_id
			    WHERE l.store_id = :storeId
			      AND l.ingredient_id IS NOT NULL AND l.qty IS NOT NULL AND l.status = 'CONFIRMED'
			      AND d.parse_status = 'CONFIRMED' AND d.kind = 'RECEIVE' AND d.purchase_order_id IS NULL
			      AND l.purchase_order_item_id IS NULL
			      AND d.doc_date IS NOT NULL
			      AND (lc.counted_at IS NULL OR d.doc_date >= (lc.counted_at AT TIME ZONE :tz)::date + lc.counted_after_close::int)
			    GROUP BY l.ingredient_id
			)
			SELECT COALESCE(po.ingredient_id, doc.ingredient_id) AS ingredient_id,
			       COALESCE(po.qty, 0) + COALESCE(doc.qty, 0) AS qty
			FROM po
			FULL OUTER JOIN doc ON doc.ingredient_id = po.ingredient_id
			""";

	/** 재료별 마지막 실사 뒤의 빼기 합. 실사가 없으면 전부. */
	private static final String ADJUSTED_SINCE_COUNT_SQL = """
			WITH last_count AS (
			    SELECT DISTINCT ON (ingredient_id) ingredient_id, counted_at
			    FROM stock_count
			    WHERE store_id = :storeId
			    ORDER BY ingredient_id, counted_at DESC, id DESC
			)
			SELECT a.ingredient_id, SUM(a.qty) AS qty
			FROM stock_adjustment a
			LEFT JOIN last_count lc ON lc.ingredient_id = a.ingredient_id
			WHERE a.store_id = :storeId
			  AND (lc.counted_at IS NULL OR a.adjusted_at > lc.counted_at)
			GROUP BY a.ingredient_id
			""";

	private static final String FIRST_SALE_DATE_SQL = """
			SELECT MIN(sold_date) FROM pos_sale WHERE store_id = :storeId
			""";

	private static final String CLOSED_DAYS_SQL = """
			SELECT closed_date FROM store_closed_day
			WHERE store_id = :storeId AND closed_date BETWEEN :from AND :to
			""";

	/** 주기형 입고 기록. 같은 날 여러 줄은 날짜별로 합친다. */
	private static final String CYCLE_RECEIPTS_SQL = """
			SELECT ingredient_id, received_on, SUM(qty) AS qty
			FROM (
			    SELECT l.ingredient_id, d.doc_date AS received_on, l.qty * COALESCE(sp.base_qty_per_pack, 1) AS qty
			    FROM purchase_doc_line l
			    JOIN purchase_doc d ON d.id = l.doc_id AND d.store_id = :storeId
			    LEFT JOIN supplier_product sp ON sp.id = l.supplier_product_id AND sp.store_id = :storeId
			    WHERE l.store_id = :storeId AND l.ingredient_id IN (:ingredientIds)
			      AND l.qty IS NOT NULL AND l.status = 'CONFIRMED'
			      AND d.parse_status = 'CONFIRMED' AND d.doc_date IS NOT NULL AND d.purchase_order_id IS NULL
			      AND l.purchase_order_item_id IS NULL
			    UNION ALL
			    SELECT poi.ingredient_id, (poi.received_at AT TIME ZONE :tz)::date, poi.received_qty * COALESCE(sp.base_qty_per_pack, 1)
			    FROM purchase_order_item poi
			    JOIN supplier_product sp ON sp.id = poi.supplier_product_id AND sp.store_id = :storeId
			    WHERE poi.store_id = :storeId AND poi.ingredient_id IN (:ingredientIds)
			      AND poi.received_qty IS NOT NULL AND poi.received_at IS NOT NULL
			) r
			GROUP BY ingredient_id, received_on
			ORDER BY ingredient_id, received_on
			""";

	/** 하루 전체 판매 그릇 수(메뉴 · 채널 합산). 주기형 그릇당 소비량의 분모. */
	private static final String DAILY_BOWLS_SQL = """
			SELECT ps.sold_date, SUM(ps.qty) AS qty
			FROM pos_sale ps
			JOIN menu m ON m.id = ps.menu_id AND m.store_id = :storeId
			WHERE ps.store_id = :storeId AND ps.sold_date BETWEEN :from AND :to
			GROUP BY ps.sold_date
			""";

	private final StoreRepository storeRepository;
	private final IngredientRepository ingredientRepository;
	private final ConsumptionService consumptionService;
	private final NamedParameterJdbcTemplate jdbc;
	private final StoreClock storeClock;

	/** 가게 시간대의 오늘 기준. */
	@Transactional(readOnly = true)
	public List<InventoryEstimateResponse> estimate(long storeId) {
		return estimate(storeId, null);
	}

	/**
	 * @param today 기준일. null 이면 가게 시간대의 오늘. 테스트가 날짜를 고정할 때 넘긴다
	 * @return 재료 id 오름차순. 재료가 없으면 빈 목록
	 */
	@Transactional(readOnly = true)
	public List<InventoryEstimateResponse> estimate(long storeId, LocalDate today) {
		return estimateRun(storeId, today, ingredientRepository.findAllByStoreIdOrderByIdAsc(storeId)).estimates();
	}

	/**
	 * 추정과 함께, 추정하느라 읽은 공통 이력(소비량 · 첫 판매일 · 휴무일)도 돌려준다. 발주 추천(RecommendationService)이
	 * 같은 SQL 을 다시 보내지 않고 안전재고 계산에 그대로 쓴다 (지라 158).
	 *
	 * @param today       기준일. null 이면 가게 시간대의 오늘
	 * @param ingredients 가게의 재료 전부(id 오름차순). 호출자가 이미 읽었으면 넘긴다
	 */
	@Transactional(readOnly = true)
	public EstimateRun estimateRun(long storeId, LocalDate today, List<Ingredient> ingredients) {
		Store store = storeRepository.findById(storeId)
				.orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
		ZoneId zone = ZoneId.of(store.getTimezone());
		LocalDate baseDate = today != null ? today : storeClock.today(storeId, zone);
		LocalDate historyFrom = baseDate.minusDays(HISTORY_DAYS);
		if (ingredients.isEmpty()) {
			return new EstimateRun(List.of(), Map.of(), null, Set.of(), historyFrom, baseDate);
		}

		Map<Long, LastCount> lastCounts = lastCounts(storeId);
		LocalDate firstSaleDate = firstSaleDate(storeId);
		Set<LocalDate> closedDays = closedDays(storeId, historyFrom, baseDate.plusDays(DailyRate.MAX_LOOKAHEAD_DAYS));

		List<Ingredient> flowIngredients = ingredients.stream().filter(i -> i.getKind() != IngredientKind.CYCLE).toList();
		List<Ingredient> cycleIngredients = ingredients.stream().filter(i -> i.getKind() == IngredientKind.CYCLE).toList();

		Map<Long, InventoryEstimateResponse> results = new HashMap<>();
		Map<Long, Map<LocalDate, BigDecimal>> consumption = Map.of();
		if (!flowIngredients.isEmpty()) {
			consumption = estimateFlow(storeId, zone, baseDate, flowIngredients, lastCounts, firstSaleDate, historyFrom,
					closedDays, results);
		}
		if (!cycleIngredients.isEmpty()) {
			estimateCycle(storeId, zone, baseDate, cycleIngredients, firstSaleDate, historyFrom, closedDays, results);
		}
		return new EstimateRun(ingredients.stream().map(i -> results.get(i.getId())).toList(), consumption, firstSaleDate,
				closedDays, historyFrom, baseDate);
	}

	// ---------------------------------------------------------------- BOM · 1:1

	/** @return 읽은 날짜별 소비량(재료 → 날짜 → 수량). 구간은 historyFrom 부터 today 까지 */
	private Map<Long, Map<LocalDate, BigDecimal>> estimateFlow(long storeId, ZoneId zone, LocalDate today,
			List<Ingredient> ingredients, Map<Long, LastCount> lastCounts, LocalDate firstSaleDate, LocalDate historyFrom,
			Set<LocalDate> closedDays, Map<Long, InventoryEstimateResponse> out) {
		Map<Long, BigDecimal> received = receivedSinceCount(storeId, zone);
		Map<Long, BigDecimal> adjusted = adjustedSinceCount(storeId);

		// 날짜별 소비량은 요일 평균에 필요한 historyFrom ~ 오늘만 읽는다. 실사일이 그보다 앞서거나 실사가 없는 재료는
		// historyFrom 전날까지를 재료별 합계 한 줄로 따로 받는다 — 날짜 × 재료 줄을 1년치 돌려받지 않는다 (지라 157)
		Map<Long, Map<LocalDate, BigDecimal>> consumption = consumptionByIngredient(storeId, historyFrom, today);
		Map<Long, BigDecimal> consumedBeforeHistory = needsEarlierTotal(ingredients, lastCounts, firstSaleDate, historyFrom,
				zone) ? consumptionService.totalSinceCountBefore(storeId, zone, historyFrom) : Map.of();

		for (Ingredient ingredient : ingredients) {
			long id = ingredient.getId();
			LastCount count = lastCounts.get(id);
			Map<LocalDate, BigDecimal> daily = consumption.getOrDefault(id, Map.of());

			LocalDate since = count != null ? count.salesFrom(zone) : null;
			BigDecimal consumed = daily.entrySet().stream()
					.filter(e -> since == null || !e.getKey().isBefore(since))
					.map(Map.Entry::getValue)
					.reduce(consumedBeforeHistory.getOrDefault(id, BigDecimal.ZERO), BigDecimal::add);
			BigDecimal raw = (count != null ? count.qty() : BigDecimal.ZERO)
					.add(received.getOrDefault(id, BigDecimal.ZERO))
					.subtract(consumed)
					.subtract(adjusted.getOrDefault(id, BigDecimal.ZERO));
			boolean negative = raw.signum() < 0;
			BigDecimal estimated = negative ? BigDecimal.ZERO : raw;

			DailyRate rate = DailyRate.of(daily, laterOf(historyFrom, firstSaleDate), today.minusDays(1), closedDays);
			LocalDate runOut = rate != null ? rate.runOutDate(estimated, today, closedDays)
					: (estimated.signum() <= 0 ? today : null);

			out.put(id, new InventoryEstimateResponse(
					String.valueOf(id),
					ingredient.getName(),
					ingredient.getUnit(),
					ingredient.getKind(),
					estimated.setScale(QTY_SCALE, RoundingMode.HALF_UP),
					count == null || negative,
					runOut == null ? null : runOut.toString(),
					count == null ? null : count.countedAt().atZoneSameInstant(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
					count == null ? EstimateBasis.NO_COUNT : EstimateBasis.COUNT));
		}
		return consumption;
	}

	/**
	 * historyFrom 전날까지의 소비량 합계를 따로 읽어야 하는가. 실사 없는 재료가 있고 첫 판매일이 historyFrom 보다 앞서거나,
	 * 실사일이 historyFrom 보다 앞선 재료가 하나라도 있으면 참.
	 */
	private static boolean needsEarlierTotal(List<Ingredient> ingredients, Map<Long, LastCount> lastCounts,
			LocalDate firstSaleDate, LocalDate historyFrom, ZoneId zone) {
		for (Ingredient ingredient : ingredients) {
			LastCount count = lastCounts.get(ingredient.getId());
			LocalDate since = count != null ? count.salesFrom(zone) : firstSaleDate;
			if (since != null && since.isBefore(historyFrom)) {
				return true;
			}
		}
		return false;
	}

	private Map<Long, Map<LocalDate, BigDecimal>> consumptionByIngredient(long storeId, LocalDate from, LocalDate to) {
		Map<Long, Map<LocalDate, BigDecimal>> result = new HashMap<>();
		if (from.isAfter(to)) {
			return result;
		}
		for (DailyConsumptionResponse row : consumptionService.daily(storeId, from, to)) {
			result.computeIfAbsent(Long.parseLong(row.ingredientId()), k -> new HashMap<>())
					.merge(LocalDate.parse(row.date()), row.qty(), BigDecimal::add);
		}
		return result;
	}

	// ---------------------------------------------------------------- 주기형

	private void estimateCycle(long storeId, ZoneId zone, LocalDate today, List<Ingredient> ingredients,
			LocalDate firstSaleDate, LocalDate historyFrom, Set<LocalDate> closedDays,
			Map<Long, InventoryEstimateResponse> out) {
		List<Long> ids = ingredients.stream().map(Ingredient::getId).toList();
		Map<Long, TreeMap<LocalDate, BigDecimal>> receipts = cycleReceipts(storeId, zone, ids);

		LocalDate bowlsFrom = historyFrom;
		for (TreeMap<LocalDate, BigDecimal> r : receipts.values()) {
			if (!r.isEmpty() && r.firstKey().isBefore(bowlsFrom)) {
				bowlsFrom = r.firstKey();
			}
		}
		Map<LocalDate, BigDecimal> bowls = dailyBowls(storeId, bowlsFrom, today);
		DailyRate bowlRate = DailyRate.of(bowls, laterOf(historyFrom, firstSaleDate), today.minusDays(1), closedDays);

		for (Ingredient ingredient : ingredients) {
			long id = ingredient.getId();
			TreeMap<LocalDate, BigDecimal> r = receipts.getOrDefault(id, new TreeMap<>());
			out.put(id, cycleLine(ingredient, r, bowls, bowlRate, today, closedDays));
		}
	}

	private InventoryEstimateResponse cycleLine(Ingredient ingredient, TreeMap<LocalDate, BigDecimal> receipts,
			Map<LocalDate, BigDecimal> bowls, DailyRate bowlRate, LocalDate today, Set<LocalDate> closedDays) {
		if (receipts.size() < 2) {
			return cycleResponse(ingredient, null, false, null, EstimateBasis.CYCLE_COLLECTING);
		}
		LocalDate first = receipts.firstKey();
		LocalDate last = receipts.lastKey();
		BigDecimal lastQty = receipts.get(last);
		BigDecimal earlierQty = receipts.headMap(last, false).values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal bowlsBetween = sumBowls(bowls, first, last.minusDays(1));

		if (bowlsBetween.signum() <= 0) {
			long intervalDays = Math.round((double) ChronoUnit.DAYS.between(first, last) / (receipts.size() - 1));
			LocalDate runOut = last.plusDays(intervalDays);
			return cycleResponse(ingredient, null, false, runOut.isBefore(today) ? today : runOut, EstimateBasis.CYCLE_INTERVAL);
		}

		BigDecimal perBowl = earlierQty.divide(bowlsBetween, MC);
		BigDecimal bowlsSince = sumBowls(bowls, last, today);
		BigDecimal raw = lastQty.subtract(perBowl.multiply(bowlsSince));
		boolean negative = raw.signum() < 0;
		BigDecimal estimated = negative ? BigDecimal.ZERO : raw;

		LocalDate runOut;
		if (bowlRate == null) {
			runOut = estimated.signum() <= 0 ? today : null;
		} else {
			runOut = bowlRate.scaled(perBowl).runOutDate(estimated, today, closedDays);
		}
		return cycleResponse(ingredient, estimated.setScale(QTY_SCALE, RoundingMode.HALF_UP), negative, runOut,
				EstimateBasis.CYCLE_SALES);
	}

	private static InventoryEstimateResponse cycleResponse(Ingredient ingredient, BigDecimal estimated, boolean needsCount,
			LocalDate runOut, EstimateBasis basis) {
		return new InventoryEstimateResponse(
				String.valueOf(ingredient.getId()),
				ingredient.getName(),
				ingredient.getUnit(),
				ingredient.getKind(),
				estimated,
				needsCount,
				runOut == null ? null : runOut.toString(),
				null,
				basis);
	}

	private static BigDecimal sumBowls(Map<LocalDate, BigDecimal> bowls, LocalDate from, LocalDate to) {
		if (from.isAfter(to)) {
			return BigDecimal.ZERO;
		}
		return from.datesUntil(to.plusDays(1))
				.map(d -> bowls.getOrDefault(d, BigDecimal.ZERO))
				.reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	// ---------------------------------------------------------------- 조회

	private Map<Long, LastCount> lastCounts(long storeId) {
		Map<Long, LastCount> result = new HashMap<>();
		jdbc.query(LAST_COUNT_SQL, new MapSqlParameterSource("storeId", storeId), rs -> {
			result.put(rs.getLong("ingredient_id"),
					new LastCount(rs.getBigDecimal("qty"), rs.getObject("counted_at", OffsetDateTime.class),
							rs.getBoolean("counted_after_close")));
		});
		return result;
	}

	private Map<Long, BigDecimal> receivedSinceCount(long storeId, ZoneId zone) {
		Map<Long, BigDecimal> result = new HashMap<>();
		jdbc.query(RECEIVED_SINCE_COUNT_SQL, storeParams(storeId, zone), rs -> {
			result.put(rs.getLong("ingredient_id"), rs.getBigDecimal("qty"));
		});
		return result;
	}

	private Map<Long, BigDecimal> adjustedSinceCount(long storeId) {
		Map<Long, BigDecimal> result = new HashMap<>();
		jdbc.query(ADJUSTED_SINCE_COUNT_SQL, new MapSqlParameterSource("storeId", storeId), rs -> {
			result.put(rs.getLong("ingredient_id"), rs.getBigDecimal("qty"));
		});
		return result;
	}

	private LocalDate firstSaleDate(long storeId) {
		Date date = jdbc.queryForObject(FIRST_SALE_DATE_SQL, new MapSqlParameterSource("storeId", storeId), Date.class);
		return date == null ? null : date.toLocalDate();
	}

	private Set<LocalDate> closedDays(long storeId, LocalDate from, LocalDate to) {
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("storeId", storeId)
				.addValue("from", Date.valueOf(from))
				.addValue("to", Date.valueOf(to));
		return new HashSet<>(jdbc.query(CLOSED_DAYS_SQL, params, (rs, i) -> rs.getDate("closed_date").toLocalDate()));
	}

	private Map<Long, TreeMap<LocalDate, BigDecimal>> cycleReceipts(long storeId, ZoneId zone, List<Long> ingredientIds) {
		Map<Long, TreeMap<LocalDate, BigDecimal>> result = new HashMap<>();
		MapSqlParameterSource params = storeParams(storeId, zone).addValue("ingredientIds", ingredientIds);
		jdbc.query(CYCLE_RECEIPTS_SQL, params, rs -> {
			result.computeIfAbsent(rs.getLong("ingredient_id"), k -> new TreeMap<>())
					.merge(rs.getDate("received_on").toLocalDate(), rs.getBigDecimal("qty"), BigDecimal::add);
		});
		return result;
	}

	private Map<LocalDate, BigDecimal> dailyBowls(long storeId, LocalDate from, LocalDate to) {
		Map<LocalDate, BigDecimal> result = new HashMap<>();
		if (from.isAfter(to)) {
			return result;
		}
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("storeId", storeId)
				.addValue("from", Date.valueOf(from))
				.addValue("to", Date.valueOf(to));
		jdbc.query(DAILY_BOWLS_SQL, params, rs -> {
			result.put(rs.getDate("sold_date").toLocalDate(), rs.getBigDecimal("qty"));
		});
		return result;
	}

	private static MapSqlParameterSource storeParams(long storeId, ZoneId zone) {
		return new MapSqlParameterSource().addValue("storeId", storeId).addValue("tz", zone.getId());
	}

	/** 둘 중 늦은 날. {@code b} 가 null 이면 {@code a}. */
	private static LocalDate laterOf(LocalDate a, LocalDate b) {
		return b == null || a.isAfter(b) ? a : b;
	}

	/**
	 * 추정 결과와 그때 읽은 공통 이력.
	 *
	 * @param estimates        재료 id 오름차순 추정 (estimate 와 같다)
	 * @param dailyConsumption BOM · 1:1 재료의 날짜별 소비량. historyFrom ~ today 구간만 담는다
	 * @param firstSaleDate    가게 첫 판매일. 판매 기록이 없으면 null
	 * @param closedDays       historyFrom ~ today + 소진일 탐색 한도 사이의 휴무일
	 * @param historyFrom      요일 평균 · 안전재고 이력 시작일 = today − 28
	 * @param today            기준일
	 */
	public record EstimateRun(List<InventoryEstimateResponse> estimates,
			Map<Long, Map<LocalDate, BigDecimal>> dailyConsumption, LocalDate firstSaleDate, Set<LocalDate> closedDays,
			LocalDate historyFrom, LocalDate today) {
	}

	/** @param afterClose 마감 확인 실사(DECISIONS #92). 그날 판매가 끝난 뒤 값 */
	private record LastCount(BigDecimal qty, OffsetDateTime countedAt, boolean afterClose) {

		/** 이 실사 뒤로 소비를 빼기 시작하는 날. 영업 전 실사는 그날, 마감 확인 실사는 다음 날. */
		LocalDate salesFrom(ZoneId zone) {
			LocalDate date = countedAt.atZoneSameInstant(zone).toLocalDate();
			return afterClose ? date.plusDays(1) : date;
		}
	}
}
