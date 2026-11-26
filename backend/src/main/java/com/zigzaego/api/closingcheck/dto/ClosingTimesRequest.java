package com.zigzaego.api.closingcheck.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** {@code PUT /stores/me/closing-times} — 요일별 마감 시각 전체를 바꾼다. 빠진 요일은 휴무(알림 없음). */
@Schema(description = "요일별 마감 시각 전체")
public record ClosingTimesRequest(
		@Schema(description = "영업하는 요일만. 같은 요일 두 번은 400. 빈 목록이면 전부 지운다(알림 없음)")
		@NotNull @Size(max = 7) List<@NotNull @Valid ClosingTimeDto> closingTimes
) {
}
