package com.zigzaego.api.onboarding.job;

import com.zigzaego.api.llm.LlmImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 수집한 메뉴 사진 URL 을 내려받아 LLM 에 넣을 바이트로 만든다. 저장하지 않는다 (설계 2.3).
 * 실패(시간 초과 · 이미지 아님 · 너무 큼)는 모두 빈 값 — 그 메뉴는 글자만으로 분석한다.
 */
@Slf4j
@Component
public class MenuImageFetcher {

	static final int MAX_BYTES = 5 * 1024 * 1024;
	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final RestClient restClient;

	public MenuImageFetcher() {
		HttpClient httpClient = HttpClient.newBuilder()
				.connectTimeout(TIMEOUT)
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(TIMEOUT);
		this.restClient = RestClient.builder().requestFactory(requestFactory).build();
	}

	public Optional<LlmImage> fetch(String url) {
		try {
			URI uri = URI.create(url);
			if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
				return Optional.empty();
			}
			ResponseEntity<byte[]> response = restClient.get().uri(uri).retrieve().toEntity(byte[].class);
			MediaType type = response.getHeaders().getContentType();
			byte[] body = response.getBody();
			if (type == null || !"image".equalsIgnoreCase(type.getType()) || body == null || body.length == 0
					|| body.length > MAX_BYTES) {
				return Optional.empty();
			}
			return Optional.of(new LlmImage(type.getType() + "/" + type.getSubtype(), body));
		} catch (RuntimeException e) {
			log.info("메뉴 사진을 받지 못해 글자만으로 분석한다: {}", e.getMessage());
			return Optional.empty();
		}
	}
}
