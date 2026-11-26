package com.zigzaego.api.purchasedoc.service;

import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.exception.CommonErrorCode;
import com.zigzaego.api.global.store.ResourceIds;
import com.zigzaego.api.ingredient.entity.Ingredient;
import com.zigzaego.api.ingredient.entity.IngredientUnit;
import com.zigzaego.api.ingredient.repository.IngredientRepository;
import com.zigzaego.api.purchasedoc.dto.ConfirmResultResponse;
import com.zigzaego.api.purchasedoc.dto.GroupConfirmResult;
import com.zigzaego.api.purchasedoc.dto.LineDecisionRequest;
import com.zigzaego.api.purchasedoc.dto.LineDecisionRequest.Action;
import com.zigzaego.api.purchasedoc.dto.ReadyDocResponse;
import com.zigzaego.api.purchasedoc.dto.ReviewBoard;
import com.zigzaego.api.purchasedoc.dto.ReviewMatchResponse;
import com.zigzaego.api.purchasedoc.dto.ReviewOptionResponse;
import com.zigzaego.api.purchasedoc.entity.DocKind;
import com.zigzaego.api.purchasedoc.entity.PurchaseDoc;
import com.zigzaego.api.purchasedoc.entity.PurchaseDocLine;
import com.zigzaego.api.purchasedoc.exception.ReceiptConfirmErrorCode;
import com.zigzaego.api.purchasedoc.repository.PurchaseDocLineRepository;
import com.zigzaego.api.purchasedoc.repository.PurchaseDocRepository;
import com.zigzaego.api.purchasedoc.service.ReceiptReview.Reason;
import com.zigzaego.api.supplier.entity.SupplierProduct;
import com.zigzaego.api.supplier.repository.SupplierProductRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 영수증 확인 목록 · 묶음 확정 (S15P31A308-113, 설계 4.4 · 6절, DECISIONS #41).
 *
 * <p>확인 전(PENDING) 줄을 상품명(앞뒤 공백 제거) 하나당 묶음 하나로 보여 주고, 묶음 하나를 확정하면 그 상품명 줄 전부에 S15P31A308-112
 * {@link ReceiptConfirmService#confirm} 규칙(사전 저장 · 주기형 생성 · 가격 이력 · 남은 PENDING 0 이면 문서 CONFIRMED)을
 * 그대로 적용한다. 자동 확정 규칙은 바꾸지 않는다 — 줄이 모두 자동 확정된 문서는 {@code readyDocs} 로 따로 보여 준다.
 */
@Service
@RequiredArgsConstructor
public class ReceiptReviewService {

	static final String CHOICE_CYCLE = "cycle";
	static final String CHOICE_IGNORE = "ignore";
	private static final int QTY_SCALE = 3;
	/** 화면에 싣는 이유 순서. */
	private static final List<Reason> REASON_ORDER = List.of(Reason.UNMAPPED, Reason.LOW_CONFIDENCE,
			Reason.UNIT_UNSET);

	/** S15P31A308-112 와 같은 가게 잠금 — 같은 묶음 동시 확정을 한 줄로 세운다. 같은 트랜잭션의 S15P31A308-112 잠금은 기다리지 않는다. */
	private static final String LOCK_STORE_SQL = "SELECT id FROM store WHERE id = :storeId FOR NO KEY UPDATE";

	/** 줄이 모두 자동 확정된 문서: PARSED · 날짜를 읽음 · 줄 1개 이상 · PENDING 줄 0. */
	private static final String READY_DOCS_SQL = """
			SELECT d.id, d.kind, d.doc_date, COUNT(l.id) AS line_count
			FROM purchase_doc d
			JOIN purchase_doc_line l ON l.doc_id = d.id AND l.store_id = :storeId
			WHERE d.store_id = :storeId AND d.parse_status = 'PARSED' AND d.kind IS NOT NULL AND d.doc_date IS NOT NULL
			GROUP BY d.id, d.kind, d.doc_date
			HAVING COUNT(*) FILTER (WHERE l.status = 'PENDING') = 0
			ORDER BY d.id
			""";

	/**
	 * 이번에 CONFIRMED 가 된 문서가 추정 재고에 더한 고른 재료 양 — InventoryEstimateService 의 입고 SQL(#41)과 같은 식:
	 * RECEIVE · 발주서 미연결 · 날짜 있는 문서의 CONFIRMED · 발주 줄 미연결 줄, 수량 × 포장 환산(모르면 1), 그 재료의 마지막 실사일(가게 시간대)
	 * 이후 문서만 — kind 는 분석 때 굳으므로 그 뒤 새 실사가 들어와도 재고 계산기와 같은 값이 나오게 실사 조건을 같이 건다.
	 * 주기형 재료는 재고 계산기가 이 입고 식을 쓰지 않으므로(소진 속도로만 계산) 기존 주기형 재료를 id 로 골라도 0 이다.
	 */
	private static final String GAIN_SQL = """
			WITH last_count AS (
			    SELECT DISTINCT ON (ingredient_id) ingredient_id, counted_at, counted_after_close
			    FROM stock_count
			    WHERE store_id = :storeId AND ingredient_id = :ingredientId
			    ORDER BY ingredient_id, counted_at DESC, id DESC
			)
			SELECT COALESCE(SUM(l.qty * COALESCE(sp.base_qty_per_pack, 1)), 0)
			FROM purchase_doc_line l
			JOIN purchase_doc d ON d.id = l.doc_id AND d.store_id = :storeId
			JOIN store s ON s.id = :storeId
			JOIN ingredient i ON i.id = l.ingredient_id AND i.store_id = :storeId AND i.kind <> 'CYCLE'
			LEFT JOIN supplier_product sp ON sp.id = l.supplier_product_id AND sp.store_id = :storeId
			LEFT JOIN last_count lc ON lc.ingredient_id = l.ingredient_id
			WHERE l.store_id = :storeId AND l.doc_id IN (:docIds) AND l.ingredient_id = :ingredientId
			  AND l.status = 'CONFIRMED' AND l.qty IS NOT NULL
			  AND d.parse_status = 'CONFIRMED' AND d.kind = 'RECEIVE' AND d.purchase_order_id IS NULL
			  AND l.purchase_order_item_id IS NULL
			  AND d.doc_date IS NOT NULL
			  AND (lc.counted_at IS NULL
			       OR d.doc_date >= (lc.counted_at AT TIME ZONE s.timezone)::date + lc.counted_after_close::int)
			""";

	private final PurchaseDocRepository docRepository;
	private final PurchaseDocLineRepository lineRepository;
	private final IngredientRepository ingredientRepository;
	private final SupplierProductRepository productRepository;
	private final ReceiptConfirmService confirmService;
	private final NamedParameterJdbcTemplate jdbc;
	private final JsonMapper jsonMapper;

	@PersistenceContext
	private EntityManager entityManager;

	// ---------------------------------------------------------------- 확인 목록

	/** 이 가게의 확인 목록. 대상 줄이 없으면 {@code matches} · {@code readyDocs} 는 빈 목록. */
	@Transactional(readOnly = true)
	public ReviewBoard board(long storeId) {
		List<Ingredient> ingredients = ingredientRepository.findAllByStoreIdOrderByIdAsc(storeId);
		Map<Long, Ingredient> ingredientsById = ingredients.stream()
				.collect(Collectors.toMap(Ingredient::getId, Function.identity()));
		List<PurchaseDocLine> targets = lineRepository.findReviewTargets(storeId);
		Map<Long, PurchaseDoc> docs = docsOf(storeId, targets);

		Map<String, List<PurchaseDocLine>> groups = new LinkedHashMap<>();
		for (PurchaseDocLine line : targets) {
			groups.computeIfAbsent(ReceiptReview.groupKey(line.getRawName()), k -> new ArrayList<>()).add(line);
		}
		List<ReviewMatchResponse> matches = new ArrayList<>();
		Map<String, Long> newestLine = new HashMap<>();
		for (Map.Entry<String, List<PurchaseDocLine>> group : groups.entrySet()) {
			List<PurchaseDocLine> lines = group.getValue();
			matches.add(match(storeId, group.getKey(), lines, docs, ingredientsById));
			newestLine.put(group.getKey(), lines.get(lines.size() - 1).getId());
		}
		// 최근에 읽힌 상품이 위(FE 목 addToGroup 이 새 묶음을 앞에 넣음), 같으면 이름 오름차순
		matches.sort(Comparator.comparing((ReviewMatchResponse m) -> newestLine.get(m.id()), Comparator.reverseOrder())
				.thenComparing(ReviewMatchResponse::id));

		List<ReviewOptionResponse> options = ingredients.stream()
				.map(i -> new ReviewOptionResponse(String.valueOf(i.getId()), i.getName())).toList();
		return new ReviewBoard(matches, options, readyDocs(storeId));
	}

	private ReviewMatchResponse match(long storeId, String key, List<PurchaseDocLine> lines, Map<Long, PurchaseDoc> docs,
			Map<Long, Ingredient> ingredientsById) {
		// 문서 거래처의 같은 상품명 사전 상품 — 사전 키는 묶음 키와 같은 규칙(150자를 넘으면 사전 없음)
		String productName = ReceiptReview.productName(lines.get(0));
		List<SupplierProduct> products = productName == null ? List.of()
				: productRepository.findAllByStoreIdAndProductNameOrderByIdAsc(storeId, productName);

		Ingredient guess = guess(lines, ingredientsById);
		Set<Long> docIds = new HashSet<>();
		BigDecimal qty = BigDecimal.ZERO;
		boolean past = true;
		Set<Reason> reasons = EnumSet.noneOf(Reason.class);
		Set<Long> suppliers = new HashSet<>();
		for (PurchaseDocLine line : lines) {
			PurchaseDoc doc = docs.get(line.getDocId());
			docIds.add(doc.getId());
			if (line.getQty() != null) {
				qty = qty.add(line.getQty());
			}
			past &= doc.getKind() == DocKind.PAST;
			if (doc.getSupplierId() != null) {
				suppliers.add(doc.getSupplierId());
			}
			reasons.addAll(ReceiptReview.reasons(line, doc.getSupplierId(), product(products, doc.getSupplierId()),
					guess));
		}

		BigDecimal basePerPack = products.stream()
				.filter(p -> suppliers.contains(p.getSupplierId()) && p.getBaseQtyPerPack() != null)
				.map(SupplierProduct::getBaseQtyPerPack).findFirst().orElse(null);
		IngredientUnit baseUnit = guess == null ? null : guess.getUnit();
		if (basePerPack == null && guess != null) {
			for (PurchaseDocLine line : lines) {
				basePerPack = ReceiptReview.suggestedBaseQty(readRaw(line), guess.getUnit());
				if (basePerPack != null) {
					break;
				}
			}
		}
		if (guess == null) {
			for (PurchaseDocLine line : lines) {
				baseUnit = ReceiptReview.packUnit(readRaw(line));
				if (baseUnit != null) {
					break;
				}
			}
		}

		List<String> orderedReasons = REASON_ORDER.stream().filter(reasons::contains).map(Reason::name).toList();
		return new ReviewMatchResponse(key, key, docIds.size(), lines.stream().map(l -> String.valueOf(l.getId())).toList(),
				guess == null ? "" : String.valueOf(guess.getId()), qty.setScale(QTY_SCALE, RoundingMode.HALF_UP),
				reasons.contains(Reason.UNIT_UNSET), basePerPack, baseUnit == null ? null : baseUnit.name(), past, true,
				orderedReasons);
	}

	/**
	 * 묶음의 기본 재료: 사전 매칭 줄(supplier_product_id 있음)의 재료 — 여럿이면 id 작은 줄 것. 없으면 LLM 제안 재료 중 가장 많이
	 * 나온 것(같으면 재료 id 작은 것). 하나도 없으면 null.
	 */
	private static Ingredient guess(List<PurchaseDocLine> lines, Map<Long, Ingredient> ingredientsById) {
		for (PurchaseDocLine line : lines) {
			if (line.getSupplierProductId() != null && line.getIngredientId() != null
					&& ingredientsById.containsKey(line.getIngredientId())) {
				return ingredientsById.get(line.getIngredientId());
			}
		}
		Map<Long, Integer> votes = new TreeMap<>();
		for (PurchaseDocLine line : lines) {
			if (line.getSupplierProductId() == null && line.getIngredientId() != null
					&& ingredientsById.containsKey(line.getIngredientId())) {
				votes.merge(line.getIngredientId(), 1, Integer::sum);
			}
		}
		Long best = null;
		int bestVotes = 0;
		for (Map.Entry<Long, Integer> vote : votes.entrySet()) { // 재료 id 오름차순 — 같은 표면 먼저 본 것
			if (vote.getValue() > bestVotes) {
				best = vote.getKey();
				bestVotes = vote.getValue();
			}
		}
		return best == null ? null : ingredientsById.get(best);
	}

	/** 문서 거래처의 사전 상품(id 작은 것). 거래처를 모르면 null. */
	private static SupplierProduct product(List<SupplierProduct> products, Long supplierId) {
		if (supplierId == null) {
			return null;
		}
		return products.stream().filter(p -> supplierId.equals(p.getSupplierId())).findFirst().orElse(null);
	}

	private List<ReadyDocResponse> readyDocs(long storeId) {
		return jdbc.query(READY_DOCS_SQL, new MapSqlParameterSource("storeId", storeId),
				(rs, i) -> new ReadyDocResponse(String.valueOf(rs.getLong("id")), rs.getString("kind"),
						rs.getDate("doc_date").toLocalDate().toString(), rs.getInt("line_count")));
	}

	private Map<Long, PurchaseDoc> docsOf(long storeId, List<PurchaseDocLine> lines) {
		if (lines.isEmpty()) {
			return Map.of();
		}
		return docRepository.findAllByStoreIdAndIdInOrderByIdAsc(storeId,
				lines.stream().map(PurchaseDocLine::getDocId).collect(Collectors.toSet()))
				.stream().collect(Collectors.toMap(PurchaseDoc::getId, Function.identity()));
	}

	// ---------------------------------------------------------------- 묶음 확정

	/**
	 * 묶음 하나에 사장님 선택을 적용해 그 상품명의 확인 전 줄 전부를 한 번에 확정한다. 한 트랜잭션 — 오류면 아무것도 저장하지 않는다.
	 * 날짜를 못 읽은 문서의 줄은 건너뛰고 목록에 남긴다.
	 *
	 * @param matchId     확인 목록 묶음 {@code id}
	 * @param choice      재료 id 문자열 · {@code "cycle"}(새 주기형) · {@code "ignore"}(무시). cycle · ignore 는 대소문자 무시
	 * @param basePerPack 포장 1개 = 기준 단위 몇. 선택 — 묶음 모든 줄에 같은 값으로 넘긴다
	 * @throws BusinessException 묶음이 없으면 404 RECEIPT_MATCH_NOT_FOUND, choice 가 비면 400 RECEIPT_INGREDIENT_REQUIRED,
	 *         줄이 전부 날짜 못 읽은 문서면 409 RECEIPT_DATE_UNKNOWN, 그 밖은 S15P31A308-112 {@code confirm} 오류 그대로
	 */
	@Transactional
	public GroupConfirmResult confirmGroup(long storeId, String matchId, String choice, BigDecimal basePerPack) {
		if (choice == null || choice.isBlank()) {
			throw new BusinessException(ReceiptConfirmErrorCode.INGREDIENT_REQUIRED);
		}
		Action action = action(choice);
		if (matchId == null) {
			throw new BusinessException(ReceiptConfirmErrorCode.MATCH_NOT_FOUND);
		}
		lockStore(storeId);

		List<PurchaseDocLine> lines = lineRepository.findReviewTargets(storeId).stream()
				.filter(l -> ReceiptReview.groupKey(l.getRawName()).equals(matchId)).toList();
		if (lines.isEmpty()) {
			throw new BusinessException(ReceiptConfirmErrorCode.MATCH_NOT_FOUND);
		}
		Map<Long, PurchaseDoc> docs = docsOf(storeId, lines);
		List<String> skipped = new ArrayList<>();
		List<LineDecisionRequest> decisions = new ArrayList<>();
		for (PurchaseDocLine line : lines) {
			PurchaseDoc doc = docs.get(line.getDocId());
			if (doc.getKind() == null || doc.getDocDate() == null) {
				skipped.add(String.valueOf(line.getId()));
				continue;
			}
			decisions.add(new LineDecisionRequest(String.valueOf(line.getId()), action,
					action == Action.INGREDIENT ? choice : null, basePerPack));
		}
		if (decisions.isEmpty()) {
			throw new BusinessException(ReceiptConfirmErrorCode.DATE_UNKNOWN);
		}

		ConfirmResultResponse confirm = confirmService.confirm(storeId, decisions);
		// S15P31A308-112 가 바꾼 엔티티를 아래 SQL 이 보게 한다
		entityManager.flush();
		BigDecimal gain = action == Action.INGREDIENT
				? gain(storeId, confirm.confirmedDocIds(), ResourceIds.parse(choice))
				: BigDecimal.ZERO.setScale(QTY_SCALE);
		return new GroupConfirmResult(confirm, skipped, gain, board(storeId));
	}

	private static Action action(String choice) {
		String lower = choice.toLowerCase(Locale.ROOT);
		if (CHOICE_CYCLE.equals(lower)) {
			return Action.CYCLE;
		}
		if (CHOICE_IGNORE.equals(lower)) {
			return Action.IGNORE;
		}
		return Action.INGREDIENT; // 재료 id — 숫자 · 가게 검사는 확정 서비스가 한다(404 COMMON_NOT_FOUND)
	}

	private BigDecimal gain(long storeId, List<String> confirmedDocIds, long ingredientId) {
		if (confirmedDocIds.isEmpty()) {
			return BigDecimal.ZERO.setScale(QTY_SCALE);
		}
		BigDecimal sum = jdbc.queryForObject(GAIN_SQL, new MapSqlParameterSource()
				.addValue("storeId", storeId)
				.addValue("docIds", confirmedDocIds.stream().map(Long::valueOf).toList())
				.addValue("ingredientId", ingredientId), BigDecimal.class);
		return (sum == null ? BigDecimal.ZERO : sum).setScale(QTY_SCALE, RoundingMode.HALF_UP);
	}

	private void lockStore(long storeId) {
		List<Long> rows = jdbc.queryForList(LOCK_STORE_SQL, new MapSqlParameterSource("storeId", storeId), Long.class);
		if (rows.isEmpty()) {
			throw new BusinessException(CommonErrorCode.NOT_FOUND);
		}
	}

	private JsonNode readRaw(PurchaseDocLine line) {
		if (line.getRaw() == null) {
			return null;
		}
		return jsonMapper.readTree(line.getRaw());
	}
}
