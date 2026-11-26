package com.zigzaego.api.purchaseorder.service;

import com.zigzaego.api.account.entity.Store;
import com.zigzaego.api.account.repository.StoreRepository;
import com.zigzaego.api.domain.order.DeliverySchedule;
import com.zigzaego.api.global.config.StoreClock;
import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.exception.CommonErrorCode;
import com.zigzaego.api.global.response.Amounts;
import com.zigzaego.api.global.store.ResourceIds;
import com.zigzaego.api.global.web.RequestDates;
import com.zigzaego.api.ingredient.entity.Ingredient;
import com.zigzaego.api.ingredient.entity.IngredientUnit;
import com.zigzaego.api.ingredient.repository.IngredientRepository;
import com.zigzaego.api.market.dto.IngredientOffersResponse;
import com.zigzaego.api.market.dto.IngredientOffersResponse.SiteOffers;
import com.zigzaego.api.market.service.MarketOfferService;
import com.zigzaego.api.onboarding.dto.RecipeUnit;
import com.zigzaego.api.purchaseorder.dto.PurchaseOrderCreateRequest;
import com.zigzaego.api.purchaseorder.dto.PurchaseOrderHistoryResponse;
import com.zigzaego.api.purchaseorder.dto.PurchaseOrderItemResponse;
import com.zigzaego.api.purchaseorder.dto.PurchaseOrderResponse;
import com.zigzaego.api.purchaseorder.entity.PurchaseOrder;
import com.zigzaego.api.purchaseorder.entity.PurchaseOrderItem;
import com.zigzaego.api.purchaseorder.entity.PurchaseOrderSource;
import com.zigzaego.api.purchaseorder.exception.PurchaseOrderErrorCode;
import com.zigzaego.api.purchaseorder.repository.PurchaseOrderItemRepository;
import com.zigzaego.api.purchaseorder.repository.PurchaseOrderRepository;
import com.zigzaego.api.purchaseorder.service.OrderMessageRenderer.Line;
import com.zigzaego.api.recommendation.entity.Recommendation;
import com.zigzaego.api.recommendation.entity.RecommendationStatus;
import com.zigzaego.api.recommendation.repository.RecommendationRepository;
import com.zigzaego.api.supplier.entity.Supplier;
import com.zigzaego.api.supplier.entity.SupplierProduct;
import com.zigzaego.api.supplier.repository.SupplierProductRepository;
import com.zigzaego.api.supplier.repository.SupplierRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 승인 건 → 거래처별 발주서 + 주문 메시지 (지라 S15P31A308-70, 3주 계획 4.7절, 설계 3절).
 *
 * <p>한 트랜잭션에서:
 * <ol>
 *   <li>승인(APPROVED)된 추천을 고른다 — id 목록을 받았으면 그것, 아니면 그날 승인 건 중 아직 발주서가 안 된 것 전부</li>
 *   <li>추천마다 거래처 상품(추천 상품 → 없으면 재료 대표 상품)을 찾아 <b>거래처별로 묶는다</b>. 상품이 없는 재료가 하나라도 있으면 409</li>
 *   <li>최종 수량(기준 단위)을 포장 단위로 <b>올림</b>한다 (대파 1,600g, 1단 = 1,000g → 2단). 환산을 모르는 상품은 기준 단위 그대로</li>
 *   <li>거래처 템플릿(없으면 4.7절 기본 문구)으로 메시지를 만들어 발주서(DRAFT) 한 장과 품목 줄을 저장한다</li>
 * </ol>
 * 같은 가게의 요청은 가게 행 잠금으로 한 줄로 세운다 — "모두 발주"를 동시에 두 번 눌러도 뒤 요청은 앞 요청이 만든 뒤의 데이터를 읽어
 * 빈 목록으로 끝난다. 품목 줄의 {@code recommendation_id} unique 는 그 안전망이다(잠금을 거치지 않는 경로가 생겨도 두 번 주문은 안 된다).
 * 검사에 하나라도 걸리면 발주서를 하나도 만들지 않는다.
 */
@Service
@RequiredArgsConstructor
public class PurchaseOrderService {

	/** 거래처 리드타임을 모르면 1일로 본다 — 추천 계산(RecommendationService.DEFAULT_LEAD_TIME_DAYS, SUPPLIER_UNSET)과 같은 가정. */
	static final int DEFAULT_LEAD_TIME_DAYS = 1;

