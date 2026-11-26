package com.zigzaego.api.onboarding.controller;

import com.zigzaego.api.global.response.ApiResponse;
import com.zigzaego.api.global.store.CurrentStoreProvider;
import com.zigzaego.api.job.dto.JobResponse;
import com.zigzaego.api.onboarding.dto.OnboardingJobRequest;
import com.zigzaego.api.onboarding.service.OnboardingJobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "Onboarding", description = "가게 자동 세팅 (설계 2절)")
@RestController
@RequestMapping("/onboarding/jobs")
@RequiredArgsConstructor
public class OnboardingJobController {

	private final OnboardingJobService onboardingJobService;
	private final CurrentStoreProvider currentStoreProvider;

	@Operation(
			summary = "가게 분석 시작 — 메뉴 수집 · 재료 구성 추정 job",
			description = "job 을 QUEUED 로 만들고 바로 돌려준다. 화면은 GET /jobs/{id} 로 2초마다 진행률을 묻는다. "
					+ "수집이 끝나면(progress 0.3) result.menus 에 메뉴 이름이, DONE 이면 재료 개수 · 레시피 확인 질문이 온다. "
					+ "메뉴가 0개면 DONE 에 result.needsMenuPhoto=true. 실패하면 FAILED 와 error "
					+ "(APIFY_UNAVAILABLE · APIFY_NOT_CONFIGURED · LLM_UNAVAILABLE · LLM_FAILED · LLM_BAD_OUTPUT). "
					+ "다시 시도는 이 주소를 다시 부른다(새 job). placeId 가 있으면 그것을, 없으면 naverMapUrl 에서 플레이스 id 를 뽑는다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "만든 job (QUEUED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
			description = "placeId · naverMapUrl 둘 다 없음 (ONBOARDING_PLACE_REQUIRED) · 링크에서 가게를 못 찾음 "
					+ "(ONBOARDING_PLACE_URL_INVALID) · placeId 가 숫자가 아님 (COMMON_INVALID_INPUT)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<JobResponse> start(@RequestBody OnboardingJobRequest request) {
		return ApiResponse.ok(onboardingJobService.startFromPlace(currentStoreProvider.currentStoreId(), request));
	}

	@Operation(
			summary = "메뉴판 사진으로 분석 시작",
			description = "메뉴 수집이 0개일 때 화면이 띄우는 단계. 사진 한 장(이미지, 10MB 이하)을 LLM 에 직접 넣어 메뉴 · 구성을 뽑는 새 job. "
					+ "사진은 저장하지 않는다. 진행 · 결과 모양은 POST /onboarding/jobs 와 같다(메뉴 출처 MENU_PHOTO, 가격 없음).")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "만든 job (QUEUED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
			description = "파일 없음 (ONBOARDING_PHOTO_REQUIRED) · 이미지 아님 (ONBOARDING_PHOTO_NOT_IMAGE) · "
					+ "10MB 초과 (ONBOARDING_PHOTO_TOO_LARGE) · 서버 업로드 상한 20MB 초과 (COMMON_INVALID_INPUT)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@PostMapping(value = "/menu-photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ApiResponse<JobResponse> startFromMenuPhoto(
			@Parameter(description = "메뉴판 사진 (image/*, 10MB 이하)")
			@RequestPart(value = "file", required = false) MultipartFile file) {
		return ApiResponse.ok(onboardingJobService.startFromMenuPhoto(currentStoreProvider.currentStoreId(), file));
	}
}
