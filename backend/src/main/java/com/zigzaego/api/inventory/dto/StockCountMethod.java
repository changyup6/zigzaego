package com.zigzaego.api.inventory.dto;

/**
 * 실사값을 어떻게 얻었나. V1 {@code stock_count.method} CHECK 값과 같다.
 * 추정치 대 사장님 답이 쌓여야 레시피 역산 보정(설계 2절 온보딩 6단계)을 할 수 있어 구분해 둔다.
 */
public enum StockCountMethod {
	/** 화면의 추정치("이만큼 남은 거 맞나요?")를 맞다고 답함. {@code estimatedQty} 가 있어야 한다. */
	CONFIRMED,
	/** 추정치가 틀려 실제 양을 고쳐 적음. {@code estimatedQty} 가 있어야 한다. */
	CORRECTED,
	/** 추정치와 상관없이 직접 다 셈. 기본값. */
	FULL_COUNT
}
