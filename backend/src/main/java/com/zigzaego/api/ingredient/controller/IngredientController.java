package com.zigzaego.api.ingredient.controller;

import com.zigzaego.api.global.response.ApiResponse;
import com.zigzaego.api.global.store.CurrentStoreProvider;
import com.zigzaego.api.ingredient.dto.IngredientCreateRequest;
import com.zigzaego.api.ingredient.dto.IngredientResponse;
import com.zigzaego.api.ingredient.dto.IngredientUpdateRequest;
import com.zigzaego.api.ingredient.service.IngredientService;
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

@Tag(name = "Ingredient", description = "가게의 재료. 다른 가게 재료 · 없는 id · 숫자가 아닌 id 는 모두 404 COMMON_NOT_FOUND")
@RestController
@RequestMapping("/ingredients")
@RequiredArgsConstructor
public class IngredientController {

	private final IngredientService ingredientService;
	private final CurrentStoreProvider currentStoreProvider;

	@Operation(
			summary = "재료 목록",
			description = "로그인한 가게의 재료 전부. 확인 필요(needsReview=true) 재료가 맨 위, 그 안에서 id 순. "
					+ "이유 코드: UNANSWERED=AI 추정 레시피 줄에 쓰임 / NEEDS_COUNT=실사 기록이 한 번도 없음.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "재료 목록")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@GetMapping
	public ApiResponse<List<IngredientResponse>> list() {
		return ApiResponse.ok(ingredientService.listForStore(currentStoreProvider.currentStoreId()));
	}

	@Operation(summary = "재료 하나 조회", description = "로그인한 가게의 재료만 보인다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "재료")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없거나 다른 가게의 재료 (COMMON_NOT_FOUND)")
	@GetMapping("/{id}")
	public ApiResponse<IngredientResponse> get(
			@Parameter(description = "재료 id", example = "3") @PathVariable String id) {
		return ApiResponse.ok(ingredientService.getForStore(currentStoreProvider.currentStoreId(), id));
	}

	@Operation(
			summary = "재료 만들기",
			description = "사장님이 정한 단위라 unitStatus=CONFIRMED. 주기형(CYCLE)은 영수증으로만 생겨 400. "
					+ "safetyDays 기본 1, sourcingType 기본 GENERIC, priceSensitive 기본은 냉동 · 상온 true / 냉장 · 신선 false.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "만든 재료")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
			description = "입력 오류 (COMMON_INVALID_INPUT) · 주기형 (INGREDIENT_CYCLE_FROM_RECEIPT_ONLY)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "같은 이름의 재료가 있음 (INGREDIENT_DUPLICATE_NAME)")
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<IngredientResponse> create(@Valid @RequestBody IngredientCreateRequest request) {
		return ApiResponse.ok(ingredientService.create(currentStoreProvider.currentStoreId(), request));
	}

	@Operation(
			summary = "재료 고치기",
			description = "보낸 칸만 바꾼다. null 이거나 빠진 칸은 그대로. unit 을 보내면 unitStatus=CONFIRMED — "
					+ "LOCKED(입고 기록 있음) 재료를 다른 단위로 바꾸면 409. 주기형 → BOM · 1:1 이동은 여기서만, "
					+ "다른 종류 → 주기형은 400. storageLocation 만 보내면 보관 위치만 바뀐다(보관 방식은 그대로).")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "고친 재료")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
			description = "입력 오류 (COMMON_INVALID_INPUT) · 없는 enum 값 (COMMON_MALFORMED_REQUEST) · 주기형으로 이동 (INGREDIENT_CYCLE_FROM_RECEIPT_ONLY) · "
					+ "보관 방식 없음 (INGREDIENT_STORAGE_TYPE_REQUIRED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없거나 다른 가게의 재료 (COMMON_NOT_FOUND)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
			description = "단위 고정 (INGREDIENT_UNIT_LOCKED) · 같은 이름 (INGREDIENT_DUPLICATE_NAME)")
	@PatchMapping("/{id}")
	public ApiResponse<IngredientResponse> update(
			@Parameter(description = "재료 id", example = "3") @PathVariable String id,
			@Valid @RequestBody IngredientUpdateRequest request) {
		return ApiResponse.ok(ingredientService.update(currentStoreProvider.currentStoreId(), id, request));
	}

	@Operation(
			summary = "재료 삭제",
			description = "재료와 그 재료가 들어간 레시피 줄을 지운다. 거래처 상품 · 실사 · 추천 · 발주 · 매입 기록이 있으면 409.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제함 (data=null)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없거나 다른 가게의 재료 (COMMON_NOT_FOUND)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "기록이 있는 재료 (INGREDIENT_IN_USE)")
	@DeleteMapping("/{id}")
	public ApiResponse<Void> delete(@Parameter(description = "재료 id", example = "3") @PathVariable String id) {
		ingredientService.delete(currentStoreProvider.currentStoreId(), id);
		return ApiResponse.ok();
	}
}
