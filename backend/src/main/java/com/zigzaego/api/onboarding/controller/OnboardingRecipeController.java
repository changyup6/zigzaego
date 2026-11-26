package com.zigzaego.api.onboarding.controller;

import com.zigzaego.api.global.response.ApiResponse;
import com.zigzaego.api.global.store.CurrentStoreProvider;
import com.zigzaego.api.onboarding.dto.PerServingRequest;
import com.zigzaego.api.onboarding.dto.RecipeAnswerRequest;
import com.zigzaego.api.onboarding.dto.RecipeBookResponse;
import com.zigzaego.api.onboarding.dto.RecipeQuestionResponse;
import com.zigzaego.api.onboarding.service.RecipeSetupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 레시피 확인 · 저장 (S15P31A308-96). 주소 · 모양은 FE {@code lib/mock/recipes.ts} 그대로.
 * 빈 이름은 레시피 CRUD 의 {@code recipe.controller.RecipeController} 와 겹치지 않게 따로 준다.
 */
@Tag(name = "Onboarding Recipe", description = "온보딩 레시피 확인 · 메뉴별 재료 1인분")
@RestController("onboardingRecipeController")
@RequiredArgsConstructor
public class OnboardingRecipeController {

	private final RecipeSetupService recipeSetupService;
	private final CurrentStoreProvider currentStoreProvider;

	@Operation(
			summary = "레시피 확인 질문",
			description = "이 가게의 가장 최근 DONE 온보딩 분석이 만든 질문(확신 낮은 재료의 1인분, 보기 3개). "
					+ "질문이 없으면 빈 목록. 끝난 분석이 없으면 404 ONBOARDING_JOB_NOT_FOUND.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "질문 목록")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "끝난 분석 없음 (ONBOARDING_JOB_NOT_FOUND)")
	@GetMapping("/onboarding/recipe-questions")
	public ApiResponse<List<RecipeQuestionResponse>> questions() {
		return ApiResponse.ok(recipeSetupService.questions(currentStoreProvider.currentStoreId()));
	}

	@Operation(
			summary = "레시피 확정 — 답을 적용해 저장",
			description = "가장 최근 DONE 분석의 추정 레시피에 답을 얹어 메뉴 · 재료 · 레시피를 저장하고 레시피를 돌려준다. "
					+ "답한 재료는 확인됨(estimated=false), 나머지는 추정값(estimated=true). 답 단위가 재료 단위와 다르면 재료 단위를 바꾼다. "
					+ "다시 불러도 행이 늘지 않는다(이름 · 메뉴×재료 기준 덮어쓰기). 한 트랜잭션.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "저장된 레시피")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
			description = "없는 질문 id (ONBOARDING_QUESTION_NOT_FOUND) · 0 이하 값(소수 셋째 자리에서 0 포함) · 단위 없음 (ONBOARDING_ANSWER_INVALID) · "
					+ "단위가 g · ml · 개 밖 (COMMON_MALFORMED_REQUEST)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "끝난 분석 없음 (ONBOARDING_JOB_NOT_FOUND)")
	@PostMapping("/onboarding/recipes/confirm")
	public ApiResponse<RecipeBookResponse> confirm(@RequestBody List<RecipeAnswerRequest> answers) {
		return ApiResponse.ok(recipeSetupService.confirm(currentStoreProvider.currentStoreId(), answers));
	}

	@Operation(
			summary = "레시피 확인 건너뛰기 — 추정값 그대로 저장",
			description = "답 없이 저장한다. 모든 재료가 추정값(estimated=true, 확인 필요)으로 남는다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "저장된 레시피")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "끝난 분석 없음 (ONBOARDING_JOB_NOT_FOUND)")
	@PostMapping("/onboarding/recipes/skip")
	public ApiResponse<RecipeBookResponse> skip() {
		return ApiResponse.ok(recipeSetupService.skip(currentStoreProvider.currentStoreId()));
	}

	@Operation(
			summary = "레시피 조회",
			description = "이 가게에 저장된 메뉴와 재료 1인분. 재료 1인분 = 그 재료 레시피 줄 중 가장 작은 양, 메뉴 줄 mult = 양 / 1인분. "
					+ "더블 메뉴에만 쓰인 재료는 1인분이 2배로 보인다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "레시피")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@GetMapping("/onboarding/recipes")
	public ApiResponse<RecipeBookResponse> book() {
		return ApiResponse.ok(recipeSetupService.book(currentStoreProvider.currentStoreId()));
	}

	@Operation(
			summary = "재료 1인분 고치기",
			description = "그 재료를 쓰는 모든 레시피 줄을 (새 1인분 / 옛 1인분) 비율로 고친다 — 더블 메뉴도 같은 비율. "
					+ "재료는 확인됨(estimated=false)이 된다. 다른 가게 재료 · 없는 id · 숫자가 아닌 id 는 404 COMMON_NOT_FOUND. "
					+ "재료 이름 · 단위 등 일반 수정은 PATCH /ingredients/{id}.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "고친 뒤 레시피")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "1인분이 없거나 0 이하 · 소수 셋째 자리에서 0 (COMMON_INVALID_INPUT)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없거나 다른 가게의 재료 (COMMON_NOT_FOUND)")
	@PatchMapping("/ingredients/{id}/serving")
	public ApiResponse<RecipeBookResponse> updatePerServing(
			@Parameter(description = "재료 id", example = "31") @PathVariable String id,
			@Valid @RequestBody PerServingRequest request) {
		return ApiResponse.ok(recipeSetupService.updatePerServing(currentStoreProvider.currentStoreId(), id,
				request.perServing()));
	}
}
