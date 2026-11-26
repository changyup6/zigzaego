package com.zigzaego.api.forecast.controller;

import com.zigzaego.api.forecast.dto.MenuForecastResponse;
import com.zigzaego.api.forecast.service.ForecastService;
import com.zigzaego.api.global.response.ApiResponse;
import com.zigzaego.api.global.store.CurrentStoreProvider;
import com.zigzaego.api.global.web.RequestDates;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Forecast", description = "메뉴별 예측 판매량 (TimesFM 우선 · 요일 평균 대체)")
@RestController
@RequestMapping("/forecast")
@RequiredArgsConstructor
public class ForecastController {

	private final ForecastService forecastService;
	private final CurrentStoreProvider currentStoreProvider;

	@Operation(
			summary = "메뉴별 예측 판매량 조회",
			description = "로그인한 가게의 활성 메뉴를 메뉴 id 오름차순으로 돌려준다. "
					+ "메뉴마다 대상일 TimesFM 배치 값(forecast.model='timesfm')이 있으면 채널(배달 · 홀) 합산값(model=TIMESFM), "
					+ "없으면 판매 기록으로 채널별 최근 4주 같은 요일 평균(휴무일 제외)을 계산해 합산한다(model=DOW_AVG). "
					+ "채널 이력이 4주 미만이면 그 채널은 최근 14일 평균. 이력 끝은 대상일 전날과 어제 중 이른 날. "
					+ "판매 · 예측 기록이 없는 메뉴는 0. 수량은 인분, 소수 3자리. 저장하지 않는다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "메뉴별 예측 판매량 목록")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
			description = "date 형식 틀림 · 없는 날짜 (COMMON_INVALID_INPUT)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@GetMapping
	public ApiResponse<List<MenuForecastResponse>> forecast(
			@Parameter(description = "대상일 (YYYY-MM-DD). 없으면 가게 시간대의 오늘", example = "2026-10-10")
			@RequestParam(value = "date", required = false) String dateText) {
		return ApiResponse.ok(forecastService.forecast(currentStoreProvider.currentStoreId(),
				RequestDates.parseOrNull(dateText)));
	}
}
