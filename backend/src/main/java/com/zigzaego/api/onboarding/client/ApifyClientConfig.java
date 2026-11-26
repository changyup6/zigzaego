package com.zigzaego.api.onboarding.client;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Apify 클라이언트 조립. run-sync 는 액터가 끝날 때까지 응답을 붙잡고 있으므로 읽기 타임아웃을 runTimeout 보다 조금 길게 둔다.
 */
@Configuration
@EnableConfigurationProperties(ApifyProperties.class)
public class ApifyClientConfig {

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration READ_MARGIN = Duration.ofSeconds(30);

	@Bean
	public ApifyNaverPlaceClient apifyNaverPlaceClient(ApifyProperties properties) {
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(properties.runTimeout().plus(READ_MARGIN));
		return new ApifyNaverPlaceClient(RestClient.builder().requestFactory(requestFactory), properties, Thread::sleep);
	}
}
