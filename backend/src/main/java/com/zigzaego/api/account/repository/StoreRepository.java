package com.zigzaego.api.account.repository;

import com.zigzaego.api.account.entity.Store;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreRepository extends JpaRepository<Store, Long> {
}
