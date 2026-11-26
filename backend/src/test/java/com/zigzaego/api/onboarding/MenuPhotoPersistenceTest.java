package com.zigzaego.api.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.zigzaego.api.support.StoreFixtures.LoggedInStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/** 사진이 확정 저장·재조회에서 남고 가게 경계를 넘지 않는지 확인한다. */
class MenuPhotoPersistenceTest extends OnboardingTestSupport {

	private static final String PHOTO = "https://images.example.com/dumplings.jpg";

	private void draft(long storeId, String photoField) {
		String result = """
				{"menus":[],"needsMenuPhoto":false,"questions":[],
				 "draft":{"menus":[{"id":"m1","name":"만두","kind":"MAIN","source":"NAVER",%s
				 "lines":[{"ingredientId":"c1","mult":1}]}],
				 "ingredients":[{"id":"c1","name":"돼지고기","kind":"BOM","unit":"G",
				 "storageType":"CHILLED","perServing":100,"confidence":0.8,"role":"PROTEIN"}]}}
				""".formatted(photoField);
		jdbc.update("INSERT INTO job(store_id,type,status,progress,result_json,finished_at) "
				+ "VALUES (?,'ONBOARDING','DONE',1,?::jsonb,now())", storeId, result);
	}

	private String save(LoggedInStore store) throws Exception {
		return mockMvc.perform(post("/onboarding/recipes/skip").header(HttpHeaders.AUTHORIZATION, store.bearer()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	@Test
	void 사진을_저장하고_메뉴와_레시피_API에서_조회하며_다른_가게는_404() throws Exception {
		LoggedInStore mine = loginNewStore("사진가게");
		LoggedInStore other = loginNewStore("다른사진가게");
		draft(mine.storeId(), "\"photoUrl\":\"" + PHOTO + "\",");
		String saved = save(mine);
		assertThat((String) JsonPath.read(saved, "$.data.menus[0].photoUrl")).isEqualTo(PHOTO);
		String id = JsonPath.read(saved, "$.data.menus[0].id");
		mockMvc.perform(get("/menus").header(HttpHeaders.AUTHORIZATION, mine.bearer()))
				.andExpect(jsonPath("$.data[0].photoUrl").value(PHOTO));
		mockMvc.perform(get("/menus/{id}", id).header(HttpHeaders.AUTHORIZATION, mine.bearer()))
				.andExpect(jsonPath("$.data.photoUrl").value(PHOTO));
		mockMvc.perform(get("/onboarding/recipes").header(HttpHeaders.AUTHORIZATION, mine.bearer()))
				.andExpect(jsonPath("$.data.menus[0].photoUrl").value(PHOTO));
		mockMvc.perform(get("/menus/{id}", id).header(HttpHeaders.AUTHORIZATION, other.bearer()))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/menus").header(HttpHeaders.AUTHORIZATION, other.bearer()))
				.andExpect(jsonPath("$.data").isEmpty());

		// 이전 형식의 분석 결과로 다시 확정해도 저장된 사진은 지우지 않는다.
		draft(mine.storeId(), "");
		assertThat((String) JsonPath.read(save(mine), "$.data.menus[0].photoUrl")).isEqualTo(PHOTO);
	}

	@Test
	void 사진_필드가_없는_이전_분석_결과도_저장한다() throws Exception {
		LoggedInStore mine = loginNewStore("사진없는가게");
		draft(mine.storeId(), "");
		assertThat((Object) JsonPath.read(save(mine), "$.data.menus[0].photoUrl")).isNull();
	}
}
