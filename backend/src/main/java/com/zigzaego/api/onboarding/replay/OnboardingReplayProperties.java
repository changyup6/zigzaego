package com.zigzaego.api.onboarding.replay;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 저장본 저장 · 지우기 실행의 검색어 ({@code application-onboarding-replay-*.yaml}). 환경 변수 ONBOARDING_REPLAY_QUERY — {@code backend/.env.example}.
 *
 * @param query 저장 · 지울 검색어. 비면 실행이 멈춘다
 */
@ConfigurationProperties(prefix = "app.onboarding-replay")
public record OnboardingReplayProperties(String query) {

	/** 정규화한 검색어. 비어 있으면 IllegalArgumentException. */
	String requireQuery() {
		String normalized = OnboardingReplayRepository.normalizeQuery(query);
		if (normalized.isEmpty()) {
			throw new IllegalArgumentException("ONBOARDING_REPLAY_QUERY(검색어)가 비어 있습니다");
		}
		return normalized;
	}
}
