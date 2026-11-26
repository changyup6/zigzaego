package com.zigzaego.api.job.service;

import lombok.Getter;

/**
 * 작업이 의도적으로 실패할 때 던진다. {@link #getCode()} 가 {@code job.error} 에 그대로 적혀 화면이 사유를 고른다
 * (예: {@code COLLECT_FAILED}). 코드는 UPPER_SNAKE_CASE.
 */
@Getter
public class JobFailedException extends RuntimeException {

	private final String code;

	public JobFailedException(String code) {
		super(code);
		this.code = code;
	}

	public JobFailedException(String code, Throwable cause) {
		super(code, cause);
		this.code = code;
	}
}
