package com.zigzaego.api.llm;

import tools.jackson.databind.JsonNode;

/**
 * LLM 호출 한 번의 결과. 토큰 수는 응답 {@code usage} 에서 읽고, 없으면 0 (비용 · 시간 기록용, S15P31A308-229).
 *
 * @param output           스키마 모양의 JSON
 * @param promptTokens     입력 토큰
 * @param completionTokens 출력 토큰
 */
public record LlmResult(JsonNode output, long promptTokens, long completionTokens) {
}
