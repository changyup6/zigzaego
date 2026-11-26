package com.zigzaego.api.onboarding.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** ai {@code tests/test_onboarding_catalog.py} 와 같은 답을 내는지 본다. */
class IngredientCatalogTest {

	private final IngredientCatalog catalog = IngredientCatalog.loadDefault();

	@ParameterizedTest
	@CsvSource({
			"새우, EA", "고추장, G", "우유, ML", "계란, EA", "파스타면, G",
			"대파, G", "식용유, ML", "치즈, G", "베이컨, G", "콜라, EA",
	})
	void 단위_예시(String name, String unit) {
		assertThat(catalog.lookup(name)).as(name).isNotNull();
		assertThat(catalog.lookup(name).unit()).isEqualTo(unit);
	}

	@Test
	void 별칭은_공백_대소문자를_빼고_맞춘다() {
		assertThat(catalog.lookup("소불고기").name()).isEqualTo("불고기");
		assertThat(catalog.lookup(" 파스타 면 ").name()).isEqualTo("파스타면");
		assertThat(catalog.lookup("달걀").name()).isEqualTo("계란");
		assertThat(catalog.lookup("Coca-Cola").name()).isEqualTo("콜라");
	}

	@ParameterizedTest
	@ValueSource(strings = {"없는재료", "", "   ", "트러플 오일"})
	void 없는_이름은_null(String name) {
		assertThat(catalog.lookup(name)).isNull();
	}

	@Test
	void 표_크기와_값() {
		assertThat(catalog.items()).hasSizeGreaterThanOrEqualTo(50);
		assertThat(catalog.items()).allSatisfy(item -> {
			assertThat(item.kind()).isIn("BOM", "ONE_TO_ONE");
			assertThat(item.unit()).isIn("G", "ML", "EA");
			assertThat(item.storageType()).isIn("FROZEN", "AMBIENT", "CHILLED", "FRESH");
			assertThat(item.perServing()).isPositive();
			assertThat(item.confidence()).isBetween(0.0, 1.0);
			assertThat(item.shelfLifeDays()).isNotNull().isGreaterThanOrEqualTo(2);
		});
	}
}
