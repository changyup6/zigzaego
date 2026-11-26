package com.zigzaego.api.global.response;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 응답 직전 숫자 모양 맞추기. 금액은 DECISIONS #55, 수량은 #42. */
public final class Amounts {

	private Amounts() {
	}

	/** 금액은 원 단위 정수. 다른 경로로 소수가 들어와 있어도 반올림해 정수로 준다. null 은 null. */
	public static Long won(BigDecimal amount) {
		return amount == null ? null : amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
	}

	/** 수량은 소수 셋째 자리까지 (5 이상 올림). null 은 null. */
	public static BigDecimal qty(BigDecimal quantity) {
		return quantity == null ? null : quantity.setScale(3, RoundingMode.HALF_UP);
	}
}
