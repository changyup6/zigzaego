package com.zigzaego.api.purchaseorder.repository;

import com.zigzaego.api.global.store.StoreScopedRepository;
import com.zigzaego.api.purchaseorder.entity.PurchaseOrderItem;
import java.util.Collection;
import java.util.List;

public interface PurchaseOrderItemRepository extends StoreScopedRepository<PurchaseOrderItem> {

	List<PurchaseOrderItem> findAllByStoreIdAndPoIdInOrderByIdAsc(Long storeId, Collection<Long> poIds);

	/** 이미 발주서가 된 추천을 찾는다 (uq_po_item_recommendation). */
	List<PurchaseOrderItem> findAllByStoreIdAndRecommendationIdIn(Long storeId, Collection<Long> recommendationIds);
}
