package com.zigzaego.api.closingcheck.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** 요일 하나의 마감 시각 (DECISIONS #88 ①, V17 store_closing_time). */
@Schema(description = "요일별 마감 시각")
public record ClosingTimeDto(
		@Schema(description = "요일. 월=1 … 일=7 (ISO). 종료가 자정을 넘으면 영업을 시작한 요일로", example = "1")
		@NotNull @Min(1) @Max(7) Integer dayOfWeek,

		@Schema(description = "영업 종료 시각 HH:mm (가게 시간대). 05:00 전이면 다음 날 새벽으로 본다", example = "22:00")
		@NotNull @Pattern(regexp = "([01]\\d|2[0-3]):[0-5]\\d") String closeTime
) {
}
