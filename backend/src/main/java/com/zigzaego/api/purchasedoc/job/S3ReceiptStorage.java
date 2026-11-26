package com.zigzaego.api.purchasedoc.job;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.NoSuchFileException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * 올린 영수증 원본을 S3 에 둔다 (ADR-0013, 운영). 키는 {@link ReceiptStorage#newKey} 값 그대로이고 버킷은 업로드 전용이다.
 * SDK 예외는 파일 구현과 같은 계약(없음 → {@link NoSuchFileException}, 그 밖 → {@link IOException})으로 바꿔 밖으로 내보내지 않는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.receipt.storage", havingValue = "s3")
public class S3ReceiptStorage implements ReceiptStorage {

	private final S3Client s3;
	private final String bucket;

	public S3ReceiptStorage(S3Client s3, @Value("${app.receipt.s3.bucket:}") String bucket) {
		if (bucket == null || bucket.isBlank()) {
			throw new IllegalStateException("app.receipt.storage=s3 이면 app.receipt.s3.bucket(RECEIPT_S3_BUCKET)이 필요하다");
		}
		this.s3 = s3;
		this.bucket = bucket.strip();
	}

	@Override
	public String save(long storeId, long jobId, String mimeType, byte[] bytes) {
		String key = ReceiptStorage.newKey(storeId, jobId, mimeType);
		try {
			s3.putObject(b -> b.bucket(bucket).key(key).contentType(mimeType), RequestBody.fromBytes(bytes));
		} catch (SdkException e) {
			throw new UncheckedIOException(new IOException("영수증 파일을 저장하지 못했다: " + key, e));
		}
		return key;
	}

	@Override
	public byte[] read(String key) throws IOException {
		check(key);
		try {
			return s3.getObjectAsBytes(b -> b.bucket(bucket).key(key)).asByteArray();
		} catch (NoSuchKeyException e) {
			throw notFound(key, e);
		} catch (S3Exception e) {
			if (e.statusCode() == 404) {
				throw notFound(key, e);
			}
			throw new IOException("영수증 파일을 읽지 못했다: " + key, e);
		} catch (SdkException e) {
			throw new IOException("영수증 파일을 읽지 못했다: " + key, e);
		}
	}

	@Override
	public void deleteQuietly(String key) {
		try {
			check(key);
			s3.deleteObject(b -> b.bucket(bucket).key(key));
		} catch (RuntimeException e) {
			log.warn("영수증 파일을 지우지 못했다: {}", key, e);
		}
	}

	/** 파일 구현의 "저장 폴더 밖 경로 거절" 과 같은 범위: 빈 키 · 절대 경로 · {@code ..} 를 받지 않는다. */
	private static void check(String key) {
		if (key == null || key.isBlank() || key.startsWith("/") || key.contains("..")) {
			throw new IllegalArgumentException("영수증 저장 키가 아니다: " + key);
		}
	}

	private static NoSuchFileException notFound(String key, Exception cause) {
		NoSuchFileException e = new NoSuchFileException(key);
		e.initCause(cause);
		return e;
	}
}
