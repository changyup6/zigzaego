package com.zigzaego.api.purchasedoc.exception;

import com.zigzaego.api.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 영수증 줄 확정의 입력 · 상태 오류 (S15P31A308-112). 이 오류면 요청의 어떤 줄도 저장하지 않는다.
 * 없는 줄 · 다른 가게 줄 · 재료는 존재를 드러내지 않게 404 COMMON_NOT_FOUND 로 답한다(ADR-0002).
 */
@Getter
@RequiredArgsConstructor
public enum ReceiptConfirmErrorCode implements ErrorCode {

	LINE_REQUIRED(HttpStatus.BAD_REQUEST, "RECEIPT_LINE_REQUIRED", "확정할 영수증 줄을 골라 주세요."),
	LINE_DUPLICATE(HttpStatus.BAD_REQUEST, "RECEIPT_LINE_DUPLICATE", "같은 영수증 줄이 두 번 들어 있습니다."),
	INGREDIENT_REQUIRED(HttpStatus.BAD_REQUEST, "RECEIPT_INGREDIENT_REQUIRED", "연결할 재료를 골라 주세요."),
	UNIT_REQUIRED(HttpStatus.BAD_REQUEST, "RECEIPT_UNIT_REQUIRED", "포장 1개가 재료 기준 단위로 얼마인지 알려 주세요."),
	UNIT_INVALID(HttpStatus.BAD_REQUEST, "RECEIPT_UNIT_INVALID", "포장 1개의 양은 0보다 크고 소수 셋째 자리까지만 넣을 수 있습니다."),
	PACK_CONFLICT(HttpStatus.BAD_REQUEST, "RECEIPT_PACK_CONFLICT", "같은 상품명 줄에 서로 다른 포장 1개의 양이 들어 있습니다."),
	DOC_REQUIRED(HttpStatus.BAD_REQUEST, "RECEIPT_DOC_REQUIRED", "확정할 영수증을 골라 주세요."),
	LINE_ALREADY_DECIDED(HttpStatus.CONFLICT, "RECEIPT_LINE_ALREADY_DECIDED", "이미 확정하거나 무시한 영수증 줄입니다."),
	DOC_NOT_PARSED(HttpStatus.CONFLICT, "RECEIPT_DOC_NOT_PARSED", "읽기가 끝나지 않았거나 이미 확정한 영수증입니다."),
	DATE_UNKNOWN(HttpStatus.CONFLICT, "RECEIPT_DATE_UNKNOWN", "날짜를 읽지 못한 영수증은 확정할 수 없습니다."),
	DOC_HAS_PENDING(HttpStatus.CONFLICT, "RECEIPT_DOC_HAS_PENDING", "아직 확인하지 않은 줄이 남은 영수증입니다."),
	/** 확인 목록 묶음(S15P31A308-113)이 없거나 이미 다 정해졌다. 다른 가게에만 있는 이름도 같다. */
	MATCH_NOT_FOUND(HttpStatus.NOT_FOUND, "RECEIPT_MATCH_NOT_FOUND", "확인할 상품 묶음을 찾을 수 없습니다.");

	private final HttpStatus status;
	private final String code;
	private final String message;
}