	/** market_product.site 의 식봄 글자, 발주 이력 줄의 식봄 · 쿠팡 발주서 이름. */
	private static final String SIKBOM_SITE = "식봄";
	private static final String COUPANG_NAME = "쿠팡";

	/** 쿠팡 상품은 수집하지 않으므로(DECISIONS #68) 재료 이름 검색 주소를 링크로 쓴다. */
	private static final String COUPANG_SEARCH_URL = "https://www.coupang.com/np/search?q=";

	/** purchase_order_item.product_url VARCHAR(500). */
	private static final int PRODUCT_URL_MAX = 500;

	/** 같은 가게 요청끼리만 서로 기다리게 한다 (ReceiptConfirmService 와 같은 방식). */
	private static final String LOCK_STORE_SQL = "SELECT id FROM store WHERE id = :storeId FOR NO KEY UPDATE";

	/** 같은 추천이 이미 줄이 됐으면(동시 요청) 넣지 않는다 — unique 위반으로 트랜잭션이 깨지는 대신 빈 결과로 알린다. */
	private static final String INSERT_ITEM_SQL = """
			INSERT INTO purchase_order_item (store_id, po_id, supplier_product_id, ingredient_id, qty, expected_date,
			                                 recommendation_id, unit_price, product_url)
			VALUES (:storeId, :poId, :productId, :ingredientId, :qty, :expectedDate, :recommendationId, :unitPrice, :productUrl)
			ON CONFLICT ON CONSTRAINT uq_po_item_recommendation DO NOTHING
			RETURNING id
			""";

	/** 정확히 YYYY-MM. 2026-1 · +2026-10 같은 꼴은 받지 않는다. */
	private static final Pattern MONTH_PATTERN = Pattern.compile("[0-9]{4}-[0-9]{2}");

	/**
	 * 한 달의 발주 줄 (S15P31A308-202). 발주일 = 보낸 시각, 없으면 만든 시각. DRAFT 발주서는 넣지 않는다(#63).
	 * 모든 JOIN 에 store_id 를 건다 (ADR-0002, ReceivingService.HISTORY_SQL 과 같은 방식).
	 */
	private static final String MONTH_HISTORY_SQL = """
			SELECT poi.id, poi.po_id, COALESCE(po.sent_at, po.created_at) AS ordered_at, po.status,
			       i.name AS ingredient_name, i.unit, s.name AS supplier_name, po.source,
			       sp.base_qty_per_pack,
			       CASE WHEN po.source = 'SUPPLIER' THEN COALESCE(poi.unit_price, sp.price) ELSE poi.unit_price END AS price,
			       poi.qty, poi.received_qty, poi.received_unit_price, poi.received_at, poi.cancelled_at
			FROM purchase_order_item poi
			JOIN purchase_order po ON po.id = poi.po_id AND po.store_id = :storeId
			LEFT JOIN supplier s ON s.id = po.supplier_id AND s.store_id = :storeId
			JOIN supplier_product sp ON sp.id = poi.supplier_product_id AND sp.store_id = :storeId
			JOIN ingredient i ON i.id = poi.ingredient_id AND i.store_id = :storeId
			WHERE poi.store_id = :storeId AND po.status <> 'DRAFT'
			  AND COALESCE(po.sent_at, po.created_at) >= :from
			  AND COALESCE(po.sent_at, po.created_at) < :to
			ORDER BY ordered_at DESC, poi.id DESC
			""";

	/** 이 가게에서 발주 줄이 있는 DRAFT 아닌 발주서의 가장 이른 발주 시각. */
	private static final String EARLIEST_SQL = """
			SELECT MIN(COALESCE(po.sent_at, po.created_at))
			FROM purchase_order po
			WHERE po.store_id = :storeId AND po.status <> 'DRAFT'
			  AND EXISTS (SELECT 1 FROM purchase_order_item poi WHERE poi.po_id = po.id AND poi.store_id = :storeId)
			""";

	private final StoreRepository storeRepository;
	private final RecommendationRepository recommendationRepository;
	private final IngredientRepository ingredientRepository;
	private final SupplierProductRepository productRepository;
	private final SupplierRepository supplierRepository;
	private final PurchaseOrderRepository orderRepository;
	private final PurchaseOrderItemRepository itemRepository;
	private final NamedParameterJdbcTemplate jdbc;
	private final MarketOfferService marketOfferService;
	private final StoreClock clock;

