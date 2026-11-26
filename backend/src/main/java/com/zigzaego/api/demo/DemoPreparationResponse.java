package com.zigzaego.api.demo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "로그인 가게의 시연 준비. eligible=false는 일반 가게이며 기존 빈 상태 계약을 사용한다.")
public record DemoPreparationResponse(
		boolean eligible,
		@Schema(allowableValues = {"NOT_STARTED", "PREPARING", "DATA_READY", "READY", "FAILED"}) String status,
		@Schema(allowableValues = {"ATTACH", "RECOMMENDATION"}, nullable = true) String phase,
		int attempt,
		@Schema(nullable = true, description = "비밀/원본 예외를 포함하지 않는 안정적인 오류 코드") String errorCode,
		boolean retryable,
		@Schema(nullable = true) OffsetDateTime updatedAt) {

	static DemoPreparationResponse notStarted(boolean eligible) {
		return new DemoPreparationResponse(eligible, "NOT_STARTED", null, 0, null, false, null);
	}
}
