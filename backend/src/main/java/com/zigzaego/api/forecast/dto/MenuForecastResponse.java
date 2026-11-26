package com.zigzaego.api.forecast.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/** 메뉴 하나의 대상일 예측 판매량. 채널(배달 · 홀) 합산값. */
@Schema(description = "메뉴별 예측 판매량 한 줄")
public record MenuForecastResponse(
		@Schema(description = "메뉴 id (문자열)", example = "7")
		String menuId,

		@Schema(description = "메뉴 이름", example = "돈까스")
		String menuName,

		@Schema(description = "대상일 (YYYY-MM-DD)", example = "2026-10-10", type = "string", format = "date")
		String targetDate,

		@Schema(description = "예측 판매량(인분, 채널 합산, 소수 3자리 반올림). 이력이 없으면 0", example = "29.897")
		BigDecimal predictedQty,

		@Schema(description = "이 줄을 낸 모델", example = "DOW_AVG")
		ForecastModel model
) {
}
