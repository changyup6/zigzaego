package com.zigzaego.api.forecast.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "이번 주 매출 예측 (상권 유동인구 · 행사 기반). 예측이 없으면 pct · reason · analysis 는 null, days · factors 는 빈 목록")
public record SalesForecastResponse(
		@Schema(description = "평소 주 대비 %", example = "38", nullable = true) Integer pct,
		@Schema(description = "가장 큰 이유 한 줄", example = "일 11.15 잠실 마라톤", nullable = true) String reason,
		@Schema(description = "이번 주 기간", example = "11월 9일 ~ 15일") String range,
		List<Day> days,
		List<Factor> factors,
		@Schema(description = "설명 문장. 아직 없으면 null", nullable = true) String analysis) {

	public record Day(
			@Schema(description = "요일 또는 '오늘'", example = "월") String label,
			@Schema(description = "평소 100 기준 지수", example = "112") int value,
			@Schema(description = "오늘이면 true") boolean today) {
	}

	public record Factor(
			@Schema(example = "잠실 마라톤") String name,
			@Schema(example = "일 11.15 · 잠실종합운동장") String note,
			@Schema(description = "이번 주 증감에 보탠 %", example = "26") int pct) {
	}
}
