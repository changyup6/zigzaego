package com.zigzaego.api.purchasedoc.job;

import java.net.URI;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** 테스트용 S3 호환 저장소(Adobe S3Mock). JVM 에 하나만 띄워 여러 테스트가 같이 쓴다. 자격 · 리전 값은 S3Mock 이 보지 않는다. */
public final class S3MockContainer {

	private static final GenericContainer<?> CONTAINER = new GenericContainer<>(DockerImageName.parse("adobe/s3mock:5.2.3"))
			.withExposedPorts(9090)
			.waitingFor(Wait.forHttp("/").forPort(9090).forStatusCodeMatching(code -> code < 500));

	private S3MockContainer() {
	}

	public static synchronized String endpoint() {
		if (!CONTAINER.isRunning()) {
			CONTAINER.start();
		}
		return "http://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(9090);
	}

	/** 앱 쪽 클라이언트(SDK 기본 체인)가 읽는 자격 · 리전. 컨텍스트를 만들기 전에 부른다. */
	public static void useDummyAwsCredentials() {
		System.setProperty("aws.accessKeyId", "test");
		System.setProperty("aws.secretAccessKey", "test");
		System.setProperty("aws.region", "us-east-1");
	}

	public static S3Client client() {
		return S3Client.builder()
				.endpointOverride(URI.create(endpoint()))
				.forcePathStyle(true)
				.region(Region.US_EAST_1)
				.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
				.build();
	}

	public static void createBucket(S3Client client, String bucket) {
		if (client.listBuckets().buckets().stream().noneMatch(b -> b.name().equals(bucket))) {
			client.createBucket(b -> b.bucket(bucket));
		}
	}
}
