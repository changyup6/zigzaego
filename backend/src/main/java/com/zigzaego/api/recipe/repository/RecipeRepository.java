package com.zigzaego.api.recipe.repository;

import com.zigzaego.api.global.store.StoreScopedRepository;
import com.zigzaego.api.recipe.entity.Recipe;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RecipeRepository extends StoreScopedRepository<Recipe> {

	List<Recipe> findAllByStoreIdOrderByIdAsc(Long storeId);

	List<Recipe> findAllByStoreIdAndMenuIdOrderByIdAsc(Long storeId, Long menuId);

	boolean existsByStoreIdAndMenuIdAndIngredientId(Long storeId, Long menuId, Long ingredientId);

	/** 온보딩 확정의 (메뉴, 재료) upsert 용. */
	Optional<Recipe> findByStoreIdAndMenuIdAndIngredientId(Long storeId, Long menuId, Long ingredientId);

	/** 재료 1인분 고치기(PATCH /ingredients/{id}/serving)가 그 재료의 줄을 모두 고칠 때. */
	List<Recipe> findAllByStoreIdAndIngredientId(Long storeId, Long ingredientId);

	/** AI 추정 줄이 남은 메뉴 id. 메뉴의 확인 필요(UNANSWERED) 판정용. */
	@Query("SELECT DISTINCT r.menuId FROM Recipe r WHERE r.storeId = :storeId AND r.source = "
			+ "com.zigzaego.api.recipe.entity.RecipeSource.AI AND r.menuId IN :menuIds")
	Set<Long> findEstimatedMenuIds(Long storeId, Collection<Long> menuIds);

	/** AI 추정 줄에 쓰인 재료 id. 재료의 확인 필요(UNANSWERED) 판정용. */
	@Query("SELECT DISTINCT r.ingredientId FROM Recipe r WHERE r.storeId = :storeId AND r.source = "
			+ "com.zigzaego.api.recipe.entity.RecipeSource.AI AND r.ingredientId IN :ingredientIds")
	Set<Long> findEstimatedIngredientIds(Long storeId, Collection<Long> ingredientIds);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("DELETE FROM Recipe r WHERE r.storeId = :storeId AND r.menuId = :menuId")
	int deleteAllInMenu(Long storeId, Long menuId);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("DELETE FROM Recipe r WHERE r.storeId = :storeId AND r.ingredientId = :ingredientId")
	int deleteAllOfIngredient(Long storeId, Long ingredientId);
}
