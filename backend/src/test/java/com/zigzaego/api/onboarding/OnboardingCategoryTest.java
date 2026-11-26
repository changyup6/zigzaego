package com.zigzaego.api.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zigzaego.api.llm.LlmRequest;
import com.zigzaego.api.support.LlmFakes;
import com.zigzaego.api.support.StoreFixtures;
import com.zigzaego.api.support.StoreFixtures.LoggedInStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * 온보딩 확정이 초안 재료 전부를 LLM 요청 1번으로 분류해 카테고리를 붙인다 (S15P31A308-274). 사장님이 이미 고른 재료(OWNER)는 그대로.
 * 데모 초안의 재료 13개 중 재료표에 있는 7개(파스타면 · 불고기 …)는 표로, 없는 6개(소스 4 · 브레드 스틱 · 음료)만 LLM 으로 간다.
 */
class OnboardingCategoryTest extends OnboardingTestSupport {

	private LoggedInStore store;

	private final List<LlmRequest> categoryCalls = new CopyOnWriteArrayList<>();

	@BeforeEach
	void setUp() throws Exception {
		store = loginNewStore("카테고리확정");
		given(placeClient.collectMenus(anyString())).willReturn(demoMenus());
		given(imageFetcher.fetch(anyString())).willReturn(Optional.empty());
		JsonNode extraction = readResource("onboarding/demo_extraction.json");
		JsonNode classify = readResource("onboarding/demo_classify.json");
		given(gmsClient.completeJson(any(LlmRequest.class))).willAnswer(invocation -> {
			LlmRequest request = invocation.getArgument(0);
			return "menu_extraction".equals(request.schemaName()) ? extraction : classify;
		});
		given(gmsClient.complete(any(LlmRequest.class))).willAnswer(invocation -> {
			LlmRequest request = invocation.getArgument(0);
			categoryCalls.add(request);
			Map<String, String> picks = new LinkedHashMap<>();
			for (String name : LlmFakes.namesOf(request)) {
				picks.put(name, name.contains("소스") ? "양념·소스" : name.equals("음료") ? "음료" : "곡물·면·떡");
			}
			return LlmFakes.categoryAnswer(picks);
		});
	}

	@Test
	void 확정하면_초안_재료_전부를_요청_1번으로_분류하고_사장님이_고른_재료는_그대로() throws Exception {
		// 사장님이 미리 만들어 육류로 고른 재료 — 같은 이름이 초안에 있어도 카테고리를 덮어쓰지 않는다
		long breadStick = StoreFixtures.insertIngredient(jdbc, store.storeId(), "브레드 스틱");
		jdbc.update("UPDATE ingredient SET category_source = 'OWNER',"
				+ " category_id = (SELECT id FROM ingredient_category WHERE store_id = ? AND code = 'MEAT') WHERE id = ?",
				store.storeId(), breadStick);

		MvcResult created = mockMvc.perform(post("/onboarding/jobs")
						.header(HttpHeaders.AUTHORIZATION, store.bearer())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"placeId\":\"1\",\"includeYogiyo\":false}"))
				.andExpect(status().isOk())
				.andReturn();
		long jobId = Long.parseLong(jsonMapper.readTree(created.getResponse().getContentAsString())
				.get("data").get("id").asString());
		assertThat(awaitFinished(jobId).get("status")).isEqualTo("DONE");
		mockMvc.perform(post("/onboarding/recipes/skip").header(HttpHeaders.AUTHORIZATION, store.bearer()))
				.andExpect(status().isOk());

		assertThat(categoryCalls).hasSize(1);
		assertThat(categoryCalls.get(0).schemaName()).isEqualTo("category");
		List<String> sent = LlmFakes.namesOf(categoryCalls.get(0));
		assertThat(sent).hasSize(6).contains("브레드 스틱", "음료", "알리오 올리오 소스");

		Map<String, String> codeByName = new LinkedHashMap<>();
		Map<String, String> sourceByName = new LinkedHashMap<>();
		jdbc.query("SELECT i.name, c.code, i.category_source FROM ingredient i JOIN ingredient_category c ON c.id = i.category_id"
				+ " WHERE i.store_id = ? ORDER BY i.id", rs -> {
					codeByName.put(rs.getString("name"), rs.getString("code"));
					sourceByName.put(rs.getString("name"), rs.getString("category_source"));
				}, store.storeId());
		assertThat(codeByName).hasSize(13)
				.containsEntry("브레드 스틱", "MEAT")               // OWNER 가 고른 것은 그대로
				.containsEntry("파스타면", "GRAIN_NOODLE")          // 재료표
				.containsEntry("불고기", "MEAT")
				.containsEntry("닭고기", "MEAT")
				.containsEntry("새우", "SEAFOOD")
				.containsEntry("베이컨", "PROCESSED")
				.containsEntry("파르메산 치즈", "EGG_DAIRY")
				.containsEntry("까르보나라 소스", "SAUCE")
				.containsEntry("음료", "BEVERAGE")                 // LLM
				.containsEntry("알리오 올리오 소스", "SAUCE")
				.containsEntry("들기름 소스", "SAUCE")
				.containsEntry("토마토 라구 소스", "SAUCE")
				.containsEntry("매콤 크림 소스", "SAUCE");
		assertThat(sourceByName).containsEntry("브레드 스틱", "OWNER");
		assertThat(sourceByName.values().stream().filter("AUTO"::equals)).hasSize(12);
	}
}
