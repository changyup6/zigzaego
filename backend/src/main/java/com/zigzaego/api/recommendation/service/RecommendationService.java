package com.zigzaego.api.recommendation.service;

import com.zigzaego.api.account.entity.Store;
import com.zigzaego.api.account.repository.StoreRepository;
import com.zigzaego.api.demo.DemoPreparationService;
import com.zigzaego.api.domain.order.DeliverySchedule;
import com.zigzaego.api.domain.order.OrderCalculator;
import com.zigzaego.api.domain.order.OrderCalculator.OrderInput;
import com.zigzaego.api.domain.order.OrderCalculator.OrderResult;
import com.zigzaego.api.domain.order.OrderReason;
import com.zigzaego.api.domain.order.SafetyStockCalculator;
import com.zigzaego.api.domain.order.SafetyStockCalculator.SafetyStockInput;
import com.zigzaego.api.domain.order.SafetyStockCalculator.SafetyStockResult;
import com.zigzaego.api.domain.price.PriceIndexCalculator;
import com.zigzaego.api.domain.price.PriceIndexCalculator.PriceIndex;
import com.zigzaego.api.domain.price.PriceIndexCalculator.PriceLevel;
import com.zigzaego.api.domain.price.PriceIndexCalculator.PriceObservation;
import com.zigzaego.api.forecast.dto.ForecastModel;
import com.zigzaego.api.forecast.dto.MenuForecastResponse;
import com.zigzaego.api.forecast.service.ForecastService;
import com.zigzaego.api.global.config.StoreClock;
import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.exception.CommonErrorCode;
import com.zigzaego.api.global.review.ReviewReason;
import com.zigzaego.api.global.store.ResourceIds;
import com.zigzaego.api.ingredient.entity.Ingredient;
import com.zigzaego.api.ingredient.entity.IngredientKind;
import com.zigzaego.api.ingredient.repository.IngredientRepository;
import com.zigzaego.api.inventory.dto.InventoryEstimateResponse;
import com.zigzaego.api.inventory.service.CountShortfallService;
import com.zigzaego.api.inventory.service.InventoryEstimateService;
import com.zigzaego.api.inventory.service.InventoryEstimateService.EstimateRun;
import com.zigzaego.api.recipe.repository.RecipeRepository;
import com.zigzaego.api.recommendation.dto.RecommendationAction;
import com.zigzaego.api.recommendation.dto.RecommendationDecisionRequest;
import com.zigzaego.api.recommendation.dto.RecommendationResponse;
import com.zigzaego.api.recommendation.dto.RecommendationRunResponse;
import com.zigzaego.api.recommendation.entity.Recommendation;
import com.zigzaego.api.recommendation.entity.RecommendationRun;
import com.zigzaego.api.recommendation.entity.RecommendationStatus;
import com.zigzaego.api.recommendation.exception.RecommendationErrorCode;
import com.zigzaego.api.recommendation.repository.RecommendationRepository;
import com.zigzaego.api.recommendation.repository.RecommendationRunRepository;
import com.zigzaego.api.supplier.entity.Supplier;
import com.zigzaego.api.supplier.entity.SupplierProduct;
import com.zigzaego.api.supplier.repository.SupplierProductRepository;
import com.zigzaego.api.supplier.repository.SupplierRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 발주 추천 생성 · 조회 (3주 계획 4.4 ~ 4.6 · 4.8절, 설계 5.3 · 5.4절).
 *
 * <pre>
 * 재료마다 (BOM · 1:1. 주기형은 하루 소비량이 없어 이 일감에서는 뺀다)
 *   추정 재고 · 소진 예상일   InventoryEstimateService (기준일 = 생성일)
 *   수요                       생성일+1 … 도착일(리드타임 수요) · 도착일+1 … 다음 도착일(주기 수요) 의 메뉴별 예측(ForecastService)
 *                             × 레시피 1인분(세트는 구성 메뉴로 한 단계 풂) 의 날짜별 합. 가게 휴무일은 0 (3주 계획 4.4절, DECISIONS #89)
 *                             forecast_model = 모든 (메뉴, 날) 이 timesfm 이면 timesfm, 하나라도 요일 평균이면 dow_avg
 *   L · R                      대표 상품(ingredient.primary_supplier_product_id)의 거래처. 없으면 1일 · 1일 (조회 때 SUPPLIER_UNSET).
 *                             배송 요일 · 주문 마감 시각이 있으면 DeliverySchedule 이 실제 도착일 · 다음 도착일로 L · R 을 다시 센다
 *   안전재고                   SafetyStockCalculator — 소비 이력 28일 이상이면 STAT, 아니면 DAYS_N. 기록으로 남긴다(ss_method)
 *                             실사 차이가 반복되는 재료(CountShortfall, 최근 3회 중 2회 손실률 ≥ 10%)는 평균 손실량을 더하고
 *                             이유 문장에 "실사 때마다 평균 N 부족." 한 줄 (지라 253, DECISIONS #88 ⑤)
 *   가격 연동                  price_sensitive 재료만. 대표 상품 단가 이력 → PriceIndexCalculator → R' (SS · ROP 불변)
 *   발주 판단 · 수량           OrderCalculator. 발주가 필요한 재료만 PENDING 으로 저장. 반올림 뒤 수량이 0 이하면 만들지 않는다(지라 219).
 *                             상한(유통기한 · max_order_qty)에 걸려 잘랐으면 이유 문장 끝에 경고 한 줄 (OrderReason.capLine)
 * 하루 한 재료 한 건           INSERT ... ON CONFLICT DO NOTHING — 같은 날 다시 돌려도 기존 건을 두고 새로 만들지 않는다
 * 실행 기록                    recommendation_run (가게 · 날짜 한 줄, ADR-0009). 그날 끝난 실행이 있으면 같은 날 재실행은
 *                             재고 추정 · 예측 · 소비량을 다시 조회하지 않고 기존 추천만 돌려준다. force=true 면 없는 재료만 다시 계산
 * 승인 · 수정 · 거절           PENDING → APPROVED | REJECTED 한 번만. UPDATE ... WHERE status = 'PENDING' 한 문장으로 바꿔
 *                             동시에 두 번 눌러도 한 번만 먹는다. 승인자(decided_by) · 시각(decided_at) 기록
 * </pre>
 *
 * 모든 SQL 에 store_id 를 건다 (ADR-0002). 오늘은 가게 시간대({@code store.timezone}) 기준.
 *
 * <p>쿼리 수(지라 158): 재고 추정이 읽은 소비량 · 첫 판매일 · 휴무일을 그대로 받아 다시 묻지 않고, 예측은 생성일+1 … +max(L+R')
 * 구간을 한 번에, 추천 INSERT 는 배치 한 번으로 보낸다. 단가 이력은 지수 계산에 필요한 112일만 읽는다.
 */
@Service
@RequiredArgsConstructor
public class RecommendationService {

	/** 거래처 설정이 비었을 때의 리드타임 · 발주 주기(일). 매일 배송 식자재몰 기준. */
	static final int DEFAULT_LEAD_TIME_DAYS = 1;
	static final int DEFAULT_CYCLE_DAYS = 1;

	private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
	private static final int QTY_SCALE = 3;

	/** 레시피 1인분. 세트 메뉴는 구성 메뉴의 레시피 × 구성 수량을 세트 메뉴 몫으로 더한다 (ConsumptionService 와 같은 식). */
	private static final String RECIPE_LINES_SQL = """
			SELECT r.menu_id, r.ingredient_id, r.qty_per_serving AS qty
			FROM recipe r
			JOIN ingredient i ON i.id = r.ingredient_id AND i.store_id = :storeId
			WHERE r.store_id = :storeId AND i.kind <> 'CYCLE'
			UNION ALL
			SELECT mc.set_menu_id, r.ingredient_id, mc.qty * r.qty_per_serving
			FROM menu_component mc
			JOIN menu cm ON cm.id = mc.component_menu_id AND cm.store_id = :storeId
			JOIN recipe r ON r.menu_id = mc.component_menu_id AND r.store_id = :storeId
			JOIN ingredient i ON i.id = r.ingredient_id AND i.store_id = :storeId
			WHERE mc.store_id = :storeId AND i.kind <> 'CYCLE'
			""";

	/** 입고 예정 = 보낸(SENT) 발주서의, 입고 확인 안 되고 안 옴 처리도 안 된 줄. 포장 → 기준 단위. DRAFT · CANCELLED 는 뺀다 (DECISIONS #63). */
	private static final String ON_ORDER_SQL = """
			SELECT poi.ingredient_id, SUM(poi.qty * COALESCE(sp.base_qty_per_pack, 1)) AS qty
			FROM purchase_order_item poi
			JOIN purchase_order po ON po.id = poi.po_id AND po.store_id = :storeId
			JOIN supplier_product sp ON sp.id = poi.supplier_product_id AND sp.store_id = :storeId
			WHERE poi.store_id = :storeId
			  AND poi.received_at IS NULL AND poi.cancelled_at IS NULL
			  AND po.status = 'SENT'
			GROUP BY poi.ingredient_id
			""";

	private static final String PRICE_HISTORY_SQL = """
			SELECT ph.supplier_product_id, (ph.observed_at AT TIME ZONE :tz)::date AS observed_on, ph.price, ph.source,
			       sp.base_qty_per_pack
			FROM price_history ph
			JOIN supplier_product sp ON sp.id = ph.supplier_product_id AND sp.store_id = :storeId
			WHERE ph.store_id = :storeId AND ph.supplier_product_id IN (:productIds)
			  AND ph.observed_at >= :from
			""";

	private static final String COUNTED_SQL = """
			SELECT DISTINCT ingredient_id FROM stock_count
			WHERE store_id = :storeId AND ingredient_id IN (:ingredientIds)
			""";

	private static final String INSERT_SQL = """
			INSERT INTO recommendation (store_id, ingredient_id, run_date, est_stock, on_order, lead_time_demand,
			    safety_stock, reorder_point, recommended_qty, price_ratio, price_level, cycle_days_used, reason,
			    status, runout_date, supplier_product_id, next_order_date, forecast_model, ss_method)
			VALUES (:storeId, :ingredientId, :runDate, :estStock, :onOrder, :leadTimeDemand,
			    :safetyStock, :reorderPoint, :recommendedQty, :priceRatio, :priceLevel, :cycleDaysUsed, :reason,
			    'PENDING', :runoutDate, :supplierProductId, :nextOrderDate, :forecastModel, :ssMethod)
			ON CONFLICT (store_id, ingredient_id, run_date) DO NOTHING
			""";

	/** 실행 기록 — 가게 · 날짜 한 줄. 같은 날 다시 계산(force)하면 시각 · 건수를 덮어쓴다. */
	private static final String UPSERT_RUN_SQL = """
			INSERT INTO recommendation_run (store_id, run_date, started_at, finished_at, created_count, no_order_count)
			VALUES (:storeId, :runDate, :startedAt, :finishedAt, :createdCount, :noOrderCount)
			ON CONFLICT (store_id, run_date) DO UPDATE
			SET started_at = EXCLUDED.started_at, finished_at = EXCLUDED.finished_at,
			    created_count = EXCLUDED.created_count, no_order_count = EXCLUDED.no_order_count
			""";

	/** 상태 전이 1회 보장 — PENDING 인 줄만 바꾼다. 0줄이면 없는 추천(404)이거나 이미 결정한 추천(409). */
	private static final String DECIDE_SQL = """
			UPDATE recommendation
			SET status = :status, final_qty = :finalQty, reject_reason = :rejectReason,
			    decided_by = :decidedBy, decided_at = :decidedAt
			WHERE id = :id AND store_id = :storeId AND status = 'PENDING'
			""";

	private final DemoPreparationService demoPreparation;
	private final StoreRepository storeRepository;
	private final IngredientRepository ingredientRepository;
	private final RecipeRepository recipeRepository;
	private final SupplierProductRepository supplierProductRepository;
	private final SupplierRepository supplierRepository;
	private final RecommendationRepository recommendationRepository;
	private final RecommendationRunRepository recommendationRunRepository;
	private final InventoryEstimateService inventoryEstimateService;
	private final CountShortfallService countShortfallService;
	private final ForecastService forecastService;
	private final NamedParameterJdbcTemplate jdbc;
	private final EntityManager entityManager;
	private final StoreClock clock;

	private final OrderCalculator orderCalculator = new OrderCalculator();
	private final SafetyStockCalculator safetyStockCalculator = new SafetyStockCalculator();
	private final PriceIndexCalculator priceIndexCalculator = new PriceIndexCalculator();

	// ---------------------------------------------------------------- 조회

	/**
	 * @param runDate 조회할 생성일. null 이면 가게 시간대의 오늘
	 * @return 그날 추천, 재료 id 오름차순. 없으면 빈 목록
	 */
	@Transactional(readOnly = true)
	public List<RecommendationResponse> list(long storeId, LocalDate runDate) {
		Store store = getStore(storeId);
		LocalDate date = runDate != null ? runDate : clock.today(storeId, ZoneId.of(store.getTimezone()));
		return toResponses(storeId, recommendationRepository.findAllByStoreIdAndRunDateOrderByIdAsc(storeId, date));
	}

	// ---------------------------------------------------------------- 승인 · 수정 · 거절

	/**
	 * 사장님의 결정 (3주 계획 8절 안전장치). approve 는 추천 수량(또는 보낸 finalQty), modify 는 보낸 finalQty 로
	 * APPROVED, reject 는 reason 을 남기고 REJECTED. 승인 건의 final_qty 가 발주서 · 입고 예정 줄의 주문 수량이 된다
	 * (POST /purchase-orders/from-recommendations, 지라 70).
	 *
	 * @param accountId 결정한 계정 — decided_by 에 남긴다
	 * @throws BusinessException 없거나 남의 가게 추천이면 404 COMMON_NOT_FOUND,
	 *                           이미 결정한 추천이면 409 RECOMMENDATION_ALREADY_DECIDED,
	 *                           modify 에 finalQty 가 없으면 400 RECOMMENDATION_FINAL_QTY_REQUIRED
	 */
	@Transactional
	public RecommendationResponse decide(long storeId, long accountId, String rawId, RecommendationDecisionRequest request) {
		long id = ResourceIds.parse(rawId);
		Recommendation current = recommendationRepository.getInStore(id, storeId);
		if (request.action() == RecommendationAction.MODIFY && request.finalQty() == null) {
			throw new BusinessException(RecommendationErrorCode.FINAL_QTY_REQUIRED);
		}

		RecommendationStatus status = request.action() == RecommendationAction.REJECT
				? RecommendationStatus.REJECTED : RecommendationStatus.APPROVED;
		BigDecimal finalQty = status == RecommendationStatus.REJECTED ? null
				: scale(request.finalQty() != null ? request.finalQty() : current.getRecommendedQty());
		String rejectReason = status == RecommendationStatus.REJECTED ? blankToNull(request.reason()) : null;

		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("id", id)
				.addValue("storeId", storeId)
				.addValue("status", status.name())
				.addValue("finalQty", finalQty)
				.addValue("rejectReason", rejectReason)
				.addValue("decidedBy", accountId)
				.addValue("decidedAt", OffsetDateTime.now(clock.of(storeId)));
		if (jdbc.update(DECIDE_SQL, params) == 0) {
			// 위에서 읽혔으니 없는 게 아니라 이미 결정된 것 (동시 요청 포함)
			throw new BusinessException(RecommendationErrorCode.ALREADY_DECIDED);
		}
		entityManager.refresh(current); // JDBC 로 바꾼 값을 영속성 컨텍스트의 엔티티에 다시 읽는다
		return toResponses(storeId, List.of(current)).getFirst();
	}

	private static String blankToNull(String text) {
		return text == null || text.isBlank() ? null : text.trim();
	}

	// ---------------------------------------------------------------- 생성

	/**
	 * 가게 하나의 추천을 만든다. 배치(04:00)와 수동 실행(POST /recommendations/run)이 같이 부른다. force 없이.
	 *
	 * @see #run(long, LocalDate, boolean)
	 */
	@Transactional
	public RecommendationRunResponse run(long storeId, LocalDate runDate) {
		return run(storeId, runDate, false);
	}

	/**
	 * 가게 하나의 추천을 만든다.
	 *
	 * <p>그날 끝난 실행 기록({@code recommendation_run.finished_at})이 있으면 재고 추정 · 예측 · 소비량을 다시 조회하지 않고
	 * 기존 추천만 돌려준다(createdCount · noOrderCount 0). 실사 · 입고 · 레시피가 바뀐 뒤 다시 계산하려면 {@code force=true} —
	 * 그래도 기존 건은 두고(하루 한 재료 한 건) 없는 재료만 새로 계산한다.
	 *
	 * @param runDate 생성 기준일. null 이면 가게 시간대의 오늘. 테스트 · 재계산이 날짜를 고정할 때 넘긴다
	 * @param force   그날 끝난 실행이 있어도 없는 재료를 다시 계산한다
	 * @throws BusinessException 가게가 없으면 404 COMMON_NOT_FOUND
	 */
	@Transactional
	public RecommendationRunResponse run(long storeId, LocalDate runDate, boolean force) {
		Store store = getStore(storeId);
		demoPreparation.requireDataReady(storeId);
		ZoneId zone = ZoneId.of(store.getTimezone());
		Clock storeClock = clock.of(storeId);
		LocalDate today = runDate != null ? runDate : LocalDate.now(storeClock.withZone(zone));
		// 주문 마감 시각 판정은 실제 오늘을 계산할 때만. 날짜를 넘긴 호출(테스트 · 재계산)은 마감 전으로 본다
		LocalTime now = runDate != null ? null : LocalTime.now(storeClock.withZone(zone));
		OffsetDateTime startedAt = OffsetDateTime.now(storeClock);

		List<Recommendation> existing = recommendationRepository.findAllByStoreIdAndRunDateOrderByIdAsc(storeId, today);
		if (!force && recommendationRunRepository.findByStoreIdAndRunDate(storeId, today)
				.map(RecommendationRun::isFinished).orElse(false)) {
			// 오늘 이미 끝낸 실행 — 컨텍스트 조회(추정 · 예측 · 소비량) 없이 기존 추천만
			return new RecommendationRunResponse(today.toString(), 0, existing.size(), 0, toResponses(storeId, existing));
		}
		Set<Long> alreadyDone = existing.stream().map(Recommendation::getIngredientId).collect(Collectors.toSet());

		List<Ingredient> ingredients = ingredientRepository.findAllByStoreIdOrderByIdAsc(storeId);
		List<Ingredient> targets = ingredients.stream()
				.filter(i -> i.getKind() != IngredientKind.CYCLE)
				.filter(i -> !alreadyDone.contains(i.getId()))
				.toList();

		int created = 0;
		int noOrder = 0;
		if (!targets.isEmpty()) {
			RunContext ctx = loadContext(storeId, zone, today, now, ingredients, targets);
			List<MapSqlParameterSource> rows = new ArrayList<>(targets.size());
			for (Ingredient ingredient : targets) {
				Computed computed = compute(ingredient, ctx);
				if (computed == null) {
					noOrder++;
					continue;
				}
				rows.add(insertParams(storeId, today, ingredient, computed));
			}
			created = insertAll(rows);
		}

		List<Recommendation> all = recommendationRepository.findAllByStoreIdAndRunDateOrderByIdAsc(storeId, today);
		if (created < 0) {
			created = all.size() - existing.size(); // 드라이버가 배치 건수를 안 알려 준 경우
		}
		recordRun(storeId, today, startedAt, created, noOrder);
		return new RecommendationRunResponse(today.toString(), created, existing.size(), noOrder, toResponses(storeId, all));
	}

	/** 실행 기록을 남긴다 — 그날 두 번째부터 계산을 건너뛰는 근거. 같은 트랜잭션이라 계산이 실패하면 기록도 남지 않는다. */
	private void recordRun(long storeId, LocalDate today, OffsetDateTime startedAt, int created, int noOrder) {
		jdbc.update(UPSERT_RUN_SQL, new MapSqlParameterSource()
				.addValue("storeId", storeId)
				.addValue("runDate", Date.valueOf(today))
				.addValue("startedAt", startedAt)
				.addValue("finishedAt", OffsetDateTime.now(clock.of(storeId)))
				.addValue("createdCount", created)
				.addValue("noOrderCount", noOrder));
	}


	/**
	 * 재료 전부에 공통인 조회를 한 번에. 재고 추정이 읽은 소비량 · 첫 판매일 · 휴무일을 그대로 받고(같은 SQL 을 다시 보내지 않는다),
	 * 재료마다의 L · R' · 예측 구간을 먼저 정해 예측은 생성일+1 … 생성일+max(L+R') 을 한 번에 묻는다.
	 *
	 * @param ingredients 가게 재료 전부 — 재고 추정에 넘긴다
	 * @param targets     이번에 계산할 재료(주기형 · 오늘 이미 만든 재료 제외)
	 */
	private RunContext loadContext(long storeId, ZoneId zone, LocalDate today, LocalTime now, List<Ingredient> ingredients,
			List<Ingredient> targets) {
		EstimateRun estimateRun = inventoryEstimateService.estimateRun(storeId, today, ingredients);
		Map<Long, InventoryEstimateResponse> estimates = estimateRun.estimates().stream()
				.collect(Collectors.toMap(e -> Long.parseLong(e.ingredientId()), Function.identity()));

		List<Long> productIds = targets.stream().map(Ingredient::getPrimarySupplierProductId).filter(id -> id != null)
				.distinct().toList();
		Map<Long, SupplierProduct> products = productIds.isEmpty() ? Map.of()
				: supplierProductRepository.findAllByStoreIdAndIdIn(storeId, productIds).stream()
						.collect(Collectors.toMap(SupplierProduct::getId, Function.identity()));
		List<Long> supplierIds = products.values().stream().map(SupplierProduct::getSupplierId).distinct().toList();
		Map<Long, Supplier> suppliers = supplierIds.isEmpty() ? Map.of()
				: supplierRepository.findAllByStoreIdAndIdIn(storeId, supplierIds).stream()
						.collect(Collectors.toMap(Supplier::getId, Function.identity()));
		Map<Long, List<PriceObservation>> priceObservations = priceObservations(storeId, zone, today, productIds);

		Map<Long, Terms> terms = new HashMap<>();
		int maxHorizon = 1;
		for (Ingredient ingredient : targets) {
			Terms t = terms(ingredient, today, now, products, suppliers, priceObservations);
			terms.put(ingredient.getId(), t);
			maxHorizon = Math.max(maxHorizon, t.horizon());
		}
		Map<LocalDate, Map<Long, MenuForecastResponse>> forecasts = new HashMap<>();
		forecastService.forecastRange(storeId, today.plusDays(1), today.plusDays(maxHorizon), today)
				.forEach((date, lines) -> forecasts.put(date, lines.stream()
						.collect(Collectors.toMap(f -> Long.parseLong(f.menuId()), Function.identity()))));

		return new RunContext(storeId, zone, today, estimates, terms, recipeLines(storeId), onOrder(storeId),
				estimateRun.dailyConsumption(), estimateRun.firstSaleDate(), estimateRun.closedDays(), forecasts,
				countShortfallService.averageLosses(storeId));
	}

	/**
	 * 재료 하나의 거래 조건 — 대표 상품 · 거래처의 L · R 을 배송 요일 · 마감 시각으로 실제 도착일 기준으로 다시 세고({@link DeliverySchedule}),
	 * 가격 연동으로 조정한 R'(하루 단위 올림 뒤 첫 배송 요일까지), 예측 구간 L+R'. SQL 없이 메모리에서 정한다.
	 */
	private Terms terms(Ingredient ingredient, LocalDate today, LocalTime now, Map<Long, SupplierProduct> products,
			Map<Long, Supplier> suppliers, Map<Long, List<PriceObservation>> priceObservations) {
		SupplierProduct product = ingredient.getPrimarySupplierProductId() == null ? null
				: products.get(ingredient.getPrimarySupplierProductId());
		Supplier supplier = product == null ? null : suppliers.get(product.getSupplierId());
		int baseLeadTime = supplier != null && supplier.getLeadTimeDays() != null ? supplier.getLeadTimeDays() : DEFAULT_LEAD_TIME_DAYS;
		int baseCycle = supplier != null && supplier.getOrderCycleDays() != null ? supplier.getOrderCycleDays() : DEFAULT_CYCLE_DAYS;
		Set<DayOfWeek> deliveryDays = supplier == null ? Set.of() : supplier.deliveryDaysOfWeek();
		DeliverySchedule.Terms schedule = DeliverySchedule.resolve(today, now, baseLeadTime, baseCycle, deliveryDays,
				supplier == null ? null : supplier.getCutoffTime());
		int leadTime = schedule.leadTimeDays();
		int cycle = schedule.cycleDays();

		// 가격 연동: R 만 바꾼다 (3주 계획 4.8절). SS 는 조정 전 R 그대로
		PriceIndex priceIndex = null;
		if (ingredient.isPriceSensitive() && product != null) {
			priceIndex = priceIndexCalculator.calculate(today, priceObservations.getOrDefault(product.getId(), List.of()));
			// 배송은 날 단위라 R' 도 하루 단위로 올리고(R=3 비쌈 → 1.5 → 2일), 그 뒤 첫 배송 요일까지 늘린다.
			// 다음 도착일 · 예측 구간 · 다음 주문일이 모두 같은 날을 본다
			int adjusted = PriceIndexCalculator.adjustedCycleDays(BigDecimal.valueOf(cycle), priceIndex.level())
					.setScale(0, RoundingMode.CEILING).intValueExact();
			schedule = DeliverySchedule.withCycle(schedule, adjusted, deliveryDays);
		}
		BigDecimal cycleUsed = BigDecimal.valueOf(schedule.cycleDays());
		int horizon = Math.max(1, leadTime + schedule.cycleDays());
		return new Terms(product, leadTime, cycle, priceIndex, cycleUsed, horizon, schedule, baseLeadTime);
	}

	/**
	 * 재료 하나의 계산. 추정 재고를 못 내는 재료(주기형 수집 중 등)와 발주가 필요 없는 재료는 null.
	 */
	private Computed compute(Ingredient ingredient, RunContext ctx) {
		InventoryEstimateResponse estimate = ctx.estimates().get(ingredient.getId());
		if (estimate == null || estimate.estimatedQty() == null) {
			return null;
		}
		Terms terms = ctx.terms().get(ingredient.getId());
		SupplierProduct product = terms.product();
		int leadTime = terms.leadTime();
		PriceIndex priceIndex = terms.priceIndex();
		BigDecimal cycleUsed = terms.cycleUsed();

		// 수요 = 생성일+1 … 도착일(리드타임) · 도착일+1 … 다음 도착일(주기) 의 날짜별 예측 소비 합. 휴무일은 0
		int horizon = terms.horizon();
		Demand demand = demand(ingredient.getId(), leadTime, horizon, ctx);

		SafetyStockResult ss = safetyStockCalculator.calculate(new SafetyStockInput(
				ingredient.getStorageType(),
				ctx.consumption().getOrDefault(ingredient.getId(), Map.of()),
				ctx.today().minusDays(1),
				ctx.firstSaleDate(),
				ctx.closedDays(),
				ingredient.getSafetyDays(),
				leadTime,
				terms.cycle(),
				false));
		BigDecimal shortfall = ctx.shortfalls().get(ingredient.getId());
		if (shortfall != null) {
			ss = new SafetyStockResult(ss.safetyStock().add(shortfall), ss.method(), ss.sigma(), ss.recentAverage());
		}

		BigDecimal onOrder = ctx.onOrder().getOrDefault(ingredient.getId(), BigDecimal.ZERO);
		OrderResult result = orderCalculator.calculate(new OrderInput(
				demand.leadTimeDemand(),
				demand.cycleDemand(),
				demand.perDay(),
				ss.safetyStock(),
				estimate.estimatedQty(),
				onOrder,
				product == null ? null : product.getBaseQtyPerPack(),
				packToBase(product, product == null ? null : product.getMoq()),
				ingredient.getShelfLifeDays(),
				ingredient.getMaxOrderQty()));
		if (!result.shouldOrder() || scale(result.recommendedQty()).signum() <= 0) {
			// 반올림 뒤 수량이 0 이면 사장님이 할 일이 없다 — 판매 · 재고 기록이 없는 새 가게는 모든 재료가 여기에 걸린다 (S15P31A308-219)
			return null;
		}

		LocalDate runOut = estimate.runOutDate() == null ? null : LocalDate.parse(estimate.runOutDate());
		DeliverySchedule.Terms schedule = terms.schedule();
		String reason = OrderReason.render(ingredient.getUnit(), estimate.estimatedQty(), onOrder, leadTime, result,
				ss.safetyStock(), runOut, schedule.arrival(), schedule.afterCutoff());
		if (shortfall != null) {
			reason += " " + OrderReason.shortfallLine(ingredient.getUnit(), shortfall);
		}
		if (priceIndex != null && priceIndex.level() != PriceLevel.NORMAL) {
			reason += " " + OrderReason.priceLine(ingredient.getUnit(), priceIndex, horizon);
		}
		if (result.capped()) {
			// 3주 계획 8절: 상한을 넘으면 자르고 경고. 경고는 이유 문장 맨 뒤 한 줄로 남긴다
			reason += " " + OrderReason.capLine(ingredient.getUnit(), result, ingredient.getMaxOrderQty());
		}

		// 다음 주문할 날 = 다음 도착일에 맞춰 주문해야 하는 날. 늦어도 내일 (7일 주문 달력용, V1 recommendation.next_order_date)
		LocalDate nextOrderDate = schedule.nextArrival().minusDays(terms.baseLeadTime());
		if (!nextOrderDate.isAfter(ctx.today())) {
			nextOrderDate = ctx.today().plusDays(1);
		}
		return new Computed(estimate.estimatedQty(), onOrder, result, ss, priceIndex, cycleUsed, reason, runOut,
				product == null ? null : product.getId(), demand.model(), nextOrderDate);
	}

	/** 포장 수량(MOQ 는 포장 단위)을 기준 단위로. 환산을 모르면 그대로 — 포장 1개 = 기준 단위 1 로 본다. */
	private static BigDecimal packToBase(SupplierProduct product, BigDecimal packQty) {
		if (product == null || packQty == null) {
			return packQty;
		}
		return product.toBaseQty(packQty).orElse(packQty);
	}

	/**
	 * 날짜별 예측 소비를 리드타임 구간(1 … leadTime)과 주기 구간(leadTime+1 … horizon)으로 나눠 더한다.
	 * 가게 휴무일은 팔지 않으니 0 — 소진 예상일(DailyRate)과 같은 가정이다. perDay 는 구간 평균(유통기한 상한용).
	 */
	private Demand demand(long ingredientId, int leadTime, int horizon, RunContext ctx) {
		Map<Long, BigDecimal> perMenu = ctx.recipeLines().getOrDefault(ingredientId, Map.of());
		BigDecimal leadTimeDemand = BigDecimal.ZERO;
		BigDecimal cycleDemand = BigDecimal.ZERO;
		boolean allTimesfm = true;
		for (int d = 1; d <= horizon; d++) {
			LocalDate target = ctx.today().plusDays(d);
			if (ctx.closedDays().contains(target)) {
				continue; // 휴무일 — 소비 0
			}
			BigDecimal day = BigDecimal.ZERO;
			// loadContext 가 생성일+1 … +max(L+R') 을 미리 읽어 두었다. 없는 날(있어서는 안 된다)은 그날만 다시 묻는다
			Map<Long, MenuForecastResponse> forecast = ctx.forecasts().computeIfAbsent(target,
					t -> forecastService.forecast(ctx.storeId(), t, ctx.today()).stream()
							.collect(Collectors.toMap(f -> Long.parseLong(f.menuId()), Function.identity())));
			for (Map.Entry<Long, BigDecimal> line : perMenu.entrySet()) {
				MenuForecastResponse f = forecast.get(line.getKey());
				if (f == null) {
					continue; // 비활성 메뉴 — 예측하지 않는다
				}
				day = day.add(f.predictedQty().multiply(line.getValue()));
				if (f.model() != ForecastModel.TIMESFM) {
					allTimesfm = false;
				}
			}
			if (d <= leadTime) {
				leadTimeDemand = leadTimeDemand.add(day);
			} else {
				cycleDemand = cycleDemand.add(day);
			}
		}
		BigDecimal perDay = leadTimeDemand.add(cycleDemand).divide(BigDecimal.valueOf(horizon), MC);
		return new Demand(leadTimeDemand, cycleDemand, perDay,
				(perMenu.isEmpty() || !allTimesfm ? ForecastModel.DOW_AVG : ForecastModel.TIMESFM).columnValue());
	}

	/**
	 * 추천 전부를 배치 한 번으로 INSERT (ON CONFLICT DO NOTHING).
	 *
	 * @return 실제로 들어간 줄 수. 드라이버가 건수를 안 알려 주면(SUCCESS_NO_INFO) −1 — 호출자가 전후 건수 차이로 센다
	 */
	private int insertAll(List<MapSqlParameterSource> rows) {
		if (rows.isEmpty()) {
			return 0;
		}
		int[] counts = jdbc.batchUpdate(INSERT_SQL, rows.toArray(MapSqlParameterSource[]::new));
		if (IntStream.of(counts).anyMatch(n -> n < 0)) {
			return -1;
		}
		return IntStream.of(counts).sum();
	}

	private static MapSqlParameterSource insertParams(long storeId, LocalDate today, Ingredient ingredient, Computed c) {
		return new MapSqlParameterSource()
				.addValue("storeId", storeId)
				.addValue("ingredientId", ingredient.getId())
				.addValue("runDate", Date.valueOf(today))
				.addValue("estStock", scale(c.estStock()))
				.addValue("onOrder", scale(c.onOrder()))
				.addValue("leadTimeDemand", scale(c.result().leadTimeDemand()))
				.addValue("safetyStock", scale(c.safetyStock().safetyStock()))
				.addValue("reorderPoint", scale(c.result().reorderPoint()))
				.addValue("recommendedQty", scale(c.result().recommendedQty()))
				.addValue("priceRatio", c.priceIndex() == null ? null : c.priceIndex().index().setScale(4, RoundingMode.HALF_UP))
				.addValue("priceLevel", c.priceIndex() == null ? null : c.priceIndex().level().name())
				.addValue("cycleDaysUsed", c.cycleDaysUsed().setScale(2, RoundingMode.HALF_UP))
				.addValue("reason", c.reason())
				.addValue("runoutDate", c.runOutDate() == null ? null : Date.valueOf(c.runOutDate()))
				.addValue("supplierProductId", c.supplierProductId())
				.addValue("nextOrderDate", Date.valueOf(c.nextOrderDate()))
				.addValue("forecastModel", c.forecastModel())
				.addValue("ssMethod", c.safetyStock().method().name());
	}

	private static BigDecimal scale(BigDecimal value) {
		return value.setScale(QTY_SCALE, RoundingMode.HALF_UP);
	}

	// ---------------------------------------------------------------- 응답

	private List<RecommendationResponse> toResponses(long storeId, List<Recommendation> recommendations) {
		if (recommendations.isEmpty()) {
			return List.of();
		}
		List<Long> ingredientIds = recommendations.stream().map(Recommendation::getIngredientId).distinct().toList();
		Map<Long, Ingredient> ingredients = ingredientRepository.findAllByStoreIdAndIdIn(storeId, ingredientIds).stream()
				.collect(Collectors.toMap(Ingredient::getId, Function.identity()));
		Set<Long> estimated = recipeRepository.findEstimatedIngredientIds(storeId, ingredientIds);
		Set<Long> counted = countedIngredientIds(storeId, ingredientIds);
		Set<Long> supplierUnset = supplierUnsetProductIds(storeId, recommendations);

		List<RecommendationResponse> result = new ArrayList<>(recommendations.size());
		for (Recommendation r : recommendations) {
			Ingredient ingredient = ingredients.get(r.getIngredientId());
			if (ingredient == null) {
				continue; // 재료가 지워졌다면(지금은 IN_USE 로 막는다) 그 추천은 보여 주지 않는다
			}
			List<ReviewReason> reasons = new ArrayList<>();
			if (estimated.contains(r.getIngredientId())) {
				reasons.add(ReviewReason.UNANSWERED);
			}
			if (!counted.contains(r.getIngredientId())) {
				reasons.add(ReviewReason.NEEDS_COUNT);
			}
			if (r.getSupplierProductId() == null || supplierUnset.contains(r.getSupplierProductId())) {
				reasons.add(ReviewReason.SUPPLIER_UNSET);
			}
			result.add(RecommendationResponse.of(r, ingredient, List.copyOf(reasons)));
		}
		result.sort((a, b) -> Long.compare(Long.parseLong(a.ingredientId()), Long.parseLong(b.ingredientId())));
		return result;
	}

	/** 추천이 가리키는 상품 중 거래처의 리드타임 · 발주 주기가 비어 기본값으로 계산된 것. */
	private Set<Long> supplierUnsetProductIds(long storeId, List<Recommendation> recommendations) {
		List<Long> productIds = recommendations.stream().map(Recommendation::getSupplierProductId)
				.filter(id -> id != null).distinct().toList();
		if (productIds.isEmpty()) {
			return Set.of();
		}
		Map<Long, SupplierProduct> products = supplierProductRepository.findAllByStoreIdAndIdIn(storeId, productIds).stream()
				.collect(Collectors.toMap(SupplierProduct::getId, Function.identity()));
		List<Long> supplierIds = products.values().stream().map(SupplierProduct::getSupplierId).distinct().toList();
		Map<Long, Supplier> suppliers = supplierIds.isEmpty() ? Map.of()
				: supplierRepository.findAllByStoreIdAndIdIn(storeId, supplierIds).stream()
						.collect(Collectors.toMap(Supplier::getId, Function.identity()));
		Set<Long> unset = new HashSet<>();
		for (Long productId : productIds) {
			SupplierProduct product = products.get(productId);
			Supplier supplier = product == null ? null : suppliers.get(product.getSupplierId());
			if (supplier == null || supplier.getLeadTimeDays() == null || supplier.getOrderCycleDays() == null) {
				unset.add(productId);
			}
		}
		return unset;
	}

	// ---------------------------------------------------------------- 조회 SQL

	private Store getStore(long storeId) {
		return storeRepository.findById(storeId).orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
	}

	private Map<Long, Map<Long, BigDecimal>> recipeLines(long storeId) {
		Map<Long, Map<Long, BigDecimal>> byIngredient = new HashMap<>();
		jdbc.query(RECIPE_LINES_SQL, new MapSqlParameterSource("storeId", storeId), rs -> {
			byIngredient.computeIfAbsent(rs.getLong("ingredient_id"), k -> new HashMap<>())
					.merge(rs.getLong("menu_id"), rs.getBigDecimal("qty"), BigDecimal::add);
		});
		return byIngredient;
	}

	private Map<Long, BigDecimal> onOrder(long storeId) {
		Map<Long, BigDecimal> result = new HashMap<>();
		jdbc.query(ON_ORDER_SQL, new MapSqlParameterSource("storeId", storeId), rs -> {
			result.put(rs.getLong("ingredient_id"), rs.getBigDecimal("qty"));
		});
		return result;
	}

	/**
	 * 단위 단가 = 포장 단가 ÷ base_qty_per_pack (모르면 포장 단가 그대로 — 지수는 비율이라 영향 없다).
	 * 지수 계산에 쓰이는 최근 {@link PriceIndexCalculator#LOOKBACK_DAYS}일(가게 시간대 자정부터)만 읽는다 — PriceHistoryService 와 같은 기준.
	 */
	private Map<Long, List<PriceObservation>> priceObservations(long storeId, ZoneId zone, LocalDate today,
			Collection<Long> productIds) {
		Map<Long, List<PriceObservation>> result = new HashMap<>();
		if (productIds.isEmpty()) {
			return result;
		}
		OffsetDateTime from = today.minusDays(PriceIndexCalculator.LOOKBACK_DAYS).atStartOfDay(zone).toOffsetDateTime();
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("storeId", storeId)
				.addValue("tz", zone.getId())
				.addValue("productIds", productIds)
				.addValue("from", from);
		jdbc.query(PRICE_HISTORY_SQL, params, rs -> {
			BigDecimal price = rs.getBigDecimal("price");
			BigDecimal perPack = rs.getBigDecimal("base_qty_per_pack");
			BigDecimal unitPrice = perPack == null || perPack.signum() <= 0 ? price : price.divide(perPack, MC);
			result.computeIfAbsent(rs.getLong("supplier_product_id"), k -> new ArrayList<>())
					.add(new PriceObservation(rs.getDate("observed_on").toLocalDate(), unitPrice, rs.getString("source")));
		});
		return result;
	}

	private Set<Long> countedIngredientIds(long storeId, Collection<Long> ingredientIds) {
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("storeId", storeId)
				.addValue("ingredientIds", ingredientIds);
		return new HashSet<>(jdbc.query(COUNTED_SQL, params, (rs, i) -> rs.getLong("ingredient_id")));
	}

	// ---------------------------------------------------------------- 내부 값

	/**
	 * 가게 하나의 생성 1회에 쓰는 공통 데이터.
	 *
	 * @param terms       재료 id → 거래 조건(L · R · R' · 예측 구간)
	 * @param consumption 재고 추정이 읽은 날짜별 소비량. 안전재고는 어제부터 28일만 본다
	 * @param forecasts   날짜별 메뉴 예측. 생성일+1 … +max(L+R') 을 미리 채운다
	 * @param shortfalls  반복 손실 재료 → 평균 손실량. 안전재고에 더한다 (지라 253)
	 */
	private record RunContext(long storeId, ZoneId zone, LocalDate today,
			Map<Long, InventoryEstimateResponse> estimates,
			Map<Long, Terms> terms,
			Map<Long, Map<Long, BigDecimal>> recipeLines,
			Map<Long, BigDecimal> onOrder,
			Map<Long, Map<LocalDate, BigDecimal>> consumption,
			LocalDate firstSaleDate,
			Set<LocalDate> closedDays,
			Map<LocalDate, Map<Long, MenuForecastResponse>> forecasts,
			Map<Long, BigDecimal> shortfalls) {
	}

	/**
	 * 재료 하나의 거래 조건.
	 *
	 * @param product    대표 상품. 없으면 null (L · R 기본값)
	 * @param leadTime   L(일)
	 * @param cycle      R(일). 안전재고는 조정 전 R 로
	 * @param priceIndex 가격 지수. 가격 연동이 꺼졌거나 대표 상품이 없으면 null
	 * @param cycleUsed  R' — 가격 연동으로 조정한 발주 주기
	 * @param horizon    예측 구간 길이(일) = max(1, L + R')
	 * @param schedule   배송 일정으로 정한 도착일 · 다음 도착일 · 마감 여부
	 * @param baseLeadTime 거래처가 적은 리드타임(일). 다음 주문일 계산에 쓴다
	 */
	private record Terms(SupplierProduct product, int leadTime, int cycle, PriceIndex priceIndex, BigDecimal cycleUsed,
			int horizon, DeliverySchedule.Terms schedule, int baseLeadTime) {
	}

	/** 날짜별 예측 소비의 합 — 리드타임 구간 · 주기 구간, 그리고 구간 평균. */
	private record Demand(BigDecimal leadTimeDemand, BigDecimal cycleDemand, BigDecimal perDay, String model) {
	}

	private record Computed(BigDecimal estStock, BigDecimal onOrder, OrderResult result, SafetyStockResult safetyStock,
			PriceIndex priceIndex, BigDecimal cycleDaysUsed, String reason, LocalDate runOutDate,
			Long supplierProductId, String forecastModel, LocalDate nextOrderDate) {
	}
}
