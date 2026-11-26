package com.zigzaego.api.recommendation.repository;

import com.zigzaego.api.global.store.StoreScopedRepository;
import com.zigzaego.api.recommendation.entity.Recommendation;
import com.zigzaego.api.recommendation.entity.RecommendationStatus;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface RecommendationRepository extends StoreScopedRepository<Recommendation> {

	/** 그날 추천 전부, 만든 순서(= 재료 id 순). */
	List<Recommendation> findAllByStoreIdAndRunDateOrderByIdAsc(Long storeId, LocalDate runDate);

	/** 그날 그 상태의 추천, 만든 순서. 발주서 만들기(지라 70)가 APPROVED 만 고른다. */
	List<Recommendation> findAllByStoreIdAndRunDateAndStatusOrderByIdAsc(Long storeId, LocalDate runDate,
			RecommendationStatus status);

	List<Recommendation> findAllByStoreIdAndIdInOrderByIdAsc(Long storeId, Collection<Long> ids);
}
