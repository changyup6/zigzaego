package com.zigzaego.api.purchasedoc.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

/** 확인 목록의 묶음 하나 — 같은 상품명(앞뒤 공백 제거)의 확인 전 줄 전부 (S15P31A308-113). */
@Schema(description = "확인 목록의 묶음 하나")
public record ReviewMatchResponse(
		@Schema(description = "묶음 키(상품명 앞뒤 공백 제거). 묶음 확정에 그대로 넘긴다. URL 에 넣을 때는 컨트롤러가 인코딩한다")
		String id,

		@Schema(description = "화면에 보일 상품명 = id")
		String name,

		@Schema(description = "그 묶음 줄이 나온 문서 수")
		int receipts,

		@Schema(description = "묶음 줄 id(문자열, 오름차순)")
		List<String> lineIds,

		@Schema(description = "서버가 미리 고른 재료 id. 없으면 \"\" — 화면이 '주기형 품목' 을 기본으로 고른다")
		String guess,

		@Schema(description = "줄 수량 합(포장 개수, 소수 셋째 자리). 수량 없는 줄은 빼고 더한다")
		BigDecimal qty,

		@Schema(description = "포장 1개 양을 알려 줘야 확정할 수 있다(이유에 UNIT_UNSET)")
		boolean unitNeeded,

		@Schema(description = "포장 1개 = 기준 단위 몇의 기본값. 사전 값 > LLM 제안. 모르면 null")
		BigDecimal basePerPack,

		@Schema(description = "기준 단위 G · ML · EA. 모르면 null")
		String baseUnit,

		@Schema(description = "묶음 줄이 모두 재고 실사 이전(PAST) 문서")
		boolean past,

		@Schema(description = "늘 true — 목록에 남은 묶음은 모두 사장님 결정을 기다린다")
		boolean needsReview,

		@Schema(description = "줄마다 이유의 합집합. 순서 UNMAPPED · LOW_CONFIDENCE · UNIT_UNSET")
		List<String> reasons
) {
}
