package com.zigzaego.api.purchasedoc.ocr;

import tools.jackson.databind.JsonNode;

/**
 * 글자 인식 결과 한 장.
 *
 * @param text LLM 정형화에 넣을 글자 덩어리(표 밖 글자는 줄, 표는 행마다 탭 구분 줄)
 * @param raw  OCR 응답 원문. {@code purchase_doc.ocr_json} 에 그대로 남긴다
 */
public record OcrResult(String text, JsonNode raw) {
}
