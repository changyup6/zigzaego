package com.zigzaego.api.receiving.dto;

import com.zigzaego.api.global.web.Decimals;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

/**
 * 입고 확인 한 줄. 수량 · 단가는 재료 기준 단위(개 · g · ml)다 — DECISIONS #42(포장 단위로 바꿔 주지 않는다) · #57(단위 단가).
 * 서버가 DB 칸 단위({@link PackReceipt}: 포장 개수 · 포장 1개 원 정수)로 바꿔 쓴다.
 */
@Schema(description = "입고 확인 — 받은 양(기준 단위)과 단위 단가")
public record ReceiveRequest(
		@Schema(description = "받은 양, 재료 기준 단위(개 · g · ml). 0 보다 크고 소수 셋째 자리까지(#42)", example = "12000")
		@NotNull @Positive @Digits(integer = QTY_INTEGER_DIGITS, fraction = 3)
		BigDecimal receivedQty,

		@Schema(description = "단위 단가(원 / 기준 단위 1, 0 이상, 소수 넷째 자리까지 — #57). 0 이거나 생략하면 거래처 상품의 지금 단가",
				example = "3.9", nullable = true)
		@PositiveOrZero @Digits(integer = UNIT_PRICE_INTEGER_DIGITS, fraction = 4)
		BigDecimal unitPrice,

		@Schema(description = "포장 1개 = 기준 단위 몇. 거래처 상품의 포장 크기가 비어 있을 때만 필요하고(없으면 400 RECEIVING_UNIT_REQUIRED), "
				+ "받으면 거래처 상품에 채운다. 포장 크기가 이미 있으면 쓰지 않는다", example = "3000", nullable = true)
		@Positive @Digits(integer = QTY_INTEGER_DIGITS, fraction = 3)
		BigDecimal basePerPack
) {

	/** 정수부 자릿수 한계. 수량은 DB 수량 칸 NUMERIC(14,3), 단위 단가는 포장 1개 값 칸 NUMERIC(12,2) 의 정수부에 맞춘다. */
	static final int QTY_INTEGER_DIGITS = 11;
	static final int UNIT_PRICE_INTEGER_DIGITS = 10;

	/**
	 * 검사 전에 숫자 모양을 맞춘다 — 끝자리 0 을 떼고, 지수 표기로 scale 이 음수가 되면 0 으로(1E+3 → 1000).
	 * @Digits 는 끝자리 0 도 자릿수로 세서 4.0000 을 거절하기 때문이다. 숫자 값이 같으면 판정도 같다.
	 * 이 생성자는 예외를 던지지 않는다 — 던지면 Jackson 이 본문을 못 읽은 것(MALFORMED)으로 바꾼다.
	 */
	public ReceiveRequest {
		receivedQty = Decimals.normalize(receivedQty, QTY_INTEGER_DIGITS);
		unitPrice = Decimals.normalize(unitPrice, UNIT_PRICE_INTEGER_DIGITS);
		basePerPack = Decimals.normalize(basePerPack, QTY_INTEGER_DIGITS);
	}

}
