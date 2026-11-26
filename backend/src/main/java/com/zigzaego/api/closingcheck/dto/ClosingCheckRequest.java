package com.zigzaego.api.closingcheck.dto;

import com.zigzaego.api.global.web.Decimals;
import com.zigzaego.api.inventory.dto.StockCountRequest;
import com.zigzaego.api.inventory.dto.StockCountRequest.Line;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * 마감 확인 저장 — 실사(남은 양)와 빼기 기록(버린 양)을 한 번에 (S15P31A308-252, DECISIONS #88).
 * 수량은 전부 재료 기준 단위(개 · g · ml), 소수 셋째 자리까지(#42). 포장 단위 환산은 화면 · 해석 API 가 끝낸 값이다.
 */
@Schema(description = "마감 확인 저장 — 실사와 빼기 기록")
public record ClosingCheckRequest(
		@Schema(description = "남은 양(실사). 기존 실사 입력과 같은 줄 모양. method 를 생략하면 FULL_COUNT, estimatedQty 를 생략하면 서버의 지금 추정 재고를 "
				+ "넣는다. 같은 재료 두 번은 400. 생략 가능", nullable = true)
		@Size(max = StockCountRequest.MAX_LINES) List<@NotNull @Valid Line> counts,

		@Schema(description = "버린 양(빼기 기록). 같은 재료가 여러 줄이어도 된다. 생략 가능", nullable = true)
		@Size(max = StockCountRequest.MAX_LINES) List<@NotNull @Valid Adjustment> adjustments,

		@Schema(description = "자유 입력 원문(글 또는 음성을 글로 바꾼 것). 빼기 기록마다 source_text 로 남긴다", nullable = true,
				example = "양파 반 망 버렸고 우유 한 팩 상했어")
		@Size(max = SOURCE_TEXT_MAX) String sourceText
) {

	public static final int SOURCE_TEXT_MAX = 2000;
	public static final int MEMO_MAX = 500;
	private static final int QTY_INTEGER_DIGITS = 11;

	public List<Line> countsOrEmpty() {
		return counts == null ? List.of() : counts;
	}

	public List<Adjustment> adjustmentsOrEmpty() {
		return adjustments == null ? List.of() : adjustments;
	}

	/** 빼기 한 줄. */
	@Schema(description = "버린 재료 하나")
	public record Adjustment(
			@Schema(description = "재료 id (문자열). 다른 가게 · 없는 · 숫자 아닌 id 는 404", example = "12")
			@NotBlank String ingredientId,

			@Schema(description = "버린 양, 재료 기준 단위. 0 보다 크고 소수 셋째 자리까지. 추정 재고보다 커도 받고 남은 양은 0", example = "500")
			@NotNull @Positive @Digits(integer = QTY_INTEGER_DIGITS, fraction = 3) BigDecimal qty,

			@Schema(description = "사유 DISCARD 폐기 · SPOILED 상함 · DAMAGED 파손 · SPILLED 쏟음 · OTHER 기타", example = "SPOILED")
			@NotNull AdjustmentReason reason,

			@Schema(description = "메모", nullable = true, example = "냉장고 고장")
			@Size(max = MEMO_MAX) String memo
	) {

		public Adjustment {
			qty = Decimals.normalize(qty, QTY_INTEGER_DIGITS);
		}
	}
}
