package com.zigzaego.api.onboarding.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

/**
 * 레시피 화면이 받는 "메뉴 목록 + 재료 목록(1인분)". FE {@code RecipeBook} 과 같은 모양 (types.ts).
 * 메뉴는 재료를 배수(mult)로 가리킨다: 메뉴의 재료 양 = 재료 perServing × mult.
 */
@Schema(description = "레시피 — 메뉴와 재료 1인분")
public record RecipeBookResponse(
		@Schema(description = "메뉴 목록 (id 순)")
		List<Menu> menus,

		@Schema(description = "레시피에 쓰인 재료 목록 (id 순)")
		List<Ingredient> ingredients
) {

	@Schema(description = "레시피 메뉴")
	public record Menu(
			@Schema(description = "메뉴 id (문자열)", example = "12")
			String id,

			@Schema(description = "메뉴 이름", example = "치킨 알리오 파스타볼")
			String name,

			@Schema(description = "판매가(원, 정수 — DECISIONS #55). 모르면 null", example = "8900", nullable = true)
			Long price,

			@Schema(description = "대표 메뉴 사진 원본 HTTP(S) URL. 없으면 null", nullable = true)
			String photoUrl,

			@Schema(description = "재료 줄")
			List<Line> lines
	) {
	}

	@Schema(description = "메뉴의 재료 한 줄")
	public record Line(
			@Schema(description = "재료 id (문자열)", example = "31")
			String ingredientId,

			@Schema(description = "재료 1인분의 몇 배. 소수 셋째 자리", example = "2.000")
			BigDecimal mult
	) {
	}

	@Schema(description = "레시피 재료")
	public record Ingredient(
			@Schema(description = "재료 id (문자열)", example = "31")
			String id,

			@Schema(description = "재료 이름", example = "파스타면")
			String name,

			@Schema(description = "단위 표기 g · ml · 개", example = "g")
			RecipeUnit unit,

			@Schema(description = "1인분 = 이 재료 레시피 줄 양 중 가장 작은 값. 소수 셋째 자리 (결정 #42). "
					+ "더블 메뉴에만 쓰인 재료는 2배로 보인다", example = "100.000")
			BigDecimal perServing,

			@Schema(description = "사장님이 확인하지 않은 추정값인지 (단위 상태 ESTIMATED)", example = "true")
			boolean estimated
	) {
	}
}
