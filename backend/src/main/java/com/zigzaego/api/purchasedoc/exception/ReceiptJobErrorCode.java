package com.zigzaego.api.purchasedoc.exception;

import com.zigzaego.api.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 영수증 업로드 job 을 만들 때의 입력 오류 (S15P31A308-111) · 한 장 다시 읽기 · 지우기의 상태 오류 (S15P31A308-113).
 * 이 오류면 job · 문서 · 파일을 만들거나 지우지 않는다.
 */
@Getter
@RequiredArgsConstructor
public enum ReceiptJobErrorCode implements ErrorCode {

	FILE_REQUIRED(HttpStatus.BAD_REQUEST, "RECEIPT_FILE_REQUIRED", "영수증 사진을 올려 주세요."),
	TOO_MANY_FILES(HttpStatus.BAD_REQUEST, "RECEIPT_TOO_MANY_FILES", "영수증은 한 번에 50장까지 올릴 수 있습니다."),
	FILE_TOO_LARGE(HttpStatus.BAD_REQUEST, "RECEIPT_FILE_TOO_LARGE", "영수증 파일은 한 장에 10MB 까지 올릴 수 있습니다."),
	FILE_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "RECEIPT_FILE_NOT_SUPPORTED", "JPG · PNG 사진이나 PDF 만 올릴 수 있습니다."),
	NOT_FAILED(HttpStatus.CONFLICT, "RECEIPT_NOT_FAILED", "실패한 영수증만 다시 읽을 수 있습니다."),
	ALREADY_CONFIRMED(HttpStatus.CONFLICT, "RECEIPT_ALREADY_CONFIRMED", "이미 확인한 줄이 있는 영수증은 지울 수 없습니다."),
	IN_PROGRESS(HttpStatus.CONFLICT, "RECEIPT_IN_PROGRESS", "아직 읽는 중인 영수증입니다."),
	/** CLOVA 키가 없어 가짜 글자 인식이 골라졌고 {@code app.receipt.allow-fake-ocr} 가 꺼져 있다 (S15P31A308-113). */
	OCR_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "RECEIPT_OCR_NOT_CONFIGURED", "영수증 인식 설정이 아직 없습니다.");

	private final HttpStatus status;
	private final String code;
	private final String message;
}