	/**
	 * @param request null 이면 가게 시간대 오늘의 승인 건 전부
	 * @return 만든 발주서, 거래처 id 순. 만들 승인 건이 없으면 빈 목록 (id 목록을 받았을 땐 빈 목록이 아니라 오류)
	 * @throws BusinessException 없거나 남의 가게 추천이면 404 COMMON_NOT_FOUND, 날짜 형식이 틀리면 400 COMMON_INVALID_INPUT,
	 *                           그 밖은 {@link PurchaseOrderErrorCode}
	 */
	@Transactional
	public List<PurchaseOrderResponse> createFromRecommendations(long storeId, PurchaseOrderCreateRequest request) {
		Store store = storeRepository.findById(storeId)
				.orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
		ZoneId zone = ZoneId.of(store.getTimezone());
		Clock storeClock = clock.of(storeId);
		LocalDate today = LocalDate.now(storeClock.withZone(zone));
		LocalTime localNow = LocalTime.now(storeClock.withZone(zone));
		jdbc.queryForList(LOCK_STORE_SQL, new MapSqlParameterSource("storeId", storeId), Long.class);
		Map<Long, PurchaseOrderSource> sourceByRec = new HashMap<>();
		List<Recommendation> approved = pick(storeId, request, today, sourceByRec);
		if (approved.isEmpty()) {
			return List.of();
		}

		Map<Long, Ingredient> ingredients = ingredientRepository.findAllByStoreIdAndIdIn(storeId,
				approved.stream().map(Recommendation::getIngredientId).collect(Collectors.toSet()))
				.stream().collect(Collectors.toMap(Ingredient::getId, Function.identity()));
		if (ingredients.size() != approved.stream().map(Recommendation::getIngredientId).distinct().count()) {
			throw new BusinessException(CommonErrorCode.NOT_FOUND); // 추천이 가리키는 재료가 이 가게에 없다 (손상 데이터)
		}
		Map<Long, Long> productIdByRec = new TreeMap<>();
		for (Recommendation rec : approved) {
			Long productId = rec.getSupplierProductId() != null ? rec.getSupplierProductId()
					: ingredients.get(rec.getIngredientId()).getPrimarySupplierProductId();
			if (productId == null) {
				throw new BusinessException(PurchaseOrderErrorCode.PRODUCT_UNSET);
			}
			productIdByRec.put(rec.getId(), productId);
		}
		Map<Long, SupplierProduct> products = productRepository.findAllByStoreIdAndIdIn(storeId,
				new LinkedHashSet<>(productIdByRec.values()))
				.stream().collect(Collectors.toMap(SupplierProduct::getId, Function.identity()));
		for (Recommendation rec : approved) {
			SupplierProduct product = products.get(productIdByRec.get(rec.getId()));
			// 상품이 지워졌거나 남의 가게 상품, 또는 영수증 확정(relinkIngredient)으로 다른 재료에 붙은 상품 — 그 포장 환산은 이 재료 것이 아니다
			if (product == null || product.getIngredientId() != rec.getIngredientId().longValue()) {
				throw new BusinessException(PurchaseOrderErrorCode.PRODUCT_UNSET);
			}
		}

		// 주문처별로 묶는다. 거래처 줄은 거래처 id 순, 그다음 식봄 · 쿠팡 한 장씩, 안에서는 추천 id 순 (S15P31A308-201)
		Map<Long, List<Recommendation>> bySupplier = new TreeMap<>();
		Map<PurchaseOrderSource, List<Recommendation>> byMarket = new EnumMap<>(PurchaseOrderSource.class);
		Map<Long, MarketQuote> quotes = new HashMap<>(); // 추천 id → 식봄 · 쿠팡 단가·링크. 발주서를 만들기 전에 다 구해 409 면 하나도 안 만든다
		for (Recommendation rec : approved) {
			SupplierProduct product = products.get(productIdByRec.get(rec.getId()));
			PurchaseOrderSource source = sourceByRec.getOrDefault(rec.getId(), PurchaseOrderSource.SUPPLIER);
			if (source == PurchaseOrderSource.SUPPLIER) {
				bySupplier.computeIfAbsent(product.getSupplierId(), k -> new ArrayList<>()).add(rec);
			} else {
				byMarket.computeIfAbsent(source, k -> new ArrayList<>()).add(rec);
				quotes.put(rec.getId(), marketQuote(storeId, product, ingredients.get(rec.getIngredientId()), source, today));
			}
		}
		Map<Long, Supplier> suppliers = supplierRepository.findAllByStoreIdAndIdIn(storeId, bySupplier.keySet())
				.stream().collect(Collectors.toMap(Supplier::getId, Function.identity()));

		OffsetDateTime now = OffsetDateTime.now(storeClock);
		List<PurchaseOrderResponse> responses = new ArrayList<>();
		for (Map.Entry<Long, List<Recommendation>> group : bySupplier.entrySet()) {
			Supplier supplier = suppliers.get(group.getKey());
			if (supplier == null) {
				throw new BusinessException(PurchaseOrderErrorCode.PRODUCT_UNSET); // 상품은 있는데 거래처가 없다
			}
			int leadTime = supplier.getLeadTimeDays() != null ? supplier.getLeadTimeDays() : DEFAULT_LEAD_TIME_DAYS;
			int cycle = supplier.getOrderCycleDays() != null ? supplier.getOrderCycleDays() : 1;
			// 추천과 같은 도착일 — 배송 요일 · 주문 마감 반영 (DeliverySchedule, DECISIONS #89)
			LocalDate expectedDate = DeliverySchedule.resolve(today, localNow, leadTime, cycle,
					supplier.deliveryDaysOfWeek(), supplier.getCutoffTime()).arrival();

			List<Line> lines = new ArrayList<>();
			for (Recommendation rec : group.getValue()) {
				SupplierProduct product = products.get(productIdByRec.get(rec.getId()));
				lines.add(new Line(product.getProductName(), toPackQty(product, orderQty(rec)), product.getPackUnit(),
						product.getBaseQtyPerPack(), ingredients.get(rec.getIngredientId()).getUnit()));
			}
			String message = OrderMessageRenderer.render(supplier.getMessageTemplate(), store.getDisplayName(),
					expectedDate, lines);
			PurchaseOrder order = orderRepository.save(
					PurchaseOrder.draft(storeId, PurchaseOrderSource.SUPPLIER, supplier.getId(), message, now));

			List<PurchaseOrderItemResponse> items = new ArrayList<>();
			for (int i = 0; i < group.getValue().size(); i++) {
				Recommendation rec = group.getValue().get(i);
				SupplierProduct product = products.get(productIdByRec.get(rec.getId()));
				items.add(saveItem(storeId, order, product, rec, ingredients.get(rec.getIngredientId()), lines.get(i).packQty(),
						expectedDate, product.getPrice(), null));
			}
			responses.add(PurchaseOrderResponse.of(order, supplier, items));
		}

		// 식봄 · 쿠팡 발주서: 거래처가 없어 리드타임은 기본 1일, 메시지는 줄마다 "품목 포장수 — 링크"
		LocalDate marketDate = today.plusDays(DEFAULT_LEAD_TIME_DAYS);
		for (PurchaseOrderSource source : List.of(PurchaseOrderSource.SIKBOM, PurchaseOrderSource.COUPANG, PurchaseOrderSource.MARKETBOM, PurchaseOrderSource.ORDERLINK)) {
			List<Recommendation> recs = byMarket.get(source);
			if (recs == null) {
				continue;
			}
			List<BigDecimal> packQtys = new ArrayList<>();
			List<String> messageLines = new ArrayList<>();
			for (Recommendation rec : recs) {
				SupplierProduct product = products.get(productIdByRec.get(rec.getId()));
				Ingredient ingredient = ingredients.get(rec.getIngredientId());
				BigDecimal packQty = toPackQty(product, orderQty(rec));
				packQtys.add(packQty);
				messageLines.add(new Line(ingredient.getName(), packQty, product.getPackUnit(), product.getBaseQtyPerPack(),
						ingredient.getUnit()).render() + " — " + quotes.get(rec.getId()).url());
			}
			PurchaseOrder order = orderRepository.save(
					PurchaseOrder.draft(storeId, source, null, String.join("\n", messageLines), now));
			List<PurchaseOrderItemResponse> items = new ArrayList<>();
			for (int i = 0; i < recs.size(); i++) {
				Recommendation rec = recs.get(i);
				MarketQuote quote = quotes.get(rec.getId());
				items.add(saveItem(storeId, order, products.get(productIdByRec.get(rec.getId())), rec,
						ingredients.get(rec.getIngredientId()), packQtys.get(i), marketDate,
						quote.unitPrice() == null ? null : BigDecimal.valueOf(quote.unitPrice()), quote.url()));
			}
			responses.add(PurchaseOrderResponse.of(order, null, items));
		}
		return responses;
	}

