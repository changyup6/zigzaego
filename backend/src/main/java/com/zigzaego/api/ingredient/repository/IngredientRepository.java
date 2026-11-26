package com.zigzaego.api.ingredient.repository;

import com.zigzaego.api.global.store.StoreScopedRepository;
import com.zigzaego.api.ingredient.entity.CategorySource;
import com.zigzaego.api.ingredient.entity.Ingredient;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IngredientRepository extends StoreScopedRepository<Ingredient> {

	List<Ingredient> findAllByStoreIdOrderByIdAsc(Long storeId);

	List<Ingredient> findAllByStoreIdAndIdIn(Long storeId, Collection<Long> ids);

	boolean existsByStoreIdAndName(Long storeId, String name);

	/** 온보딩 확정의 이름 기준 upsert 용. */
	Optional<Ingredient> findByStoreIdAndName(Long storeId, String name);

	/** 일괄 자동 분류 대상: 그 카테고리(기타)에 자동 분류로 놓인 재료. 사장님이 고른 것은 빠진다 (S15P31A308-274). */
	List<Ingredient> findAllByStoreIdAndCategoryIdAndCategorySourceOrderByIdAsc(Long storeId, Long categoryId,
			CategorySource categorySource);

	long countByStoreIdAndCategoryId(Long storeId, Long categoryId);

	/** 카테고리 목록의 재료 수 — {@code [categoryId, count]} (S15P31A308-275). */
	@Query("select i.categoryId, count(i) from Ingredient i where i.storeId = :storeId group by i.categoryId")
	List<Object[]> countByStoreIdGroupByCategory(@Param("storeId") Long storeId);

	/** 카테고리를 지울 때 그 재료를 전부 다른 카테고리(기타)로. 같은 가게 안에서만 (S15P31A308-275). */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update Ingredient i set i.categoryId = :toCategoryId, i.categorySource = :source"
			+ " where i.storeId = :storeId and i.categoryId = :fromCategoryId")
	int moveCategory(@Param("storeId") Long storeId, @Param("fromCategoryId") Long fromCategoryId,
			@Param("toCategoryId") Long toCategoryId, @Param("source") CategorySource source);
}
