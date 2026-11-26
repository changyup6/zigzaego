package com.zigzaego.api.purchasedoc.job;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/**
 * {@code app.receipt.storage=s3} 일 때만 S3 클라이언트를 만든다. 자격 · 리전은 SDK 기본 체인(운영은 서버 역할과 AWS_REGION)이다.
 * {@code app.receipt.s3.endpoint} 를 주면 그 주소에 경로 방식으로 붙는다(S3 호환 저장소 · 테스트).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.receipt.storage", havingValue = "s3")
public class S3ReceiptStorageConfig {

	@Bean(destroyMethod = "close")
	S3Client receiptS3Client(@Value("${app.receipt.s3.endpoint:}") String endpoint) {
		S3ClientBuilder builder = S3Client.builder();
		if (endpoint != null && !endpoint.isBlank()) {
			builder.endpointOverride(URI.create(endpoint.strip())).forcePathStyle(true);
		}
		return builder.build();
	}
}
