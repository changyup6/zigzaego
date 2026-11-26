package com.zigzaego.api.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zigzaego.api.llm.LlmRequest;
import com.zigzaego.api.support.StoreFixtures.LoggedInStore;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 분류 출력의 유통기한(shelfLifeDays)이 없거나 쓸 수 없는 값이면 job 은 그대로 DONE 이고, 저장 때 보관 방식 표
 * (냉동 90 · 상온 180 · 냉장 7 · 신선 3)로 채운다 — ai {@code catalog._shelf_life} 와 같은 규칙(정수 · 2 이상만 쓴다).
 */
class RecipeShelfLifeFallbackTest extends OnboardingTestSupport {

	@Test
	void shelfLife_분류값이_없거나_2미만_정수아님이면_보관방식_표로_대체하고_job은_DONE() throws Exception {
		LoggedInStore store = loginNewStore("유통기한대체");
		given(placeClient.collectMenus(anyString())).willReturn(demoMenus());
		given(imageFetcher.fetch(anyString())).willReturn(Optional.empty());
		JsonNode extraction = readResource("onboarding/demo_extraction.json");
		JsonNode classify = readResource("onboarding/demo_classify.json").deepCopy();
		for (JsonNode node : classify.get("items")) {
			ObjectNode item = (ObjectNode) node;
			switch (item.get("name").asString()) {
				case "알리오 올리오 소스" -> item.put("shelfLifeDays", 0); // CHILLED → 7
				case "들기름 소스" -> item.put("shelfLifeDays", 1); // AMBIENT → 180
				case "토마토 라구 소스" -> item.remove("shelfLifeDays"); // CHILLED → 7
				case "매콤 크림 소스" -> item.put("shelfLifeDays", true); // CHILLED → 7
				case "브레드 스틱" -> item.put("shelfLifeDays", 30.0); // FROZEN → 90
				case "음료" -> item.putNull("shelfLifeDays"); // AMBIENT → 180
				default -> {
				}
			}
		}
		given(gmsClient.completeJson(any(LlmRequest.class))).willAnswer(invocation -> {
			LlmRequest request = invocation.getArgument(0);
			return "menu_extraction".equals(request.schemaName()) ? extraction : classify;
		});

		MvcResult created = mockMvc.perform(post("/onboarding/jobs")
						.header(HttpHeaders.AUTHORIZATION, store.bearer())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"placeId\":\"1\",\"includeYogiyo\":false}"))
				.andExpect(status().isOk())
				.andReturn();
		long jobId = Long.parseLong(jsonMapper.readTree(created.getResponse().getContentAsString())
				.get("data").get("id").asString());
		Map<String, Object> job = awaitFinished(jobId);
		assertThat(job.get("status")).as(String.valueOf(job)).isEqualTo("DONE");

		Map<String, JsonNode> draft = new HashMap<>();
		resultOf(job).get("draft").get("ingredients").forEach(i -> {
			assertThat(i.has("shelfLifeDays")).as(i.toString()).isTrue();
			draft.put(i.get("name").asString(), i.get("shelfLifeDays"));
		});
		for (String name : new String[] {"알리오 올리오 소스", "들기름 소스", "토마토 라구 소스", "매콤 크림 소스", "브레드 스틱",
				"음료"}) {
			assertThat(draft.get(name).isNull()).as(name).isTrue();
		}
		assertThat(draft.get("불고기").intValue()).isEqualTo(3); // 재료표 값은 그대로

		mockMvc.perform(post("/onboarding/recipes/skip").header(HttpHeaders.AUTHORIZATION, store.bearer()))
				.andExpect(status().isOk());

		Map<String, Integer> saved = new HashMap<>();
		jdbc.query("SELECT name, shelf_life_days FROM ingredient WHERE store_id = ?",
				rs -> {
					saved.put(rs.getString("name"), (Integer) rs.getObject("shelf_life_days"));
				}, store.storeId());
		assertThat(saved).containsEntry("알리오 올리오 소스", 7).containsEntry("들기름 소스", 180)
				.containsEntry("토마토 라구 소스", 7).containsEntry("매콤 크림 소스", 7).containsEntry("브레드 스틱", 90)
				.containsEntry("음료", 180).containsEntry("불고기", 3);
	}
}