	/** 줄 하나를 저장하고 응답 줄로 돌려준다. unitPrice 는 포장 1개 상당 원(모르면 null). */
	private PurchaseOrderItemResponse saveItem(long storeId, PurchaseOrder order, SupplierProduct product, Recommendation rec,
			Ingredient ingredient, BigDecimal packQty, LocalDate expectedDate, BigDecimal unitPrice, String productUrl) {
		long itemId = insertItem(storeId, order.getId(), product, rec, packQty, expectedDate, unitPrice, productUrl);
		BigDecimal baseQty = product.toBaseQty(packQty).orElse(packQty);
		return new PurchaseOrderItemResponse(String.valueOf(itemId), String.valueOf(rec.getId()),
				String.valueOf(rec.getIngredientId()), ingredient.getName(), String.valueOf(product.getId()),
				product.getProductName(), Amounts.qty(baseQty), expectedDate.toString(), Amounts.won(unitPrice), productUrl);
	}

	/** 식봄 · 쿠팡 줄의 단가(포장 1개 상당 원, 쿠팡은 null)와 링크. */
	private record MarketQuote(Long unitPrice, String url) {
	}

	/**
	 * 식봄 1위 단가(원/기준 단위) × 재료 단위 환산 계수 × 대표 상품 포장당 기준 단위량(모르면 1)을 원 정수로 반올림(HALF_UP, #55).
	 * 식봄 사이트 줄이 없거나 환산 계수 · 단가가 없으면 409 SOURCE_PRICE_UNSET.
	 */
	private MarketQuote marketQuote(long storeId, SupplierProduct product, Ingredient ingredient, PurchaseOrderSource source, LocalDate today) {
		IngredientOffersResponse offers = marketOfferService.offers(storeId, String.valueOf(ingredient.getId()));
		SiteOffers site = offers.sites().stream().filter(s -> source.site().equals(s.site())).findFirst().orElse(null);
		if (site == null && source == PurchaseOrderSource.COUPANG) return coupangQuote(ingredient);
		if (site == null || site.offers().isEmpty() || site.bestUnitPrice() == null || offers.toIngredientUnitFactor() == null
				|| !today.equals(site.collectedOn())) {
			throw new BusinessException(PurchaseOrderErrorCode.SOURCE_PRICE_UNSET);
		}
		BigDecimal perPack = product.getBaseQtyPerPack() != null ? product.getBaseQtyPerPack() : BigDecimal.ONE;
		long won = site.bestUnitPrice().multiply(offers.toIngredientUnitFactor()).multiply(perPack)
				.setScale(0, RoundingMode.HALF_UP).longValueExact();
		return new MarketQuote(won, site.offers().getFirst().url());
	}

