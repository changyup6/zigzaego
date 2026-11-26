package com.zigzaego.api.closingcheck.service;

import com.zigzaego.api.account.entity.Store;
import com.zigzaego.api.account.repository.StoreRepository;
import com.zigzaego.api.closingcheck.dto.ClosingItemReason;
import com.zigzaego.api.closingcheck.dto.ClosingItemResponse;
import com.zigzaego.api.global.config.StoreClock;
import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.exception.CommonErrorCode;
import com.zigzaego.api.ingredient.entity.IngredientKind;
import com.zigzaego.api.inventory.dto.InventoryEstimateResponse;
import com.zigzaego.api.inventory.service.CountShortfallService;
import com.zigzaego.api.inventory.service.InventoryEstimateService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 오늘 마감 때 확인할 재료 (S15P31A308-249, DECISIONS #88 ②). 확인이 필요한 재료만, 최대 {@value #MAX_ITEMS}개.
 *
 * <pre>
 * 대상   주기형(CYCLE) 아닌 재료 중 아래 하나에 걸리는 것. 오늘(가게 시간대) 이미 실사한 재료는 뺀다
 * 순위   ① 실사 필요(needsCount)  ② 실사 차이 반복(CountShortfallService)  ③ 소진 예상일 ≤ 오늘 + 3일
 *        ④ 마지막 실사가 7일보다 오래됨. 여러 개에 걸리면 앞 순위
 * 같은 순위 안  포장 단위가 있는 재료 먼저 → ③ 은 소진일 이른 순, ② 는 손실 큰 순, ④ 는 실사 오래된 순 → 재료 id
 * 포장   그 재료의 가장 최근 입고(입고 확인한 발주 줄 · 확정 매입 문서 줄)의 거래처 상품, 없으면 대표 상품.
 *        pack_unit · base_qty_per_pack 이 둘 다 있어야 포장 단위로 묻는다
 * </pre>
 *
 * 조건에 걸리는 재료가 없으면 빈 목록 — 마감 알림(지라 248)은 그날 보내지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ClosingItemService {

	public static final int MAX_ITEMS = 5;
	/** 소진 예상일이 오늘부터 이 날수 안이면 묻는다. */
	static final int RUNNING_OUT_DAYS = 3;
	/** 마지막 실사가 이 날수보다 오래되면 묻는다. */
	static final int STALE_COUNT_DAYS = 7;

	private static final BigDecimal HALF = new BigDecimal("0.5");
	private static final BigDecimal TWO = BigDecimal.valueOf(2);

	/** 재료별 가장 최근 입고의 거래처 상품. 같은 시각이면 발주 줄 먼저. */
	private static final String LATEST_RECEIVED_PRODUCT_SQL = """
			SELECT DISTINCT ON (r.ingredient_id) r.ingredient_id, sp.pack_unit, sp.base_qty_per_pack
			FROM (
			    SELECT poi.ingredient_id, poi.supplier_product_id, poi.received_at AS at, 0 AS src
			    FROM purchase_order_item poi
			    WHERE poi.store_id = :storeId AND poi.received_at IS NOT NULL AND poi.supplier_product_id IS NOT NULL
			    UNION ALL
			    SELECT l.ingredient_id, l.supplier_product_id, (d.doc_date::timestamp AT TIME ZONE :tz) AS at, 1 AS src
			    FROM purchase_doc_line l
			    JOIN purchase_doc d ON d.id = l.doc_id AND d.store_id = :storeId
			    WHERE l.store_id = :storeId AND l.ingredient_id IS NOT NULL AND l.supplier_product_id IS NOT NULL
			      AND l.status = 'CONFIRMED' AND d.parse_status = 'CONFIRMED' AND d.doc_date IS NOT NULL
			) r
			JOIN supplier_product sp ON sp.id = r.supplier_product_id AND sp.store_id = :storeId
			ORDER BY r.ingredient_id, r.at DESC, r.src
			""";

	/** 대표 상품 — 입고 기록이 없는 재료의 포장. */
	private static final String PRIMARY_PRODUCT_SQL = """
			SELECT i.id AS ingredient_id, sp.pack_unit, sp.base_qty_per_pack
			FROM ingredient i
			JOIN supplier_product sp ON sp.id = i.primary_supplier_product_id AND sp.store_id = :storeId
			WHERE i.store_id = :storeId
			""";

	private final StoreRepository storeRepository;
	private final InventoryEstimateService inventoryEstimateService;
	private final CountShortfallService countShortfallService;
	private final NamedParameterJdbcTemplate jdbc;
	private final StoreClock clock;

	@Transactional(readOnly = true)
	public List<ClosingItemResponse> items(long storeId) {
		Store store = storeRepository.findById(storeId)
				.orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
		ZoneId zone = ZoneId.of(store.getTimezone());
		LocalDate today = LocalDate.now(clock.of(storeId).withZone(zone));
		Map<Long, BigDecimal> losses = countShortfallService.averageLosses(storeId);
		Map<Long, Pack> packs = packs(storeId, zone);

		List<Candidate> candidates = new ArrayList<>();
		for (InventoryEstimateResponse e : inventoryEstimateService.estimate(storeId, today)) {
			if (e.kind() == IngredientKind.CYCLE || e.estimatedQty() == null) {
				continue;
			}
			long id = Long.parseLong(e.ingredientId());
			LocalDate lastCount = e.lastCountedAt() == null ? null
					: OffsetDateTime.parse(e.lastCountedAt()).atZoneSameInstant(zone).toLocalDate();
			if (today.equals(lastCount)) {
				continue;
			}
			LocalDate runOut = e.runOutDate() == null ? null : LocalDate.parse(e.runOutDate());
			ClosingItemReason reason;
			if (e.needsCount()) {
				reason = ClosingItemReason.NEEDS_COUNT;
			} else if (losses.containsKey(id)) {
				reason = ClosingItemReason.REPEATED_LOSS;
			} else if (runOut != null && !runOut.isAfter(today.plusDays(RUNNING_OUT_DAYS))) {
				reason = ClosingItemReason.RUNNING_OUT;
			} else if (lastCount != null && lastCount.isBefore(today.minusDays(STALE_COUNT_DAYS))) {
				reason = ClosingItemReason.STALE_COUNT;
			} else {
				continue;
			}
			candidates.add(new Candidate(id, e, reason, packs.get(id), losses.getOrDefault(id, BigDecimal.ZERO), runOut,
					lastCount));
		}

		return candidates.stream()
				.sorted(Comparator.comparing(Candidate::reason)
						.thenComparing(c -> c.pack() == null)
						.thenComparing(Candidate::withinRank)
						.thenComparingLong(Candidate::id))
				.limit(MAX_ITEMS)
				.map(ClosingItemService::toResponse)
				.toList();
	}

	private Map<Long, Pack> packs(long storeId, ZoneId zone) {
		MapSqlParameterSource params = new MapSqlParameterSource().addValue("storeId", storeId).addValue("tz", zone.getId());
		Map<Long, Pack> primary = new HashMap<>();
		jdbc.query(PRIMARY_PRODUCT_SQL, params, rs -> {
			Pack p = Pack.of(rs.getString("pack_unit"), rs.getBigDecimal("base_qty_per_pack"));
			primary.put(rs.getLong("ingredient_id"), p);
		});
		Map<Long, Pack> result = new HashMap<>(primary);
		jdbc.query(LATEST_RECEIVED_PRODUCT_SQL, params, rs -> {
			result.put(rs.getLong("ingredient_id"), Pack.of(rs.getString("pack_unit"), rs.getBigDecimal("base_qty_per_pack")));
		});
		result.values().removeIf(p -> p == null);
		return result;
	}

	private static ClosingItemResponse toResponse(Candidate c) {
		InventoryEstimateResponse e = c.estimate();
		Pack pack = c.pack();
		BigDecimal estimatedPacks = pack == null ? null : roundToHalf(e.estimatedQty().divide(pack.perPack(), 10,
				RoundingMode.HALF_UP));
		return new ClosingItemResponse(e.ingredientId(), e.ingredientName(), e.estimatedQty(), e.unit(),
				pack == null ? null : pack.unit(),
				pack == null ? null : pack.perPack(),
				estimatedPacks,
				pack == null ? BigDecimal.ONE : HALF,
				c.reason());
	}

	/** 0.5 단위 반올림 (1.45 → 1.5, 1.2 → 1, 1.25 → 1.5). */
	static BigDecimal roundToHalf(BigDecimal value) {
		return value.multiply(TWO).setScale(0, RoundingMode.HALF_UP).divide(TWO, 1, RoundingMode.UNNECESSARY);
	}

	/** 포장 단위와 포장 1개의 기준 단위 양. 둘 중 하나라도 모르면 null. */
	private record Pack(String unit, BigDecimal perPack) {

		static Pack of(String unit, BigDecimal perPack) {
			return unit == null || unit.isBlank() || perPack == null || perPack.signum() <= 0 ? null
					: new Pack(unit.strip(), perPack);
		}
	}

	private record Candidate(long id, InventoryEstimateResponse estimate, ClosingItemReason reason, Pack pack,
			BigDecimal loss, LocalDate runOut, LocalDate lastCount) {

		/** 같은 순위 안의 순서. 작을수록 먼저. */
		BigDecimal withinRank() {
			return switch (reason) {
				case REPEATED_LOSS -> loss.negate();
				case RUNNING_OUT -> BigDecimal.valueOf(runOut.toEpochDay());
				case STALE_COUNT -> BigDecimal.valueOf(lastCount.toEpochDay());
				case NEEDS_COUNT -> BigDecimal.ZERO;
			};
		}
	}
}
