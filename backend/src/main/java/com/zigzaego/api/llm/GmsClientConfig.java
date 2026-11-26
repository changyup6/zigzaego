package com.zigzaego.api.llm;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** GMS 클라이언트 조립. 응답 대기는 {@code app.llm.timeout}. */
@Configuration
@EnableConfigurationProperties(GmsProperties.class)
public class GmsClientConfig {

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

	@Bean
	public GmsClient gmsClient(GmsProperties properties, JsonMapper jsonMapper) {
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(properties.timeout());
		return new GmsClient(RestClient.builder().requestFactory(requestFactory), properties, jsonMapper,
				Thread::sleep);
	}
}
