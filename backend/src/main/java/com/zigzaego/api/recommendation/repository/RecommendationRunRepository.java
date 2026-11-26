package com.zigzaego.api.recommendation.repository;

import com.zigzaego.api.global.store.StoreScopedRepository;
import com.zigzaego.api.recommendation.entity.RecommendationRun;
import java.time.LocalDate;
import java.util.Optional;

public interface RecommendationRunRepository extends StoreScopedRepository<RecommendationRun> {

	/** 그날 그 가게의 실행 기록. 가게 · 날짜마다 한 줄이다. */
	Optional<RecommendationRun> findByStoreIdAndRunDate(Long storeId, LocalDate runDate);
}
