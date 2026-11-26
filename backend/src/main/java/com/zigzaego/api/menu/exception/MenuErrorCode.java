package com.zigzaego.api.menu.exception;

import com.zigzaego.api.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum MenuErrorCode implements ErrorCode {

	/** 가게 안에서 메뉴 이름은 하나 (uq_menu_store_name). */
	DUPLICATE_NAME(HttpStatus.CONFLICT, "MENU_DUPLICATE_NAME", "같은 이름의 메뉴가 이미 있습니다."),
	/** 판매 · 예측 기록이나 세트 구성에 쓰인 메뉴는 지우지 않는다. 판매 중지(active=false)로 바꾼다. */
	IN_USE(HttpStatus.CONFLICT, "MENU_IN_USE", "판매 기록이 있는 메뉴는 삭제할 수 없습니다. 판매 중지로 바꿔 주세요.");

	private final HttpStatus status;
	private final String code;
	private final String message;
}
