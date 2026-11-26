package com.zigzaego.api.receiving.exception;

import com.zigzaego.api.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ReceivingErrorCode implements ErrorCode {

	/** 입고 확인은 한 번만. 고쳐 입력은 다음 일감 (입고 확인 API 제안 4절 4번 제안값). */
	ALREADY_RECEIVED(HttpStatus.CONFLICT, "RECEIVING_ALREADY_RECEIVED", "이미 입고 확인한 줄입니다."),
	/** 안 옴 처리된 줄이나 취소된 발주서의 줄은 입고 예정이 아니다 (DECISIONS #40). */
	CANCELLED(HttpStatus.CONFLICT, "RECEIVING_CANCELLED", "취소된 발주 줄은 입고 확인할 수 없습니다."),
	/** 거래처 상품의 포장 크기(base_qty_per_pack)가 비어 있는데 요청에 basePerPack 이 없다 — 기준 단위 양을 포장 개수로 못 바꾼다. */
	UNIT_REQUIRED(HttpStatus.BAD_REQUEST, "RECEIVING_UNIT_REQUIRED", "포장 1개가 재료 기준 단위로 얼마인지(basePerPack) 알려 주세요.");

	private final HttpStatus status;
	private final String code;
	private final String message;
}
