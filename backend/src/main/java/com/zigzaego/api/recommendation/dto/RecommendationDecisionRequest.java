package com.zigzaego.api.recommendation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** PATCH /recommendations/{id} 본문. 상태 전이는 PENDING → APPROVED | REJECTED 한 번만. */
@Schema(description = "추천 승인 · 수정 · 거절")
public record RecommendationDecisionRequest(
		@Schema(description = "approve=추천 수량대로 승인 / modify=수량을 바꿔 승인(finalQty 필수) / reject=거절",
				example = "approve", allowableValues = {"approve", "modify", "reject"})
		@NotNull(message = "action 은 approve · modify · reject 중 하나입니다.")
		RecommendationAction action,

		@Schema(description = "최종 수량(재료 기준 단위, 소수 3자리). approve 면 생략 시 추천 수량, modify 면 필수, reject 면 무시",
				example = "4000.000", nullable = true)
		@Positive @Digits(integer = 11, fraction = 3)
		BigDecimal finalQty,

		@Schema(description = "거절 사유. reject 일 때만 저장", example = "재고가 충분함", nullable = true)
		@Size(max = 500)
		String reason
) {
}
