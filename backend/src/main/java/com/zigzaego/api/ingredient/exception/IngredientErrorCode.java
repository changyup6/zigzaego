package com.zigzaego.api.ingredient.exception;

import com.zigzaego.api.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum IngredientErrorCode implements ErrorCode {

	/** 가게 안에서 재료 이름은 하나 (uq_ingredient_store_name). */
	DUPLICATE_NAME(HttpStatus.CONFLICT, "INGREDIENT_DUPLICATE_NAME", "같은 이름의 재료가 이미 있습니다."),
	/** unit_status=LOCKED — 그 단위로 입고 기록이 생겨 단위를 바꿀 수 없다 (설계 3.1절). */
	UNIT_LOCKED(HttpStatus.CONFLICT, "INGREDIENT_UNIT_LOCKED", "입고 기록이 있는 재료는 단위를 바꿀 수 없습니다."),
	/** 주기형은 영수증으로만 생긴다 (설계 4.1절). 만들거나 다른 종류에서 주기형으로 옮길 수 없다. */
	CYCLE_FROM_RECEIPT_ONLY(HttpStatus.BAD_REQUEST, "INGREDIENT_CYCLE_FROM_RECEIPT_ONLY", "주기형 재료는 영수증으로만 생깁니다."),
	/** BOM · 1:1 은 보관 방식이 있어야 한다 (ck_ingredient_storage_type_required). */
	STORAGE_TYPE_REQUIRED(HttpStatus.BAD_REQUEST, "INGREDIENT_STORAGE_TYPE_REQUIRED", "보관 방식을 정해 주세요."),
	/** 거래처 상품 · 실사 · 추천 · 발주 · 매입 · 단가 기록이 있는 재료는 지우지 않는다. */
	IN_USE(HttpStatus.CONFLICT, "INGREDIENT_IN_USE", "거래 · 재고 기록이 있는 재료는 삭제할 수 없습니다.");

	private final HttpStatus status;
	private final String code;
	private final String message;
}
