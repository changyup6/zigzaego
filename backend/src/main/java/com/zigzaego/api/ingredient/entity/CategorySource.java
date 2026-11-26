package com.zigzaego.api.ingredient.entity;

/**
 * 재료 카테고리를 누가 정했나 (S15P31A308-274, V12 {@code ingredient.category_source}).
 * 자동 분류는 {@link #AUTO} 인 재료만 다시 분류하고 {@link #OWNER}(사장님이 직접 고름, S15P31A308-275)는 건드리지 않는다.
 */
public enum CategorySource {
	/** 자동 분류(재료표 · 품목군 매핑 → LLM → 기타)가 붙였다. 정하지 못해 기타가 된 것도 AUTO. */
	AUTO,
	/** 사장님이 직접 골랐다. */
	OWNER
}
