package com.zigzaego.api.ingredient.controller;

import com.zigzaego.api.global.response.ApiResponse;
import com.zigzaego.api.global.store.CurrentStoreProvider;
import com.zigzaego.api.ingredient.dto.AutoAssignResultResponse;
import com.zigzaego.api.ingredient.dto.IngredientCategoryCreateRequest;
import com.zigzaego.api.ingredient.dto.IngredientCategoryResponse;
import com.zigzaego.api.ingredient.dto.IngredientCategoryUpdateRequest;
import com.zigzaego.api.ingredient.service.IngredientCategoryClassifier;
import com.zigzaego.api.ingredient.service.IngredientCategoryService;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 재료 카테고리 (S15P31A308-274 · 275). 재료에 카테고리를 붙이는 것은 {@code PATCH /ingredients/{id}} 의 categoryId. */
@Tag(name = "IngredientCategory", description = "가게의 재료 카테고리. 재료 탭의 필터 · 묶음 표시용. 다른 가게 · 없는 · 숫자 아닌 id 는 모두 404 COMMON_NOT_FOUND")
@RestController
@RequestMapping("/ingredient-categories")
@RequiredArgsConstructor
public class IngredientCategoryController {

	private final IngredientCategoryService categoryService;
	private final IngredientCategoryClassifier classifier;
	private final CurrentStoreProvider currentStoreProvider;

	@Operation(summary = "카테고리 목록", description = "로그인한 가게의 카테고리 전부(sortOrder → id 순)와 각각의 재료 수. 기본 11종은 code 가 있고 사장님이 만든 것은 null.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "카테고리 목록")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@GetMapping
	public ApiResponse<List<IngredientCategoryResponse>> list() {
		return ApiResponse.ok(categoryService.list(currentStoreProvider.currentStoreId()));
	}

	@Operation(summary = "카테고리 만들기", description = "이름은 가게 안에서 하나(공백을 빼고 견준다). 가게당 최대 30개. 순서는 기본 10종 뒤 · 기타 앞.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "만든 카테고리")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
			description = "입력 오류 (COMMON_INVALID_INPUT) · 30개 넘침 (INGREDIENT_CATEGORY_LIMIT_EXCEEDED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "같은 이름 (INGREDIENT_CATEGORY_DUPLICATE_NAME)")
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<IngredientCategoryResponse> create(@Valid @RequestBody IngredientCategoryCreateRequest request) {
		return ApiResponse.ok(categoryService.create(currentStoreProvider.currentStoreId(), request));
	}

	@Operation(summary = "카테고리 고치기", description = "보낸 칸만 바꾼다. '기타'는 이름을 바꿀 수 없고 순서도 99 고정.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "고친 카테고리")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
			description = "입력 오류 (COMMON_INVALID_INPUT) · 기타 이름 변경 (INGREDIENT_CATEGORY_OTHER_IMMUTABLE)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없거나 다른 가게의 카테고리 (COMMON_NOT_FOUND)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "같은 이름 (INGREDIENT_CATEGORY_DUPLICATE_NAME)")
	@PatchMapping("/{id}")
	public ApiResponse<IngredientCategoryResponse> update(
			@Parameter(description = "카테고리 id", example = "12") @PathVariable String id,
			@Valid @RequestBody IngredientCategoryUpdateRequest request) {
		return ApiResponse.ok(categoryService.update(currentStoreProvider.currentStoreId(), id, request));
	}

	@Operation(summary = "카테고리 삭제", description = "그 카테고리의 재료를 전부 '기타'로 옮기고 지운다(한 트랜잭션). '기타'는 지울 수 없다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제함 (data=null)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "기타 삭제 (INGREDIENT_CATEGORY_OTHER_IMMUTABLE)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없거나 다른 가게의 카테고리 (COMMON_NOT_FOUND)")
	@DeleteMapping("/{id}")
	public ApiResponse<Void> delete(@Parameter(description = "카테고리 id", example = "12") @PathVariable String id) {
		categoryService.delete(currentStoreProvider.currentStoreId(), id);
		return ApiResponse.ok();
	}

	@Operation(
			summary = "기존 재료 일괄 자동 분류",
			description = "로그인한 가게에서 자동 분류로 '기타'에 놓인 재료만 다시 분류한다 — 백업 파일 → 재료표 · 품목군 표 매핑 → LLM → 기타 순. "
					+ "사장님이 직접 고른 카테고리는 건드리지 않는다. LLM 이 안 되면 분류되지 않은 재료는 기타로 남고 요청은 성공한다. "
					+ "assignedCount = 이번에 기타에서 옮긴 수, otherCount = 그 뒤에도 기타인 수.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "옮긴 수 · 남은 기타 수")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@PostMapping("/auto-assign")
	public ApiResponse<AutoAssignResultResponse> autoAssign() {
		return ApiResponse.ok(classifier.autoAssign(currentStoreProvider.currentStoreId()));
	}
}
