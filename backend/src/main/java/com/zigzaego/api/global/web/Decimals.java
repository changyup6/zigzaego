package com.zigzaego.api.global.web;

import java.math.BigDecimal;

/** 요청 본문의 수량 · 단가 숫자를 검사하기 전에 모양을 맞춘다. 입고 확인(ReceiveRequest)과 재고 실사(StockCountRequest)가 같이 쓴다. */
public final class Decimals {

	/** 정규화할 숫자 자릿수 상한. Jackson 의 숫자 길이 상한(StreamReadConstraints 기본 1000)과 같은 값. */
	public static final int MAX_PRECISION = 1000;

	private Decimals() {
	}

	/**
	 * 끝자리 0 을 떼고, 지수 표기로 scale 이 음수가 되면 0 으로(1E+3 → 1000). @Digits 는 끝자리 0 도 자릿수로 세서
	 * 4.0000 을 거절하기 때문이다. 숫자 값이 같으면 판정도 같다. 예외를 던지지 않는다 — record 생성자에서 던지면
	 * Jackson 이 본문을 못 읽은 것(MALFORMED)으로 바꾼다.
	 *
	 * <p>정수부 자릿수(precision − scale)가 한계를 넘으면 펼치지 않고 그대로 둔다 — 어차피 @Digits 가 400 으로 거른다.
	 * 1E+99999999 를 setScale(0) 하면 1억 자리 정수를 만들어 요청이 수십 초 묶이고, 1E+999999999 는 BigInteger 넘침 예외가 난다.
	 * 정수부 자릿수는 끝자리 0 을 떼도 바뀌지 않아 가드를 먼저 건다. long 으로 빼서 int 넘침도 없다.
	 */
	public static BigDecimal normalize(BigDecimal value, int maxIntegerDigits) {
		if (value == null) {
			return null;
		}
		// 0 은 지수가 붙어도 0 (0E+10 · 0.00E+12). 0 의 precision 은 늘 1 이라 아래 정수부 자릿수 셈에 넣으면 안 된다
		if (value.signum() == 0) {
			return BigDecimal.ZERO;
		}
		// 1000자리가 넘는 숫자는 손대지 않는다 — 끝자리 0 떼기가 길이 제곱으로 느려진다. @Digits 가 400 으로 거른다
		if (value.precision() > MAX_PRECISION) {
			return value;
		}
		long integerDigits = (long) value.precision() - value.scale();
		if (integerDigits > maxIntegerDigits) {
			return value;
		}
		BigDecimal stripped = value.stripTrailingZeros();
		return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
	}
}
