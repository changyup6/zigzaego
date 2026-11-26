package com.zigzaego.api.recommendation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zigzaego.api.account.entity.Store;
import com.zigzaego.api.account.repository.StoreRepository;
import com.zigzaego.api.recommendation.dto.RecommendationRunResponse;
import com.zigzaego.api.recommendation.service.RecommendationScheduler;
import com.zigzaego.api.recommendation.service.RecommendationService;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 추천 배치 실행 로그 · 안전장치 (S15P31A308-73). 한 가게가 실패해도 다음 가게는 계속 돌고, 배치 자체는 예외를 밖으로 던지지
 * 않는다 — 던지면 스케줄러 스레드가 그날 배치를 중단한다. 실패는 로그로만 남기고 다음 날 다시 시도한다.
 */
class RecommendationSchedulerTest {

	private final StoreRepository storeRepository = mock(StoreRepository.class);
	private final RecommendationService recommendationService = mock(RecommendationService.class);
	private final RecommendationScheduler scheduler = new RecommendationScheduler(storeRepository, recommendationService);

	@Test
	void 한_가게가_실패해도_다음_가게는_계속_돌고_예외를_밖으로_던지지_않는다() {
		Store failing = store(1L);
		Store next = store(2L);
		when(storeRepository.findAll()).thenReturn(List.of(failing, next));
		when(recommendationService.run(eq(1L), isNull())).thenThrow(new IllegalStateException("가게 1 실패"));
		when(recommendationService.run(eq(2L), isNull()))
				.thenReturn(new RecommendationRunResponse("2026-10-10", 1, 0, 0, List.of()));

		assertThatCode(scheduler::runDaily).doesNotThrowAnyException();

		verify(recommendationService).run(eq(1L), isNull());
		verify(recommendationService).run(eq(2L), isNull());
	}

	@Test
	void 가게가_없으면_아무것도_하지_않는다() {
		when(storeRepository.findAll()).thenReturn(List.of());

		assertThatCode(scheduler::runDaily).doesNotThrowAnyException();
	}

	private static Store store(long id) {
		Store store = mock(Store.class);
		when(store.getId()).thenReturn(id);
		return store;
	}
}
