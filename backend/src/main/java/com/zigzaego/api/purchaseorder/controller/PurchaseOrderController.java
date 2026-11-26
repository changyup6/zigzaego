package com.zigzaego.api.purchaseorder.controller;

import com.zigzaego.api.global.response.ApiResponse;
import com.zigzaego.api.global.store.CurrentStoreProvider;
import com.zigzaego.api.purchaseorder.dto.PurchaseOrderCreateRequest;
import com.zigzaego.api.purchaseorder.dto.PurchaseOrderHistoryResponse;
import com.zigzaego.api.purchaseorder.dto.PurchaseOrderResponse;
import com.zigzaego.api.purchaseorder.service.PurchaseOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "PurchaseOrder", description = "발주서 — 승인 건을 거래처별로 묶고 주문 메시지를 만든다")
@RestController
@RequestMapping("/purchase-orders")
@RequiredArgsConstructor
public class PurchaseOrderController {

	private final PurchaseOrderService purchaseOrderService;
	private final CurrentStoreProvider currentStoreProvider;

	@Operation(
			summary = "발주 이력 월별 조회",
			description = "그 달(가게 시간대 1일 00:00 이상 · 다음 달 1일 00:00 미만)에 발주한 줄. 발주일 = 발주서를 보낸 시각, 없으면 만든 시각. "
					+ "보내지 않은 DRAFT 발주서는 넣지 않는다(#63). 발주일 내림차순, 같은 날이면 줄 id 내림차순, 페이지 없음. "
					+ "qty 는 재료 기준 단위(입고 확인한 줄은 받은 양), 포장 크기를 모르면 포장 수와 포장 이름. "
					+ "amount 는 원 정수 — 입고 확인한 줄은 받은 포장 수 × 받은 포장 단가, 아니면 주문 포장 수 × 발주서를 만들 때 저장한 줄 단가(옛 거래처 줄은 지금 상품 포장 가격), 모르면 null(쿠팡 줄). "
					+ "식봄 · 쿠팡 발주서 줄의 supplierName 은 '식봄' · '쿠팡'. "
					+ "totalAmount 는 CANCELLED 를 뺀 합, earliestMonth 는 이 가게의 가장 이른 발주 달(없으면 null).")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "그 달의 발주 이력. 없으면 lines 빈 배열")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
			description = "month 형식 틀림 · 없는 달 (COMMON_INVALID_INPUT)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@GetMapping("/history")
	public ApiResponse<PurchaseOrderHistoryResponse> history(
			@Parameter(description = "조회할 달 YYYY-MM. 생략하면 가게 시간대 이번 달", example = "2026-10")
			@RequestParam(required = false) String month) {
		return ApiResponse.ok(purchaseOrderService.history(currentStoreProvider.currentStoreId(), month));
	}

	@Operation(
			summary = "승인 건 → 거래처별 발주서 + 주문 메시지",
			description = "승인(APPROVED)한 추천을 거래처 상품(추천 상품 → 없으면 재료 대표 상품)의 거래처별로 묶어 발주서(DRAFT)를 "
					+ "거래처마다 한 장 만든다. 본문을 생략하면 가게 시간대 오늘의 승인 건 중 아직 발주서가 안 된 것 전부, "
					+ "recommendationIds 를 주면 그 추천만(하나라도 승인 전 · 이미 발주서면 409). "
					+ "수량은 최종 수량(기준 단위)을 포장 단위로 올린 값이다 — 대파 1,600g, 1단 = 1,000g 이면 '대파 2단'. "
					+ "메시지는 거래처 템플릿({가게} {배송일} {품목})으로 만들고, 템플릿이 없으면 기본 문구를 쓴다. "
					+ "입고 예정일 = 오늘 + 거래처 리드타임(모르면 1일). "
					+ "items 를 빈 목록으로 보내면 400. "
					+ "items 를 주면(recommendationIds 와 같이 못 줌) 추천마다 주문처 source(SUPPLIER · SIKBOM · COUPANG · MARKETBOM · ORDERLINK, 생략하면 SUPPLIER)를 고른다 — "
					+ "SUPPLIER 는 거래처별 한 장, SIKBOM · COUPANG 은 각각 한 장(supplierId · supplierName 이 null, 메시지는 줄마다 '품목 포장수 — 링크'). "
					+ "줄마다 그 순간의 포장 1개 단가 unitPrice 를 저장한다 — 거래처 = 상품 가격, 식봄 = 식봄 1위 단가 환산(서버 계산), 쿠팡 = null. "
					+ "발주 이력 금액은 이 단가 × 주문 포장 수라 나중에 가격이 바뀌어도 그대로다. "
					+ "만든 발주서는 보냄 기록(POST /purchase-orders/{id}/send) 뒤에 입고 예정량(onOrder)에 들어간다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
			description = "만든 발주서 목록(거래처 id 순). 만들 승인 건이 없으면 빈 목록")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
			description = "date 형식 틀림 · 없는 날짜 · 알 수 없는 source · items 와 recommendationIds 를 같이 줌 (COMMON_INVALID_INPUT), 같은 추천이 두 번 (PURCHASE_ORDER_RECOMMENDATION_DUPLICATE)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
			description = "recommendationIds 에 없거나 다른 가게의 추천 (COMMON_NOT_FOUND)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
			description = "승인 전 추천 (PURCHASE_ORDER_NOT_APPROVED) · 이미 발주서가 된 추천 (PURCHASE_ORDER_ALREADY_ORDERED) · "
					+ "거래처 상품이 없는 재료 (PURCHASE_ORDER_PRODUCT_UNSET) · "
					+ "SIKBOM 인데 식봄 상품이 없거나 재료 단위로 바꿀 수 없는 재료 (PURCHASE_ORDER_SOURCE_PRICE_UNSET)")
	@PostMapping("/from-recommendations")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<List<PurchaseOrderResponse>> createFromRecommendations(
			@Valid @RequestBody(required = false) PurchaseOrderCreateRequest request) {
		return ApiResponse.ok(purchaseOrderService.createFromRecommendations(currentStoreProvider.currentStoreId(), request));
	}

	@Operation(
			summary = "발주서 보냄 기록 (DRAFT → SENT)",
			description = "사장님이 주문 메시지를 복사 · 보냄 · 주문 완료로 실제 보냈을 때 부른다(DECISIONS #63). "
					+ "상태를 SENT 로, sentAt 을 지금 시각으로 남긴다. 보낸(SENT) 발주서의 줄만 입고 예정(GET /receiving incoming)과 "
					+ "추천의 입고 예정량(onOrder)에 들어간다. 이미 SENT 면 아무것도 바꾸지 않고 200(sentAt 은 처음 보낸 시각). 본문 없음.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "보낸 발주서 (status SENT · sentAt 채워짐)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
			description = "없거나 다른 가게의 발주서 · 숫자가 아닌 id (COMMON_NOT_FOUND)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
			description = "취소한 발주서 (PURCHASE_ORDER_CANCELLED)")
	@PostMapping("/{id}/send")
	public ApiResponse<PurchaseOrderResponse> send(
			@Parameter(description = "발주서 id", example = "5") @PathVariable String id) {
		return ApiResponse.ok(purchaseOrderService.send(currentStoreProvider.currentStoreId(), id));
	}
}
