package com.zigzaego.api.purchasedoc.ocr;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CLOVA General OCR 설정 (S15P31A308-111, 설계 1.1). 값은 {@code backend/.env.example} 참조.
 * url · secret 이 둘 다 있어야 실제 CLOVA 를 부른다. 하나라도 비면 {@link FakeOcrClient} 를 쓴다.
 *
 * @param url         APIGW Invoke URL 전체 (General OCR 도메인의 주소)
 * @param secret      X-OCR-SECRET 값
 * @param timeout     요청 한 번의 응답 대기 시간
 * @param retryDelays 429 · 5xx · 연결 실패 · 타임아웃 재시도 간격. 기본 1초 · 3초 → 최대 3번 호출
 * @param minInterval 호출 사이 최소 간격 (설계 4.2 "CLOVA 권장 1 tps")
 */
@ConfigurationProperties(prefix = "app.ocr.clova")
public record ClovaOcrProperties(
		String url,
		String secret,
		Duration timeout,
		List<Duration> retryDelays,
		Duration minInterval
) {

	public ClovaOcrProperties {
		timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
		retryDelays = retryDelays == null ? List.of() : List.copyOf(retryDelays);
		minInterval = minInterval == null ? Duration.ZERO : minInterval;
	}

	public boolean configured() {
		return notBlank(url) && notBlank(secret);
	}

	private static boolean notBlank(String value) {
		return value != null && !value.isBlank();
	}
}
