package com.zigzaego.api.llm;

import com.zigzaego.api.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** GMS(LLM) 호출 실패. job 안에서 나면 코드가 그대로 {@code job.error} 가 된다. */
@Getter
@RequiredArgsConstructor
public enum LlmErrorCode implements ErrorCode {

	UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "LLM_UNAVAILABLE", "분석 설정이 없습니다. 관리자에게 문의해 주세요."),
	FAILED(HttpStatus.BAD_GATEWAY, "LLM_FAILED", "분석 서버가 응답하지 않습니다. 잠시 뒤 다시 시도해 주세요."),
	BAD_OUTPUT(HttpStatus.BAD_GATEWAY, "LLM_BAD_OUTPUT", "분석 결과를 읽지 못했습니다. 잠시 뒤 다시 시도해 주세요.");

	private final HttpStatus status;
	private final String code;
	private final String message;
}
