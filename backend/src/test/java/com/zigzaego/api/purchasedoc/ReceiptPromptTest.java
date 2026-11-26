package com.zigzaego.api.purchasedoc;

import static org.assertj.core.api.Assertions.assertThat;

import com.zigzaego.api.onboarding.ai.OnboardingPrompt;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 영수증 프롬프트(지라 110 의 사본, resources {@code receipt/}). 사본이 ai 원본과 바이트까지 같은지도 본다. 원본이 없으면
 * 건너뛰지 않고 실패한다 — 원본을 옮기거나 고쳤으면 사본도 같이 맞춰야 한다. 테스트 작업 폴더는 {@code backend/}.
 */
class ReceiptPromptTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@ParameterizedTest
	@CsvSource({"normalize.md", "normalize.schema.json", "match.md", "match.schema.json"})
	void 사본이_ai_원본과_바이트가_같다(String file) throws IOException {
		Path originalPath = Path.of("../ai/prompts/receipt/" + file);
		assertThat(originalPath).as("ai 원본이 있어야 한다: " + originalPath.toAbsolutePath()).isRegularFile();

		byte[] copy;
		try (InputStream in = new ClassPathResource("receipt/" + file).getInputStream()) {
			copy = in.readAllBytes();
		}
		assertThat(copy).isEqualTo(Files.readAllBytes(originalPath));
	}

	@Test
	void normalize_가_system_user_로_읽히고_스키마가_strict() {
		OnboardingPrompt prompt = OnboardingPrompt.load("receipt", "normalize", JSON);

		assertThat(prompt.system()).contains("supplier").doesNotContain("## user");
		String user = prompt.user("ocr_text", "대파 1단 3,980");
		assertThat(user).contains("대파 1단 3,980").doesNotContain("{{");
		JsonNode schema = prompt.schema();
		assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
		assertThat(schema.get("required")).hasSize(3);
		JsonNode item = schema.get("properties").get("items").get("items");
		assertThat(item.get("additionalProperties").asBoolean()).isFalse();
		assertThat(item.get("required")).hasSize(5);
	}

	@Test
	void match_가_system_user_로_읽히고_스키마가_strict() {
		OnboardingPrompt prompt = OnboardingPrompt.load("receipt", "match", JSON);

		assertThat(prompt.system()).contains("is_new_periodic").doesNotContain("## user");
		String user = prompt.userTemplate().replace("{{catalog_json}}", "[]").replace("{{lines_json}}", "[]");
		assertThat(user).doesNotContain("{{");
		JsonNode schema = prompt.schema();
		assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
		JsonNode item = schema.get("properties").get("items").get("items");
		assertThat(item.get("additionalProperties").asBoolean()).isFalse();
		assertThat(item.get("required")).hasSize(6);
	}
}
