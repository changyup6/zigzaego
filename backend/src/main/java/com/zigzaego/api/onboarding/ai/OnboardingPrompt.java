package com.zigzaego.api.onboarding.ai;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code onboarding/<이름>.md} 의 {@code ## system} · {@code ## user} 본문과 {@code <이름>.schema.json}.
 * 파일은 ai {@code prompts/onboarding/} 원본의 복사본이다 (바이트 비교 테스트가 지킨다).
 *
 * @param name         프롬프트 이름 = 응답 스키마 이름 (예 {@code menu_extraction})
 * @param system       시스템 지시
 * @param userTemplate 사용자 메시지 틀. {@code {{...}}} 자리를 채워 쓴다
 * @param schema       응답 JSON 스키마 (strict)
 */
public record OnboardingPrompt(String name, String system, String userTemplate, JsonNode schema) {

	private static final String SYSTEM_HEADING = "## system";
	private static final String USER_HEADING = "## user";

	public static OnboardingPrompt load(String name, JsonMapper jsonMapper) {
		return load("onboarding", name, jsonMapper);
	}

	/**
	 * {@code <dir>/<name>.md} 와 {@code <dir>/<name>.schema.json} 을 같은 규칙으로 읽는다.
	 * 영수증 프롬프트({@code receipt/}, S15P31A308-110 · S15P31A308-111)도 같은 형식이라 이 로더를 같이 쓴다.
	 */
	public static OnboardingPrompt load(String dir, String name, JsonMapper jsonMapper) {
		String markdown = read(dir + "/" + name + ".md").replace("\r\n", "\n");
		int system = headingAt(markdown, SYSTEM_HEADING);
		int user = headingAt(markdown, USER_HEADING);
		if (system < 0 || user < 0 || user < system) {
			throw new IllegalStateException("프롬프트에 ## system · ## user 제목이 없다: " + name);
		}
		String systemText = markdown.substring(system + SYSTEM_HEADING.length(), user).strip();
		String userText = markdown.substring(user + USER_HEADING.length()).strip();
		JsonNode schema = jsonMapper.readTree(read(dir + "/" + name + ".schema.json"));
		return new OnboardingPrompt(name, systemText, userText, schema);
	}

	/** {@code {{key}}} 를 value 로 바꾼 사용자 글. */
	public String user(String key, String value) {
		return userTemplate.replace("{{" + key + "}}", value);
	}

	private static int headingAt(String markdown, String heading) {
		if (markdown.startsWith(heading + "\n")) {
			return 0;
		}
		int i = markdown.indexOf("\n" + heading + "\n");
		return i < 0 ? -1 : i + 1;
	}

	private static String read(String resource) {
		try (InputStream in = new ClassPathResource(resource).getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("프롬프트를 읽지 못했다: " + resource, e);
		}
	}
}
