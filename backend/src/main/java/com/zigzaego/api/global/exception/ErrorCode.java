package com.zigzaego.api.global.exception;

import org.springframework.http.HttpStatus;

/**
 * 오류 코드 계약. 도메인별로 이 인터페이스를 구현한 enum을 만든다.
 * 코드는 {@code <도메인>_<사유>} 형태의 UPPER_SNAKE_CASE (예: {@code RECOMMENDATION_ALREADY_DECIDED}).
 */
public interface ErrorCode {

	HttpStatus getStatus();

	String getCode();

	String getMessage();
}
