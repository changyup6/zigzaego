package com.zigzaego.api.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zigzaego.api.support.StoreFixtures.LoggedInStore;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * 레시피 확정 API 경계 (지라 S15P31A308-96). 분석 결과는 DONE 온보딩 job 행을 직접 넣어 만든다 — 파이프라인은 RecipeConfirmTest 가 본다.
 * 가게 2개로 가게 범위(ADR-0002)를 확인한다.
 */
class OnboardingRecipeControllerTest extends OnboardingTestSupport {

	/** 치킨 1인분 90g — 보통 메뉴 ×1, 더블 메뉴 ×2. 새우는 개(EA) 단위. */
	private static final String RESULT = """
			{"menus":[{"id":"m1","name":"치킨 파스타","price":9900,"ingredientCount":2},
			          {"id":"m2","name":"더블 치킨 파스타","price":12900,"ingredientCount":2},
			          {"id":"m3","name":"새우 파스타","price":11900,"ingredientCount":2}],
			 "needsMenuPhoto":false,
			 "draft":{"menus":[
			   {"id":"m1","name":"치킨 파스타","price":9900,"kind":"MAIN","source":"NAVER",
			    "lines":[{"ingredientId":"c1","mult":1},{"ingredientId":"c2","mult":1}]},
			   {"id":"m2","name":"더블 치킨 파스타","price":12900,"kind":"MAIN","source":"NAVER",
			    "lines":[{"ingredientId":"c1","mult":2},{"ingredientId":"c2","mult":1}]},
			   {"id":"m3","name":"새우 파스타","price":11900,"kind":"MAIN","source":"NAVER",
			    "lines":[{"ingredientId":"c3","mult":1},{"ingredientId":"c2","mult":1}]}],
			  "ingredients":[
			   {"id":"c1","name":"닭고기","kind":"BOM","unit":"G","storageType":"CHILLED","perServing":90,"confidence":0.5,"role":"PROTEIN"},
			   {"id":"c2","name":"파스타면","kind":"BOM","unit":"G","storageType":"AMBIENT","perServing":100,"confidence":0.5,"role":"BASE"},
			   {"id":"c3","name":"새우","kind":"BOM","unit":"EA","storageType":"FROZEN","perServing":5,"confidence":0.5,"role":"PROTEIN"}]},
			 "questions":[
			   {"id":"q1","ingredientId":"c1","text":"닭고기 1인분","unit":"g","options":[70,90,110]},
			   {"id":"q2","ingredientId":"c3","text":"새우 1인분","unit":"개","options":[4,5,6]}],
			 "extraction":{"menus":[]},"classify":{"items":[]}}
			""";

	private LoggedInStore storeA;

	@BeforeEach
	void setUp() throws Exception {
		storeA = loginNewStore("레시피A");
		insertDoneJob(storeA.storeId(), RESULT);
	}

	private void insertDoneJob(long storeId, String resultJson) {
		jdbc.update("INSERT INTO job (store_id, type, status, progress, result_json, finished_at)"
				+ " VALUES (?, 'ONBOARDING', 'DONE', 1, ?::jsonb, now())", storeId, resultJson);
	}

	private JsonNode ok(RequestBuilder request) throws Exception {
		MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
		return jsonMapper.readTree(result.getResponse().getContentAsString()).get("data");
	}

	private BigDecimal qty(long storeId, String menu, String ingredient) {
		return jdbc.queryForObject("SELECT r.qty_per_serving FROM recipe r JOIN menu m ON m.id = r.menu_id"
				+ " JOIN ingredient i ON i.id = r.ingredient_id WHERE r.store_id = ? AND m.name = ? AND i.name = ?",
				BigDecimal.class, storeId, menu, ingredient);
	}

	private long ingredientId(long storeId, String name) {
		return jdbc.queryForObject("SELECT id FROM ingredient WHERE store_id = ? AND name = ?", Long.class, storeId,
				name);
	}

	private JsonNode skip(LoggedInStore store) throws Exception {
		return ok(post("/onboarding/recipes/skip").header(HttpHeaders.AUTHORIZATION, store.bearer()));
	}

