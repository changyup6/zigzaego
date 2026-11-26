package com.zigzaego.api.supplier.controller;

import com.zigzaego.api.global.response.ApiResponse;
import com.zigzaego.api.global.store.CurrentStoreProvider;
import com.zigzaego.api.supplier.dto.SupplierProductCreateRequest;
import com.zigzaego.api.supplier.dto.SupplierProductResponse;
import com.zigzaego.api.supplier.dto.SupplierProductUpdateRequest;
import com.zigzaego.api.supplier.service.SupplierProductService;
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

@Tag(name = "SupplierProduct", description = "거래처가 파는 상품 = 영수증 상품명 사전. 다른 가게 id · 없는 id · 숫자가 아닌 id 는 모두 404 COMMON_NOT_FOUND")
@RestController
@RequestMapping("/supplier-products")
@RequiredArgsConstructor
public class SupplierProductController {

	private final SupplierProductService supplierProductService;
	private final CurrentStoreProvider currentStoreProvider;

	@Operation(
			summary = "거래처 상품 목록",
			description = "로그인한 가게의 거래처 상품. 거래처 · 재료 · 상품명으로 좁힐 수 있고, 상품명은 앞뒤 공백을 빼고 똑같은 것만 "
					+ "(영수증 상품명 사전 조회). id 순.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "거래처 상품 목록")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "거래처 · 재료가 없거나 다른 가게 것 (COMMON_NOT_FOUND)")
	@GetMapping
	public ApiResponse<List<SupplierProductResponse>> list(
			@Parameter(description = "거래처 id", example = "4") @RequestParam(required = false) String supplierId,
			@Parameter(description = "재료 id", example = "3") @RequestParam(required = false) String ingredientId,
			@Parameter(description = "상품명 (똑같은 것만)", example = "대파 1단") @RequestParam(required = false) String productName) {
		return ApiResponse.ok(supplierProductService.listForStore(
				currentStoreProvider.currentStoreId(), supplierId, ingredientId, productName));
	}

	@Operation(summary = "거래처 상품 하나 조회", description = "로그인한 가게의 상품만 보인다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "거래처 상품")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없거나 다른 가게의 상품 (COMMON_NOT_FOUND)")
	@GetMapping("/{id}")
	public ApiResponse<SupplierProductResponse> get(
			@Parameter(description = "거래처 상품 id", example = "9") @PathVariable String id) {
		return ApiResponse.ok(supplierProductService.getForStore(currentStoreProvider.currentStoreId(), id));
	}

	@Operation(
			summary = "거래처 상품 만들기",
			description = "단가를 넣으면 단가 이력(MANUAL)에 한 줄. primary=true 면 그 재료의 대표 상품이 된다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "만든 거래처 상품")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력 오류 (COMMON_INVALID_INPUT)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "거래처 · 재료가 없거나 다른 가게 것 (COMMON_NOT_FOUND)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "그 거래처에 같은 상품명이 있음 (SUPPLIER_PRODUCT_DUPLICATE_NAME)")
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<SupplierProductResponse> create(@Valid @RequestBody SupplierProductCreateRequest request) {
		return ApiResponse.ok(supplierProductService.create(currentStoreProvider.currentStoreId(), request));
	}

	@Operation(
			summary = "거래처 상품 고치기",
			description = "보낸 칸만 바꾼다. 단가가 지금 값과 다르면 단가 이력(MANUAL)에 한 줄. 거래처 · 재료는 바꾸지 않는다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "고친 거래처 상품")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력 오류 (COMMON_INVALID_INPUT)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없거나 다른 가게의 상품 (COMMON_NOT_FOUND)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "그 거래처에 같은 상품명이 있음 (SUPPLIER_PRODUCT_DUPLICATE_NAME)")
	@PatchMapping("/{id}")
	public ApiResponse<SupplierProductResponse> update(
			@Parameter(description = "거래처 상품 id", example = "9") @PathVariable String id,
			@Valid @RequestBody SupplierProductUpdateRequest request) {
		return ApiResponse.ok(supplierProductService.update(currentStoreProvider.currentStoreId(), id, request));
	}

	@Operation(
			summary = "거래처 상품 삭제",
			description = "상품과 그 단가 이력을 지운다. 재료의 대표 상품이었으면 비운다. 추천 · 발주 · 매입 줄에 쓰인 상품은 409.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제함 (data=null)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없거나 다른 가게의 상품 (COMMON_NOT_FOUND)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "추천 · 발주 · 매입 기록이 있음 (SUPPLIER_PRODUCT_IN_USE)")
	@DeleteMapping("/{id}")
	public ApiResponse<Void> delete(@Parameter(description = "거래처 상품 id", example = "9") @PathVariable String id) {
		supplierProductService.delete(currentStoreProvider.currentStoreId(), id);
		return ApiResponse.ok();
	}
}
