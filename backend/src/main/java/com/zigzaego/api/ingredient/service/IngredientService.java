package com.zigzaego.api.ingredient.service;

import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.review.ReviewReason;
import com.zigzaego.api.global.store.ResourceIds;
import com.zigzaego.api.ingredient.dto.IngredientCreateRequest;
import com.zigzaego.api.ingredient.dto.IngredientResponse;
import com.zigzaego.api.ingredient.dto.IngredientUpdateRequest;
import com.zigzaego.api.ingredient.entity.CategorySource;
import com.zigzaego.api.ingredient.entity.Ingredient;
import com.zigzaego.api.ingredient.entity.IngredientCategory;
import com.zigzaego.api.ingredient.entity.IngredientKind;
import com.zigzaego.api.ingredient.entity.SourcingType;
import com.zigzaego.api.ingredient.entity.StorageType;
import com.zigzaego.api.ingredient.entity.UnitStatus;
import com.zigzaego.api.ingredient.exception.IngredientErrorCode;
import com.zigzaego.api.ingredient.repository.IngredientRepository;
import com.zigzaego.api.recipe.repository.RecipeRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 재료 CRUD. 모든 조회 · 쓰기를 로그인한 가게로 좁힌다 — 다른 가게 재료 · 없는 id · 숫자가 아닌 id 는 모두 404 (ADR-0002).
 *
 * <ul>
 *   <li>단위: unit_status=LOCKED 면 다른 단위로 바꾸면 409. 그 전에는 사장님이 정하면 CONFIRMED (설계 3.1절)</li>
 *   <li>종류: 주기형은 영수증으로만 생긴다. 만들거나 주기형으로 옮기지 못하고, 주기형 → BOM · 1:1 은 사장님이 이 API 로만 (설계 4.1절)</li>
 *   <li>확인 필요(설계 6절): AI 추정 레시피 줄에 쓰이면 UNANSWERED, 실사 기록이 없으면 NEEDS_COUNT. 목록은 확인 필요가 맨 위</li>
 *   <li>카테고리: 만들 때 categoryId 를 보내면 그것(출처 OWNER), 없으면 이름으로 자동 분류 — 재료표 · 품목군 매핑 → LLM → 기타
 *       ({@link IngredientCategoryClassifier}, S15P31A308-274). 고치기의 categoryId 도 OWNER 로 붙는다 (275)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class IngredientService {

	private static final BigDecimal DEFAULT_SAFETY_DAYS = BigDecimal.ONE;

	private static final String COUNTED_SQL = """
			SELECT DISTINCT ingredient_id FROM stock_count
			WHERE store_id = :storeId AND ingredient_id IN (:ingredientIds)
			""";

	/** 거래처 상품(단가 이력이 달림) · 실사 · 추천 · 발주 · 매입 줄은 재료를 지우면 사라지는 기록이라 삭제를 막는다. */
	private static final String IN_USE_SQL = """
			SELECT EXISTS (SELECT 1 FROM supplier_product WHERE store_id = :storeId AND ingredient_id = :ingredientId)
			    OR EXISTS (SELECT 1 FROM stock_count WHERE store_id = :storeId AND ingredient_id = :ingredientId)
			    OR EXISTS (SELECT 1 FROM recommendation WHERE store_id = :storeId AND ingredient_id = :ingredientId)
			    OR EXISTS (SELECT 1 FROM purchase_order_item WHERE store_id = :storeId AND ingredient_id = :ingredientId)
			    OR EXISTS (SELECT 1 FROM purchase_doc_line WHERE store_id = :storeId AND ingredient_id = :ingredientId)
			""";

	private final IngredientRepository ingredientRepository;
	private final RecipeRepository recipeRepository;
	private final IngredientCategoryService categoryService;
	private final IngredientCategoryClassifier classifier;
	private final IngredientCategoryBackup categoryBackup;
	private final NamedParameterJdbcTemplate jdbc;
	private final TransactionTemplate transactions;

	@Transactional(readOnly = true)
	public List<IngredientResponse> listForStore(long storeId) {
		List<Ingredient> ingredients = ingredientRepository.findAllByStoreIdOrderByIdAsc(storeId);
		if (ingredients.isEmpty()) {
			return List.of();
		}
		ReviewState review = reviewState(storeId, ingredients.stream().map(Ingredient::getId).toList());
		Map<Long, IngredientCategory> categories = categoryService.byId(storeId);
		return ingredients.stream()
				.map(ingredient -> IngredientResponse.of(ingredient, review.reasons(ingredient.getId()),
						categories.get(ingredient.getCategoryId())))
				.sorted(Comparator.comparing(IngredientResponse::needsReview).reversed())
				.toList();
	}

	/** 현재 가게의 재료 하나. 다른 가게 재료 · 없는 id · 숫자가 아닌 id 는 모두 404 (ADR-0002). */
	@Transactional(readOnly = true)
	public IngredientResponse getForStore(long storeId, String rawId) {
		return toResponse(storeId, ingredientRepository.getInStore(ResourceIds.parse(rawId), storeId));
	}

	/** 카테고리 자동 분류(LLM 대기 최대 20초)는 트랜잭션 밖에서 하고, 저장만 짧은 트랜잭션으로 묶는다 (S15P31A308-274). */
	public IngredientResponse create(long storeId, IngredientCreateRequest request) {
		if (request.kind() == IngredientKind.CYCLE) {
			throw new BusinessException(IngredientErrorCode.CYCLE_FROM_RECEIPT_ONLY);
		}
		String name = request.name().strip();
		requireUniqueName(storeId, name);  // LLM 을 부르기 전에 걸러 비용을 아낀다. 트랜잭션 안에서 한 번 더 본다
		boolean chosen = request.categoryId() != null;
		IngredientCategory category = chosen ? categoryService.get(storeId, request.categoryId())
				: classifier.classify(storeId, List.of(name)).get(name);
		return transactions.execute(status -> save(storeId, request, name, category, chosen));
	}

	private IngredientResponse save(long storeId, IngredientCreateRequest request, String name,
			IngredientCategory category, boolean chosen) {
		requireUniqueName(storeId, name);
		Ingredient ingredient = ingredientRepository.save(Ingredient.create(
				storeId,
				name,
				request.unit(),
				request.kind(),
				UnitStatus.CONFIRMED,
				request.storageType(),
				request.safetyDays() == null ? DEFAULT_SAFETY_DAYS : request.safetyDays(),
				request.maxOrderQty(),
				request.priceSensitive() == null ? defaultPriceSensitive(request.storageType()) : request.priceSensitive(),
				request.shelfLifeDays(),
				request.sourcingType() == null ? SourcingType.GENERIC : request.sourcingType(),
				category.getId()));
		if (chosen) {
			ingredient.assignCategory(category.getId(), CategorySource.OWNER);
		}
		categoryBackup.exportAfterCommit();
		return toResponse(storeId, ingredient, category);
	}

	/**
	 * 보낸 칸만 바꾼다.
	 *
	 * @throws BusinessException LOCKED 재료의 단위를 바꾸면 409 INGREDIENT_UNIT_LOCKED,
	 *         주기형으로 옮기면 400 INGREDIENT_CYCLE_FROM_RECEIPT_ONLY,
	 *         보관 방식 없이 주기형을 BOM · 1:1 로 옮기면 400 INGREDIENT_STORAGE_TYPE_REQUIRED
	 */
	@Transactional
	public IngredientResponse update(long storeId, String rawId, IngredientUpdateRequest request) {
		Ingredient ingredient = ingredientRepository.getInStore(ResourceIds.parse(rawId), storeId);
		if (request.unit() != null) {
			if (ingredient.unitLocked() && request.unit() != ingredient.getUnit()) {
				throw new BusinessException(IngredientErrorCode.UNIT_LOCKED);
			}
			if (!ingredient.unitLocked()) {
				ingredient.confirmUnit(request.unit());
			}
		}
		if (request.kind() != null && request.kind() != ingredient.getKind()) {
			if (request.kind() == IngredientKind.CYCLE) {
				throw new BusinessException(IngredientErrorCode.CYCLE_FROM_RECEIPT_ONLY);
			}
			if (request.storageType() == null && ingredient.getStorageType() == null) {
				throw new BusinessException(IngredientErrorCode.STORAGE_TYPE_REQUIRED);
			}
			ingredient.changeKind(request.kind());
		}
		if (request.name() != null) {
			String name = request.name().strip();
			if (!name.equals(ingredient.getName())) {
				requireUniqueName(storeId, name);
				ingredient.rename(name);
			}
		}
		if (request.storageType() != null) {
			ingredient.changeStorageType(request.storageType());
		}
		if (request.storageLocation() != null) {
			ingredient.moveTo(request.storageLocation());
		}
		if (request.safetyDays() != null) {
			ingredient.changeSafetyDays(request.safetyDays());
		}
		if (request.maxOrderQty() != null) {
			ingredient.changeMaxOrderQty(request.maxOrderQty());
		}
		if (request.priceSensitive() != null) {
			ingredient.changePriceSensitive(request.priceSensitive());
		}
		if (request.shelfLifeDays() != null) {
			ingredient.changeShelfLifeDays(request.shelfLifeDays());
		}
		if (request.sourcingType() != null) {
			ingredient.changeSourcingType(request.sourcingType());
		}
		if (request.categoryId() != null) {
			// 사장님이 고른 카테고리 (S15P31A308-275): 남의 가게 · 없는 · 숫자 아닌 id 는 404. 출처 OWNER 라 자동 분류가 덮어쓰지 않는다
			IngredientCategory category = categoryService.get(storeId, request.categoryId());
			ingredient.assignCategory(category.getId(), CategorySource.OWNER);
			categoryBackup.exportAfterCommit();
		}
		return toResponse(storeId, ingredient);
	}

	/**
	 * 재료와 그 재료가 들어간 레시피 줄을 지운다.
	 *
	 * @throws BusinessException 거래처 상품 · 실사 · 추천 · 발주 · 매입 기록이 있으면 409 INGREDIENT_IN_USE
	 */
	@Transactional
	public void delete(long storeId, String rawId) {
		long ingredientId = ingredientRepository.getInStore(ResourceIds.parse(rawId), storeId).getId();
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("storeId", storeId)
				.addValue("ingredientId", ingredientId);
		if (Boolean.TRUE.equals(jdbc.queryForObject(IN_USE_SQL, params, Boolean.class))) {
			throw new BusinessException(IngredientErrorCode.IN_USE);
		}
		recipeRepository.deleteAllOfIngredient(storeId, ingredientId);
		// 레시피 일괄 삭제가 영속성 컨텍스트를 비웠으니 다시 읽어 지운다
		ingredientRepository.delete(ingredientRepository.getInStore(ingredientId, storeId));
	}

	private IngredientResponse toResponse(long storeId, Ingredient ingredient) {
		return toResponse(storeId, ingredient, categoryService.get(storeId, ingredient.getCategoryId()));
	}

	/** 카테고리를 이미 읽어 둔 경로(생성)용 — 같은 행을 다시 조회하지 않는다. */
	private IngredientResponse toResponse(long storeId, Ingredient ingredient, IngredientCategory category) {
		return IngredientResponse.of(ingredient,
				reviewState(storeId, List.of(ingredient.getId())).reasons(ingredient.getId()), category);
	}

	private ReviewState reviewState(long storeId, Collection<Long> ingredientIds) {
		Set<Long> counted = new HashSet<>(jdbc.queryForList(COUNTED_SQL,
				new MapSqlParameterSource().addValue("storeId", storeId).addValue("ingredientIds", ingredientIds),
				Long.class));
		return new ReviewState(recipeRepository.findEstimatedIngredientIds(storeId, ingredientIds), counted);
	}

	private void requireUniqueName(long storeId, String name) {
		if (ingredientRepository.existsByStoreIdAndName(storeId, name)) {
			throw new BusinessException(IngredientErrorCode.DUPLICATE_NAME);
		}
	}

	/** 3주 계획 4.8절: 신선 재료 기본 OFF, 냉동 · 상온 기본 ON. 냉장은 상하기 쉬워 신선 쪽으로 둔다. */
	private static boolean defaultPriceSensitive(StorageType storageType) {
		return storageType == StorageType.FROZEN || storageType == StorageType.AMBIENT;
	}

	private record ReviewState(Set<Long> estimated, Set<Long> counted) {

		List<ReviewReason> reasons(long ingredientId) {
			List<ReviewReason> reasons = new ArrayList<>(2);
			if (estimated.contains(ingredientId)) {
				reasons.add(ReviewReason.UNANSWERED);
			}
			if (!counted.contains(ingredientId)) {
				reasons.add(ReviewReason.NEEDS_COUNT);
			}
			return List.copyOf(reasons);
		}
	}
}
