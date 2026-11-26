package com.zigzaego.api.closingcheck.service;

import com.zigzaego.api.account.entity.Store;
import com.zigzaego.api.account.repository.StoreRepository;
import com.zigzaego.api.closingcheck.dto.ClosingCheckRequest;
import com.zigzaego.api.closingcheck.dto.ClosingCheckRequest.Adjustment;
import com.zigzaego.api.global.config.StoreClock;
import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.exception.CommonErrorCode;
import com.zigzaego.api.global.store.ResourceIds;
import com.zigzaego.api.ingredient.entity.Ingredient;
import com.zigzaego.api.ingredient.entity.IngredientKind;
import com.zigzaego.api.ingredient.repository.IngredientRepository;
import com.zigzaego.api.inventory.dto.InventoryEstimateResponse;
import com.zigzaego.api.inventory.dto.StockCountRequest;
import com.zigzaego.api.inventory.service.InventoryEstimateService;
import com.zigzaego.api.inventory.service.StockCountService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
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
 * 마감 확인 저장 (S15P31A308-252, DECISIONS #88 · #92). 빼기 기록과 실사를 한 트랜잭션에 넣는다.
 *
 * <ol>
 *   <li>빼기({@code stock_adjustment})를 먼저 넣는다. adjusted_at = 지금 − 1초, before = 지금 추정 재고(같은 요청의 앞 빼기를 뺀 값),
 *       after = max(0, before − qty)</li>
 *   <li>실사는 기존 실사 로직({@link StockCountService#record})으로 counted_at = 지금, 마감 확인 실사(counted_after_close)로 넣는다.
 *       같은 재료에 빼기와 실사가 함께 오면 실사가 최종값이다 — 실사가 빼기보다 1초 늦어 빼기는 기준점에 흡수된다</li>
 * </ol>
 *
 * <p>추정 재고 식은 "− Σ 빼기(마지막 실사 이후)" 를 뺀다({@link InventoryEstimateService}). 재료 하나라도 남의 가게 것이면 404 로
 * 아무것도 쓰지 않는다(ADR-0002). 주기형 재료는 실사 API 와 같이 400.
 */
@Service
@RequiredArgsConstructor
public class ClosingCheckService {

	private static final String INSERT_ADJUSTMENT_SQL = """
			INSERT INTO stock_adjustment (store_id, ingredient_id, adjusted_at, qty, reason, memo, before_qty, after_qty,
			    source_text)
			VALUES (:storeId, :ingredientId, :adjustedAt, :qty, :reason, :memo, :beforeQty, :afterQty, :sourceText)
			""";

	private final StoreRepository storeRepository;
	private final IngredientRepository ingredientRepository;
	private final InventoryEstimateService inventoryEstimateService;
	private final StockCountService stockCountService;
	private final NamedParameterJdbcTemplate jdbc;
	private final StoreClock clock;

	/**
	 * @return 바뀐 추정 재고 전체 ({@code GET /inventory/estimate} 와 같은 모양)
	 * @throws BusinessException 실사 · 빼기 둘 다 비었음 · 같은 재료 실사 두 번 · 주기형 재료는 400 COMMON_INVALID_INPUT,
	 *         남의 가게 · 없는 · 숫자 아닌 재료 id 는 404 COMMON_NOT_FOUND
	 */
	@Transactional
	public List<InventoryEstimateResponse> save(long storeId, ClosingCheckRequest request) {
		Store store = storeRepository.findById(storeId)
				.orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
		List<StockCountRequest.Line> counts = request.countsOrEmpty();
		List<Adjustment> adjustments = request.adjustmentsOrEmpty();
		if (counts.isEmpty() && adjustments.isEmpty()) {
			throw invalid("counts · adjustments (둘 다 비었습니다)");
		}
		ZoneId zone = ZoneId.of(store.getTimezone());
		Clock storeClock = clock.of(storeId);
		OffsetDateTime now = OffsetDateTime.now(storeClock);
		LocalDate today = LocalDate.now(storeClock.withZone(zone));

		checkIngredients(storeId, counts, adjustments);

		Map<Long, BigDecimal> estimated = new HashMap<>();
		for (InventoryEstimateResponse e : inventoryEstimateService.estimate(storeId, today)) {
			if (e.estimatedQty() != null) {
				estimated.put(Long.parseLong(e.ingredientId()), e.estimatedQty());
			}
		}

		// 실사 줄의 estimatedQty 를 비우면 빼기까지 반영한 추정치를 넣는다 — 말한 손실이 실사 차이(기록 안 된 손실)로 또 잡히지 않게
		Map<Long, BigDecimal> afterAdjustments = insertAdjustments(storeId, adjustments, now.minusSeconds(1), estimated,
				blankToNull(request.sourceText()));
		if (!counts.isEmpty()) {
			stockCountService.record(storeId, counts, now, true, afterAdjustments);
		}
		return inventoryEstimateService.estimate(storeId, today);
	}

	/** 실사 · 빼기에 나온 재료가 전부 내 가게의 주기형 아닌 재료인지. 실사의 줄 검사(같은 재료 두 번 등)는 StockCountService 가 한다. */
	private void checkIngredients(long storeId, List<StockCountRequest.Line> counts, List<Adjustment> adjustments) {
		Set<Long> ids = new LinkedHashSet<>();
		counts.forEach(c -> ids.add(ResourceIds.parse(c.ingredientId())));
		adjustments.forEach(a -> ids.add(ResourceIds.parse(a.ingredientId())));
		List<Ingredient> mine = ingredientRepository.findAllByStoreIdAndIdIn(storeId, ids);
		if (mine.size() != ids.size()) {
			throw new BusinessException(CommonErrorCode.NOT_FOUND);
		}
		for (Ingredient ingredient : mine) {
			if (ingredient.getKind() == IngredientKind.CYCLE) {
				throw invalid("ingredientId=" + ingredient.getId() + " (주기형 재료는 마감 확인을 받지 않습니다 — 입고 기록으로 계산합니다)");
			}
		}
	}

	/** @return 빼기를 반영한 재료별 추정 재고 */
	private Map<Long, BigDecimal> insertAdjustments(long storeId, List<Adjustment> adjustments, OffsetDateTime adjustedAt,
			Map<Long, BigDecimal> estimated, String sourceText) {
		Map<Long, BigDecimal> running = new HashMap<>(estimated);
		if (adjustments.isEmpty()) {
			return running;
		}
		List<SqlParameterSource> rows = new ArrayList<>();
		for (Adjustment a : adjustments) {
			long id = Long.parseLong(a.ingredientId());
			BigDecimal before = running.getOrDefault(id, BigDecimal.ZERO);
			BigDecimal after = before.subtract(a.qty()).max(BigDecimal.ZERO);
			running.put(id, after);
			rows.add(new MapSqlParameterSource()
					.addValue("storeId", storeId)
					.addValue("ingredientId", id)
					.addValue("adjustedAt", adjustedAt)
					.addValue("qty", a.qty())
					.addValue("reason", a.reason().name())
					.addValue("memo", blankToNull(a.memo()))
					.addValue("beforeQty", before)
					.addValue("afterQty", after)
					.addValue("sourceText", sourceText));
		}
		jdbc.batchUpdate(INSERT_ADJUSTMENT_SQL, rows.toArray(SqlParameterSource[]::new));
		return running;
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.strip();
	}

	private static BusinessException invalid(String detail) {
		return new BusinessException(CommonErrorCode.INVALID_INPUT, CommonErrorCode.INVALID_INPUT.getMessage() + " " + detail);
	}
}
