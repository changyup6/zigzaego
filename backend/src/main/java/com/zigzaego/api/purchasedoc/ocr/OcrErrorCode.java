package com.zigzaego.api.purchasedoc.ocr;

import com.zigzaego.api.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 영수증 글자 인식(CLOVA OCR) 실패. 영수증 job 안에서 나면 코드가 그 장의 {@code purchase_doc.error} 가 된다. */
@Getter
@RequiredArgsConstructor
public enum OcrErrorCode implements ErrorCode {

	UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "OCR_UNAVAILABLE", "글자 인식 설정이 없습니다. 관리자에게 문의해 주세요."),
	FAILED(HttpStatus.BAD_GATEWAY, "OCR_FAILED", "글자 인식 서버가 응답하지 않습니다. 잠시 뒤 다시 시도해 주세요."),
	EMPTY(HttpStatus.BAD_REQUEST, "OCR_EMPTY", "사진에서 글자를 찾지 못했습니다. 다시 찍어 주세요.");

	private final HttpStatus status;
	private final String code;
	private final String message;
}
