package com.zigzaego.api.onboarding.replay;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 운영자용 온보딩 저장본 지우기 (S15P31A308-321, ADR-0016). {@code onboarding-replay-delete} 프로필로 띄울 때만 돈다.
 * 그 검색어의 SEARCH 행과 그 행에 든 후보들의 MENUS · ANALYSIS 행을 지우고 끝난다. 지울 것이 없으면 로그만 남기고 정상 종료한다.
 */
@Slf4j
@Component
@Profile("onboarding-replay-delete")
@EnableConfigurationProperties(OnboardingReplayProperties.class)
@RequiredArgsConstructor
public class OnboardingReplayDeleteRunner implements ApplicationRunner {

	private final OnboardingReplayProperties properties;
	private final OnboardingReplayRepository replays;

	@Override
	public void run(ApplicationArguments args) {
		String query = properties.requireQuery();
		if (replays.deleteSearchWithMenus(query)) {
			log.info("온보딩 저장본 삭제 끝 — 검색어 '{}' 의 검색 결과와 후보 메뉴 · 분석 결과", query);
		} else {
			log.info("지울 저장본 없음 — 검색어 '{}'", query);
		}
	}
}
