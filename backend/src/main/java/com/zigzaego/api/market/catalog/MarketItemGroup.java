package com.zigzaego.api.market.catalog;

import com.zigzaego.api.ingredient.entity.IngredientUnit;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 품목군 한 줄 ({@code market_item_group}, ADR-0012). 사장님이 발주할 때 서로 바꿔 쓸 수 있고 같은 기준 단위로 값을 비교할 수 있는 상품 묶음.
 * 정본은 {@code ai/seed/market_item_group.csv}, 환산 규칙은 크롤러 쪽 {@code weglee_ai.market.taxonomy.Taxonomy.to_base} 와 같다.
 *
 * @param comparable false 면 부위 품목군의 부모(돼지고기 · 소고기 · 닭고기 · 치즈) — 최저가 · 추이를 내지 않는다
 * @param eaWeightG 1개 무게(g). 개 ↔ g 환산. null 이면 환산하지 않는다
 * @param density g / ml. g ↔ ml 환산. null 이면 환산하지 않는다
 */
public record MarketItemGroup(String code, String name, String parentCode, String category, boolean comparable,
		IngredientUnit baseUnit, BigDecimal eaWeightG, BigDecimal density, List<String> aliases, String note) {

	private static final int SCALE = 10;

	/** 데이터셋 단위 글자(g · ml · 개) 또는 G · ML · EA → 기준 단위 enum. 모르면 null. */
	public static IngredientUnit unitOf(String unit) {
		if (unit == null) {
			return null;
		}
		return switch (unit.strip().toLowerCase()) {
			case "g" -> IngredientUnit.G;
			case "ml" -> IngredientUnit.ML;
			case "개", "ea" -> IngredientUnit.EA;
			default -> null;
		};
	}

	/**
	 * (총량, 단위) → 기준 단위 양. 환산값이 없으면 null (기준 문서 5.2절: 근사값으로 채우지 않는다).
	 */
	public BigDecimal toBase(BigDecimal qty, String unit) {
		IngredientUnit from = unitOf(unit);
		if (qty == null || qty.signum() <= 0 || from == null) {
			return null;
		}
		if (from == baseUnit) {
			return qty;
		}
		BigDecimal grams = switch (from) {
			case G -> qty;
			case EA -> eaWeightG == null ? null : qty.multiply(eaWeightG);
			case ML -> density == null ? null : qty.multiply(density);
		};
		if (grams == null) {
			return null;
		}
		return switch (baseUnit) {
			case G -> grams;
			case EA -> eaWeightG == null ? null : grams.divide(eaWeightG, SCALE, RoundingMode.HALF_UP);
			case ML -> density == null ? null : grams.divide(density, SCALE, RoundingMode.HALF_UP);
		};
	}
}
