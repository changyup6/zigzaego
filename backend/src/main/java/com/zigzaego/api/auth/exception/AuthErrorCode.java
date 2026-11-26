package com.zigzaego.api.auth.exception;

import com.zigzaego.api.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements ErrorCode {

	/** 없는 이메일과 틀린 비밀번호를 구분하지 않는다 — 계정이 있는지 알려주지 않는다. */
	INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다."),
	/** 없거나 이미 쓴(rotation) · 만료 · 로그아웃된 refresh token. FE는 로그인 화면으로 보낸다. */
	INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_REFRESH_TOKEN", "다시 로그인해 주세요."),
	/** access token 만료. FE는 이 코드를 받으면 /auth/refresh 를 부른다. */
	TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "AUTH_TOKEN_EXPIRED", "인증이 만료되었습니다."),
	INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_TOKEN", "인증 정보가 올바르지 않습니다."),
	NO_STORE(HttpStatus.FORBIDDEN, "AUTH_NO_STORE", "연결된 가게가 없습니다."),
	/** 가입하려는 이메일로 이미 계정이 있다. */
	EMAIL_TAKEN(HttpStatus.CONFLICT, "AUTH_EMAIL_TAKEN", "이미 가입된 이메일입니다.");

	private final HttpStatus status;
	private final String code;
	private final String message;
}
