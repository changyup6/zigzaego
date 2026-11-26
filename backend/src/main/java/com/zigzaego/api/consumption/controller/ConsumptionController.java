package com.zigzaego.api.consumption.controller;

import com.zigzaego.api.consumption.dto.DailyConsumptionResponse;
import com.zigzaego.api.consumption.service.ConsumptionService;
import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.exception.CommonErrorCode;
import com.zigzaego.api.global.response.ApiResponse;
import com.zigzaego.api.global.store.CurrentStoreProvider;
import com.zigzaego.api.global.web.RequestDates;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Consumption", description = "판매 기록으로 계산한 재료별 소비량")
@RestController
@RequestMapping("/consumption")
@RequiredArgsConstructor
public class ConsumptionController {

	/** 한 번에 조회할 수 있는 최대 일수 (from · to 포함). */
	static final long MAX_DAYS = 92;

	private final ConsumptionService consumptionService;
	private final CurrentStoreProvider currentStoreProvider;

	@Operation(
			summary = "재료별 일 소비량 조회",
			description = "로그인한 가게의 기간(from · to 포함) 판매 × 레시피로 재료별 하루 소비량을 계산한다. "
					+ "채널(배달 · 홀) 합산, 세트는 구성 메뉴로 한 단계 풀고 세트 자기 레시피도 센다. "
					+ "유료 옵션 메뉴도 레시피로 센다. 주기형(CYCLE) 재료와 소비량 0 인 줄은 빠진다. "
					+ "단위는 재료 기준 단위(EA · G · ML) 그대로. 날짜 오름차순 → 재료 id 오름차순. 판매가 없으면 [].")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "날짜 × 재료별 소비량 목록")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
			description = "from · to 빠짐, 날짜 형식 틀림, from > to, 기간 92일 초과 (COMMON_INVALID_INPUT)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@GetMapping("/daily")
	public ApiResponse<List<DailyConsumptionResponse>> daily(
			@Parameter(description = "시작일 (포함, YYYY-MM-DD)", example = "2026-10-01", required = true)
			@RequestParam("from") String fromText,
			@Parameter(description = "종료일 (포함, YYYY-MM-DD). 기간은 최대 92일", example = "2026-10-07", required = true)
			@RequestParam("to") String toText) {
		LocalDate from = RequestDates.parseOrNull(fromText);
		LocalDate to = RequestDates.parseOrNull(toText);
		if (from.isAfter(to)) {
			throw new BusinessException(CommonErrorCode.INVALID_INPUT, "from 은 to 보다 늦을 수 없습니다.");
		}
		if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
			throw new BusinessException(CommonErrorCode.INVALID_INPUT, "기간은 최대 " + MAX_DAYS + "일입니다.");
		}
		return ApiResponse.ok(consumptionService.daily(currentStoreProvider.currentStoreId(), from, to));
	}
}
