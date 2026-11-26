package com.zigzaego.api.purchaseorder.repository;

import com.zigzaego.api.global.store.StoreScopedRepository;
import com.zigzaego.api.purchaseorder.entity.PurchaseOrder;
import java.util.Collection;
import java.util.List;

public interface PurchaseOrderRepository extends StoreScopedRepository<PurchaseOrder> {

	List<PurchaseOrder> findAllByStoreIdAndIdInOrderByIdAsc(Long storeId, Collection<Long> ids);
}
