package com.zigzaego.api.recipe.controller;

import com.zigzaego.api.global.response.ApiResponse;
import com.zigzaego.api.global.store.CurrentStoreProvider;
import com.zigzaego.api.recipe.dto.RecipeCreateRequest;
import com.zigzaego.api.recipe.dto.RecipeResponse;
import com.zigzaego.api.recipe.dto.RecipeUpdateRequest;
import com.zigzaego.api.recipe.service.RecipeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Recipe", description = "메뉴 1인분에 드는 재료. 다른 가게 메뉴 · 재료 · 레시피 줄 id 는 모두 404 COMMON_NOT_FOUND")
@RestController
@RequestMapping("/recipes")
@RequiredArgsConstructor
public class RecipeController {

	private final RecipeService recipeService;
	private final CurrentStoreProvider currentStoreProvider;

	@Operation(
			summary = "레시피 줄 목록",
			description = "menuId 를 주면 그 메뉴의 줄만, 없으면 가게 전체. 확인 필요(AI 추정 — needsReview=true, UNANSWERED) 줄이 맨 위.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "레시피 줄 목록")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없거나 다른 가게의 메뉴 (COMMON_NOT_FOUND)")
	@GetMapping
	public ApiResponse<List<RecipeResponse>> list(
			@Parameter(description = "메뉴 id", example = "7") @RequestParam(required = false) String menuId) {
		return ApiResponse.ok(recipeService.listForStore(currentStoreProvider.currentStoreId(), menuId));
	}

	@Operation(summary = "레시피 줄 하나 조회")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "레시피 줄")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없거나 다른 가게의 줄 (COMMON_NOT_FOUND)")
	@GetMapping("/{id}")
	public ApiResponse<RecipeResponse> get(
			@Parameter(description = "레시피 줄 id", example = "12") @PathVariable String id) {
		return ApiResponse.ok(recipeService.getForStore(currentStoreProvider.currentStoreId(), id));
	}

	@Operation(summary = "레시피 줄 만들기", description = "사장님 입력(source=MANUAL)으로 만든다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "만든 줄")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력 오류 (COMMON_INVALID_INPUT)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없거나 다른 가게의 메뉴 · 재료 (COMMON_NOT_FOUND)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
			description = "이미 들어간 재료 (RECIPE_DUPLICATE) · 주기형 재료 (RECIPE_CYCLE_INGREDIENT)")
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<RecipeResponse> create(@Valid @RequestBody RecipeCreateRequest request) {
		return ApiResponse.ok(recipeService.create(currentStoreProvider.currentStoreId(), request));
	}

	@Operation(
			summary = "레시피 줄 고치기 · 확인하기",
			description = "양을 바꾼다. AI 추정 줄은 OWNER_CONFIRMED, 그 밖의 줄은 MANUAL 이 되고 확인 필요가 풀린다. "
					+ "추정값을 그대로 보내면 확인만 한다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "고친 줄")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력 오류 (COMMON_INVALID_INPUT)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없거나 다른 가게의 줄 (COMMON_NOT_FOUND)")
	@PatchMapping("/{id}")
	public ApiResponse<RecipeResponse> update(
			@Parameter(description = "레시피 줄 id", example = "12") @PathVariable String id,
			@Valid @RequestBody RecipeUpdateRequest request) {
		return ApiResponse.ok(recipeService.update(currentStoreProvider.currentStoreId(), id, request));
	}

	@Operation(summary = "레시피 줄 삭제")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제함 (data=null)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없거나 다른 가게의 줄 (COMMON_NOT_FOUND)")
	@DeleteMapping("/{id}")
	public ApiResponse<Void> delete(@Parameter(description = "레시피 줄 id", example = "12") @PathVariable String id) {
		recipeService.delete(currentStoreProvider.currentStoreId(), id);
		return ApiResponse.ok();
	}
}
