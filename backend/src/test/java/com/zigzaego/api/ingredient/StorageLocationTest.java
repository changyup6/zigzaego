package com.zigzaego.api.ingredient;

import static org.assertj.core.api.Assertions.assertThat;

import com.zigzaego.api.ingredient.entity.IngredientKind;
import com.zigzaego.api.ingredient.entity.StorageLocation;
import com.zigzaego.api.ingredient.entity.StorageType;
import org.junit.jupiter.api.Test;

/** 보관 위치 기본값 규칙 (S15P31A308-268). V9 마이그레이션의 기존 행 채우기와 같아야 한다. */
class StorageLocationTest {

	@Test
	void FROZEN_은_냉동() {
		assertThat(StorageLocation.defaultFor(IngredientKind.BOM, StorageType.FROZEN)).isEqualTo(StorageLocation.FREEZER);
		assertThat(StorageLocation.defaultFor(IngredientKind.ONE_TO_ONE, StorageType.FROZEN))
				.isEqualTo(StorageLocation.FREEZER);
	}

	@Test
	void CHILLED_FRESH_는_냉장() {
		assertThat(StorageLocation.defaultFor(IngredientKind.BOM, StorageType.CHILLED)).isEqualTo(StorageLocation.FRIDGE);
		assertThat(StorageLocation.defaultFor(IngredientKind.BOM, StorageType.FRESH)).isEqualTo(StorageLocation.FRIDGE);
	}

	@Test
	void AMBIENT_는_선반() {
		assertThat(StorageLocation.defaultFor(IngredientKind.BOM, StorageType.AMBIENT)).isEqualTo(StorageLocation.SHELF);
	}

	@Test
	void 주기형이나_보관_방식_없음은_창고() {
		assertThat(StorageLocation.defaultFor(IngredientKind.CYCLE, null)).isEqualTo(StorageLocation.WAREHOUSE);
		assertThat(StorageLocation.defaultFor(IngredientKind.CYCLE, StorageType.AMBIENT))
				.isEqualTo(StorageLocation.WAREHOUSE);
		assertThat(StorageLocation.defaultFor(IngredientKind.BOM, null)).isEqualTo(StorageLocation.WAREHOUSE);
	}
}
