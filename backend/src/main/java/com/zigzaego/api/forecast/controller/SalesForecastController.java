package com.zigzaego.api.forecast.controller;

import com.zigzaego.api.forecast.dto.SalesForecastResponse;
import com.zigzaego.api.forecast.service.SalesForecastService;
import com.zigzaego.api.global.response.ApiResponse;
import com.zigzaego.api.global.store.CurrentStoreProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "SalesForecast", description = "이번 주 매출 예측 (상권 유동인구 · 행사 기반)")
@RestController
@RequestMapping("/sales-forecast")
@RequiredArgsConstructor
public class SalesForecastController {

	private final SalesForecastService salesForecastService;
	private final CurrentStoreProvider currentStoreProvider;

	@Operation(
			summary = "이번 주 매출 예측",
			description = "로그인한 가게의 이번 주(가게 시간대 오늘이 든 월 ~ 일) 예측. AI 배치가 상권 유동인구 · 행사 예측을 "
					+ "가게 구 단위로 묶어 저장한 값을 그대로 준다. 저장된 예측이 없으면(서울 밖 · 배치 전) pct · reason 은 null, "
					+ "days · factors 는 빈 목록. days.value 는 평소 100 기준 지수, 오늘은 label '오늘' · today true.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "이번 주 매출 예측")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@GetMapping("/this-week")
	public ApiResponse<SalesForecastResponse> thisWeek() {
		return ApiResponse.ok(salesForecastService.thisWeek(currentStoreProvider.currentStoreId()));
	}
}
