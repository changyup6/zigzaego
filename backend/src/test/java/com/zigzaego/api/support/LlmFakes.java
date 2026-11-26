package com.zigzaego.api.support;

import com.zigzaego.api.llm.LlmRequest;
import com.zigzaego.api.llm.LlmResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 재료 카테고리 분류 프롬프트({@code ingredient/category.md})용 가짜 LLM 응답 · 요청 읽기 (S15P31A308-274). */
public final class LlmFakes {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	/** 사용자 글의 "1. 이름" 줄. */
	private static final Pattern NUMBERED = Pattern.compile("^\\d+\\. (.+)$", Pattern.MULTILINE);

	private LlmFakes() {
	}

	/** {@code {items: [{name, category}]}} 모양의 응답. 토큰 수는 아무 값. */
	public static LlmResult categoryAnswer(Map<String, String> picks) {
		ObjectNode root = JSON.createObjectNode();
		ArrayNode items = root.putArray("items");
		new LinkedHashMap<>(picks).forEach((name, category) -> items.addObject().put("name", name).put("category", category));
		return new LlmResult(root, 10, 5);
	}

	/** 분류 요청의 사용자 글에 번호 붙어 든 재료 이름들 (입력 순서). */
	public static List<String> namesOf(LlmRequest request) {
		List<String> names = new ArrayList<>();
		Matcher m = NUMBERED.matcher(request.user());
		while (m.find()) {
			names.add(m.group(1).strip());
		}
		return names;
	}

	/** 요청에 든 이름 전부에 같은 카테고리를 답한다. */
	public static LlmResult answerAll(LlmRequest request, String category) {
		Map<String, String> picks = new LinkedHashMap<>();
		for (String name : namesOf(request)) {
			picks.put(name, category);
		}
		return categoryAnswer(picks);
	}
}
