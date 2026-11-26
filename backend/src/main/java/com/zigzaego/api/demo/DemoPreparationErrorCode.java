package com.zigzaego.api.demo;

import com.zigzaego.api.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum DemoPreparationErrorCode implements ErrorCode {
	DATA_PREPARING(HttpStatus.CONFLICT, "DEMO_DATA_PREPARING", "시연 자료를 준비하고 있습니다. 준비 상태를 확인해 주세요."),
	DATA_FAILED(HttpStatus.CONFLICT, "DEMO_DATA_FAILED", "시연 자료 준비에 실패했습니다. 준비 상태에서 재시도 조건을 확인해 주세요."),
	NOT_ELIGIBLE(HttpStatus.CONFLICT, "DEMO_NOT_ELIGIBLE", "시연 자료 준비 대상 가게가 아닙니다."),
	ONBOARDING_REQUIRED(HttpStatus.CONFLICT, "DEMO_ONBOARDING_REQUIRED", "메뉴와 기본 레시피 저장을 먼저 완료해 주세요.");

	private final HttpStatus status;
	private final String code;
	private final String message;
}