	@Test
	void serving_치킨_90에서_100으로_고치면_보통_100_더블_200() throws Exception {
		skip(storeA);
		assertThat(qty(storeA.storeId(), "치킨 파스타", "닭고기")).isEqualByComparingTo("90");
		assertThat(qty(storeA.storeId(), "더블 치킨 파스타", "닭고기")).isEqualByComparingTo("180");
		long chicken = ingredientId(storeA.storeId(), "닭고기");

		JsonNode book = ok(patch("/ingredients/{id}/serving", chicken).header(HttpHeaders.AUTHORIZATION, storeA.bearer())
				.contentType(MediaType.APPLICATION_JSON).content("{\"perServing\":100}"));

		assertThat(qty(storeA.storeId(), "치킨 파스타", "닭고기")).isEqualByComparingTo("100");
		assertThat(qty(storeA.storeId(), "더블 치킨 파스타", "닭고기")).isEqualByComparingTo("200");
		JsonNode ingredient = find(book.get("ingredients"), "name", "닭고기");
		assertThat(ingredient.get("perServing").decimalValue()).isEqualByComparingTo("100");
		assertThat(ingredient.get("estimated").asBoolean()).isFalse();
		JsonNode doubleMenu = find(book.get("menus"), "name", "더블 치킨 파스타");
		assertThat(find(doubleMenu.get("lines"), "ingredientId", String.valueOf(chicken)).get("mult").decimalValue())
				.isEqualByComparingTo("2");
		assertThat(jdbc.queryForList("SELECT DISTINCT source FROM recipe WHERE ingredient_id = ?", String.class,
				chicken)).containsExactly("OWNER_CONFIRMED");
		assertThat(jdbc.queryForObject("SELECT unit_status FROM ingredient WHERE id = ?", String.class, chicken))
				.isEqualTo("CONFIRMED");
	}

