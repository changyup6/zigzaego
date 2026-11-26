package com.zigzaego.api.recommendation.exception;

import com.zigzaego.api.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum RecommendationErrorCode implements ErrorCode {

	/** PENDING → APPROVED | REJECTED 한 번만 (3주 계획 8절). 이미 결정한 추천을 다시 바꾸려면 새 추천. */
	ALREADY_DECIDED(HttpStatus.CONFLICT, "RECOMMENDATION_ALREADY_DECIDED", "이미 결정한 추천은 다시 바꿀 수 없습니다."),
	/** modify 는 바꿀 수량이 있어야 한다. */
	FINAL_QTY_REQUIRED(HttpStatus.BAD_REQUEST, "RECOMMENDATION_FINAL_QTY_REQUIRED", "수정할 수량을 보내 주세요.");

	private final HttpStatus status;
	private final String code;
	private final String message;
}
