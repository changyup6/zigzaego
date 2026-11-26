package com.zigzaego.api.forecast.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 예측 한 줄을 낸 모델. {@code forecast.model} 칼럼 값과 1:1 (DECISIONS #24).
 *
 * <ul>
 *   <li>TIMESFM: 야간 배치가 저장한 대상일 {@code forecast(model='timesfm')} 행을 채널 합산한 값</li>
 *   <li>DOW_AVG: 대상일 timesfm 행이 없어 판매 기록으로 계산한 요일 평균(채널별 계산 후 합산)</li>
 * </ul>
 */
@Schema(description = "예측을 낸 모델", enumAsRef = true)
public enum ForecastModel {
	TIMESFM("timesfm"),
	DOW_AVG("dow_avg");

	private final String columnValue;

	ForecastModel(String columnValue) {
		this.columnValue = columnValue;
	}

	/** {@code forecast.model} 칼럼에 들어가는 값. */
	public String columnValue() {
		return columnValue;
	}
}