	@Test
	void 답_단위가_g_인데_재료가_EA_면_재료_단위를_G_로_바꾼다() throws Exception {
		JsonNode book = ok(post("/onboarding/recipes/confirm").header(HttpHeaders.AUTHORIZATION, storeA.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content("[{\"questionId\":\"q2\",\"value\":60,\"unit\":\"g\"}]"));

		assertThat(jdbc.queryForObject("SELECT unit FROM ingredient WHERE store_id = ? AND name = '새우'",
				String.class, storeA.storeId())).isEqualTo("G");
		assertThat(qty(storeA.storeId(), "새우 파스타", "새우")).isEqualByComparingTo("60");
		JsonNode shrimp = find(book.get("ingredients"), "name", "새우");
		assertThat(shrimp.get("unit").asString()).isEqualTo("g");
		assertThat(shrimp.get("estimated").asBoolean()).isFalse();
		assertThat(find(book.get("ingredients"), "name", "닭고기").get("unit").asString()).isEqualTo("g");
	}

	@Test
	void 모르는_questionId_는_400_ONBOARDING_QUESTION_NOT_FOUND_아무것도_저장_안_함() throws Exception {
		mockMvc.perform(post("/onboarding/recipes/confirm").header(HttpHeaders.AUTHORIZATION, storeA.bearer())
						.contentType(MediaType.APPLICATION_JSON)
						.content("[{\"questionId\":\"q99\",\"value\":100,\"unit\":\"g\"}]"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("ONBOARDING_QUESTION_NOT_FOUND"));
		assertThat(count("menu", storeA.storeId())).isZero();
	}

	@Test
	void 값이_0_이하면_400() throws Exception {
		mockMvc.perform(post("/onboarding/recipes/confirm").header(HttpHeaders.AUTHORIZATION, storeA.bearer())
						.contentType(MediaType.APPLICATION_JSON)
						.content("[{\"questionId\":\"q1\",\"value\":0,\"unit\":\"g\"}]"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("ONBOARDING_ANSWER_INVALID"));
		skip(storeA);
		mockMvc.perform(patch("/ingredients/{id}/serving", ingredientId(storeA.storeId(), "닭고기"))
						.header(HttpHeaders.AUTHORIZATION, storeA.bearer())
						.contentType(MediaType.APPLICATION_JSON).content("{\"perServing\":-1}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
	}

	@Test
	void 값이_소수_셋째_자리에서_0_이_되면_400() throws Exception {
		mockMvc.perform(post("/onboarding/recipes/confirm").header(HttpHeaders.AUTHORIZATION, storeA.bearer())
						.contentType(MediaType.APPLICATION_JSON)
						.content("[{\"questionId\":\"q1\",\"value\":0.00001,\"unit\":\"g\"}]"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("ONBOARDING_ANSWER_INVALID"));
		assertThat(count("menu", storeA.storeId())).isZero();

		skip(storeA);
		mockMvc.perform(patch("/ingredients/{id}/serving", ingredientId(storeA.storeId(), "닭고기"))
						.header(HttpHeaders.AUTHORIZATION, storeA.bearer())
						.contentType(MediaType.APPLICATION_JSON).content("{\"perServing\":0.0004}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
		assertThat(qty(storeA.storeId(), "치킨 파스타", "닭고기")).isEqualByComparingTo("90");

		// 0.0005 는 셋째 자리에서 0.001 — 받는다
		ok(patch("/ingredients/{id}/serving", ingredientId(storeA.storeId(), "닭고기"))
				.header(HttpHeaders.AUTHORIZATION, storeA.bearer())
				.contentType(MediaType.APPLICATION_JSON).content("{\"perServing\":0.0005}"));
	}

	@Test
	void DONE_job_이_없으면_404_ONBOARDING_JOB_NOT_FOUND() throws Exception {
		LoggedInStore fresh = loginNewStore("분석없음");
		jdbc.update("INSERT INTO job (store_id, type, status) VALUES (?, 'ONBOARDING', 'RUNNING')", fresh.storeId());

		mockMvc.perform(get("/onboarding/recipe-questions").header(HttpHeaders.AUTHORIZATION, fresh.bearer()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("ONBOARDING_JOB_NOT_FOUND"));
		mockMvc.perform(post("/onboarding/recipes/skip").header(HttpHeaders.AUTHORIZATION, fresh.bearer()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("ONBOARDING_JOB_NOT_FOUND"));
	}

	@Test
	void serving_가게_2개_B_로_A_재료를_고치면_404_B_의_레시피에_A_메뉴_없음() throws Exception {
		LoggedInStore storeB = loginNewStore("레시피B");
		insertDoneJob(storeB.storeId(), RESULT.replace("치킨 파스타", "B 치킨 파스타"));
		skip(storeA);
		skip(storeB);
		long chickenA = ingredientId(storeA.storeId(), "닭고기");

		mockMvc.perform(patch("/ingredients/{id}/serving", chickenA).header(HttpHeaders.AUTHORIZATION, storeB.bearer())
						.contentType(MediaType.APPLICATION_JSON).content("{\"perServing\":100}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));
		assertThat(qty(storeA.storeId(), "치킨 파스타", "닭고기")).isEqualByComparingTo("90");

		JsonNode bookB = ok(get("/onboarding/recipes").header(HttpHeaders.AUTHORIZATION, storeB.bearer()));
		assertThat(bookB.get("menus")).hasSize(3);
		bookB.get("menus").forEach(menu -> assertThat(menu.get("name").asString()).isNotEqualTo("치킨 파스타"));
		bookB.get("ingredients").forEach(i -> assertThat(i.get("id").asString()).isNotEqualTo(String.valueOf(chickenA)));
	}

	@Test
	void serving_없는_재료_id_는_404() throws Exception {
		mockMvc.perform(patch("/ingredients/{id}/serving", "999999999").header(HttpHeaders.AUTHORIZATION, storeA.bearer())
						.contentType(MediaType.APPLICATION_JSON).content("{\"perServing\":100}"))
				.andExpect(status().isNotFound());
	}

	@Test
	void serving_과_별개로_재료_일반_수정_PATCH_ingredients_id_는_그대로_200() throws Exception {
		skip(storeA);
		long chicken = ingredientId(storeA.storeId(), "닭고기");

		JsonNode ingredient = ok(patch("/ingredients/{id}", chicken).header(HttpHeaders.AUTHORIZATION, storeA.bearer())
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"닭다리살\"}"));

		assertThat(ingredient.get("name").asString()).isEqualTo("닭다리살");
		assertThat(jdbc.queryForObject("SELECT name FROM ingredient WHERE id = ?", String.class, chicken))
				.isEqualTo("닭다리살");
		assertThat(qty(storeA.storeId(), "치킨 파스타", "닭다리살")).isEqualByComparingTo("90");
	}

	@Test
	void 레시피_메뉴_price_는_원_정수() throws Exception {
		skip(storeA);
		// DB 에서 다시 읽은 값(NUMERIC(12,2) → 9900.00)도 정수로 나가야 한다
		MvcResult result = mockMvc.perform(get("/onboarding/recipes")
						.header(HttpHeaders.AUTHORIZATION, storeA.bearer()))
				.andExpect(status().isOk()).andReturn();
		String body = result.getResponse().getContentAsString();

		JsonNode price = find(jsonMapper.readTree(body).get("data").get("menus"), "name", "치킨 파스타").get("price");
		assertThat(price.isIntegralNumber()).as("price=%s", price).isTrue();
		assertThat(price.longValue()).isEqualTo(9900L);
		assertThat(body).contains("\"price\":9900").doesNotContain("9900.0");
	}

	@Test
	void 인증_없으면_401() throws Exception {
		mockMvc.perform(get("/onboarding/recipes"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("COMMON_UNAUTHORIZED"));
	}

	private static JsonNode find(JsonNode array, String field, String value) {
		for (JsonNode node : array) {
			if (value.equals(node.get(field).asString())) {
				return node;
			}
		}
		throw new AssertionError(field + "=" + value + " 없음: " + array);
	}
}
