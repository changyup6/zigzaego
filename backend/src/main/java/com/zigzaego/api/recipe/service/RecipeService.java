package com.zigzaego.api.recipe.service;

import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.store.ResourceIds;
import com.zigzaego.api.ingredient.entity.Ingredient;
import com.zigzaego.api.ingredient.entity.IngredientKind;
import com.zigzaego.api.ingredient.repository.IngredientRepository;
import com.zigzaego.api.menu.repository.MenuRepository;
import com.zigzaego.api.recipe.dto.RecipeCreateRequest;
import com.zigzaego.api.recipe.dto.RecipeResponse;
import com.zigzaego.api.recipe.dto.RecipeUpdateRequest;
import com.zigzaego.api.recipe.entity.Recipe;
import com.zigzaego.api.recipe.exception.RecipeErrorCode;
import com.zigzaego.api.recipe.repository.RecipeRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 레시피 줄 CRUD. 메뉴 · 재료 · 레시피 줄 모두 로그인한 가게로 좁힌다 — 다른 가게 id 는 모두 404 (ADR-0002).
 * AI 추정 줄(source=AI)은 확인 필요(UNANSWERED). 사장님이 양을 고치거나 그대로 보내면 추정이 풀린다.
 */
@Service
@RequiredArgsConstructor
public class RecipeService {

	private final RecipeRepository recipeRepository;
	private final MenuRepository menuRepository;
	private final IngredientRepository ingredientRepository;

	/** 메뉴 id 를 주면 그 메뉴의 줄만. 확인 필요 줄이 맨 위, 그 안에서 id 순. */
	@Transactional(readOnly = true)
	public List<RecipeResponse> listForStore(long storeId, String rawMenuId) {
		List<Recipe> recipes;
		if (rawMenuId == null) {
			recipes = recipeRepository.findAllByStoreIdOrderByIdAsc(storeId);
		} else {
			long menuId = menuRepository.getInStore(ResourceIds.parse(rawMenuId), storeId).getId();
			recipes = recipeRepository.findAllByStoreIdAndMenuIdOrderByIdAsc(storeId, menuId);
		}
		if (recipes.isEmpty()) {
			return List.of();
		}
		Map<Long, Ingredient> ingredients = ingredientRepository
				.findAllByStoreIdAndIdIn(storeId, recipes.stream().map(Recipe::getIngredientId).distinct().toList())
				.stream()
				.collect(Collectors.toMap(Ingredient::getId, Function.identity()));
		return recipes.stream()
				.map(recipe -> RecipeResponse.of(recipe, ingredients.get(recipe.getIngredientId())))
				.sorted(Comparator.comparing(RecipeResponse::needsReview).reversed())
				.toList();
	}

	@Transactional(readOnly = true)
	public RecipeResponse getForStore(long storeId, String rawId) {
		return toResponse(storeId, recipeRepository.getInStore(ResourceIds.parse(rawId), storeId));
	}

	/**
	 * @throws BusinessException 메뉴 · 재료가 없거나 다른 가게면 404, 주기형 재료면 409 RECIPE_CYCLE_INGREDIENT,
	 *         이미 그 메뉴에 들어간 재료면 409 RECIPE_DUPLICATE
	 */
	@Transactional
	public RecipeResponse create(long storeId, RecipeCreateRequest request) {
		long menuId = menuRepository.getInStore(ResourceIds.parse(request.menuId()), storeId).getId();
		Ingredient ingredient = ingredientRepository.getInStore(ResourceIds.parse(request.ingredientId()), storeId);
		if (ingredient.getKind() == IngredientKind.CYCLE) {
			throw new BusinessException(RecipeErrorCode.CYCLE_INGREDIENT);
		}
		if (recipeRepository.existsByStoreIdAndMenuIdAndIngredientId(storeId, menuId, ingredient.getId())) {
			throw new BusinessException(RecipeErrorCode.DUPLICATE);
		}
		Recipe recipe = recipeRepository.save(
				Recipe.manual(storeId, menuId, ingredient.getId(), request.qtyPerServing()));
		return RecipeResponse.of(recipe, ingredient);
	}

	@Transactional
	public RecipeResponse update(long storeId, String rawId, RecipeUpdateRequest request) {
		Recipe recipe = recipeRepository.getInStore(ResourceIds.parse(rawId), storeId);
		recipe.confirmByOwner(request.qtyPerServing());
		return toResponse(storeId, recipe);
	}

	@Transactional
	public void delete(long storeId, String rawId) {
		recipeRepository.delete(recipeRepository.getInStore(ResourceIds.parse(rawId), storeId));
	}

	private RecipeResponse toResponse(long storeId, Recipe recipe) {
		return RecipeResponse.of(recipe, ingredientRepository.getInStore(recipe.getIngredientId(), storeId));
	}
}
