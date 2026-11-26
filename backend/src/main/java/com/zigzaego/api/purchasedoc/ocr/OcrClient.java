package com.zigzaego.api.purchasedoc.ocr;

/**
 * 영수증 사진 → 글자 (설계 4.2절, DECISIONS #32). 구현은 {@link ClovaOcrClient}(CLOVA General OCR) 와
 * 키가 없을 때 쓰는 {@link FakeOcrClient}. 어느 쪽을 쓸지는 {@link OcrClientConfig} 가 설정값으로 고른다.
 */
public interface OcrClient {

	/**
	 * @throws OcrException {@code OCR_UNAVAILABLE} · {@code OCR_FAILED} · {@code OCR_EMPTY}
	 */
	OcrResult recognize(OcrImage image);
}