	/** 쿠팡은 가격을 수집하지 않는다(#68) — 단가 없이 재료 이름 쿠팡 검색 주소만. */
	private static MarketQuote coupangQuote(Ingredient ingredient) {
		return new MarketQuote(null, COUPANG_SEARCH_URL + encodeWithin(ingredient.getName().strip(), PRODUCT_URL_MAX - COUPANG_SEARCH_URL.length()));
	}

	/** product_url VARCHAR(500) 을 넘지 않게, 이름을 앞에서부터 글자(코드포인트) 단위로 잘라 인코딩한 길이가 limit 이하가 되게 한다. */
	private static String encodeWithin(String name, int limit) {
		StringBuilder out = new StringBuilder();
		int i = 0;
		while (i < name.length()) {
			int cp = name.codePointAt(i);
			String piece = URLEncoder.encode(new String(Character.toChars(cp)), StandardCharsets.UTF_8);
			if (out.length() + piece.length() > limit) {
				break;
			}
			out.append(piece);
			i += Character.charCount(cp);
		}
		return out.toString();
	}

	/**
	 * 보냄 기록 (S15P31A308-200, DECISIONS #63). DRAFT → SENT · sent_at = 지금. 이미 SENT 면 그대로 돌려준다(멱등, 첫 시각 유지).
	 * 보낸 발주서만 입고 예정(ReceivingService.INCOMING_SQL · RecommendationService.ON_ORDER_SQL)에 들어간다.
	 * 같은 가게 요청은 가게 행 잠금으로 한 줄로 세워, 동시에 두 번 눌러도 sent_at 은 처음 것 하나다.
	 *
	 * @param rawId URL 의 발주서 id
	 * @throws BusinessException 없거나 다른 가게 발주서 · 숫자 아닌 id 면 404 COMMON_NOT_FOUND,
	 *                           취소한 발주서면 409 {@link PurchaseOrderErrorCode#CANCELLED}
	 */
	@Transactional
	public PurchaseOrderResponse send(long storeId, String rawId) {
		long orderId = ResourceIds.parse(rawId);
		jdbc.queryForList(LOCK_STORE_SQL, new MapSqlParameterSource("storeId", storeId), Long.class);
		PurchaseOrder order = orderRepository.getInStore(orderId, storeId);
		if (order.isCancelled()) {
			throw new BusinessException(PurchaseOrderErrorCode.CANCELLED);
		}
		order.markSent(OffsetDateTime.now(clock.of(storeId)));
		return toResponse(storeId, order);
	}

