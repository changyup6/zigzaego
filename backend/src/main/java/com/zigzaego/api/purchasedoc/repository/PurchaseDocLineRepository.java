package com.zigzaego.api.purchasedoc.repository;

import com.zigzaego.api.global.store.StoreScopedRepository;
import com.zigzaego.api.purchasedoc.entity.DocLineStatus;
import com.zigzaego.api.purchasedoc.entity.PurchaseDocLine;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PurchaseDocLineRepository extends StoreScopedRepository<PurchaseDocLine> {

	List<PurchaseDocLine> findAllByStoreIdAndDocIdOrderByLineNoAsc(Long storeId, Long docId);

	/** 영수증 줄 확정(S15P31A308-112)이 고른 줄들. id 오름차순. */
	List<PurchaseDocLine> findAllByStoreIdAndIdInOrderByIdAsc(Long storeId, Collection<Long> ids);

	/** 문서의 남은 PENDING 줄 수 — 0 이면 문서를 CONFIRMED 로 (DECISIONS #41). */
	long countByStoreIdAndDocIdAndStatus(Long storeId, Long docId, DocLineStatus status);

	/**
	 * 확인 목록 대상 줄(S15P31A308-113): 이 가게의 PENDING 줄 중 문서가 PARSED 인 것. 문서 kind 무관. 줄 id 오름차순.
	 * CONFIRMED · IGNORED 줄과 PENDING · PROCESSING · FAILED · CONFIRMED 문서의 줄은 빠진다.
	 */
	@Query("""
			SELECT l FROM PurchaseDocLine l, PurchaseDoc d
			WHERE l.storeId = :storeId AND d.storeId = :storeId AND d.id = l.docId
			  AND l.status = com.zigzaego.api.purchasedoc.entity.DocLineStatus.PENDING
			  AND d.parseStatus = com.zigzaego.api.purchasedoc.entity.ParseStatus.PARSED
			ORDER BY l.id
			""")
	List<PurchaseDocLine> findReviewTargets(Long storeId);

	/** 문서별 줄 수(상태 무관, S15P31A308-113 영수증 목록 {@code lineCount}). 행마다 [docId, count]. 줄 없는 문서는 빠진다. */
	@Query("""
			SELECT l.docId, COUNT(l) FROM PurchaseDocLine l
			WHERE l.storeId = :storeId AND l.docId IN :docIds
			GROUP BY l.docId
			""")
	List<Object[]> countLinesByDoc(Long storeId, Collection<Long> docIds);

	/** 한 문서의 줄을 모두 지운다(S15P31A308-113 다시 읽기 · 지우기). 영속성 컨텍스트에 남은 줄 엔티티는 바꾸지 않는다. */
	@Modifying(flushAutomatically = true)
	@Query("DELETE FROM PurchaseDocLine l WHERE l.storeId = :storeId AND l.docId = :docId")
	int deleteAllByStoreIdAndDocId(Long storeId, Long docId);
}
