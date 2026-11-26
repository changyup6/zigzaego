package com.zigzaego.api.onboarding.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/** {@code PATCH /ingredients/{id}} 본문. */
@Schema(description = "재료 1인분 고치기")
public record PerServingRequest(
		@Schema(description = "새 1인분 (재료 단위). 0보다 커야 한다", example = "100")
		@NotNull @Positive
		BigDecimal perServing
) {
}
