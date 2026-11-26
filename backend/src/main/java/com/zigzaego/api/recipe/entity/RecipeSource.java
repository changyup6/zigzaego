package com.zigzaego.api.recipe.entity;

/**
 * 레시피 양이 어디서 왔나. MANUAL=사장님 입력 / AI=AI 추정 / OWNER_CONFIRMED=AI 추정을 사장님이 확인 /
 * REGRESSION=매입 · 판매 역산 보정 / FILE=레시피 파일 업로드. 설계의 {@code recipe.estimated} = AI.
 */
public enum RecipeSource {
	MANUAL, AI, OWNER_CONFIRMED, REGRESSION, FILE;

	public boolean estimated() {
		return this == AI;
	}
}
