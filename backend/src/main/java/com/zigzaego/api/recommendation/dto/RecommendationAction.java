package com.zigzaego.api.recommendation.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** 사장님의 결정. JSON 값은 소문자({@code approve} · {@code modify} · {@code reject}), 대소문자는 가리지 않는다. */
public enum RecommendationAction {

	/** 추천 수량 그대로 승인. finalQty 를 보내면 그 값으로. */
	APPROVE,
	/** 수량을 바꿔 승인 (finalQty 필수). 상태는 APPROVED. */
	MODIFY,
	/** 거절. reason 은 reject_reason 에 남긴다. */
	REJECT;

	@JsonValue
	public String jsonValue() {
		return name().toLowerCase(Locale.ROOT);
	}

	/** 모르는 값은 null → @NotNull 이 400 으로 돌린다. */
	@JsonCreator
	public static RecommendationAction fromJson(String value) {
		if (value == null) {
			return null;
		}
		for (RecommendationAction action : values()) {
			if (action.name().equalsIgnoreCase(value.trim())) {
				return action;
			}
		}
		return null;
	}
}
