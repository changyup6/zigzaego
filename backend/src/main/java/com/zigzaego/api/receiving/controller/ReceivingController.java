package com.zigzaego.api.receiving.controller;

import com.zigzaego.api.global.response.ApiResponse;
import com.zigzaego.api.global.store.CurrentStoreProvider;
import com.zigzaego.api.receiving.dto.ReceiveRequest;
import com.zigzaego.api.receiving.dto.ReceivingFeedResponse;
import com.zigzaego.api.receiving.service.ReceivingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Receiving", description = "입고 탭: 입고 예정 · 최근 발주 피드와 발주 줄 입고 확인 (S15P31A308-71). 수량은 재료 기준 단위(#42), 단가는 단위 단가 원 / 기준 단위 1(#57). "
		+ "다른 가게 id · 없는 id · 숫자가 아닌 id 는 모두 404 COMMON_NOT_FOUND")
@RestController
@RequiredArgsConstructor
public class ReceivingController {

	private final ReceivingService receivingService;
	private final CurrentStoreProvider currentStoreProvider;

	@Operation(
			summary = "입고 탭 피드",
			description = "입고 예정 줄(보낸(SENT) 발주서 — 보냄 기록 POST /purchase-orders/{id}/send 뒤 · 줄 안 옴 처리 안 됨 · 아직 입고 확인 안 됨), 최근 14일 발주 줄(최대 50줄, 새 발주부터), "
					+ "첫 입고 여부.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "입고 탭 피드")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@GetMapping("/receiving")
	public ApiResponse<ReceivingFeedResponse> feed() {
		return ApiResponse.ok(receivingService.feed(currentStoreProvider.currentStoreId()));
	}

	@Operation(
			summary = "발주 줄 입고 확인",
			description = "받은 양(기준 단위) · 단위 단가로 입고 확인. 서버가 포장 개수(받은 양 ÷ 포장 크기, 소수 셋째 자리) · "
					+ "포장 1개 값(단위 단가 × 포장 크기, 원 정수)으로 바꿔 기록한다. 단가 0 · 생략이면 거래처 상품 단가. "
					+ "포장 크기를 모르는 상품은 basePerPack 이 있어야 하고 받으면 상품에 채운다. 단가 이력(RECEIVE)에 한 줄, "
					+ "그 재료의 첫 입고면 단위가 LOCKED. 한 번만 할 수 있다. 응답은 바뀐 피드 전체(GET /receiving 과 같은 모양).")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "바뀐 입고 탭 피드")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "receivedQty ≤ 0 · 소수 넷째 자리 이상, unitPrice 음수 · 소수 다섯째 자리 이상, "
			+ "포장 개수가 0 으로 반올림됨 · 포장 1개 값이 0원 또는 칸을 넘음 (COMMON_INVALID_INPUT), "
			+ "포장 크기를 모르는데 basePerPack 없음 (RECEIVING_UNIT_REQUIRED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없거나 다른 가게의 발주서 · 줄, 줄이 그 발주서 것이 아님 (COMMON_NOT_FOUND)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 입고 확인한 줄 (RECEIVING_ALREADY_RECEIVED), 취소된 줄 · 발주서 (RECEIVING_CANCELLED)")
	@PatchMapping("/purchase-orders/{orderId}/items/{itemId}/receive")
	public ApiResponse<ReceivingFeedResponse> receive(
			@Parameter(description = "발주서 id", example = "31") @PathVariable String orderId,
			@Parameter(description = "발주 줄 id", example = "77") @PathVariable String itemId,
			@Valid @RequestBody ReceiveRequest request) {
		return ApiResponse.ok(receivingService.receive(currentStoreProvider.currentStoreId(), orderId, itemId, request));
	}
}
