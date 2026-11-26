package com.zigzaego.api.supplier.repository;

import com.zigzaego.api.global.store.StoreScopedRepository;
import com.zigzaego.api.supplier.entity.SupplierProduct;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Query;

public interface SupplierProductRepository extends StoreScopedRepository<SupplierProduct> {

	/** 영수증 상품명 사전 조회 (설계 4.2절). 상품명이 똑같은 것만, idx_supplier_product_store_name 을 탄다. */
	List<SupplierProduct> findAllByStoreIdAndProductNameOrderByIdAsc(Long storeId, String productName);

	/** 조건을 null 로 주면 그 조건은 걸지 않는다. 상품명은 똑같은 것만. */
	@Query("""
			SELECT p FROM SupplierProduct p
			WHERE p.storeId = :storeId
			  AND (:supplierId IS NULL OR p.supplierId = :supplierId)
			  AND (:ingredientId IS NULL OR p.ingredientId = :ingredientId)
			  AND (:productName IS NULL OR p.productName = :productName)
			ORDER BY p.id
			""")
	List<SupplierProduct> search(Long storeId, Long supplierId, Long ingredientId, String productName);

	boolean existsByStoreIdAndSupplierIdAndProductName(Long storeId, Long supplierId, String productName);
	List<SupplierProduct> findAllByStoreIdAndIdIn(Long storeId, Collection<Long> ids);
}
