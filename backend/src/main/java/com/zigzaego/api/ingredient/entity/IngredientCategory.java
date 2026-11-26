package com.zigzaego.api.ingredient.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 가게의 재료 카테고리 하나 (S15P31A308-273). 표는 V11 {@code ingredient_category}. 재료 탭의 필터 · 묶음 표시에만 쓰고
 * 계산에는 쓰지 않는다. 기본 11종은 {@link DefaultIngredientCategory} 코드를 가지고, 사장님이 만든 것은 코드가 없다.
 */
@Entity
@Table(name = "ingredient_category")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IngredientCategory {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "store_id", nullable = false)
	private Long storeId;

	/** 기본 카테고리 코드. 사장님이 만든 카테고리는 null. */
	@Enumerated(EnumType.STRING)
	@Column(length = 20)
	private DefaultIngredientCategory code;

	@Column(nullable = false, length = 30)
	private String name;

	/** 화면 순서(오름차순). 기본 11종은 1~10 · 기타 99. */
	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	/** 가게가 생길 때 넣는 기본 카테고리 한 줄. */
	public static IngredientCategory ofDefault(long storeId, DefaultIngredientCategory code) {
		IngredientCategory category = new IngredientCategory();
		category.storeId = storeId;
		category.code = code;
		category.name = code.getDisplayName();
		category.sortOrder = code.getSortOrder();
		return category;
	}

	/** 사장님이 만든 카테고리 한 줄 (S15P31A308-275). 코드가 없다. */
	public static IngredientCategory ofOwner(long storeId, String name, int sortOrder) {
		IngredientCategory category = new IngredientCategory();
		category.storeId = storeId;
		category.code = null;
		category.name = name;
		category.sortOrder = sortOrder;
		return category;
	}

	/** 이름을 바꾼다. 기타는 못 바꾼다 — 부르는 쪽이 확인한다. 코드는 그대로라 기본 11종의 판별은 흔들리지 않는다. */
	public void rename(String name) {
		this.name = name;
	}

	public void reorder(int sortOrder) {
		this.sortOrder = sortOrder;
	}

	/** 기본 11종 중 하나인지. */
	public boolean isDefault() {
		return code != null;
	}

	/** 기타인지 — 가게마다 1개, 지우거나 이름을 바꾸지 못한다. */
	public boolean isOther() {
		return code == DefaultIngredientCategory.OTHER;
	}
}
