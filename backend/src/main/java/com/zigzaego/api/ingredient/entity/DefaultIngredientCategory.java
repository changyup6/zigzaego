package com.zigzaego.api.ingredient.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 가게가 생길 때 복사해 넣는 기본 재료 카테고리 11종 (S15P31A308-273, DECISIONS #77). 정본은 여기이고 V11 의 VALUES 는 복사본 —
 * 목록이 바뀌면 새 마이그레이션으로 기존 가게에도 넣는다. 나누는 기준과 품목군 표(market_item_group.category) 17종 → 11종 매핑은
 * {@code docs/plans/2026-11-06_재료_카테고리_분류_기준.md}.
 *
 * <p>{@link #OTHER}(기타)는 가게마다 반드시 1개이고 지우거나 이름을 바꾸지 못한다. 어느 카테고리에도 맞지 않는 재료는 기타.
 */
@Getter
@RequiredArgsConstructor
public enum DefaultIngredientCategory {

	MEAT("육류", 1),
	SEAFOOD("수산", 2),
	VEGETABLE("채소", 3),
	FRUIT("과일", 4),
	EGG_DAIRY("계란·유제품", 5),
	GRAIN_NOODLE("곡물·면·떡", 6),
	TOFU_BEAN("두부·콩", 7),
	SAUCE("양념·소스", 8),
	PROCESSED("가공식품", 9),
	BEVERAGE("음료", 10),
	/** 기타. 맨 뒤에 보인다. */
	OTHER("기타", 99);

	private final String displayName;

	private final int sortOrder;
}
