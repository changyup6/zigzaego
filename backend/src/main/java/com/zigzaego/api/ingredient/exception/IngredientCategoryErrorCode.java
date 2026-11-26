package com.zigzaego.api.ingredient.exception;

import com.zigzaego.api.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** 재료 카테고리 CRUD 오류 (S15P31A308-275). 없거나 남의 가게 카테고리는 공통 404 COMMON_NOT_FOUND. */
@Getter
@RequiredArgsConstructor
public enum IngredientCategoryErrorCode implements ErrorCode {

	/** 가게 안에서 카테고리 이름은 하나 — 공백을 빼고 견준다 (uq_ingredient_category_store_name). */
	DUPLICATE_NAME(HttpStatus.CONFLICT, "INGREDIENT_CATEGORY_DUPLICATE_NAME", "같은 이름의 카테고리가 이미 있습니다."),
	/** '기타'(OTHER)는 가게마다 반드시 1개 — 이름을 바꾸거나 지우지 못한다 (DECISIONS #77). */
	OTHER_IMMUTABLE(HttpStatus.BAD_REQUEST, "INGREDIENT_CATEGORY_OTHER_IMMUTABLE", "'기타' 카테고리는 바꾸거나 지울 수 없습니다."),
	/** 가게당 카테고리는 기본 11종을 포함해 최대 30개. */
	LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "INGREDIENT_CATEGORY_LIMIT_EXCEEDED", "카테고리는 가게당 최대 30개까지 만들 수 있습니다.");

	private final HttpStatus status;
	private final String code;
	private final String message;
}
