package com.zigzaego.api.onboarding.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.zigzaego.api.ingredient.entity.IngredientUnit;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 레시피 화면의 단위 표기 {@code g | ml | 개} (FE {@code RecipeUnit}). DB 기준 단위(G · ML · EA)와 1:1 이고 환산은 없다.
 * 수량 자릿수 규칙은 결정 #42 그대로.
 */
@Schema(description = "레시피 단위 표기", allowableValues = {"g", "ml", "개"})
public enum RecipeUnit {
	G("g", IngredientUnit.G),
	ML("ml", IngredientUnit.ML),
	EA("개", IngredientUnit.EA);

	private final String label;
	private final IngredientUnit unit;

	RecipeUnit(String label, IngredientUnit unit) {
		this.label = label;
		this.unit = unit;
	}

	@JsonValue
	public String label() {
		return label;
	}

	public IngredientUnit toIngredientUnit() {
		return unit;
	}

	public static RecipeUnit of(IngredientUnit unit) {
		for (RecipeUnit value : values()) {
			if (value.unit == unit) {
				return value;
			}
		}
		throw new IllegalArgumentException("unknown unit: " + unit);
	}

	/** JSON 의 {@code g | ml | 개}. 모르는 값이면 null 이 아니라 오류 — 요청 본문 400. */
	@JsonCreator
	public static RecipeUnit fromLabel(String label) {
		for (RecipeUnit value : values()) {
			if (value.label.equals(label)) {
				return value;
			}
		}
		throw new IllegalArgumentException("unit 은 g · ml · 개 중 하나: " + label);
	}
}
