package com.zigzaego.api.menu.controller;

import com.zigzaego.api.global.response.ApiResponse;
import com.zigzaego.api.global.store.CurrentStoreProvider;
import com.zigzaego.api.menu.dto.MenuCreateRequest;
import com.zigzaego.api.menu.dto.MenuResponse;
import com.zigzaego.api.menu.dto.MenuUpdateRequest;
import com.zigzaego.api.menu.service.MenuService;
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

@Tag(name = "Menu", description = "가게의 메뉴. 다른 가게 메뉴 · 없는 id · 숫자가 아닌 id 는 모두 404 COMMON_NOT_FOUND")
@RestController
@RequestMapping("/menus")
@RequiredArgsConstructor
public class MenuController {

	private final MenuService menuService;
	private final CurrentStoreProvider currentStoreProvider;

	@Operation(
			summary = "메뉴 목록",
			description = "로그인한 가게의 메뉴 전부. 확인 필요(needsReview=true — AI 추정 레시피 줄이 남음) 메뉴가 맨 위, 그 안에서 id 순.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "메뉴 목록")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@GetMapping
	public ApiResponse<List<MenuResponse>> list() {
		return ApiResponse.ok(menuService.listForStore(currentStoreProvider.currentStoreId()));
	}

	@Operation(summary = "메뉴 하나 조회", description = "로그인한 가게의 메뉴만 보인다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "메뉴")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없거나 다른 가게의 메뉴 (COMMON_NOT_FOUND)")
	@GetMapping("/{id}")
	public ApiResponse<MenuResponse> get(
			@Parameter(description = "메뉴 id", example = "7") @PathVariable String id) {
		return ApiResponse.ok(menuService.getForStore(currentStoreProvider.currentStoreId(), id));
	}

	@Operation(summary = "메뉴 만들기", description = "판매 중(active=true)으로 만든다. kind 를 빼면 MAIN.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "만든 메뉴")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력 오류 (COMMON_INVALID_INPUT)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "같은 이름의 메뉴가 있음 (MENU_DUPLICATE_NAME)")
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<MenuResponse> create(@Valid @RequestBody MenuCreateRequest request) {
		return ApiResponse.ok(menuService.create(currentStoreProvider.currentStoreId(), request));
	}

	@Operation(summary = "메뉴 고치기", description = "보낸 칸만 바꾼다. null 이거나 빠진 칸은 그대로.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "고친 메뉴")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력 오류 (COMMON_INVALID_INPUT)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없거나 다른 가게의 메뉴 (COMMON_NOT_FOUND)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "같은 이름의 메뉴가 있음 (MENU_DUPLICATE_NAME)")
	@PatchMapping("/{id}")
	public ApiResponse<MenuResponse> update(
			@Parameter(description = "메뉴 id", example = "7") @PathVariable String id,
			@Valid @RequestBody MenuUpdateRequest request) {
		return ApiResponse.ok(menuService.update(currentStoreProvider.currentStoreId(), id, request));
	}

	@Operation(
			summary = "메뉴 삭제",
			description = "메뉴와 그 레시피 줄 · 포스 이름 연결을 지운다. 판매 · 예측 기록이나 세트 구성에 쓰인 메뉴는 409 — "
					+ "active=false 로 판매 중지한다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제함 (data=null)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없거나 다른 가게의 메뉴 (COMMON_NOT_FOUND)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "판매 기록 등에 쓰인 메뉴 (MENU_IN_USE)")
	@DeleteMapping("/{id}")
	public ApiResponse<Void> delete(@Parameter(description = "메뉴 id", example = "7") @PathVariable String id) {
		menuService.delete(currentStoreProvider.currentStoreId(), id);
		return ApiResponse.ok();
	}
}
