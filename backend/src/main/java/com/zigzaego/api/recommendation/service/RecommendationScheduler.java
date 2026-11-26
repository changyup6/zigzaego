package com.zigzaego.api.recommendation.service;

import com.zigzaego.api.account.entity.Store;
import com.zigzaego.api.account.repository.StoreRepository;
import com.zigzaego.api.recommendation.dto.RecommendationRunResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 04:00 추천 생성 배치 (설계 5절: ai-batch 03:00 뒤). 시각 · 시간대는 {@code app.recommendation.cron} · {@code .zone}.
 * 가게마다 따로 돌리고, 한 가게가 실패해도 다음 가게는 계속한다 — 실패는 로그로만 남기고 다음 날 다시 시도한다.
 * 같은 날 이미 추천이 있으면 {@link RecommendationService#run}이 새로 만들지 않으므로 여러 번 돌아도 안전하다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecommendationScheduler {

	private final StoreRepository storeRepository;
	private final RecommendationService recommendationService;

	@Scheduled(cron = "${app.recommendation.cron}", zone = "${app.recommendation.zone}")
	public void runDaily() {
		for (Store store : storeRepository.findAll()) {
			try {
				RecommendationRunResponse result = recommendationService.run(store.getId(), null);
				log.info("추천 배치 완료 — storeId={}, runDate={}, created={}, existing={}, noOrder={}",
						store.getId(), result.runDate(), result.createdCount(), result.existingCount(),
						result.noOrderCount());
			} catch (RuntimeException e) {
				log.error("추천 배치 실패 — storeId={}", store.getId(), e);
			}
		}
	}
}
