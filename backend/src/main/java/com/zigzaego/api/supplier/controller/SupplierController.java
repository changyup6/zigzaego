package com.zigzaego.api.supplier.controller;

import com.zigzaego.api.global.response.ApiResponse;
import com.zigzaego.api.global.store.CurrentStoreProvider;
import com.zigzaego.api.supplier.dto.SupplierCreateRequest;
import com.zigzaego.api.supplier.dto.SupplierResponse;
import com.zigzaego.api.supplier.dto.SupplierUpdateRequest;
import com.zigzaego.api.supplier.service.SupplierService;
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

@Tag(name = "Supplier", description = "가게의 거래처. 다른 가게 거래처 · 없는 id · 숫자가 아닌 id 는 모두 404 COMMON_NOT_FOUND")
@RestController
@RequestMapping("/suppliers")
@RequiredArgsConstructor
public class SupplierController {

	private final SupplierService supplierService;
	private final CurrentStoreProvider currentStoreProvider;

	@Operation(summary = "거래처 목록", description = "로그인한 가게의 거래처 전부. id 순.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "거래처 목록")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@GetMapping
	public ApiResponse<List<SupplierResponse>> list() {
		return ApiResponse.ok(supplierService.listForStore(currentStoreProvider.currentStoreId()));
	}

	@Operation(summary = "거래처 하나 조회", description = "로그인한 가게의 거래처만 보인다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "거래처")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없거나 다른 가게의 거래처 (COMMON_NOT_FOUND)")
	@GetMapping("/{id}")
	public ApiResponse<SupplierResponse> get(
			@Parameter(description = "거래처 id", example = "4") @PathVariable String id) {
		return ApiResponse.ok(supplierService.getForStore(currentStoreProvider.currentStoreId(), id));
	}

	@Operation(summary = "거래처 만들기", description = "이름 말고는 비워도 된다. 모르는 값은 null 로 두고 나중에 고친다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "만든 거래처")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력 오류 (COMMON_INVALID_INPUT · COMMON_MALFORMED_REQUEST)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "같은 이름의 거래처가 있음 (SUPPLIER_DUPLICATE_NAME)")
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<SupplierResponse> create(@Valid @RequestBody SupplierCreateRequest request) {
		return ApiResponse.ok(supplierService.create(currentStoreProvider.currentStoreId(), request));
	}

	@Operation(summary = "거래처 고치기", description = "보낸 칸만 바꾼다. null 이거나 빠진 칸은 그대로. deliveryDays 를 빈 목록으로 보내면 비운다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "고친 거래처")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력 오류 (COMMON_INVALID_INPUT · COMMON_MALFORMED_REQUEST)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없거나 다른 가게의 거래처 (COMMON_NOT_FOUND)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "같은 이름의 거래처가 있음 (SUPPLIER_DUPLICATE_NAME)")
	@PatchMapping("/{id}")
	public ApiResponse<SupplierResponse> update(
			@Parameter(description = "거래처 id", example = "4") @PathVariable String id,
			@Valid @RequestBody SupplierUpdateRequest request) {
		return ApiResponse.ok(supplierService.update(currentStoreProvider.currentStoreId(), id, request));
	}

	@Operation(summary = "거래처 삭제", description = "거래처 상품 · 발주서 · 매입 문서가 달린 거래처는 409 — 상품을 먼저 지운다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제함 (data=null)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없거나 다른 가게의 거래처 (COMMON_NOT_FOUND)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "상품 · 발주 · 매입 기록이 있음 (SUPPLIER_IN_USE)")
	@DeleteMapping("/{id}")
	public ApiResponse<Void> delete(@Parameter(description = "거래처 id", example = "4") @PathVariable String id) {
		supplierService.delete(currentStoreProvider.currentStoreId(), id);
		return ApiResponse.ok();
	}
}
