package com.zigzaego.api.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/** {@code PATCH /stores/me}. 보낸 칸만 바꾼다. null 이거나 빠진 칸은 그대로 둔다. */
@Schema(description = "내 가게 고치기 — 보낸 칸만 바뀐다")
public record StoreUpdateRequest(
		@Schema(description = "가게가 있는 시군구. \"<시도> <시군구>\" 한글(예 \"서울 중구\" · \"경기 성남시\"). 빈 글자면 지운다",
				example = "서울 중구", nullable = true)
		@Size(max = 50)
		@Pattern(regexp = "^\\s*$|^\\s*[가-힣]+(\\s+[가-힣]+)?\\s*$", message = "시군구는 \"<시도> <시군구>\" 한글로 적습니다 (예: 서울 중구).")
		String sigungu,

		@Schema(description = "식자재몰 가격 비교(발주 화면 품목 상세의 최저가)를 보일지. null 이거나 빠지면 그대로",
				example = "false", nullable = true)
		@JsonDeserialize(using = StrictBoolean.class)
		Boolean priceCompare
) {

	/**
	 * JSON true · false 만 받는다. Jackson 기본은 {@code "true"} 문자열 · 숫자 1 도 불리언으로 바꿔 주므로
	 * {@code "yes"} · {@code 1} 같은 값이 그대로 저장되지 않게 본문을 못 읽은 것(400)으로 돌린다 (S15P31A308-205).
	 */
	static final class StrictBoolean extends ValueDeserializer<Boolean> {

		@Override
		public Boolean deserialize(JsonParser p, DeserializationContext ctxt) {
			JsonToken token = p.currentToken();
			if (token == JsonToken.VALUE_TRUE) {
				return Boolean.TRUE;
			}
			if (token == JsonToken.VALUE_FALSE) {
				return Boolean.FALSE;
			}
			return (Boolean) ctxt.handleUnexpectedToken(Boolean.class, p);
		}
	}
}
