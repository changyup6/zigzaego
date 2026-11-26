package com.zigzaego.api.inventory.service;

import com.zigzaego.api.account.entity.Store;
import com.zigzaego.api.account.repository.StoreRepository;
import com.zigzaego.api.global.config.StoreClock;
import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.exception.CommonErrorCode;
import com.zigzaego.api.global.store.ResourceIds;
import com.zigzaego.api.ingredient.entity.Ingredient;
import com.zigzaego.api.ingredient.entity.IngredientKind;
import com.zigzaego.api.ingredient.repository.IngredientRepository;
import com.zigzaego.api.inventory.dto.InventoryEstimateResponse;
import com.zigzaego.api.inventory.dto.StockCountMethod;
import com.zigzaego.api.inventory.dto.StockCountRequest;
import com.zigzaego.api.inventory.dto.StockCountRequest.Line;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 실사 입력 (S15P31A308-47). 재료마다 {@code stock_count} 에 한 줄을 넣는다 — 덮어쓰지 않고 쌓는다.
 *
 * <p>실사가 하는 일은 행을 넣는 것뿐이다. 그 뒤는 이미 있는 읽기 쪽이 최신 행을 보고 따라온다:
 * <ul>
 *   <li>추정 재고({@link InventoryEstimateService})가 마지막 실사량을 기준점으로 쓰고 {@code needsCount=false} 가 된다
 *       (음수로 내려가 0 보정한 재료만 다시 true, DECISIONS #9)</li>
 *   <li>가게 설정 체크리스트({@code StoreService} STOCK_COUNT)가 끝남으로 바뀐다 (3주 계획 6절)</li>
 *   <li>영수증 날짜 분류가 가게의 마지막 실사일을 PAST · RECEIVE 경계로 쓴다 (설계 4.2절, {@code ReceiptAnalyzer})</li>
 * </ul>
 *
 * <p><b>실사는 영업 시작 전 값이다.</b> 판매는 날짜 단위(pos_sale.sold_date)라 추정 재고는 실사한 날의 판매량부터 뺀다
 * (InventoryEstimateService: 소비는 실사일 포함, 입고 확인은 실사 시각 뒤). 마감 뒤에 셌으면 다음 날 영업 전에 입력한다.
 *
 * <p>주기형(CYCLE) 재료는 받지 않는다 — 추정 재고가 입고 기록으로만 계산해(설계 5.1절) 실사값을 쓰지 않는데, 행만 쌓이면
 * 체크리스트와 영수증 경계에만 영향을 줘 헷갈린다.
 *
 * <p>한 트랜잭션. 재료 하나라도 내 가게 것이 아니면 404 로 끝나고 아무것도 쓰지 않는다 (ADR-0002).
 */
@Service
@RequiredArgsConstructor
public class StockCountService {

	/** 휴대폰 시계가 서버보다 조금 빨라도 "지금" 실사로 받는다. */
	static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(2);

	private static final String INSERT_SQL = """
			INSERT INTO stock_count (store_id, ingredient_id, counted_at, qty, estimated_qty, method, counted_after_close)
			VALUES (:storeId, :ingredientId, :countedAt, :qty, :estimatedQty, :method, :afterClose)
			""";

	/** 재료별 지금까지의 최신 실사 시각. 그보다 이른 실사는 기준점이 되지 못하므로 받지 않는다. */
	private static final String LATEST_COUNT_SQL = """
			SELECT ingredient_id, MAX(counted_at) AS counted_at
			FROM stock_count
			WHERE store_id = :storeId AND ingredient_id IN (:ingredientIds)
			GROUP BY ingredient_id
			""";

	private final StoreRepository storeRepository;
	private final IngredientRepository ingredientRepository;
	private final InventoryEstimateService inventoryEstimateService;
	private final NamedParameterJdbcTemplate jdbc;
	private final StoreClock clock;

	/**
	 * @return 바뀐 추정 재고 전체 ({@code GET /inventory/estimate} 와 같은 모양, 재료 id 오름차순)
	 * @throws BusinessException 남의 가게 · 없는 · 숫자 아닌 재료 id 는 404 COMMON_NOT_FOUND. 같은 재료 두 번 · 미래 시각 ·
	 *         그 재료의 기존 최신 실사보다 이른 시각 · 주기형 재료 · CONFIRMED · CORRECTED 인데 estimatedQty 없음은
	 *         400 COMMON_INVALID_INPUT
	 */
	@Transactional
	public List<InventoryEstimateResponse> count(long storeId, StockCountRequest request) {
		Store store = storeRepository.findById(storeId)
				.orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
		ZoneId zone = ZoneId.of(store.getTimezone());
		Clock storeClock = clock.of(storeId);
		OffsetDateTime now = OffsetDateTime.now(storeClock);
		OffsetDateTime countedAt = request.countedAt() != null ? request.countedAt() : now;
		if (countedAt.isAfter(now.plus(FUTURE_TOLERANCE))) {
			throw invalid("countedAt=" + countedAt + " (미래 시각으로는 실사할 수 없습니다)");
		}

		LocalDate today = LocalDate.now(storeClock.withZone(zone));
		record(storeId, request.counts(), countedAt, false, fullCountEstimates(storeId, today, request.counts()));
		return inventoryEstimateService.estimate(storeId, today);
	}

	/**
	 * 직접 다 센(FULL_COUNT) 줄의 estimated_qty 를 서버의 직전 추정치로 채운다 (지라 253) — 모든 실사에 추정 대비 차이가 남아야
	 * 기록 안 된 손실(추정 − 실사)을 셀 수 있다. CONFIRMED · CORRECTED 는 화면에 보여 준 값이 따로 있으므로 채우지 않는다(없으면 400).
	 */
	private Map<Long, BigDecimal> fullCountEstimates(long storeId, LocalDate today, List<Line> lines) {
		Set<Long> ids = new HashSet<>();
		for (Line line : lines) {
			if (line.methodOrDefault() == StockCountMethod.FULL_COUNT && line.estimatedQty() == null
					&& line.ingredientId() != null && line.ingredientId().matches("\\d{1,18}")) {
				ids.add(Long.parseLong(line.ingredientId()));
			}
		}
		if (ids.isEmpty()) {
			return Map.of();
		}
		Map<Long, BigDecimal> result = new HashMap<>();
		for (InventoryEstimateResponse e : inventoryEstimateService.estimate(storeId, today)) {
			long id = Long.parseLong(e.ingredientId());
			if (ids.contains(id) && e.estimatedQty() != null) {
				result.put(id, e.estimatedQty());
			}
		}
		return result;
	}

	/**
	 * 실사 줄을 검사하고 넣는다. 응답은 만들지 않는다 — 마감 확인 저장(ClosingCheckService, 지라 252)이 빼기 기록과 같은 트랜잭션에서
	 * 부른다. 호출자의 트랜잭션에 들어간다.
	 *
	 * @param afterClose        마감 확인 실사면 true — 추정 재고가 다음 날 판매부터 뺀다 (DECISIONS #92)
	 * @param estimatedFallback estimatedQty 가 빈 줄에 넣을 추정치(재료 id → 기준 단위). 비어 있으면 넣지 않는다
	 * @throws BusinessException {@link #count} 와 같은 404 · 400
	 */
	public void record(long storeId, List<Line> lines, OffsetDateTime countedAt, boolean afterClose,
			Map<Long, BigDecimal> estimatedFallback) {
		Map<Long, Line> byId = new HashMap<>();
		for (Line line : lines) {
			long id = ResourceIds.parse(line.ingredientId());
			if (byId.put(id, line) != null) {
				throw invalid("counts[ingredientId=" + line.ingredientId() + "] (같은 재료가 두 번 들어 있습니다)");
			}
			if (line.methodOrDefault() != StockCountMethod.FULL_COUNT && line.estimatedQty() == null
					&& !estimatedFallback.containsKey(id)) {
				throw invalid("counts[ingredientId=" + line.ingredientId() + "].estimatedQty ("
						+ line.methodOrDefault() + " 은 화면에 보여 준 추정치가 있어야 합니다)");
			}
		}
		Set<Long> ids = new LinkedHashSet<>(byId.keySet());

		List<Ingredient> mine = ingredientRepository.findAllByStoreIdAndIdIn(storeId, ids);
		if (mine.size() != ids.size()) {
			throw new BusinessException(CommonErrorCode.NOT_FOUND);
		}
		for (Ingredient ingredient : mine) {
			if (ingredient.getKind() == IngredientKind.CYCLE) {
				throw invalid("counts[ingredientId=" + ingredient.getId() + "] (주기형 재료는 실사하지 않습니다 — 입고 기록으로 계산합니다)");
			}
		}
		rejectEarlierThanLatest(storeId, ids, countedAt);

		SqlParameterSource[] rows = byId.entrySet().stream()
				.map(e -> new MapSqlParameterSource()
						.addValue("storeId", storeId)
						.addValue("ingredientId", e.getKey())
						.addValue("countedAt", countedAt)
						.addValue("qty", e.getValue().qty())
						.addValue("estimatedQty", e.getValue().estimatedQty() != null ? e.getValue().estimatedQty()
								: estimatedFallback.get(e.getKey()))
						.addValue("method", e.getValue().methodOrDefault().name())
						.addValue("afterClose", afterClose))
				.toArray(SqlParameterSource[]::new);
		jdbc.batchUpdate(INSERT_SQL, rows);
	}

	/** 기존 최신 실사보다 이른 시각은 넣어도 기준점이 바뀌지 않아 아무 일도 안 일어난 것처럼 보인다. 400 으로 알린다. */
	private void rejectEarlierThanLatest(long storeId, Set<Long> ids, OffsetDateTime countedAt) {
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("storeId", storeId)
				.addValue("ingredientIds", ids);
		jdbc.query(LATEST_COUNT_SQL, params, rs -> {
			OffsetDateTime latest = rs.getObject("counted_at", OffsetDateTime.class);
			if (countedAt.isBefore(latest)) {
				throw invalid("countedAt=" + countedAt + " (재료 " + rs.getLong("ingredient_id") + " 의 마지막 실사 "
						+ latest + " 보다 이릅니다)");
			}
		});
	}

	private static BusinessException invalid(String detail) {
		return new BusinessException(CommonErrorCode.INVALID_INPUT, CommonErrorCode.INVALID_INPUT.getMessage() + " " + detail);
	}
}
