package com.zigzaego.api.purchasedoc.service;

import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.exception.CommonErrorCode;
import com.zigzaego.api.global.store.ResourceIds;
import com.zigzaego.api.ingredient.entity.CategorySource;
import com.zigzaego.api.ingredient.entity.Ingredient;
import com.zigzaego.api.ingredient.entity.IngredientCategory;
import com.zigzaego.api.ingredient.entity.IngredientKind;
import com.zigzaego.api.ingredient.entity.IngredientUnit;
import com.zigzaego.api.ingredient.entity.SourcingType;
import com.zigzaego.api.ingredient.entity.StorageLocation;
import com.zigzaego.api.ingredient.entity.UnitStatus;
import com.zigzaego.api.ingredient.repository.IngredientRepository;
import com.zigzaego.api.ingredient.service.IngredientCategoryBackup;
import com.zigzaego.api.ingredient.service.IngredientCategoryClassifier;
import com.zigzaego.api.purchasedoc.dto.ConfirmResultResponse;
import com.zigzaego.api.purchasedoc.dto.LineDecisionRequest;
import com.zigzaego.api.purchasedoc.dto.LineDecisionRequest.Action;
import com.zigzaego.api.purchasedoc.entity.DocKind;
import com.zigzaego.api.purchasedoc.entity.DocLineStatus;
import com.zigzaego.api.purchasedoc.entity.ParseStatus;
import com.zigzaego.api.purchasedoc.entity.PurchaseDoc;
import com.zigzaego.api.purchasedoc.entity.PurchaseDocLine;
import com.zigzaego.api.purchasedoc.exception.ReceiptConfirmErrorCode;
import com.zigzaego.api.purchasedoc.repository.PurchaseDocLineRepository;
import com.zigzaego.api.purchasedoc.repository.PurchaseDocRepository;
import com.zigzaego.api.purchaseorder.service.PackSizeFillRescaler;
import com.zigzaego.api.receiving.service.ReceivingService;
import com.zigzaego.api.supplier.entity.SupplierProduct;
import com.zigzaego.api.supplier.repository.SupplierProductRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 영수증 줄 확정 (S15P31A308-112, 설계 4.1 · 4.2 · 4.3, DECISIONS #41).
 *
 * <p>사장님이 줄마다 재료 연결 · 새 주기형 · 무시를 정하면 한 트랜잭션에서:
 * <ol>
 *   <li>재료를 정한다 — 주기형은 같은 이름 재료가 있으면 그것, 없으면 새로 만든다(V1 주석 기본값, recipe 없음)</li>
 *   <li>문서 거래처가 있으면 상품명 → 재료 사전(supplier_product)을 저장한다 — 다음 영수증의 같은 상품명은 LLM 없이 맞는다.
 *       거래처를 모르는 문서는 사전 없이 줄만 확정한다(거래처를 새로 만들지 않는다)</li>
 *   <li>줄을 CONFIRMED · 재료 단위를 LOCKED 로, 사전 상품이 있고 단가가 있으면 price_history(DOC) 1행 — PAST · RECEIVE 모두</li>
 *   <li>남은 PENDING 줄이 0 인 문서는 CONFIRMED — 그때부터 RECEIVE 문서가 추정 재고에 들어간다(InventoryEstimateService 의 기존 SQL)</li>
 *   <li>확정된 RECEIVE 문서의 줄이 같은 입고 예정 줄에 맞으면 그 줄들을 입고 확인(71 {@link ReceivingService#recordReceipt})하고
 *       영수증 줄을 발주 줄에 잇는다(전부 한 발주서에 맞으면 문서도 발주서에) — 재고는 발주 줄로 한 번만 센다(DECISIONS #41)</li>
 * </ol>
 * 검사에 하나라도 걸리면 아무것도 저장하지 않는다. 같은 가게의 확정은 가게 행 잠금으로 한 줄로 세운다 — 같은 줄 두 번 확정 ·
 * 같은 사전 상품 · 같은 이름 주기형 재료를 동시에 만드는 충돌을 막는다(뒤 요청은 앞 요청이 만든 것을 찾아 쓴다).
 * 잠금을 거치지 않는 다른 경로(거래처 상품 · 재료 API)와의 unique 충돌은 {@code ON CONFLICT DO NOTHING} 뒤 다시 찾아 쓴다.
 * 같은 상품명 · 같은 재료 줄이 한 요청에 여럿이면 포장 환산은 그중 한 줄에만 줘도 된다(줄 순서와 무관).
 * 같은 상품명 줄들이 서로 다른 포장 환산을 주면 400 RECEIPT_PACK_CONFLICT 로 거절한다.
 */
@Service
@RequiredArgsConstructor
public class ReceiptConfirmService {

	/** 포장 단위를 모를 때 단가 기록의 단위 — 45 SupplierProductService 와 같은 값. 단가는 늘 포장 1개 값이다. */
	static final String UNKNOWN_PACK_UNIT = "PACK";
	/** 재료 이름 칸 길이(ingredient.name VARCHAR(100)). */
	private static final int INGREDIENT_NAME_MAX = 100;
	private static final String AUTO_CONFIRMED = "autoConfirmed";

	/**
	 * 같은 가게 확정끼리만 서로 기다리게 한다. FOR NO KEY UPDATE 는 다른 표의 store FK 삽입(FOR KEY SHARE)은 막지 않는다.
	 */
	private static final String LOCK_STORE_SQL = "SELECT id FROM store WHERE id = :storeId FOR NO KEY UPDATE";

	/**
	 * 새 사전 상품. 확정끼리는 가게 잠금으로 줄을 서지만 거래처 상품 API(45)는 잠금을 거치지 않으므로, 같은 (거래처, 상품명)이
	 * 그 사이 생겼으면 넣지 않고(빈 결과) 다시 찾아 쓴다 — unique 위반으로 트랜잭션 전체가 깨지지 않게.
	 */
	private static final String INSERT_PRODUCT_SQL = """
			INSERT INTO supplier_product (store_id, supplier_id, ingredient_id, product_name, base_qty_per_pack, price)
			VALUES (:storeId, :supplierId, :ingredientId, :productName, :baseQty, :price)
			ON CONFLICT ON CONSTRAINT uq_supplier_product_supplier_name DO NOTHING
			RETURNING id
			""";

	/**
	 * 새 주기형 재료 (V1 ingredient.sourcing_type · price_sensitive 주석: GENERIC · false, 보관 방식 NULL,
	 * 보관 위치는 {@link StorageLocation#defaultFor} 규칙, 카테고리는 그 가게의 기타 — S15P31A308-273). 충돌 처리는 위와 같다.
	 */
	private static final String INSERT_CYCLE_INGREDIENT_SQL = """
			INSERT INTO ingredient (store_id, name, unit, safety_days, price_sensitive, sourcing_type, kind, unit_status,
			                        storage_type, storage_location, shelf_life_days, category_id, category_source)
			VALUES (:storeId, :name, :unit, 1, false, :sourcingType, :kind, :unitStatus, NULL, :storageLocation, NULL,
			        :categoryId, :categorySource)
			ON CONFLICT ON CONSTRAINT uq_ingredient_store_name DO NOTHING
			RETURNING id
			""";

	/** 관찰 시각 = 문서 날짜 00:00 (가게 시간대). */
	private static final String INSERT_PRICE_SQL = """
			INSERT INTO price_history (store_id, supplier_product_id, price, unit, observed_at, source, doc_line_id)
			SELECT s.id, :productId, :price, :unit, CAST(CAST(:docDate AS date) AS timestamp) AT TIME ZONE s.timezone,
			       'DOC', :lineId
			FROM store s WHERE s.id = :storeId
			""";

	/**
	 * 이 문서 거래처의 입고 예정 줄(71 피드의 입고 예정과 같은 정의) 중 문서 날짜까지 만든 발주서의 것 + 자동 입고된 줄(auto_received)
	 * 중 예정일이 문서 날짜 ±3일 안인 것 (S15P31A308-238). 아직 안 들어온 줄 먼저(먼저 만든 발주서부터), 그다음 자동 입고 줄을
	 * 예정일이 문서 날짜에 가까운 순(같으면 먼저 만든 발주서).
	 * 탭 입고 확인과 겹치지 않게 줄을 잠근다.
	 */
	private static final String INCOMING_FOR_DOC_SQL = """
			SELECT poi.id, poi.po_id, poi.supplier_product_id, poi.ingredient_id, poi.auto_received
			FROM purchase_order_item poi
			JOIN purchase_order po ON po.id = poi.po_id AND po.store_id = :storeId
			JOIN store s ON s.id = po.store_id
			WHERE poi.store_id = :storeId AND po.supplier_id = :supplierId
			  AND (poi.received_at IS NULL
			       OR (poi.auto_received AND poi.expected_date IS NOT NULL
			           AND ABS(CAST(:docDate AS date) - poi.expected_date) <= 3))
			  AND poi.cancelled_at IS NULL AND po.status <> 'CANCELLED'
			  AND CAST(po.created_at AT TIME ZONE s.timezone AS date) <= :docDate
			  AND poi.supplier_product_id IN (:productIds)
			ORDER BY (poi.received_at IS NOT NULL),
			         CASE WHEN poi.received_at IS NOT NULL THEN ABS(CAST(:docDate AS date) - poi.expected_date) END,
			         po.created_at, po.id, poi.id
			FOR UPDATE OF poi
			""";

	/** 발주서와 이어진 문서의 줄은 추정 재고 ② 에서 빠진다 — 발주 줄 received_qty(①)로 한 번만 센다 (DECISIONS #41). */
	private static final String LINK_DOC_SQL = """
			UPDATE purchase_doc SET purchase_order_id = :orderId WHERE id = :docId AND store_id = :storeId
			""";

	/** 영수증 줄 ↔ 발주 줄 (V15). 이어진 줄은 추정 재고 ② 에서 빠진다. */
	private static final String LINK_LINE_SQL = """
			UPDATE purchase_doc_line SET purchase_order_item_id = :itemId WHERE id = :lineId AND store_id = :storeId
			""";

	/** 입고 확인 단가 칸의 정수 자릿수 (ReceiveRequest.PRICE_INTEGER_DIGITS 와 같은 값, DB NUMERIC(12,2)). */
	private static final int RECEIPT_PRICE_INTEGER_DIGITS = 10;

	private final ReceivingService receivingService;
	private final PackSizeFillRescaler packSizeFillRescaler;
	private final PurchaseDocRepository docRepository;
	private final PurchaseDocLineRepository lineRepository;
	private final IngredientRepository ingredientRepository;
	private final IngredientCategoryClassifier categoryClassifier;
	private final IngredientCategoryBackup categoryBackup;
	private final SupplierProductRepository productRepository;
	private final NamedParameterJdbcTemplate jdbc;
	private final JsonMapper jsonMapper;

	/**
	 * @param decisions 줄마다 결정. 줄 id 오름차순으로 반영한다
	 * @throws BusinessException 검사에 걸리면 아무것도 저장하지 않고 {@link ReceiptConfirmErrorCode} 또는 404 COMMON_NOT_FOUND
	 */
	@Transactional
	public ConfirmResultResponse confirm(long storeId, List<LineDecisionRequest> decisions) {
		if (decisions == null || decisions.isEmpty()) {
			throw new BusinessException(ReceiptConfirmErrorCode.LINE_REQUIRED);
		}
		Set<String> rawIds = new HashSet<>();
		for (LineDecisionRequest decision : decisions) {
			if (decision == null) {
				throw new BusinessException(CommonErrorCode.INVALID_INPUT);
			}
			if (!rawIds.add(decision.lineId())) {
				throw new BusinessException(ReceiptConfirmErrorCode.LINE_DUPLICATE);
			}
		}
		lockStore(storeId);
		Map<Long, LineDecisionRequest> byLineId = new TreeMap<>();
		for (LineDecisionRequest decision : decisions) {
			if (byLineId.put(ResourceIds.parse(decision.lineId()), decision) != null) {
				throw new BusinessException(ReceiptConfirmErrorCode.LINE_DUPLICATE); // "7" 과 "07"
			}
		}
		Map<Long, PurchaseDocLine> lines = lineRepository.findAllByStoreIdAndIdInOrderByIdAsc(storeId, byLineId.keySet())
				.stream().collect(Collectors.toMap(PurchaseDocLine::getId, Function.identity()));
		if (lines.size() != byLineId.size()) {
			throw new BusinessException(CommonErrorCode.NOT_FOUND);
		}
		Map<Long, PurchaseDoc> docs = docRepository.findAllByStoreIdAndIdInOrderByIdAsc(storeId,
				lines.values().stream().map(PurchaseDocLine::getDocId).collect(Collectors.toSet()))
				.stream().collect(Collectors.toMap(PurchaseDoc::getId, Function.identity()));

		List<Plan> plans = validate(storeId, byLineId, lines, docs);
		return apply(storeId, plans, docs);
	}

	/**
	 * 모든 줄이 자동 확정 · 확인된 문서(남은 PENDING 0)를 사장님이 한 번에 확정한다 (S15P31A308-112 · 진입점은 S15P31A308-113).
	 * 자동 확정 줄 몫의 가격 이력 · 단위 LOCKED 를 이때 기록한다. 같은 id 가 여러 번 오면 한 번으로 친다.
	 *
	 * @throws BusinessException 목록이 비면 400 RECEIPT_DOC_REQUIRED, 없거나 다른 가게 문서면 404, PARSED 아님 409
	 *         RECEIPT_DOC_NOT_PARSED, 날짜 없음 409 RECEIPT_DATE_UNKNOWN, PENDING 줄이 남으면 409 RECEIPT_DOC_HAS_PENDING
	 */
	@Transactional
	public ConfirmResultResponse confirmDocuments(long storeId, List<String> docIds) {
		if (docIds == null || docIds.isEmpty()) {
			throw new BusinessException(ReceiptConfirmErrorCode.DOC_REQUIRED);
		}
		lockStore(storeId);
		Set<Long> ids = new TreeSet<>();
		for (String rawId : docIds) {
			ids.add(ResourceIds.parse(rawId));
		}
		List<PurchaseDoc> docs = docRepository.findAllByStoreIdAndIdInOrderByIdAsc(storeId, ids);
		if (docs.size() != ids.size()) {
			throw new BusinessException(CommonErrorCode.NOT_FOUND);
		}
		for (PurchaseDoc doc : docs) {
			requireConfirmable(doc);
			if (lineRepository.countByStoreIdAndDocIdAndStatus(storeId, doc.getId(), DocLineStatus.PENDING) > 0) {
				throw new BusinessException(ReceiptConfirmErrorCode.DOC_HAS_PENDING);
			}
		}
		Result result = new Result();
		for (PurchaseDoc doc : docs) {
			confirmDocument(storeId, doc, result);
		}
		return result.toConfirmResult();
	}

	// ---------------------------------------------------------------- 검사

	/** 한 줄의 반영 계획. 검사 단계에서 정한다. */
	/**
	 * @param baseQtyPerPack 이 줄이 사전에 쓸 포장 환산: 이 줄의 요청 값, 없으면 같은 요청의 같은 상품명 줄이 준 값(그런 줄들의
	 *                       값은 모두 같아야 한다). 둘 다 없으면 null
	 */
	private record Plan(PurchaseDocLine line, LineDecisionRequest decision, Ingredient ingredient, String cycleName,
			String productName, BigDecimal baseQtyPerPack) {
	}

	private List<Plan> validate(long storeId, Map<Long, LineDecisionRequest> byLineId, Map<Long, PurchaseDocLine> lines,
			Map<Long, PurchaseDoc> docs) {
		Map<Long, Ingredient> ingredients = new HashMap<>();
		// 이 요청의 어느 줄이든 알려 준 포장 환산(사전 키별) · 사장님이 단위를 확인한 재료 — 같은 상품명 · 같은 재료 줄이
		// 여럿이면 한 줄에만 줘도 된다. 줄 순서와 상관없게 검사를 두 번에 나눠 돈다
		Map<String, BigDecimal> requestedBaseQty = new HashMap<>();
		Set<Long> unitConfirmedInRequest = new HashSet<>();
		List<Plan> plans = new ArrayList<>();
		for (Map.Entry<Long, LineDecisionRequest> entry : byLineId.entrySet()) {
			PurchaseDocLine line = lines.get(entry.getKey());
			LineDecisionRequest decision = entry.getValue();
			PurchaseDoc doc = docs.get(line.getDocId());
			Action action = decision.action();
			if (action == null) {
				throw new BusinessException(CommonErrorCode.INVALID_INPUT);
			}
			Ingredient ingredient = null;
			if (action == Action.INGREDIENT) {
				if (decision.ingredientId() == null || decision.ingredientId().isBlank()) {
					throw new BusinessException(ReceiptConfirmErrorCode.INGREDIENT_REQUIRED);
				}
				long ingredientId = ResourceIds.parse(decision.ingredientId());
				ingredient = ingredients.computeIfAbsent(ingredientId,
						id -> ingredientRepository.getInStore(id, storeId));
			}
			if (line.getStatus() != DocLineStatus.PENDING && !autoConfirmed(line)) {
				throw new BusinessException(ReceiptConfirmErrorCode.LINE_ALREADY_DECIDED);
			}
			requireConfirmable(doc);
			if (decision.baseQtyPerPack() != null && !ReceiptReview.validBaseQty(decision.baseQtyPerPack())) {
				throw new BusinessException(ReceiptConfirmErrorCode.UNIT_INVALID);
			}
			if (action == Action.IGNORE) {
				plans.add(new Plan(line, decision, null, null, null, null));
				continue;
			}
			String cycleName = null;
			if (action == Action.CYCLE) {
				cycleName = cycleName(line);
				ingredient = ingredientRepository.findByStoreIdAndName(storeId, cycleName).orElse(null);
			}
			String productName = ReceiptReview.dictionaryTarget(line, doc.getSupplierId())
					? ReceiptReview.productName(line) : null;
			if (decision.baseQtyPerPack() != null) {
				if (productName != null) {
					BigDecimal earlier = requestedBaseQty.putIfAbsent(productKey(doc.getSupplierId(), productName),
							decision.baseQtyPerPack());
					// 같은 상품명 줄들이 서로 다른 포장 환산을 주면 어느 쪽이 맞는지 모른다 — 아무것도 저장하지 않고 거절
					if (earlier != null && earlier.compareTo(decision.baseQtyPerPack()) != 0) {
						throw new BusinessException(ReceiptConfirmErrorCode.PACK_CONFLICT);
					}
				}
				if (ingredient != null) {
					unitConfirmedInRequest.add(ingredient.getId());
				}
			}
			plans.add(new Plan(line, decision, ingredient, cycleName, productName, decision.baseQtyPerPack()));
		}

		// 단위 미확정 검사 — 요청 전체를 본 뒤
		List<Plan> checked = new ArrayList<>();
		for (Plan plan : plans) {
			if (plan.decision().action() == Action.IGNORE || plan.baseQtyPerPack() != null) {
				checked.add(plan);
				continue;
			}
			PurchaseDoc doc = docs.get(plan.line().getDocId());
			BigDecimal groupBaseQty = plan.productName() == null ? null
					: requestedBaseQty.get(productKey(doc.getSupplierId(), plan.productName()));
			BigDecimal knownBaseQty = groupBaseQty;
			if (plan.productName() != null && knownBaseQty == null) {
				SupplierProduct existing = findProduct(storeId, doc.getSupplierId(), plan.productName());
				knownBaseQty = existing == null ? null : existing.getBaseQtyPerPack();
			}
			Ingredient ingredient = plan.ingredient();
			// 새로 만들 주기형 재료는 이 확정이 첫 입고라 LOCKED 로 생긴다
			UnitStatus unitStatus = ingredient == null || unitConfirmedInRequest.contains(ingredient.getId())
					? UnitStatus.LOCKED : ingredient.getUnitStatus();
			if (ReceiptReview.unitUnset(plan.productName() != null, knownBaseQty, unitStatus)) {
				throw new BusinessException(ReceiptConfirmErrorCode.UNIT_REQUIRED);
			}
			checked.add(new Plan(plan.line(), plan.decision(), ingredient, plan.cycleName(), plan.productName(),
					groupBaseQty));
		}
		return checked;
	}

	/** 확정할 수 있는 문서: 읽기가 끝났고(PARSED) 날짜를 읽어 PAST/RECEIVE 가 정해졌다. */
	private static void requireConfirmable(PurchaseDoc doc) {
		if (doc.getParseStatus() != ParseStatus.PARSED) {
			throw new BusinessException(ReceiptConfirmErrorCode.DOC_NOT_PARSED);
		}
		if (doc.getKind() == null || doc.getDocDate() == null) {
			throw new BusinessException(ReceiptConfirmErrorCode.DATE_UNKNOWN);
		}
	}

	// ---------------------------------------------------------------- 반영

	private ConfirmResultResponse apply(long storeId, List<Plan> plans, Map<Long, PurchaseDoc> docs) {
		Result result = new Result();
		Map<String, Ingredient> cycleIngredients = new HashMap<>();
		// 새로 생길 주기형 재료 이름을 모아 카테고리를 한 번에 정한다 (LLM 요청 1번, S15P31A308-274)
		Map<String, IngredientCategory> cycleCategories = categoryClassifier.classify(storeId, plans.stream()
				.filter(plan -> plan.decision().action() == Action.CYCLE && plan.ingredient() == null)
				.map(Plan::cycleName).toList());
		Map<String, SupplierProduct> products = new HashMap<>();
		Set<Long> touchedDocs = new TreeSet<>();
		for (Plan plan : plans) {
			PurchaseDocLine line = plan.line();
			PurchaseDoc doc = docs.get(line.getDocId());
			touchedDocs.add(doc.getId());
			Action action = plan.decision().action();
			if (action == Action.IGNORE) {
				line.ignore(withDecision(line, action));
				result.ignoredLineIds.add(line.getId());
				continue;
			}
			Ingredient ingredient = action == Action.CYCLE
					? cycleIngredients.computeIfAbsent(plan.cycleName(), name -> cycleIngredient(storeId, line, name,
							plan.ingredient(), cycleCategories.get(name.strip()), result))
					: plan.ingredient();

			SupplierProduct product = null;
			if (plan.productName() != null) {
				String key = productKey(doc.getSupplierId(), plan.productName());
				SupplierProduct known = products.get(key);
				product = known != null ? known
						: findProduct(storeId, doc.getSupplierId(), plan.productName());
				product = saveDictionary(storeId, doc, line, plan.productName(), product, ingredient,
						plan.baseQtyPerPack(), result);
				products.put(key, product);
			}

			line.confirm(ingredient.getId(), product == null ? null : product.getId(), withDecision(line, action));
			if (!ingredient.unitLocked()) {
				ingredient.lockUnit();
			}
			if (recordPrice(storeId, doc, line, product)) {
				result.priceHistoryCount++;
			}
			result.confirmedLineIds.add(line.getId());
		}
		for (Long docId : touchedDocs) {
			PurchaseDoc doc = docs.get(docId);
			if (lineRepository.countByStoreIdAndDocIdAndStatus(storeId, docId, DocLineStatus.PENDING) == 0) {
				confirmDocument(storeId, doc, result);
			}
		}
		return result.toConfirmResult();
	}

	/** 같은 이름 재료가 있으면 그것, 없으면 새 주기형 재료 (V1 ingredient.sourcing_type · price_sensitive 주석 기본값). */
	private Ingredient cycleIngredient(long storeId, PurchaseDocLine line, String name, Ingredient existing,
			IngredientCategory category, Result result) {
		if (existing != null) {
			return existing;
		}
		IngredientUnit unit = ReceiptReview.packUnit(readRaw(line));
		// 카테고리는 apply 가 새 이름 전부를 한 번에 분류해 넘긴다 (S15P31A308-274)
		long categoryId = category.getId();
		List<Long> inserted = jdbc.queryForList(INSERT_CYCLE_INGREDIENT_SQL, new MapSqlParameterSource()
				.addValue("storeId", storeId)
				.addValue("name", name)
				.addValue("categoryId", categoryId)
				.addValue("categorySource", CategorySource.AUTO.name())
				.addValue("unit", (unit == null ? IngredientUnit.EA : unit).name())
				.addValue("kind", IngredientKind.CYCLE.name())
				.addValue("unitStatus", UnitStatus.LOCKED.name())
				.addValue("storageLocation", StorageLocation.defaultFor(IngredientKind.CYCLE, null).name())
				.addValue("sourcingType", SourcingType.GENERIC.name()), Long.class);
		if (inserted.isEmpty()) {
			// 그 사이 다른 경로(재료 API 등)가 같은 이름을 만들었다 — 그것을 쓴다
			return ingredientRepository.findByStoreIdAndName(storeId, name)
					.orElseThrow(() -> new BusinessException(CommonErrorCode.CONFLICT));
		}
		result.createdIngredientIds.add(inserted.get(0));
		categoryBackup.exportAfterCommit();
		return ingredientRepository.getInStore(inserted.get(0), storeId);
	}

	/**
	 * (거래처, 상품명) 사전 상품을 이번 재료로 저장한다. 사장님이 고친 매칭이 이긴다.
	 * 포장 환산 = 요청 값 &gt; 기존 값 &gt; LLM 제안(단위가 재료 기준 단위와 같을 때). 기존 상품 단가는 RECEIVE 문서일 때만 바꾼다.
	 */
	private SupplierProduct saveDictionary(long storeId, PurchaseDoc doc, PurchaseDocLine line, String productName,
			SupplierProduct product, Ingredient ingredient, BigDecimal requestedBaseQty, Result result) {
		BigDecimal price = wonPrice(line);
		if (product == null) {
			List<Long> inserted = jdbc.queryForList(INSERT_PRODUCT_SQL, new MapSqlParameterSource()
					.addValue("storeId", storeId)
					.addValue("supplierId", doc.getSupplierId())
					.addValue("ingredientId", ingredient.getId())
					.addValue("productName", productName)
					.addValue("baseQty", requestedBaseQty != null ? requestedBaseQty
							: ReceiptReview.suggestedBaseQty(readRaw(line), ingredient.getUnit()))
					.addValue("price", price), Long.class);
			if (!inserted.isEmpty()) {
				result.createdProductIds.add(inserted.get(0));
				return productRepository.getInStore(inserted.get(0), storeId);
			}
			// 그 사이 다른 경로(거래처 상품 API 등)가 같은 (거래처, 상품명)을 만들었다 — 다시 찾아 그것을 고친다
			product = findProduct(storeId, doc.getSupplierId(), productName);
			if (product == null) {
				throw new BusinessException(CommonErrorCode.CONFLICT);
			}
		}
		if (product.getIngredientId() == null || product.getIngredientId() != ingredient.getId().longValue()) {
			product.relinkIngredient(ingredient.getId());
		}
		BigDecimal filledFrom = product.getBaseQtyPerPack();
		if (requestedBaseQty != null) {
			if (product.getBaseQtyPerPack() == null || product.getBaseQtyPerPack().compareTo(requestedBaseQty) != 0) {
				product.changeBaseQtyPerPack(requestedBaseQty);
			}
		} else if (product.getBaseQtyPerPack() == null) {
			product.changeBaseQtyPerPack(ReceiptReview.suggestedBaseQty(readRaw(line), ingredient.getUnit()));
		}
		if (filledFrom == null && product.getBaseQtyPerPack() != null) {
			// 비어 있던 포장 환산을 채웠다 — 그동안의 발주 줄(qty = 기준 단위 양)을 포장 개수로 (S15P31A308-301)
			packSizeFillRescaler.filled(storeId, product.getId(), product.getBaseQtyPerPack());
		}
		// PAST 는 옛 단가라 지금 단가를 덮지 않는다
		if (doc.getKind() == DocKind.RECEIVE && price != null && product.priceDiffers(price)) {
			product.changePrice(price);
		}
		return product;
	}

	/**
	 * 문서를 CONFIRMED 로 두고, 그 문서의 자동 확정 줄(사장님이 다시 정하지 않은 것) 몫의 단위 LOCKED · 가격 이력을 기록한다.
	 */
	private void confirmDocument(long storeId, PurchaseDoc doc, Result result) {
		if (doc.getParseStatus() != ParseStatus.PARSED) {
			return;
		}
		doc.confirm();
		result.confirmedDocIds.add(doc.getId());
		List<PurchaseDocLine> docLines = lineRepository.findAllByStoreIdAndDocIdOrderByLineNoAsc(storeId, doc.getId());
		for (PurchaseDocLine line : docLines) {
			if (line.getStatus() != DocLineStatus.CONFIRMED || !autoConfirmed(line) || line.getIngredientId() == null) {
				continue;
			}
			Ingredient ingredient = ingredientRepository.getInStore(line.getIngredientId(), storeId);
			if (!ingredient.unitLocked()) {
				ingredient.lockUnit();
			}
			SupplierProduct product = line.getSupplierProductId() == null ? null
					: productRepository.getInStore(line.getSupplierProductId(), storeId);
			if (recordPrice(storeId, doc, line, product)) {
				result.priceHistoryCount++;
			}
		}
		linkPurchaseOrder(storeId, doc, docLines);
	}

	/**
	 * RECEIVE 문서가 확정될 때 같은 입고 예정 줄을 채운다 (입고 확인 API 제안 2절 "영수증으로 채우는 경로",
	 * 설계 4.3 "입고 예정 — RECEIVE: 해당 줄을 입고 완료", DECISIONS #41).
	 *
	 * <ol>
	 *   <li>문서의 재고에 들어갈 줄(CONFIRMED · 재료 연결) <b>전부가</b> 한 발주서의 서로 다른 입고 예정 줄(같은 사전 상품 · 같은 재료)에
	 *       맞으면 그 줄들을 입고하고 문서를 발주서에 잇는다({@code purchase_doc.purchase_order_id}). 발주서가 여럿 맞으면 먼저 만든 것</li>
	 *   <li>아니면 <b>줄 단위로</b> 잇는다 (S15P31A308-301) — 줄마다 맞는 입고 예정 줄(발주서 무관, 후보 순서)을 입고하고
	 *       {@code purchase_doc_line.purchase_order_item_id} 에 적는다. 추정 재고 ② 는 이어진 줄을 빼므로 맞는 줄은 발주 줄(①)로,
	 *       안 맞는 줄은 영수증(②)으로 한 번씩만 들어간다. 예전에는 일부만 맞으면 잇지 않아 맞는 줄이 ② 로 들어가고 입고 예정은
	 *       남아, 자동 입고 · 탭 입고 확인이 같은 양을 한 번 더 더했다</li>
	 * </ol>
	 * 두 경우 모두 이은 영수증 줄에 발주 줄 id 를 적는다. 줄마다 {@link ReceivingService#recordReceipt} — 가격 이력은 이 문서가 이미
	 * DOC 행으로 썼으므로 RECEIVE 행은 쓰지 않는다. 입고 예정 줄은 {@code FOR UPDATE} 로 잠가 탭 입고 확인과 겹쳐도 한쪽만 입고된다.
	 * 사전 상품이 없거나 수량이 없는 줄은 잇지 않는다(② 로 들어간다).
	 */
	private void linkPurchaseOrder(long storeId, PurchaseDoc doc, List<PurchaseDocLine> docLines) {
		if (doc.getKind() != DocKind.RECEIVE || doc.getSupplierId() == null || doc.getDocDate() == null) {
			return;
		}
		List<PurchaseDocLine> counted = docLines.stream()
				.filter(l -> l.getStatus() == DocLineStatus.CONFIRMED && l.getIngredientId() != null)
				.toList();
		List<PurchaseDocLine> linkable = counted.stream()
				.filter(l -> l.getSupplierProductId() != null && l.getQty() != null && l.getQty().signum() > 0)
				.toList();
		if (linkable.isEmpty()) {
			return;
		}
		List<IncomingItem> candidates = jdbc.query(INCOMING_FOR_DOC_SQL, new MapSqlParameterSource()
				.addValue("storeId", storeId)
				.addValue("supplierId", doc.getSupplierId())
				.addValue("docDate", doc.getDocDate())
				.addValue("productIds", linkable.stream().map(PurchaseDocLine::getSupplierProductId)
						.collect(Collectors.toSet())),
				(rs, i) -> new IncomingItem(rs.getLong("id"), rs.getLong("po_id"), rs.getLong("supplier_product_id"),
						rs.getLong("ingredient_id"), rs.getBoolean("auto_received")));
		if (linkable.size() == counted.size()) {
			Map<Long, List<IncomingItem>> byOrder = new java.util.LinkedHashMap<>();
			for (IncomingItem item : candidates) {
				byOrder.computeIfAbsent(item.orderId(), id -> new ArrayList<>()).add(item);
			}
			for (Map.Entry<Long, List<IncomingItem>> order : byOrder.entrySet()) {
				Map<PurchaseDocLine, IncomingItem> assigned = assign(counted, order.getValue(), true);
				if (assigned == null) {
					continue;
				}
				receive(storeId, doc, assigned);
				jdbc.update(LINK_DOC_SQL, new MapSqlParameterSource()
						.addValue("storeId", storeId)
						.addValue("docId", doc.getId())
						.addValue("orderId", order.getKey()));
				return;
			}
		}
		receive(storeId, doc, assign(linkable, candidates, false));
	}

	/** 맞춘 줄마다 입고 예정 줄을 입고(자동 입고 줄이면 영수증 값으로 덮기)하고 영수증 줄에 발주 줄 id 를 적는다. */
	private void receive(long storeId, PurchaseDoc doc, Map<PurchaseDocLine, IncomingItem> assigned) {
		for (Map.Entry<PurchaseDocLine, IncomingItem> pair : assigned.entrySet()) {
			IncomingItem target = pair.getValue();
			if (target.autoReceived()) {
				// 자동 입고 줄 — 영수증 값으로 덮는다. 위 쿼리가 FOR UPDATE 로 줄을 잠그고(기다렸다면 WHERE 를 다시 평가해
				// 그 사이 다른 영수증이 덮은 줄은 후보에서 빠진다) 같은 트랜잭션이 이어 쓰므로 false 는 나올 수 없는 방어 분기
				if (!receivingService.overwriteAutoReceipt(storeId, target.itemId(), pair.getKey().getQty(),
						receiptPrice(pair.getKey()), doc.getDocDate())) {
					throw new IllegalStateException("자동 입고 줄을 덮지 못했다 — itemId=" + target.itemId());
				}
			} else {
				receivingService.recordReceipt(storeId, target.itemId(), pair.getKey().getQty(),
						receiptPrice(pair.getKey()), false, doc.getDocDate());
			}
			jdbc.update(LINK_LINE_SQL, new MapSqlParameterSource()
					.addValue("storeId", storeId)
					.addValue("lineId", pair.getKey().getId())
					.addValue("itemId", target.itemId()));
		}
	}

	/**
	 * 문서 줄마다 같은 사전 상품 · 같은 재료의 아직 안 쓴 입고 예정 줄 하나(후보 순서대로).
	 *
	 * @param all true 면 하나라도 못 찾을 때 null, false 면 찾은 줄만
	 */
	private static Map<PurchaseDocLine, IncomingItem> assign(List<PurchaseDocLine> lines, List<IncomingItem> items,
			boolean all) {
		Map<PurchaseDocLine, IncomingItem> assigned = new java.util.LinkedHashMap<>();
		Set<Long> used = new HashSet<>();
		for (PurchaseDocLine line : lines) {
			IncomingItem match = items.stream()
					.filter(item -> !used.contains(item.itemId())
							&& item.productId() == line.getSupplierProductId()
							&& item.ingredientId() == line.getIngredientId())
					.findFirst().orElse(null);
			if (match == null) {
				if (all) {
					return null;
				}
				continue;
			}
			used.add(match.itemId());
			assigned.put(line, match);
		}
		return assigned;
	}

	/** 입고 확인에 넘길 포장 1개 단가: 가격 이력과 같은 원 정수. 입고 확인 단가 칸(정수 10자리)을 넘으면 null(거래처 단가로). */
	private static BigDecimal receiptPrice(PurchaseDocLine line) {
		BigDecimal won = wonPrice(line);
		return won == null || won.precision() - won.scale() > RECEIPT_PRICE_INTEGER_DIGITS ? null : won;
	}

	private record IncomingItem(long itemId, long orderId, long productId, long ingredientId, boolean autoReceived) {
	}

	/** 사전 상품이 있고 단가가 있으면 price_history(DOC) 1행. PAST · RECEIVE 모두. @return 기록했으면 true */
	private boolean recordPrice(long storeId, PurchaseDoc doc, PurchaseDocLine line, SupplierProduct product) {
		BigDecimal price = wonPrice(line);
		if (product == null || price == null) {
			return false;
		}
		jdbc.update(INSERT_PRICE_SQL, new MapSqlParameterSource()
				.addValue("storeId", storeId)
				.addValue("productId", product.getId())
				.addValue("price", price)
				.addValue("unit", product.getPackUnit() != null ? product.getPackUnit() : UNKNOWN_PACK_UNIT)
				.addValue("docDate", doc.getDocDate())
				.addValue("lineId", line.getId()));
		return true;
	}

	// ---------------------------------------------------------------- 도움

	private void lockStore(long storeId) {
		List<Long> rows = jdbc.queryForList(LOCK_STORE_SQL, new MapSqlParameterSource("storeId", storeId), Long.class);
		if (rows.isEmpty()) {
			throw new BusinessException(CommonErrorCode.NOT_FOUND);
		}
	}

	private SupplierProduct findProduct(long storeId, long supplierId, String productName) {
		List<SupplierProduct> hits = productRepository.search(storeId, supplierId, null, productName);
		return hits.isEmpty() ? null : hits.get(0);
	}

	private static String productKey(long supplierId, String productName) {
		return supplierId + "\u0000" + productName;
	}

	/** 주기형 재료 이름: 상품명 앞뒤 공백 제거, 100자(코드 포인트)에서 자른다 — 이모지 한가운데를 자르지 않게. */
	private static String cycleName(PurchaseDocLine line) {
		String name = line.getRawName() == null ? "" : line.getRawName().strip();
		if (name.isEmpty()) {
			throw new BusinessException(CommonErrorCode.INVALID_INPUT);
		}
		if (name.codePointCount(0, name.length()) > INGREDIENT_NAME_MAX) {
			name = name.substring(0, name.offsetByCodePoints(0, INGREDIENT_NAME_MAX)).strip();
		}
		return name;
	}

	/** 단가(원 정수, DECISIONS #55). 없거나 0 이하(할인 · 증정 줄)면 null — 단가로 기록하지 않는다. */
	private static BigDecimal wonPrice(PurchaseDocLine line) {
		BigDecimal price = line.getUnitPrice();
		if (price == null || price.signum() <= 0) {
			return null;
		}
		BigDecimal won = price.setScale(0, RoundingMode.HALF_UP);
		return won.signum() > 0 ? won : null;
	}

	private boolean autoConfirmed(PurchaseDocLine line) {
		JsonNode flag = readRaw(line).get(AUTO_CONFIRMED);
		return line.getStatus() == DocLineStatus.CONFIRMED && flag != null && flag.isBoolean() && flag.booleanValue();
	}

	private ObjectNode readRaw(PurchaseDocLine line) {
		if (line.getRaw() == null) {
			return jsonMapper.createObjectNode();
		}
		JsonNode node = jsonMapper.readTree(line.getRaw());
		return node instanceof ObjectNode object ? object : jsonMapper.createObjectNode();
	}

	/** 원문 줄에 사장님 결정을 덧붙인다. 자동 확정 표시는 지운다(사장님이 다시 정했다). */
	private String withDecision(PurchaseDocLine line, Action action) {
		ObjectNode raw = readRaw(line);
		raw.remove(AUTO_CONFIRMED);
		raw.putObject("decision").put("action", action.name()).put("by", "OWNER");
		return jsonMapper.writeValueAsString(raw);
	}

	/** 결과 모으기. */
	private static final class Result {
		private final List<Long> confirmedLineIds = new ArrayList<>();
		private final List<Long> ignoredLineIds = new ArrayList<>();
		private final List<Long> confirmedDocIds = new ArrayList<>();
		private final List<Long> createdIngredientIds = new ArrayList<>();
		private final List<Long> createdProductIds = new ArrayList<>();
		private int priceHistoryCount;

		ConfirmResultResponse toConfirmResult() {
			return new ConfirmResultResponse(ids(confirmedLineIds), ids(ignoredLineIds), ids(confirmedDocIds),
					ids(createdIngredientIds), ids(createdProductIds), priceHistoryCount);
		}

		private static List<String> ids(List<Long> ids) {
			return ids.stream().sorted().map(String::valueOf).toList();
		}
	}
}
