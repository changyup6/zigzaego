package com.zigzaego.api.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.zigzaego.api.onboarding.client.ApifyNaverPlaceClient;
import com.zigzaego.api.onboarding.dto.PlaceCandidateResponse;
import com.zigzaego.api.onboarding.replay.OnboardingPlaceSource;
import com.zigzaego.api.onboarding.replay.OnboardingReplayRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 저장본 재생 지연 (ADR-0016). 저장본으로 답할 때만 기다린다. */
class OnboardingPlaceSourceDelayTest {

	private static final Duration DELAY = Duration.ofMillis(300);

	private final ApifyNaverPlaceClient placeClient = mock(ApifyNaverPlaceClient.class);
	private final OnboardingReplayRepository replays = mock(OnboardingReplayRepository.class);
	private final OnboardingPlaceSource source = new OnboardingPlaceSource(placeClient, replays, DELAY, DELAY);

	private static long millis(Runnable work) {
		long start = System.nanoTime();
		work.run();
		return (System.nanoTime() - start) / 1_000_000;
	}

	@Test
	void searchWaitsOnlyOnReplayHit() {
		List<PlaceCandidateResponse> saved = List.of(new PlaceCandidateResponse("1", "가게", "주소", null, false));
		given(replays.findSearch("저장된 검색어")).willReturn(Optional.of(saved));
		given(replays.findSearch("없는 검색어")).willReturn(Optional.empty());
		given(placeClient.searchPlaces("없는 검색어")).willReturn(List.of());

		assertThat(millis(() -> assertThat(source.searchPlaces("저장된 검색어")).isEqualTo(saved)))
				.isGreaterThanOrEqualTo(DELAY.toMillis());
		verify(placeClient, never()).searchPlaces(anyString());

		assertThat(millis(() -> source.searchPlaces("없는 검색어"))).isLessThan(DELAY.toMillis());
		verify(placeClient).searchPlaces("없는 검색어");
	}

	@Test
	void paceAnalysisSpreadsProgressOverDelay() {
		List<Double> seen = new ArrayList<>();

		assertThat(millis(() -> source.paceAnalysis(seen::add))).isGreaterThanOrEqualTo(DELAY.toMillis());

		assertThat(seen).hasSize(30).isSorted().allMatch(p -> p > 0 && p < 1);
	}

	@Test
	void zeroDelayDoesNotPace() {
		List<Double> seen = new ArrayList<>();

		new OnboardingPlaceSource(placeClient, replays).paceAnalysis(seen::add);

		assertThat(seen).isEmpty();
	}
}
