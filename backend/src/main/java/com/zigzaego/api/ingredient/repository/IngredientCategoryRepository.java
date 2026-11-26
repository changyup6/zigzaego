package com.zigzaego.api.ingredient.repository;

import com.zigzaego.api.global.store.StoreScopedRepository;
import com.zigzaego.api.ingredient.entity.DefaultIngredientCategory;
import com.zigzaego.api.ingredient.entity.IngredientCategory;
import java.util.List;
import java.util.Optional;

public interface IngredientCategoryRepository extends StoreScopedRepository<IngredientCategory> {

	/** 화면 순서(sort_order) → id 순. */
	List<IngredientCategory> findAllByStoreIdOrderBySortOrderAscIdAsc(Long storeId);

	/** 기본 카테고리 하나 (예: 기타). 사장님이 만든 카테고리는 코드가 없어 여기로 못 찾는다. */
	Optional<IngredientCategory> findByStoreIdAndCode(Long storeId, DefaultIngredientCategory code);

	boolean existsByStoreId(Long storeId);
}
