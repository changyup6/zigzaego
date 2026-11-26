package com.zigzaego.api.price.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 단위 단가 · 가격 지수의 응답 자릿수. 금액은 원 정수(DECISIONS #55)지만 g 당 4.25원처럼 원 미만이 생기는
 * 단위 단가는 예외로 소수 넷째 자리까지 준다(DECISIONS #57). 다섯째 자리에서 반올림(5 이상 올림).
 */
public final class UnitPrices {

	public static final int SCALE = 4;

	private UnitPrices() {
	}

	/** null 은 null. */
	public static BigDecimal round(BigDecimal value) {
		return value == null ? null : value.setScale(SCALE, RoundingMode.HALF_UP);
	}
}
