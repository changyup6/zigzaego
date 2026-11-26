package com.zigzaego.api.onboarding.controller;

import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.exception.CommonErrorCode;
import com.zigzaego.api.global.response.ApiResponse;
import com.zigzaego.api.onboarding.dto.PlaceCandidateResponse;
import com.zigzaego.api.onboarding.replay.OnboardingPlaceSource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Onboarding", description = "가게 자동 세팅 (설계 2절)")
@RestController
@RequestMapping("/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

	private static final int MAX_QUERY_LENGTH = 50;

	private final OnboardingPlaceSource placeSource;

	@Operation(
			summary = "가게 찾기 — 상호명으로 네이버 플레이스 후보 검색",
			description = "후보 최대 5곳. 0곳이면 빈 목록(화면은 링크 입력을 펼친다). 가게 데이터를 읽지 않는다. "
					+ "Apify 호출이라 10초 남짓 걸리고, 실패해도 재시도하지 않는다 — 화면의 '다시 시도'로 다시 부른다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "후보 목록 (0~5곳)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "검색어가 비었거나 50자 초과 (COMMON_INVALID_INPUT)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "Apify 호출 실패 (ONBOARDING_APIFY_UNAVAILABLE)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "Apify 토큰 미설정 (ONBOARDING_APIFY_NOT_CONFIGURED)")
	@GetMapping("/places")
	public ApiResponse<List<PlaceCandidateResponse>> searchPlaces(
			@Parameter(description = "상호명", example = "위글리 파스타볼") @RequestParam String query) {
		String trimmed = query.strip();
		if (trimmed.isEmpty() || trimmed.length() > MAX_QUERY_LENGTH) {
			throw new BusinessException(CommonErrorCode.INVALID_INPUT, "검색어는 1~50자로 입력해 주세요.");
		}
		return ApiResponse.ok(placeSource.searchPlaces(trimmed));
	}
}
