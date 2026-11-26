package com.zigzaego.api.purchasedoc.ocr;

/**
 * 글자 인식에 넣을 파일 한 장.
 *
 * @param fileName 파일 이름(요청의 images[].name)
 * @param mimeType {@code image/jpeg} · {@code image/png} · {@code application/pdf}
 * @param bytes    원본 바이트
 */
public record OcrImage(String fileName, String mimeType, byte[] bytes) {

	/** CLOVA 의 images[].format 값. */
	public String format() {
		return switch (mimeType == null ? "" : mimeType.toLowerCase()) {
			case "image/png" -> "png";
			case "application/pdf" -> "pdf";
			default -> "jpg";
		};
	}
}
