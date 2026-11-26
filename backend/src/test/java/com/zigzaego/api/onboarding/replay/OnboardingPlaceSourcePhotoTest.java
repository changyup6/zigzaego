package com.zigzaego.api.onboarding.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.zigzaego.api.onboarding.client.ApifyNaverPlaceClient;
import com.zigzaego.api.onboarding.dto.CollectedMenu;
import com.zigzaego.api.onboarding.job.OnboardingResult;
import com.zigzaego.api.onboarding.job.OnboardingResult.Draft;
import com.zigzaego.api.onboarding.job.OnboardingResult.DraftMenu;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OnboardingPlaceSourcePhotoTest {

	private final ApifyNaverPlaceClient client = mock(ApifyNaverPlaceClient.class);
	private final OnboardingReplayRepository replays = mock(OnboardingReplayRepository.class);
	private final OnboardingPlaceSource source = new OnboardingPlaceSource(client, replays);

	private OnboardingResult analysis(String photoUrl) {
		return new OnboardingResult(List.of(), false, new Draft(List.of(
				new DraftMenu("m1", "만두", 6000, "MAIN", "NAVER", List.of(), photoUrl)), List.of()),
				List.of(), null, null);
	}

	@Test
	void 예전_분석_저장본에_같은_플레이스_메뉴_사진을_채우고_외부_수집은_하지_않는다() {
		given(replays.findAnalysis("123")).willReturn(Optional.of(analysis(null)));
		given(replays.findMenus("123")).willReturn(Optional.of(List.of(
				new CollectedMenu("만두", 6000, null, List.of("https://images.example.com/menu.jpg")))));
		assertThat(source.findAnalysis("123").orElseThrow().draft().menus().getFirst().photoUrl())
				.isEqualTo("https://images.example.com/menu.jpg");
		verifyNoInteractions(client);
	}

	@Test
	void 사진이_있는_분석_저장본은_기존_사진을_유지한다() {
		given(replays.findAnalysis("123")).willReturn(Optional.of(analysis("https://images.example.com/saved.jpg")));
		given(replays.findMenus("123")).willReturn(Optional.of(List.of(
				new CollectedMenu("만두", 6000, null, List.of("https://images.example.com/menu.jpg")))));
		assertThat(source.findAnalysis("123").orElseThrow().draft().menus().getFirst().photoUrl())
				.isEqualTo("https://images.example.com/saved.jpg");
		verifyNoInteractions(client);
	}

	@Test
	void 메뉴_저장본도_없으면_외부_수집_없이_기존_결과를_돌려준다() {
		OnboardingResult saved = analysis(null);
		given(replays.findAnalysis("123")).willReturn(Optional.of(saved));
		given(replays.findMenus("123")).willReturn(Optional.empty());
		assertThat(source.findAnalysis("123").orElseThrow()).isEqualTo(saved);
		verifyNoInteractions(client);
	}
}
