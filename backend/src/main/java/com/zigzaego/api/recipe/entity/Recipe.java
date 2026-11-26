package com.zigzaego.api.recipe.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 메뉴 1인분에 드는 재료 한 줄. 표는 V1 {@code recipe}. 양은 재료 기준 단위(EA · G · ML). */
@Entity
@Table(name = "recipe")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Recipe {

	/** DB 칸 NUMERIC(14,4). */
	public static final int QTY_SCALE = 4;

	/** DB 칸 NUMERIC(4,3). */
	public static final int CONFIDENCE_SCALE = 3;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "store_id", nullable = false)
	private Long storeId;

	@Column(name = "menu_id", nullable = false)
	private Long menuId;

	@Column(name = "ingredient_id", nullable = false)
	private Long ingredientId;

	@Column(name = "qty_per_serving", nullable = false, precision = 14, scale = 4)
	private BigDecimal qtyPerServing;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private RecipeSource source;

	/** AI 확신도 0~1. 사장님이 넣은 줄은 null. */
	@Column(precision = 4, scale = 3)
	private BigDecimal confidence;

	/** 사장님이 직접 넣은 줄. */
	public static Recipe manual(long storeId, long menuId, long ingredientId, BigDecimal qtyPerServing) {
		Recipe recipe = new Recipe();
		recipe.storeId = storeId;
		recipe.menuId = menuId;
		recipe.ingredientId = ingredientId;
		recipe.qtyPerServing = qtyPerServing;
		recipe.source = RecipeSource.MANUAL;
		return recipe;
	}

	/** 온보딩 확정이 만든 줄 (S15P31A308-96). 출처는 AI(추정) 또는 OWNER_CONFIRMED(질문에 답함). */
	public static Recipe fromOnboarding(long storeId, long menuId, long ingredientId, BigDecimal qtyPerServing,
			RecipeSource source, BigDecimal confidence) {
		Recipe recipe = new Recipe();
		recipe.storeId = storeId;
		recipe.menuId = menuId;
		recipe.ingredientId = ingredientId;
		recipe.updateFromOnboarding(qtyPerServing, source, confidence);
		return recipe;
	}

	/** 온보딩 확정이 다시 들어올 때 양 · 출처 · 확신도를 새 값으로. */
	public void updateFromOnboarding(BigDecimal qtyPerServing, RecipeSource source, BigDecimal confidence) {
		this.qtyPerServing = qtyPerServing.setScale(QTY_SCALE, RoundingMode.HALF_UP);
		this.source = source;
		this.confidence = confidence == null ? null : confidence.setScale(CONFIDENCE_SCALE, RoundingMode.HALF_UP);
	}

	/**
	 * 사장님이 양을 고치거나 그대로 확인했다. AI 추정에서 온 줄(AI · OWNER_CONFIRMED)은 OWNER_CONFIRMED,
	 * 나머지는 MANUAL — 더는 추정값이 아니다. 두 번째로 고쳐도 AI 에서 왔다는 표시는 남는다.
	 */
	public void confirmByOwner(BigDecimal qtyPerServing) {
		boolean fromAi = this.source == RecipeSource.AI || this.source == RecipeSource.OWNER_CONFIRMED;
		this.source = fromAi ? RecipeSource.OWNER_CONFIRMED : RecipeSource.MANUAL;
		this.qtyPerServing = qtyPerServing;
	}
}
