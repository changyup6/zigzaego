package com.zigzaego.api.purchasedoc.repository;

import com.zigzaego.api.global.store.StoreScopedRepository;
import com.zigzaego.api.purchasedoc.entity.PurchaseDoc;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Query;

public interface PurchaseDocRepository extends StoreScopedRepository<PurchaseDoc> {

	/** 영수증 job 이 처리할 장들. 올린 순서(id 오름차순). */
	List<PurchaseDoc> findAllByStoreIdAndJobIdOrderByIdAsc(Long storeId, Long jobId);

	/** 영수증 확정(S15P31A308-112)이 건드리는 문서들. id 오름차순. */
	List<PurchaseDoc> findAllByStoreIdAndIdInOrderByIdAsc(Long storeId, Collection<Long> ids);

	/**
	 * 영수증 목록의 장들(S15P31A308-113 {@code GET /receipts}): 영수증 job 으로 올린 문서 중 CONFIRMED 가 아닌 것 전부 + CONFIRMED 는
	 * {@code since} 이후에 올린 것. id 오름차순.
	 */
	@Query("""
			SELECT d FROM PurchaseDoc d
			WHERE d.storeId = :storeId AND d.jobId IS NOT NULL
			  AND (d.parseStatus <> com.zigzaego.api.purchasedoc.entity.ParseStatus.CONFIRMED OR d.createdAt >= :since)
			ORDER BY d.id
			""")
	List<PurchaseDoc> findReceiptFiles(Long storeId, OffsetDateTime since);
}
