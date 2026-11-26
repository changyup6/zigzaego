package com.zigzaego.api.purchasedoc.ocr;

import java.net.http.HttpClient;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * 글자 인식 클라이언트 고르기. {@code app.ocr.clova.url} · {@code secret} 이 둘 다 있으면 CLOVA, 하나라도 비면 가짜
 * ({@link FakeOcrClient}, 기동 때 WARN 한 줄). 사용자 10/19 "가짜 응답으로 시작" — 키가 오면 설정만 넣으면 켜진다.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(ClovaOcrProperties.class)
public class OcrClientConfig {

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

	@Bean
	public OcrClient ocrClient(ClovaOcrProperties properties, JsonMapper jsonMapper) {
		return create(properties, jsonMapper);
	}

	static OcrClient create(ClovaOcrProperties properties, JsonMapper jsonMapper) {
		if (!properties.configured()) {
			log.warn("CLOVA OCR 설정(CLOVA_OCR_URL · CLOVA_OCR_SECRET)이 없어 가짜 글자 인식(FakeOcrClient)을 쓴다");
			return new FakeOcrClient(jsonMapper);
		}
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(properties.timeout());
		return new ClovaOcrClient(RestClient.builder().requestFactory(requestFactory), properties, jsonMapper,
				Thread::sleep, System::currentTimeMillis);
	}
}
