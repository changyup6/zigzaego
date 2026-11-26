package com.zigzaego.api.account.repository;

import com.zigzaego.api.account.entity.StoreMember;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoreMemberRepository extends JpaRepository<StoreMember, Long> {

	/** 3주 안에는 계정당 가게 1곳. 여러 곳이면 가장 먼저 연결된 가게로 로그인한다. */
	Optional<StoreMember> findFirstByAccountIdOrderByIdAsc(long accountId);

	boolean existsByAccountIdAndStoreId(long accountId, long storeId);
}
