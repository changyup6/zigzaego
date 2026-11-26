package com.zigzaego.api.llm;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * JSON 스키마로 응답 모양을 강제하는 LLM 호출 한 번.
 *
 * @param system     시스템 지시
 * @param user       사용자 글
 * @param images     함께 넣을 이미지(없으면 빈 목록)
 * @param schemaName 스키마 이름 (response_format.json_schema.name)
 * @param schema     JSON 스키마 (strict)
 */
public record LlmRequest(String system, String user, List<LlmImage> images, String schemaName, JsonNode schema) {

	public LlmRequest {
		images = images == null ? List.of() : List.copyOf(images);
	}
}
