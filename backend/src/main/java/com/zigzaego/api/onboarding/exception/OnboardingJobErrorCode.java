package com.zigzaego.api.onboarding.exception;

import com.zigzaego.api.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 온보딩 분석 job · 레시피 확정 오류 (S15P31A308-95 · 96). 가게 검색 · 수집 오류는 {@link OnboardingErrorCode}. */
@Getter
@RequiredArgsConstructor
public enum OnboardingJobErrorCode implements ErrorCode {

	PLACE_REQUIRED(HttpStatus.BAD_REQUEST, "ONBOARDING_PLACE_REQUIRED", "가게를 골라 주세요."),
	PLACE_URL_INVALID(HttpStatus.BAD_REQUEST, "ONBOARDING_PLACE_URL_INVALID", "네이버 지도 가게 링크를 확인해 주세요."),
	PHOTO_REQUIRED(HttpStatus.BAD_REQUEST, "ONBOARDING_PHOTO_REQUIRED", "메뉴판 사진을 올려 주세요."),
	PHOTO_NOT_IMAGE(HttpStatus.BAD_REQUEST, "ONBOARDING_PHOTO_NOT_IMAGE", "사진 파일만 올릴 수 있습니다."),
	PHOTO_TOO_LARGE(HttpStatus.BAD_REQUEST, "ONBOARDING_PHOTO_TOO_LARGE", "사진은 10MB 까지 올릴 수 있습니다."),
	JOB_NOT_FOUND(HttpStatus.NOT_FOUND, "ONBOARDING_JOB_NOT_FOUND", "끝난 가게 분석이 없습니다. 가게 찾기부터 다시 해 주세요."),
	QUESTION_NOT_FOUND(HttpStatus.BAD_REQUEST, "ONBOARDING_QUESTION_NOT_FOUND", "없는 질문에 대한 답이 있습니다."),
	ANSWER_INVALID(HttpStatus.BAD_REQUEST, "ONBOARDING_ANSWER_INVALID", "1인분은 0보다 커야 합니다.");

	private final HttpStatus status;
	private final String code;
	private final String message;
}
