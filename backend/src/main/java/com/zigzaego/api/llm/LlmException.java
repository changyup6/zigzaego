package com.zigzaego.api.llm;

import com.zigzaego.api.global.exception.BusinessException;

/** GMS 호출이 재시도까지 다 실패했을 때. 코드는 {@link LlmErrorCode}. */
public class LlmException extends BusinessException {

	public LlmException(LlmErrorCode errorCode) {
		super(errorCode);
	}

	public LlmException(LlmErrorCode errorCode, String message) {
		super(errorCode, message);
	}
}