	/** 저장된 발주서 한 장을 응답 모양으로. 품목 줄은 줄 id 순(만들 때 추천 id 순으로 넣었다). */
	private PurchaseOrderResponse toResponse(long storeId, PurchaseOrder order) {
		Supplier supplier = order.getSupplierId() == null ? null : supplierRepository.getInStore(order.getSupplierId(), storeId);
		List<PurchaseOrderItem> rows = itemRepository.findAllByStoreIdAndPoIdInOrderByIdAsc(storeId, List.of(order.getId()));
		Map<Long, Ingredient> ingredients = ingredientRepository.findAllByStoreIdAndIdIn(storeId,
						rows.stream().map(PurchaseOrderItem::getIngredientId).collect(Collectors.toSet()))
				.stream().collect(Collectors.toMap(Ingredient::getId, Function.identity()));
		Map<Long, SupplierProduct> products = productRepository.findAllByStoreIdAndIdIn(storeId,
						rows.stream().map(PurchaseOrderItem::getSupplierProductId).collect(Collectors.toSet()))
				.stream().collect(Collectors.toMap(SupplierProduct::getId, Function.identity()));
		List<PurchaseOrderItemResponse> items = new ArrayList<>();
		for (PurchaseOrderItem row : rows) {
			SupplierProduct product = products.get(row.getSupplierProductId());
			Ingredient ingredient = ingredients.get(row.getIngredientId());
			BigDecimal baseQty = product == null ? row.getQty() : product.toBaseQty(row.getQty()).orElse(row.getQty());
			items.add(new PurchaseOrderItemResponse(String.valueOf(row.getId()),
					row.getRecommendationId() == null ? null : String.valueOf(row.getRecommendationId()),
					String.valueOf(row.getIngredientId()), ingredient == null ? null : ingredient.getName(),
					String.valueOf(row.getSupplierProductId()), product == null ? null : product.getProductName(),
					Amounts.qty(baseQty), row.getExpectedDate() == null ? null : row.getExpectedDate().toString(),
						Amounts.won(row.getUnitPrice()), row.getProductUrl()));
		}
		return PurchaseOrderResponse.of(order, supplier, items);
	}

	/**
	 * 발주 이력 한 달치 (S15P31A308-202). 달 경계는 가게 시간대 그 달 1일 00:00 이상 · 다음 달 1일 00:00 미만.
	 *
	 * @param rawMonth YYYY-MM. null 이면 가게 시간대 이번 달
	 * @throws BusinessException 형식이 틀리거나 없는 달이면 400 COMMON_INVALID_INPUT
	 */
	@Transactional(readOnly = true)
	public PurchaseOrderHistoryResponse history(long storeId, String rawMonth) {
		Store store = storeRepository.findById(storeId)
				.orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
		ZoneId zone = ZoneId.of(store.getTimezone());
		YearMonth month = rawMonth == null ? YearMonth.now(clock.of(storeId).withZone(zone)) : parseMonth(rawMonth);

		MapSqlParameterSource params = new MapSqlParameterSource("storeId", storeId)
				.addValue("from", month.atDay(1).atStartOfDay(zone).toOffsetDateTime())
				.addValue("to", month.plusMonths(1).atDay(1).atStartOfDay(zone).toOffsetDateTime());
		List<PurchaseOrderHistoryResponse.Line> lines = new ArrayList<>(
				jdbc.query(MONTH_HISTORY_SQL, params, (rs, i) -> historyLine(rs, zone)));
		// 발주일(가게 시간대 날짜) 내림차순, 같은 날이면 줄 id 내림차순 — 시각 순이 아니라 날짜 순
		lines.sort(Comparator.comparing(PurchaseOrderHistoryResponse.Line::date).reversed()
				.thenComparing(l -> Long.parseLong(l.itemId()), Comparator.reverseOrder()));
		long total = lines.stream()
				.filter(l -> l.status() != PurchaseOrderHistoryResponse.Status.CANCELLED && l.amount() != null)
				.mapToLong(PurchaseOrderHistoryResponse.Line::amount)
				.sum();

		List<OffsetDateTime> earliest = jdbc.query(EARLIEST_SQL, new MapSqlParameterSource("storeId", storeId),
				(rs, i) -> rs.getObject(1, OffsetDateTime.class));
		OffsetDateTime first = earliest.isEmpty() ? null : earliest.getFirst();
		String earliestMonth = first == null ? null : YearMonth.from(first.atZoneSameInstant(zone)).toString();
		return new PurchaseOrderHistoryResponse(month.toString(), lines, total, earliestMonth);
	}

