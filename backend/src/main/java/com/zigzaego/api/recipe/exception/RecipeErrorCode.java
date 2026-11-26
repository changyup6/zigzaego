package com.zigzaego.api.recipe.exception;

import com.zigzaego.api.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum RecipeErrorCode implements ErrorCode {

	/** 한 메뉴에 같은 재료는 한 줄 (uq_recipe_menu_ingredient). 양을 바꾸려면 그 줄을 고친다. */
	DUPLICATE(HttpStatus.CONFLICT, "RECIPE_DUPLICATE", "이 메뉴에 이미 들어간 재료입니다."),
	/** 주기형은 메뉴별 판매와 연결하지 않는다 (설계 4.1절). 레시피에 넣으려면 먼저 재료를 BOM 으로 옮긴다. */
	CYCLE_INGREDIENT(HttpStatus.CONFLICT, "RECIPE_CYCLE_INGREDIENT", "주기형 재료는 레시피에 넣을 수 없습니다.");

	private final HttpStatus status;
	private final String code;
	private final String message;
}
