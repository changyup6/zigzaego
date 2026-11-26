package com.zigzaego.api.forecast.service;

import com.zigzaego.api.account.entity.Store;
import com.zigzaego.api.account.repository.StoreRepository;
import com.zigzaego.api.forecast.dto.ForecastModel;
import com.zigzaego.api.forecast.dto.MenuForecastResponse;
import com.zigzaego.api.global.config.StoreClock;
import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.exception.CommonErrorCode;
import com.zigzaego.api.global.response.Amounts;
import com.zigzaego.api.menu.entity.Menu;
import com.zigzaego.api.menu.repository.MenuRepository;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 메뉴별 대상일 예측 판매량 (DECISIONS #24, 설계 5.2절). 조회 때 계산하고 저장하지 않는다.
 *
 * <pre>
 * 메뉴마다
 *   대상일 forecast(model='timesfm') 행이 있으면 → 채널(DELIVERY · HALL · NONE) 합산, model=TIMESFM
 *   없으면                                     → 채널별 요일 평균({@link DowAverage})을 합산, model=DOW_AVG
 *     이력 끝 = 대상일 전날과 어제 중 이른 날 (오늘은 아직 진행 중)
 *     채널 이력(그 채널의 가게 첫 판매일부터)이 4주 미만이면 그 채널은 14일 평균 — 배달은 1년, 홀은 2주인 가게가 있다
 * 활성(active) 메뉴만, 메뉴 id 오름차순. 판매 · 예측 기록이 없는 메뉴는 0 (DOW_AVG)
 * </pre>
 *
 * 모든 SQL 에 store_id 를 건다 (ADR-0002). 오늘은 가게 시간대({@code store.timezone}) 기준.
 */
@Service
@RequiredArgsConstructor
public class ForecastService {

	private static final String TIMESFM_SQL = """
			SELECT f.target_date, f.menu_id, SUM(f.predicted_qty) AS qty
			FROM forecast f
			JOIN menu m ON m.id = f.menu_id AND m.store_id = :storeId
			WHERE f.store_id = :storeId
			  AND f.target_date BETWEEN :from AND :to
			  AND f.model = :model
			GROUP BY f.target_date, f.menu_id
			""";

	private static final String CHANNEL_FIRST_SALE_SQL = """
			SELECT channel, MIN(sold_date) AS first_sale
			FROM pos_sale
			WHERE store_id = :storeId
			GROUP BY channel
			""";

	private static final String DAILY_SALES_SQL = """
			SELECT ps.menu_id, ps.channel, ps.sold_date, SUM(ps.qty) AS qty
			FROM pos_sale ps
			JOIN menu m ON m.id = ps.menu_id AND m.store_id = :storeId
			WHERE ps.store_id = :storeId
			  AND ps.menu_id IN (:menuIds)
			  AND ps.sold_date BETWEEN :from AND :to
			GROUP BY ps.menu_id, ps.channel, ps.sold_date
			""";

	private static final String CLOSED_DAYS_SQL = """
			SELECT closed_date FROM store_closed_day
			WHERE store_id = :storeId AND closed_date BETWEEN :from AND :to
			""";

	private final StoreRepository storeRepository;
	private final MenuRepository menuRepository;
	private final NamedParameterJdbcTemplate jdbc;
	private final StoreClock storeClock;

	/** 가게 시간대의 오늘 기준. {@code targetDate} 가 null 이면 오늘을 예측한다. */
	@Transactional(readOnly = true)
	public List<MenuForecastResponse> forecast(long storeId, LocalDate targetDate) {
		return forecast(storeId, targetDate, null);
	}

	/**
	 * @param targetDate 대상일. null 이면 오늘
	 * @param today      오늘. null 이면 가게 시간대의 오늘. 테스트가 날짜를 고정할 때 넘긴다
	 * @return 활성 메뉴 id 오름차순. 메뉴가 없으면 빈 목록
	 * @throws BusinessException 가게가 없으면 404 COMMON_NOT_FOUND
	 */
	@Transactional(readOnly = true)
	public List<MenuForecastResponse> forecast(long storeId, LocalDate targetDate, LocalDate today) {
		Store store = storeRepository.findById(storeId)
				.orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
		LocalDate baseToday = today != null ? today : storeClock.today(storeId, ZoneId.of(store.getTimezone()));
		LocalDate target = targetDate != null ? targetDate : baseToday;
		return forecastRange(storeId, target, target, baseToday).getOrDefault(target, List.of());
	}

	/**
	 * 대상일 구간(from · to 포함)을 한 번에 예측한다. 날마다 {@link #forecast} 를 부르는 것과 결과는 같고, timesfm · 판매 ·
	 * 휴무일 조회를 구간 전체에 한 번씩만 보낸다 (지라 158 — 발주 추천이 생성일+1 … +L+R' 을 묻는다).
	 *
	 * @param today 오늘. null 이면 가게 시간대의 오늘
	 * @return 대상일 → 활성 메뉴 id 오름차순 예측. 메뉴가 없으면 모든 날이 빈 목록
	 * @throws BusinessException 가게가 없으면 404 COMMON_NOT_FOUND, from &gt; to 면 400 COMMON_INVALID_INPUT
	 */
	@Transactional(readOnly = true)
	public Map<LocalDate, List<MenuForecastResponse>> forecastRange(long storeId, LocalDate from, LocalDate to,
			LocalDate today) {
		if (from.isAfter(to)) {
			throw new BusinessException(CommonErrorCode.INVALID_INPUT, "from 은 to 보다 늦을 수 없습니다.");
		}
		Store store = storeRepository.findById(storeId)
				.orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
		LocalDate baseToday = today != null ? today : storeClock.today(storeId, ZoneId.of(store.getTimezone()));
		List<LocalDate> targets = from.datesUntil(to.plusDays(1)).toList();

		List<Menu> menus = menuRepository.findAllByStoreIdOrderByIdAsc(storeId).stream()
				.filter(Menu::isActive)
				.toList();
		Map<LocalDate, List<MenuForecastResponse>> result = new LinkedHashMap<>();
		if (menus.isEmpty()) {
			targets.forEach(t -> result.put(t, List.of()));
			return result;
		}

		Map<LocalDate, Map<Long, BigDecimal>> timesfm = timesfm(storeId, from, to);
		Map<LocalDate, Map<Long, BigDecimal>> dowAvg = dowAverage(storeId, menus, timesfm, targets, baseToday);

		for (LocalDate target : targets) {
			Map<Long, BigDecimal> batch = timesfm.getOrDefault(target, Map.of());
			Map<Long, BigDecimal> fallback = dowAvg.getOrDefault(target, Map.of());
			List<MenuForecastResponse> lines = new ArrayList<>(menus.size());
			for (Menu menu : menus) {
				BigDecimal fromBatch = batch.get(menu.getId());
				ForecastModel model = fromBatch != null ? ForecastModel.TIMESFM : ForecastModel.DOW_AVG;
				BigDecimal qty = fromBatch != null ? fromBatch : fallback.getOrDefault(menu.getId(), BigDecimal.ZERO);
				lines.add(new MenuForecastResponse(
						String.valueOf(menu.getId()),
						menu.getName(),
						target.toString(),
						Amounts.qty(qty),
						model));
			}
			result.put(target, lines);
		}
		return result;
	}

	// ---------------------------------------------------------------- 요일 평균

	/**
	 * 대상일마다 timesfm 행이 없는 메뉴의 요일 평균. 이력 끝은 대상일마다 다르지만(대상일 전날과 어제 중 이른 날) 판매 · 휴무일은
	 * 모든 대상일의 이력 구간을 합친 범위로 한 번만 읽는다 — {@link DowAverage#predict} 는 자기 구간 밖 날짜를 보지 않는다.
	 *
	 * @return 대상일 → 메뉴 id → 예측. 그날 요일 평균이 필요한 메뉴가 없으면 그날 항목이 없다
	 */
	private Map<LocalDate, Map<Long, BigDecimal>> dowAverage(long storeId, List<Menu> menus,
			Map<LocalDate, Map<Long, BigDecimal>> timesfm, List<LocalDate> targets, LocalDate today) {
		Map<LocalDate, List<Long>> fallbackByTarget = new LinkedHashMap<>();
		Set<Long> fallbackIds = new LinkedHashSet<>();
		for (LocalDate target : targets) {
			Map<Long, BigDecimal> batch = timesfm.getOrDefault(target, Map.of());
			List<Long> ids = menus.stream().map(Menu::getId).filter(id -> !batch.containsKey(id)).toList();
			if (!ids.isEmpty()) {
				fallbackByTarget.put(target, ids);
				fallbackIds.addAll(ids);
			}
		}
		Map<LocalDate, Map<Long, BigDecimal>> result = new HashMap<>();
		if (fallbackByTarget.isEmpty()) {
			return result;
		}
		Map<String, LocalDate> channelFirstSale = channelFirstSale(storeId);
		if (channelFirstSale.isEmpty()) {
			return result;
		}

		LocalDate rangeEnd = historyEnd(targets.getLast(), today);
		LocalDate rangeStart = historyEnd(targets.getFirst(), today).minusDays(DowAverage.HISTORY_DAYS - 1);
		Set<LocalDate> closedDays = closedDays(storeId, rangeStart, rangeEnd);
		Map<Long, Map<String, Map<LocalDate, BigDecimal>>> sales = dailySales(storeId, List.copyOf(fallbackIds),
				rangeStart, rangeEnd);

		for (Map.Entry<LocalDate, List<Long>> e : fallbackByTarget.entrySet()) {
			LocalDate target = e.getKey();
			LocalDate end = historyEnd(target, today);
			Map<Long, BigDecimal> byMenu = new HashMap<>();
			for (Long menuId : e.getValue()) {
				Map<String, Map<LocalDate, BigDecimal>> byChannel = sales.getOrDefault(menuId, Map.of());
				BigDecimal total = BigDecimal.ZERO;
				for (Map.Entry<String, LocalDate> channel : channelFirstSale.entrySet()) {
					Map<LocalDate, BigDecimal> daily = byChannel.getOrDefault(channel.getKey(), Map.of());
					total = total.add(DowAverage.predict(daily, target, channel.getValue(), end, closedDays));
				}
				byMenu.put(menuId, total);
			}
			result.put(target, byMenu);
		}
		return result;
	}

	/** 요일 평균 이력 끝 = 대상일 전날과 어제 중 이른 날 (오늘은 아직 진행 중). */
	private static LocalDate historyEnd(LocalDate target, LocalDate today) {
		return earlierOf(target.minusDays(1), today.minusDays(1));
	}

	// ---------------------------------------------------------------- 조회

	/** @return 대상일 → 메뉴 id → timesfm 예측(채널 합산). 행이 없는 날은 항목이 없다 */
	private Map<LocalDate, Map<Long, BigDecimal>> timesfm(long storeId, LocalDate from, LocalDate to) {
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("storeId", storeId)
				.addValue("from", Date.valueOf(from))
				.addValue("to", Date.valueOf(to))
				.addValue("model", ForecastModel.TIMESFM.columnValue());
		Map<LocalDate, Map<Long, BigDecimal>> result = new HashMap<>();
		jdbc.query(TIMESFM_SQL, params, rs -> {
			result.computeIfAbsent(rs.getDate("target_date").toLocalDate(), k -> new HashMap<>())
					.put(rs.getLong("menu_id"), rs.getBigDecimal("qty"));
		});
		return result;
	}

	private Map<String, LocalDate> channelFirstSale(long storeId) {
		Map<String, LocalDate> result = new HashMap<>();
		jdbc.query(CHANNEL_FIRST_SALE_SQL, new MapSqlParameterSource("storeId", storeId), rs -> {
			result.put(rs.getString("channel"), rs.getDate("first_sale").toLocalDate());
		});
		return result;
	}

	private Map<Long, Map<String, Map<LocalDate, BigDecimal>>> dailySales(long storeId, List<Long> menuIds,
			LocalDate from, LocalDate to) {
		Map<Long, Map<String, Map<LocalDate, BigDecimal>>> result = new HashMap<>();
		if (from.isAfter(to)) {
			return result;
		}
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("storeId", storeId)
				.addValue("menuIds", menuIds)
				.addValue("from", Date.valueOf(from))
				.addValue("to", Date.valueOf(to));
		jdbc.query(DAILY_SALES_SQL, params, rs -> {
			result.computeIfAbsent(rs.getLong("menu_id"), k -> new HashMap<>())
					.computeIfAbsent(rs.getString("channel"), k -> new HashMap<>())
					.put(rs.getDate("sold_date").toLocalDate(), rs.getBigDecimal("qty"));
		});
		return result;
	}

	private Set<LocalDate> closedDays(long storeId, LocalDate from, LocalDate to) {
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("storeId", storeId)
				.addValue("from", Date.valueOf(from))
				.addValue("to", Date.valueOf(to));
		return new HashSet<>(jdbc.query(CLOSED_DAYS_SQL, params, (rs, i) -> rs.getDate("closed_date").toLocalDate()));
	}

	private static LocalDate earlierOf(LocalDate a, LocalDate b) {
		return a.isBefore(b) ? a : b;
	}
}
