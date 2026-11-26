package com.zigzaego.api.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 체크리스트 단계 하나. */
@Schema(description = "가게 설정 체크리스트 단계 하나")
public record SetupStepResponse(
		@Schema(description = "단계", example = "RECIPE")
		SetupStep step,

		@Schema(description = "끝났는지", example = "false")
		boolean done,

		@Schema(description = "남은 개수. RECIPE 는 답하지 않은 질문 수, 나머지 단계는 끝났으면 0 · 아니면 1", example = "2")
		int remaining
) {

	/** 있으면 끝나는 단계. 남은 개수는 0 또는 1. */
	public static SetupStepResponse ofFlag(SetupStep step, boolean done) {
		return new SetupStepResponse(step, done, done ? 0 : 1);
	}
}
