package com.zigzaego.api.onboarding.exception;

import com.zigzaego.api.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum OnboardingErrorCode implements ErrorCode {

	APIFY_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "ONBOARDING_APIFY_NOT_CONFIGURED",
			"가게 검색 설정이 없습니다. 관리자에게 문의해 주세요."),
	APIFY_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "ONBOARDING_APIFY_UNAVAILABLE",
			"가게 정보를 가져오지 못했습니다. 잠시 뒤 다시 시도해 주세요.");

	private final HttpStatus status;
	private final String code;
	private final String message;
}
