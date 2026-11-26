package com.zigzaego.api.onboarding.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/**
 * 백엔드에 복사한 프롬프트 · 스키마 · 재료표가 ai 원본과 바이트까지 같은지 본다. 원본이 없으면 건너뛰지 않고 실패한다 —
 * 원본을 옮기거나 지웠으면 복사본도 같이 맞춰야 한다. 테스트 작업 폴더는 {@code backend/}.
 */
class OnboardingResourcesTest {

	@ParameterizedTest
	@CsvSource({
			"menu_extraction.md, ../ai/prompts/onboarding/menu_extraction.md",
			"menu_extraction.schema.json, ../ai/prompts/onboarding/menu_extraction.schema.json",
			"ingredient_classify.md, ../ai/prompts/onboarding/ingredient_classify.md",
			"ingredient_classify.schema.json, ../ai/prompts/onboarding/ingredient_classify.schema.json",
			"ingredient_catalog.csv, ../ai/seed/ingredient_catalog.csv",
	})
	void 복사본이_ai_원본과_바이트가_같다(String resource, String original) throws IOException {
		Path originalPath = Path.of(original);
		assertThat(originalPath).as("ai 원본이 있어야 한다: " + originalPath.toAbsolutePath()).isRegularFile();

		byte[] copy;
		try (InputStream in = new ClassPathResource("onboarding/" + resource).getInputStream()) {
			copy = in.readAllBytes();
		}
		assertThat(copy).isEqualTo(Files.readAllBytes(originalPath));
	}

	@ParameterizedTest
	@CsvSource({"menu_extraction", "ingredient_classify"})
	void 프롬프트에서_system_user_와_스키마를_읽는다(String name) {
		OnboardingPrompt prompt = OnboardingPrompt.load(name, JsonMapper.builder().build());

		assertThat(prompt.system()).isNotBlank().doesNotContain("## user").doesNotContain("## system");
		assertThat(prompt.userTemplate()).isNotBlank().contains("{{");
		assertThat(prompt.schema().get("type").asString()).isEqualTo("object");
	}
}
