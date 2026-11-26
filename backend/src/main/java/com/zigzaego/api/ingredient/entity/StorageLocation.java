package com.zigzaego.api.ingredient.entity;

/**
 * 보관 위치: 냉동 · 냉장 · 선반 · 창고. 재료 탭 그림에서 재료를 놓는 자리다 (S15P31A308-268).
 * 계산(안전재고 z · 유통기한)은 {@link StorageType}이 맡고, 이 값은 화면 배치에만 쓴다.
 */
public enum StorageLocation {
	FREEZER, FRIDGE, SHELF, WAREHOUSE;

	/**
	 * 새 재료의 기본 위치. V9 마이그레이션의 기존 행 채우기와 같은 규칙이다.
	 * 주기형(CYCLE)은 보관 방식이 있어도 창고에 둔다.
	 */
	public static StorageLocation defaultFor(IngredientKind kind, StorageType storageType) {
		if (kind == IngredientKind.CYCLE || storageType == null) {
			return WAREHOUSE;
		}
		return switch (storageType) {
			case FROZEN -> FREEZER;
			case CHILLED, FRESH -> FRIDGE;
			case AMBIENT -> SHELF;
		};
	}
}
