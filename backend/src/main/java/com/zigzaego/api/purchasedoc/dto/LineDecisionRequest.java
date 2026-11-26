package com.zigzaego.api.purchasedoc.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/** 영수증 줄 하나에 대한 사장님 결정 (S15P31A308-112). 컨트롤러가 요청 본문을 이 모양으로 넘긴다. */
@Schema(description = "영수증 줄 하나에 대한 결정")
public record LineDecisionRequest(
		@Schema(description = "purchase_doc_line id (문자열)")
		String lineId,

		@Schema(description = "결정 종류. INGREDIENT 기존 재료에 연결 / CYCLE 새 주기형 품목 / IGNORE 무시")
		Action action,

		@Schema(description = "INGREDIENT 일 때 연결할 재료 id (문자열). 다른 결정이면 무시한다")
		String ingredientId,

		@Schema(description = "포장 1개 = 재료 기준 단위 몇 (대파 1단 → 1000). 선택 — 단위 미확정 줄이면 필수")
		BigDecimal baseQtyPerPack
) {

	/** INGREDIENT 기존 재료에 연결 / CYCLE 새 주기형 품목(FE '주기형 품목') / IGNORE 무시(재고 · 가격에 안 씀). */
	public enum Action {
		INGREDIENT, CYCLE, IGNORE
	}
}