	private static YearMonth parseMonth(String text) {
		if (!MONTH_PATTERN.matcher(text).matches()) {
			throw new BusinessException(CommonErrorCode.INVALID_INPUT, "month 는 YYYY-MM 형식이어야 합니다.");
		}
		try {
			return YearMonth.parse(text);
		} catch (DateTimeParseException e) {
			throw new BusinessException(CommonErrorCode.INVALID_INPUT, "없는 달입니다: " + text);
		}
	}

	/**
	 * 줄 하나. 상태: 줄 취소 · 발주서 취소 → CANCELLED, 입고 확인 → RECEIVED, 그 밖은 SENT.
	 * 양 · 금액: 입고 확인한 줄은 받은 포장 수(× 받은 포장 단가), 아니면 주문 포장 수(× 저장한 줄 단가 —
	 * 거래처 줄의 옛 줄처럼 null 이면 지금 상품 포장 가격, 쿠팡 줄은 null 이면 금액 null).
	 */
	private static PurchaseOrderHistoryResponse.Line historyLine(ResultSet rs, ZoneId zone) throws SQLException {
		boolean received = rs.getObject("received_at") != null;
		boolean cancelled = rs.getObject("cancelled_at") != null || "CANCELLED".equals(rs.getString("status"));
		PurchaseOrderHistoryResponse.Status status = cancelled ? PurchaseOrderHistoryResponse.Status.CANCELLED
				: received ? PurchaseOrderHistoryResponse.Status.RECEIVED : PurchaseOrderHistoryResponse.Status.SENT;

		BigDecimal receivedPacks = rs.getBigDecimal("received_qty");
		boolean useReceived = received && receivedPacks != null;
		BigDecimal packs = useReceived ? receivedPacks : rs.getBigDecimal("qty");
		BigDecimal packPrice = useReceived ? rs.getBigDecimal("received_unit_price") : rs.getBigDecimal("price");

		// 포장 크기를 모르면 qty 는 null — 포장 개수를 기준 단위 양처럼 내지 않는다(#42, ReceivingService.toBase 와 같은 규칙).
		BigDecimal basePerPack = rs.getBigDecimal("base_qty_per_pack");
		BigDecimal qty = basePerPack == null ? null : packs.multiply(basePerPack);
		String unit = RecipeUnit.of(IngredientUnit.valueOf(rs.getString("unit"))).label();

		OffsetDateTime orderedAt = rs.getObject("ordered_at", OffsetDateTime.class);
		return new PurchaseOrderHistoryResponse.Line(
				orderedAt.atZoneSameInstant(zone).toLocalDate().toString(),
				String.valueOf(rs.getLong("po_id")),
				String.valueOf(rs.getLong("id")),
				rs.getString("ingredient_name"),
				switch (PurchaseOrderSource.valueOf(rs.getString("source"))) {
					case SUPPLIER -> rs.getString("supplier_name");
					case SIKBOM -> SIKBOM_SITE;
					case COUPANG -> COUPANG_NAME;
					case MARKETBOM -> "마켓봄";
					case ORDERLINK -> "오더링크";
				},
				Amounts.qty(qty),
				unit,
				packPrice == null ? null : Amounts.won(packs.multiply(packPrice)),
				status);
	}

