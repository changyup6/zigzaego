package com.zigzaego.api.ingredient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.exception.CommonErrorCode;
import com.zigzaego.api.ingredient.entity.DefaultIngredientCategory;
import com.zigzaego.api.ingredient.entity.IngredientCategory;
import com.zigzaego.api.ingredient.service.IngredientCategoryService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** 재료 카테고리 기본 11종 넣기 · 기타 찾기 · 가게 격리 (S15P31A308-273). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class IngredientCategoryServiceTest {

	@Autowired
	private IngredientCategoryService service;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void 새_가게에_기본_11종을_넣고_다시_불러도_늘지_않는다() {
		long storeId = insertBareStore("씨드");

		service.seedDefaults(storeId);
		service.seedDefaults(storeId);

		Map<Long, IngredientCategory> byId = service.byId(storeId);
		assertThat(byId).hasSize(DefaultIngredientCategory.values().length);
		assertThat(byId.values()).extracting(IngredientCategory::getCode)
				.containsExactly(DefaultIngredientCategory.values());
		assertThat(byId.values()).extracting(IngredientCategory::getName)
				.containsExactly("육류", "수산", "채소", "과일", "계란·유제품", "곡물·면·떡", "두부·콩", "양념·소스", "가공식품",
						"음료", "기타");
		assertThat(byId.values()).allSatisfy(category -> assertThat(category.isDefault()).isTrue());

		IngredientCategory other = service.other(storeId);
		assertThat(other.isOther()).isTrue();
		assertThat(other.getSortOrder()).isEqualTo(99);
		assertThat(byId.values()).filteredOn(IngredientCategory::isOther).hasSize(1);
	}

	@Test
	void 기타가_없는_가게는_데이터가_깨진_것이라_예외() {
		long storeId = insertBareStore("기타없음");

		assertThatThrownBy(() -> service.other(storeId))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining(String.valueOf(storeId));
	}

	@Test
	void 남의_가게_카테고리는_없는_것과_같다() {
		long mine = insertBareStore("내가게");
		long theirs = insertBareStore("남의가게");
		service.seedDefaults(mine);
		service.seedDefaults(theirs);

		long theirOther = service.other(theirs).getId();
		assertThat(service.byId(mine)).doesNotContainKey(theirOther);
		assertThat(service.other(mine).getId()).isNotEqualTo(theirOther);
		assertThatThrownBy(() -> service.get(mine, theirOther))
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(CommonErrorCode.NOT_FOUND);
	}

	/** 카테고리 없이 가게만. 서비스가 넣는 것을 보는 테스트라 AuthTestSupport.insertStore(카테고리 포함)를 쓰지 않는다. */
	private long insertBareStore(String label) {
		String name = label + "-" + System.nanoTime();
		return jdbc.queryForObject("INSERT INTO store (name, display_name) VALUES (?, ?) RETURNING id", Long.class,
				name, name);
	}
}
