package com.zigzaego.api.llm;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * GMS(SSAFY 제공 LLM 창구) 설정. 값은 {@code backend/.env.example} 참조 (지라 S15P31A308-93).
 *
 * @param key         API 키. 비어 있으면 앱은 뜨고 호출할 때 {@code LLM_UNAVAILABLE}
 * @param baseUrl     OpenAI 호환 주소. 뒤에 {@code /chat/completions} 를 붙인다
 * @param model       모델 이름
 * @param timeout     요청 한 번의 응답 대기 시간
 * @param retryDelays 429 · 5xx · 연결 실패 · 타임아웃 재시도 간격. 기본 1초 · 3초 → 최대 3번 호출
 */
@ConfigurationProperties(prefix = "app.llm")
public record GmsProperties(
		String key,
		String baseUrl,
		String model,
		Duration timeout,
		List<Duration> retryDelays
) {

	public GmsProperties {
		timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
		retryDelays = retryDelays == null ? List.of() : List.copyOf(retryDelays);
	}

	public boolean configured() {
		return notBlank(key) && notBlank(baseUrl) && notBlank(model);
	}

	private static boolean notBlank(String value) {
		return value != null && !value.isBlank();
	}
}
