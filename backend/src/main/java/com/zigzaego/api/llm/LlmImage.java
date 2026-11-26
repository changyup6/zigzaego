package com.zigzaego.api.llm;

/**
 * LLM 에 넣을 이미지 한 장. 저장하지 않고 요청 본문에 data URI 로만 넣는다 (설계 2.3).
 *
 * @param mimeType 예 {@code image/jpeg}
 * @param bytes    이미지 원본 바이트
 */
public record LlmImage(String mimeType, byte[] bytes) {
}
