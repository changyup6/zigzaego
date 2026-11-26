package com.zigzaego.api.supplier.repository;

import com.zigzaego.api.global.store.StoreScopedRepository;
import com.zigzaego.api.supplier.entity.Supplier;
import java.util.Collection;
import java.util.List;

public interface SupplierRepository extends StoreScopedRepository<Supplier> {

	List<Supplier> findAllByStoreIdOrderByIdAsc(Long storeId);

	List<Supplier> findAllByStoreIdAndIdIn(Long storeId, Collection<Long> ids);

	boolean existsByStoreIdAndName(Long storeId, String name);
}
