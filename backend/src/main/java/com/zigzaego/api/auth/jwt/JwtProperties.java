package com.zigzaego.api.auth.jwt;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 설정 (ADR-0005). 값은 환경 변수로만 받는다 — {@code backend/.env.example} 참조.
 *
 * @param secret     HS256 서명 키. 32바이트(256비트) 미만이면 앱을 띄우지 않는다
 * @param accessTtl  access token 만료
 * @param refreshTtl refresh token 만료 (= Redis TTL)
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String secret, Duration accessTtl, Duration refreshTtl) {

	private static final int MIN_SECRET_BYTES = 32;

	public JwtProperties {
		if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
			throw new IllegalStateException(
					"JWT_SECRET 은 32바이트 이상이어야 합니다. 생성 예: openssl rand -base64 32 (backend/.env.example)");
		}
		if (!isPositive(accessTtl) || !isPositive(refreshTtl)) {
			throw new IllegalStateException("JWT_ACCESS_TTL · JWT_REFRESH_TTL 은 0보다 커야 합니다.");
		}
	}

	private static boolean isPositive(Duration duration) {
		return duration != null && duration.isPositive();
	}
}
