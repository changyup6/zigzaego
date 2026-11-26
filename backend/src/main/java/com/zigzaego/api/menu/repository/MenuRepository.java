package com.zigzaego.api.menu.repository;

import com.zigzaego.api.global.store.StoreScopedRepository;
import com.zigzaego.api.menu.entity.Menu;
import java.util.List;
import java.util.Optional;

public interface MenuRepository extends StoreScopedRepository<Menu> {

	List<Menu> findAllByStoreIdOrderByIdAsc(Long storeId);

	boolean existsByStoreIdAndName(Long storeId, String name);

	/** 온보딩 확정의 이름 기준 upsert 용. */
	Optional<Menu> findByStoreIdAndName(Long storeId, String name);
}
