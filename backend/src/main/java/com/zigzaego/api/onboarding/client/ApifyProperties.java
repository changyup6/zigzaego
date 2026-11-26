package com.zigzaego.api.onboarding.client;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Apify 네이버 플레이스 수집 설정 (설계 1.1 · 2.2절). 값은 {@code backend/.env.example} 참조.
 *
 * @param token        Apify API 토큰. 비어 있어도 앱은 뜨고, 호출할 때 {@code ONBOARDING_APIFY_NOT_CONFIGURED} 로 실패한다
 * @param baseUrl      Apify API 주소
 * @param actorId      액터 id ({@code 사용자~액터이름})
 * @param runTimeout   run-sync 한 번을 기다리는 최대 시간 (Apify 상한 300초)
 * @param retryDelays  메뉴 수집 재시도 간격. 기본 30초 · 2분 → 최대 3번 호출
 */
@ConfigurationProperties(prefix = "app.apify")
public record ApifyProperties(
		String token,
		String baseUrl,
		String actorId,
		Duration runTimeout,
		List<Duration> retryDelays
) {

	public ApifyProperties {
		retryDelays = retryDelays == null ? List.of() : List.copyOf(retryDelays);
	}

	public boolean hasToken() {
		return token != null && !token.isBlank();
	}
}
