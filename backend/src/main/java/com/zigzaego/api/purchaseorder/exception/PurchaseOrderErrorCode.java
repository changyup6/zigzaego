package com.zigzaego.api.purchaseorder.exception;

import com.zigzaego.api.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 승인 건 → 발주서 만들기(지라 S15P31A308-70)와 보냄 기록(S15P31A308-200)의 입력 · 상태 오류. 이 오류면 아무것도 바꾸지 않는다.
 * 없는 추천 · 다른 가게 추천은 존재를 드러내지 않게 404 COMMON_NOT_FOUND 로 답한다(ADR-0002).
 */
@Getter
@RequiredArgsConstructor
public enum PurchaseOrderErrorCode implements ErrorCode {

	/** recommendationIds 에 같은 추천이 두 번. */
	RECOMMENDATION_DUPLICATE(HttpStatus.BAD_REQUEST, "PURCHASE_ORDER_RECOMMENDATION_DUPLICATE",
			"같은 추천이 두 번 들어 있습니다."),
	/** 거래처 상품(추천 상품 · 재료 대표 상품)이 없는 재료는 어느 거래처에 보낼지 모른다. */
	PRODUCT_UNSET(HttpStatus.CONFLICT, "PURCHASE_ORDER_PRODUCT_UNSET",
			"거래처 상품이 없는 재료가 있습니다. 재료의 대표 거래처 상품을 먼저 정해 주세요."),
	/** 승인(APPROVED)한 추천만 발주서가 된다. PENDING · REJECTED 는 안 된다. */
	NOT_APPROVED(HttpStatus.CONFLICT, "PURCHASE_ORDER_NOT_APPROVED", "승인한 추천만 발주서로 만들 수 있습니다."),
	/** 같은 추천을 두 번 주문으로 만들 수 없다 (uq_po_item_recommendation). */
	ALREADY_ORDERED(HttpStatus.CONFLICT, "PURCHASE_ORDER_ALREADY_ORDERED", "이미 발주서로 만든 추천입니다."),
	/** 취소(CANCELLED)한 발주서는 보냄 기록을 할 수 없다 (S15P31A308-200). */
	CANCELLED(HttpStatus.CONFLICT, "PURCHASE_ORDER_CANCELLED", "취소한 발주서는 보냄 기록을 할 수 없습니다."),
	/** 식봄으로 보낼 줄의 가격을 정할 수 없다 — 식봄 상품이 없거나 재료 단위로 바꿀 수 없다 (S15P31A308-201). */
	SOURCE_PRICE_UNSET(HttpStatus.CONFLICT, "PURCHASE_ORDER_SOURCE_PRICE_UNSET",
			"식봄 가격을 정할 수 없는 재료가 있습니다. 다른 주문처를 고르거나 거래처로 주문해 주세요.");

	private final HttpStatus status;
	private final String code;
	private final String message;
}