	/** 발주서로 만들 승인 건. id 를 받았으면 전부 있고 · 내 가게고 · APPROVED 고 · 아직 발주서가 아니어야 한다. */
	private List<Recommendation> pick(long storeId, PurchaseOrderCreateRequest request, LocalDate today,
			Map<Long, PurchaseOrderSource> sourceByRec) {
		List<String> rawIds = request == null ? null : request.recommendationIds();
		List<PurchaseOrderCreateRequest.Item> requested = request == null ? null : request.items();
		if (requested != null && requested.isEmpty()) {
			throw new BusinessException(CommonErrorCode.INVALID_INPUT, "items 를 빈 목록으로 보낼 수 없습니다.");
		}
		boolean hasItems = requested != null;
		if (hasItems && rawIds != null && !rawIds.isEmpty()) {
			throw new BusinessException(CommonErrorCode.INVALID_INPUT, "items 와 recommendationIds 는 같이 줄 수 없습니다.");
		}
		if (hasItems) {
			rawIds = new ArrayList<>();
			for (PurchaseOrderCreateRequest.Item item : requested) {
				rawIds.add(item.recommendationId());
			}
		}
		if (rawIds == null || rawIds.isEmpty()) {
			LocalDate date = request == null ? null : RequestDates.parseOrNull(request.date());
			List<Recommendation> approved = recommendationRepository.findAllByStoreIdAndRunDateAndStatusOrderByIdAsc(
					storeId, date != null ? date : today, RecommendationStatus.APPROVED);
			Set<Long> ordered = alreadyOrdered(storeId, approved);
			return approved.stream().filter(r -> !ordered.contains(r.getId())).toList();
		}
		Set<Long> ids = new LinkedHashSet<>();
		for (int i = 0; i < rawIds.size(); i++) {
			long id = ResourceIds.parse(rawIds.get(i));
			if (!ids.add(id)) {
				throw new BusinessException(PurchaseOrderErrorCode.RECOMMENDATION_DUPLICATE);
			}
			if (hasItems && requested.get(i).source() != null) {
				sourceByRec.put(id, requested.get(i).source());
			}
		}
		List<Recommendation> found = recommendationRepository.findAllByStoreIdAndIdInOrderByIdAsc(storeId, ids);
		if (found.size() != ids.size()) {
			throw new BusinessException(CommonErrorCode.NOT_FOUND);
		}
		if (found.stream().anyMatch(r -> r.getStatus() != RecommendationStatus.APPROVED)) {
			throw new BusinessException(PurchaseOrderErrorCode.NOT_APPROVED);
		}
		if (!alreadyOrdered(storeId, found).isEmpty()) {
			throw new BusinessException(PurchaseOrderErrorCode.ALREADY_ORDERED);
		}
		return found;
	}

	private Set<Long> alreadyOrdered(long storeId, List<Recommendation> recommendations) {
		if (recommendations.isEmpty()) {
			return Set.of();
		}
		return itemRepository.findAllByStoreIdAndRecommendationIdIn(storeId,
						recommendations.stream().map(Recommendation::getId).toList())
				.stream().map(PurchaseOrderItem::getRecommendationId).collect(Collectors.toSet());
	}

	/** 주문할 양(기준 단위) = 사장님 최종 수량. APPROVED 인데 final_qty 가 비어 있으면(손상 데이터) 추천 수량. */
	private static BigDecimal orderQty(Recommendation rec) {
		return rec.getFinalQty() != null ? rec.getFinalQty() : rec.getRecommendedQty();
	}

	/** 기준 단위 → 포장 단위 올림. 환산을 모르면 기준 단위 그대로 (포장 1개 = 기준 단위 1, ON_ORDER_SQL 의 COALESCE 와 같다). */
	static BigDecimal toPackQty(SupplierProduct product, BigDecimal baseQty) {
		if (product.getBaseQtyPerPack() == null) {
			return baseQty.setScale(3, RoundingMode.HALF_UP);
		}
		return baseQty.divide(product.getBaseQtyPerPack(), 0, RoundingMode.CEILING).setScale(3, RoundingMode.HALF_UP);
	}

	private long insertItem(long storeId, long poId, SupplierProduct product, Recommendation rec, BigDecimal packQty,
			LocalDate expectedDate, BigDecimal unitPrice, String productUrl) {
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("storeId", storeId)
				.addValue("poId", poId)
				.addValue("productId", product.getId())
				.addValue("ingredientId", rec.getIngredientId())
				.addValue("qty", packQty)
				.addValue("expectedDate", expectedDate)
				.addValue("recommendationId", rec.getId())
				.addValue("unitPrice", unitPrice)
				.addValue("productUrl", productUrl);
		List<Long> inserted = jdbc.queryForList(INSERT_ITEM_SQL, params, Long.class);
		if (inserted.isEmpty()) {
			throw new BusinessException(PurchaseOrderErrorCode.ALREADY_ORDERED); // 동시 요청이 먼저 만들었다
		}
		return inserted.getFirst();
	}
}
