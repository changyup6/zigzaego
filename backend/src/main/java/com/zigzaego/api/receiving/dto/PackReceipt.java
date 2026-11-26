package com.zigzaego.api.receiving.dto;

import com.zigzaego.api.global.web.Decimals;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

/**
 * DB 칸 단위의 입고 한 줄 — 받은 포장 개수({@code purchase_order_item.received_qty}, NUMERIC(14,3))와
 * 포장 1개 단가({@code received_unit_price} · {@code price_history.price}, 원 정수 #55, NUMERIC(12,2)).
 * 주소 경로는 {@link ReceiveRequest}(기준 단위)를 이 모양으로 바꿔 쓰고, 영수증 확정이 부르는
 * {@code ReceivingService.recordReceipt} 는 이 모양을 그대로 받는다.
 */
public record PackReceipt(
		@NotNull @Positive @Digits(integer = PackReceipt.PACKS_INTEGER_DIGITS, fraction = 3)
		BigDecimal receivedPacks,

		@PositiveOrZero
		@Digits(integer = PackReceipt.PRICE_INTEGER_DIGITS, fraction = 0, message = "금액은 원 단위 정수여야 합니다.")
		BigDecimal unitPricePerPack
) {

	/** 정수부 자릿수 한계. DB 칸 NUMERIC(14,3) · NUMERIC(12,2) 에 맞춘 값. */
	public static final int PACKS_INTEGER_DIGITS = 11;
	public static final int PRICE_INTEGER_DIGITS = 10;

	/** 끝자리 0 을 떼고 지수 표기를 펼친다 — {@link Decimals#normalize}, ReceiveRequest 와 같은 규칙. */
	public PackReceipt {
		receivedPacks = Decimals.normalize(receivedPacks, PACKS_INTEGER_DIGITS);
		unitPricePerPack = Decimals.normalize(unitPricePerPack, PRICE_INTEGER_DIGITS);
	}
}
