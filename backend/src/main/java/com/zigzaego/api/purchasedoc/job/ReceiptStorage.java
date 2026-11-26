package com.zigzaego.api.purchasedoc.job;

import java.io.IOException;
import java.util.UUID;

/**
 * 올린 영수증 원본 저장소 (ADR-0013). 설정 {@code app.receipt.storage} 로 구현 하나만 뜬다: {@code file}(기본, 서버 디스크) ·
 * {@code s3}(운영). 저장 키는 상대 경로 {@code {storeId}/{jobId}/{uuid}.{jpg|png|pdf}} 이고 그대로
 * {@code purchase_doc.file_path} 에 들어간다. 두 구현의 예외 계약은 같다.
 */
public interface ReceiptStorage {

	/**
	 * @return 저장 키
	 * @throws java.io.UncheckedIOException 저장하지 못하면
	 */
	String save(long storeId, long jobId, String mimeType, byte[] bytes);

	/**
	 * @throws java.nio.file.NoSuchFileException 원본이 없으면
	 * @throws IOException 그 밖에 읽지 못하면
	 */
	byte[] read(String key) throws IOException;

	/** 지우기. 실패해도 던지지 않는다(롤백 정리용). */
	void deleteQuietly(String key);

	static String newKey(long storeId, long jobId, String mimeType) {
		return storeId + "/" + jobId + "/" + UUID.randomUUID() + "." + extension(mimeType);
	}

	/** 저장한 키의 확장자로 형식을 되돌린다. */
	static String mimeTypeOf(String key) {
		String lower = key.toLowerCase();
		if (lower.endsWith(".png")) {
			return "image/png";
		}
		if (lower.endsWith(".pdf")) {
			return "application/pdf";
		}
		return "image/jpeg";
	}

	private static String extension(String mimeType) {
		return switch (mimeType) {
			case "image/png" -> "png";
			case "application/pdf" -> "pdf";
			default -> "jpg";
		};
	}
}
