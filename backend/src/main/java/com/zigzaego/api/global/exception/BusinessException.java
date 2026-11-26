package com.zigzaego.api.global.exception;

import lombok.Getter;

/**
 * 의도된 비즈니스 오류. 서비스에서 던지면 {@link GlobalExceptionHandler}가 공통 응답으로 바꾼다.
 */
@Getter
public class BusinessException extends RuntimeException {

	private final ErrorCode errorCode;

	public BusinessException(ErrorCode errorCode) {
		super(errorCode.getMessage());
		this.errorCode = errorCode;
	}

	public BusinessException(ErrorCode errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}
}
