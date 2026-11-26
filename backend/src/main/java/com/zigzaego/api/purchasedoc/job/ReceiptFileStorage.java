package com.zigzaego.api.purchasedoc.job;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 올린 영수증 원본을 서버 디스크에 둔다 (설계 4.5 — /data 볼륨, DB 에는 경로만). 로컬 · 스테이징의 기본 구현.
 * 저장 키는 {@code app.receipt.storage-dir} 기준 상대 경로다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.receipt.storage", havingValue = "file", matchIfMissing = true)
public class ReceiptFileStorage implements ReceiptStorage {

	private final Path root;

	public ReceiptFileStorage(@Value("${app.receipt.storage-dir:./data/receipts}") String storageDir) {
		this.root = Path.of(storageDir).toAbsolutePath().normalize();
	}

	/** @return 저장 폴더 기준 상대 경로 (구분자 {@code /}) */
	@Override
	public String save(long storeId, long jobId, String mimeType, byte[] bytes) {
		String relative = ReceiptStorage.newKey(storeId, jobId, mimeType);
		Path target = resolve(relative);
		try {
			Files.createDirectories(target.getParent());
			Files.write(target, bytes);
		} catch (IOException e) {
			throw new UncheckedIOException("영수증 파일을 저장하지 못했다", e);
		}
		return relative;
	}

	/**
	 * @throws NoSuchFileException 파일이 없으면
	 */
	@Override
	public byte[] read(String relative) throws IOException {
		return Files.readAllBytes(resolve(relative));
	}

	/** 지우기. 실패해도 던지지 않는다(롤백 정리용). */
	@Override
	public void deleteQuietly(String relative) {
		try {
			Files.deleteIfExists(resolve(relative));
		} catch (IOException | RuntimeException e) {
			log.warn("영수증 파일을 지우지 못했다: {}", relative, e);
		}
	}

	/** 저장 폴더 안의 실제 위치. 폴더 밖을 가리키는 경로는 거절한다. */
	public Path resolve(String relative) {
		Path path = root.resolve(relative).normalize();
		if (!path.startsWith(root)) {
			throw new IllegalArgumentException("저장 폴더 밖 경로: " + relative);
		}
		return path;
	}
}
