package com.zigzaego.api.inventory.dto;

import com.zigzaego.api.global.web.Decimals;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 재고 실사 입력 — 재료마다 지금 실제로 남은 양 (S15P31A308-47, 3주 계획 4.2절, DECISIONS #7 · #9).
 * 수량은 재료 기준 단위(개 · g · ml) 그대로, 소수 셋째 자리까지 (#42). 포장 단위로 받지 않는다.
 */
@Schema(description = "재고 실사 입력 — 재료별 실제 남은 양")
public record StockCountRequest(
		@Schema(description = "실사한 재료들. 같은 재료를 두 번 넣으면 400. 한 번에 최대 " + MAX_LINES + "줄")
		@NotEmpty @Size(max = MAX_LINES) List<@NotNull @Valid Line> counts,

		@Schema(description = "실사 시각 (ISO-8601, 시간대 포함). 생략하면 지금 — 보통 생략한다. 미래(2분 허용)거나 그 재료의 기존 최신 실사보다 이르면 400. "
						+ "이 시각이 추정 재고의 기준점이자 영수증 PAST · RECEIVE 판정 기준일이 된다. 실사는 영업 시작 전 값으로 본다(실사한 날의 판매량부터 뺀다)",
				example = "2026-10-14T09:30:00+09:00", nullable = true)
		OffsetDateTime countedAt
) {

	/** 핵심 재료 10~15개가 보통이라 넉넉히. 한 요청이 가게 재료 전부를 넘지 않게 막는 상한. */
	public static final int MAX_LINES = 200;

	/** 정수부 자릿수 한계. DB 수량 칸 NUMERIC(14,3) 의 정수부. */
	static final int QTY_INTEGER_DIGITS = 11;

	/** 실사 한 줄. */
	@Schema(description = "재료 하나의 실사값")
	public record Line(
			@Schema(description = "재료 id (문자열). 다른 가게 · 없는 · 숫자 아닌 id 는 404", example = "12")
			@NotBlank String ingredientId,

			@Schema(description = "실제 남은 양, 재료 기준 단위(개 · g · ml). 0 이상 소수 셋째 자리까지(#42). 0 = 다 썼다. 주기형(CYCLE) 재료는 400", example = "4250")
			@NotNull @PositiveOrZero @Digits(integer = QTY_INTEGER_DIGITS, fraction = 3)
			BigDecimal qty,

			@Schema(description = "실사 때 화면에 보여 준 추정 재고. 직접 다 셌으면(FULL_COUNT) 생략. CONFIRMED · CORRECTED 면 필수",
					example = "4000", nullable = true)
			@PositiveOrZero @Digits(integer = QTY_INTEGER_DIGITS, fraction = 3)
			BigDecimal estimatedQty,

			@Schema(description = "실사값을 얻은 방법. 생략하면 FULL_COUNT", example = "FULL_COUNT", nullable = true)
			StockCountMethod method
	) {

		/** 검사 전에 숫자 모양을 맞춘다 (끝자리 0 · 지수 표기). 예외를 던지지 않는다. */
		public Line {
			qty = Decimals.normalize(qty, QTY_INTEGER_DIGITS);
			estimatedQty = Decimals.normalize(estimatedQty, QTY_INTEGER_DIGITS);
		}

		/** 생략은 FULL_COUNT. */
		public StockCountMethod methodOrDefault() {
			return method == null ? StockCountMethod.FULL_COUNT : method;
		}
	}
}
