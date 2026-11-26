package com.zigzaego.api.ingredient.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.zigzaego.api.ingredient.entity.DefaultIngredientCategory;
import com.zigzaego.api.onboarding.ai.IngredientCatalog;
import com.zigzaego.api.onboarding.ai.OnboardingPrompt;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 자동 분류가 읽는 표 · 프롬프트가 원본과 어긋나지 않는지 (S15P31A308-274). 재료표 이름마다 카테고리 매핑이 하나씩 있고,
 * 품목군 표의 {@code category} 값마다 11종 매핑이 있다. 테스트 작업 폴더는 {@code backend/}.
 */
class IngredientCategoryResourcesTest {

	@Test
	void 재료표_이름마다_카테고리_매핑이_하나씩_있고_남는_줄이_없다() {
		Set<String> catalogNames = new LinkedHashSet<>(
				IngredientCatalog.loadDefault().items().stream().map(IngredientCatalog.Item::name).toList());
		Map<String, DefaultIngredientCategory> mapping = IngredientCategoryClassifier.loadCatalogCategory();

		assertThat(mapping.keySet()).containsExactlyInAnyOrderElementsOf(catalogNames);
		assertThat(mapping.values()).doesNotContain(DefaultIngredientCategory.OTHER);  // 재료표 재료는 전부 정해진다
	}

	@Test
	void 품목군_표의_category_값마다_11종_매핑이_있다() throws IOException {
		Path original = Path.of("../ai/seed/market_item_group.csv");
		assertThat(original).as("ai 원본이 있어야 한다: " + original.toAbsolutePath()).isRegularFile();
		List<String> lines = Files.readAllLines(original, StandardCharsets.UTF_8);
		List<String> header = IngredientCategoryBackup.split(lines.get(0).replace("﻿", ""));
		int category = header.indexOf("category");
		assertThat(category).isNotNegative();
		Set<String> categories = new LinkedHashSet<>();
		for (String line : lines.subList(1, lines.size())) {
			if (!line.isBlank()) {
				categories.add(IngredientCategoryBackup.split(line).get(category).strip());
			}
		}

		assertThat(IngredientCategoryClassifier.ITEM_GROUP_CATEGORY.keySet()).containsExactlyInAnyOrderElementsOf(categories);
	}

	@Test
	void 프롬프트에서_system_user_와_스키마를_읽는다() {
		OnboardingPrompt prompt = OnboardingPrompt.load(IngredientCategoryClassifier.PROMPT_DIR,
				IngredientCategoryClassifier.PROMPT_NAME, JsonMapper.builder().build());

		assertThat(prompt.name()).isEqualTo("category");
		assertThat(prompt.system()).contains("{{categories}}").doesNotContain("## user");
		assertThat(prompt.userTemplate()).contains("{{ingredients}}");
		assertThat(prompt.schema().get("type").asString()).isEqualTo("object");
		assertThat(prompt.schema().path("properties").path("items").path("items").path("required").toString())
				.contains("name").contains("category");
	}
}
