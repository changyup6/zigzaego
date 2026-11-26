package com.zigzaego.api.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zigzaego.api.ingredient.entity.IngredientKind;
import com.zigzaego.api.ingredient.entity.StorageLocation;
import com.zigzaego.api.ingredient.entity.StorageType;
import com.zigzaego.api.llm.LlmRequest;
import com.zigzaego.api.support.StoreFixtures.LoggedInStore;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

/**
 * ai 데모(공개 메뉴 7개 + 더블 1개, Claude 수기 출력)를 실제 job 파이프라인으로 돌린 결과로 레시피를 확정한다 (지라 S15P31A308-96).
 * 가짜 LLM 은 구성 추출이면 demo_extraction.json, 분류면 demo_classify.json 을 그대로 돌려준다.
 */
class RecipeConfirmTest extends OnboardingTestSupport {

	private LoggedInStore store;

	private long jobId;

	@BeforeEach
	void setUp() throws Exception {
		store = loginNewStore("레시피확정");
		given(placeClient.collectMenus(anyString())).willReturn(demoMenus());
		given(imageFetcher.fetch(anyString())).willReturn(Optional.empty());
		JsonNode extraction = readResource("onboarding/demo_extraction.json");
		JsonNode classify = readResource("onboarding/demo_classify.json");
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
		jobId = Long.parseLong(jsonMapper.readTree(created.getResponse().getContentAsString())
				.get("data").get("id").asString());
		assertThat(awaitFinished(jobId).get("status")).isEqualTo("DONE");
	}

	private JsonNode perform(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
		MvcResult result = mockMvc.perform(request).andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true)).andReturn();
		return jsonMapper.readTree(result.getResponse().getContentAsString()).get("data");
	}

	private JsonNode skip() throws Exception {
		return perform(post("/onboarding/recipes/skip").header(HttpHeaders.AUTHORIZATION, store.bearer()));
	}

	private JsonNode questions() throws Exception {
		return perform(get("/onboarding/recipe-questions").header(HttpHeaders.AUTHORIZATION, store.bearer()));
	}

	private JsonNode confirm(String answersJson) throws Exception {
		return perform(post("/onboarding/recipes/confirm").header(HttpHeaders.AUTHORIZATION, store.bearer())
				.contentType(MediaType.APPLICATION_JSON).content(answersJson));
	}

	private Map<String, Long> recipeRowsPerMenu() {
		Map<String, Long> rows = new HashMap<>();
		jdbc.query("SELECT m.name, count(r.id) AS n FROM menu m LEFT JOIN recipe r ON r.menu_id = m.id"
				+ " WHERE m.store_id = ? GROUP BY m.name", rs -> {
					rows.put(rs.getString("name"), rs.getLong("n"));
				}, store.storeId());
		return rows;
	}

	private BigDecimal qty(String menu, String ingredient) {
		return jdbc.queryForObject("SELECT r.qty_per_serving FROM recipe r JOIN menu m ON m.id = r.menu_id"
				+ " JOIN ingredient i ON i.id = r.ingredient_id WHERE r.store_id = ? AND m.name = ? AND i.name = ?",
				BigDecimal.class, store.storeId(), menu, ingredient);
	}

	@Test
	void 건너뛰기_메뉴_8개_더블은_단백질_2배_모든_재료_추정값_source_AI() throws Exception {
		JsonNode book = skip();

		assertThat(book.get("menus")).hasSize(8);
		Map<String, Long> rows = recipeRowsPerMenu();
		assertThat(rows).hasSize(8);
		// 데모 구성: "브레드 스틱 2개" 만 구성요소가 하나(브레드 스틱 ×2), 나머지 7개는 2줄 이상
		assertThat(rows.get("브레드 스틱 2개")).isEqualTo(1);
		rows.entrySet().stream().filter(e -> !e.getKey().equals("브레드 스틱 2개"))
				.forEach(e -> assertThat(e.getValue()).as(e.getKey()).isGreaterThanOrEqualTo(2));
		assertThat(qty("더블 불고기 파스타볼", "불고기"))
				.isEqualByComparingTo(qty("불고기 들기름 파스타볼", "불고기").multiply(BigDecimal.valueOf(2)));
		assertThat(qty("브레드 스틱 2개", "브레드 스틱")).isEqualByComparingTo("2"); // 1인분 1개 × 2

		book.get("ingredients").forEach(i -> assertThat(i.get("estimated").asBoolean()).as(i.toString()).isTrue());
		assertThat(jdbc.queryForList("SELECT DISTINCT source FROM recipe WHERE store_id = ?", String.class,
				store.storeId())).containsExactly("AI");
		assertThat(jdbc.queryForList("SELECT DISTINCT unit_status FROM ingredient WHERE store_id = ?", String.class,
				store.storeId())).containsExactly("ESTIMATED");
		assertThat(jdbc.queryForList("SELECT DISTINCT source FROM menu WHERE store_id = ?", String.class,
				store.storeId())).containsExactly("NAVER");
		// RecipeBookResponse 모양: id 문자열 · 단위 g|ml|개 · 메뉴 줄 mult
		JsonNode menu = book.get("menus").get(0);
		assertThat(menu.get("id").isString()).isTrue();
		assertThat(menu.get("price").isNumber()).isTrue();
		assertThat(menu.get("lines").get(0).get("ingredientId").isString()).isTrue();
		book.get("ingredients").forEach(i -> assertThat(i.get("unit").asString()).isIn("g", "ml", "개"));
	}

	@Test
	void 건너뛰기로_만든_재료는_보관_방식대로_보관_위치가_채워진다() throws Exception {
		skip();

		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT name, kind, storage_type, storage_location FROM ingredient WHERE store_id = ?", store.storeId());
		assertThat(rows).isNotEmpty();
		rows.forEach(row -> assertThat(row.get("storage_location")).as(row.toString()).isEqualTo(
				StorageLocation.defaultFor(IngredientKind.valueOf((String) row.get("kind")),
						row.get("storage_type") == null ? null : StorageType.valueOf((String) row.get("storage_type")))
						.name()));
		// 데모 재료는 보관 방식이 섞여 있어 위치도 둘 이상으로 나뉜다
		assertThat(rows.stream().map(row -> row.get("storage_location")).distinct().count()).isGreaterThan(1);
	}

	private Integer shelfLife(String ingredient) {
		return jdbc.queryForObject("SELECT shelf_life_days FROM ingredient WHERE store_id = ? AND name = ?",
				Integer.class, store.storeId(), ingredient);
	}

	@Test
	void shelfLife_건너뛰기_재료표_재료는_재료표값_분류_재료는_분류값() throws Exception {
		JsonNode draftIngredients = resultOf(readJob(jobId)).get("draft").get("ingredients");
		Map<String, JsonNode> draftShelfLife = new HashMap<>();
		draftIngredients.forEach(i -> {
			assertThat(i.has("shelfLifeDays")).as(i.toString()).isTrue();
			draftShelfLife.put(i.get("name").asString(), i.get("shelfLifeDays"));
		});
		assertThat(draftShelfLife.get("불고기").intValue()).isEqualTo(3); // 재료표
		assertThat(draftShelfLife.get("들기름 소스").intValue()).isEqualTo(14); // 분류 출력

		skip();

		// 재료표: 불고기 CHILLED 3일(보관 방식 표라면 7) · 파스타면 AMBIENT 365 · 파르메산 치즈 CHILLED 30
		assertThat(shelfLife("불고기")).isEqualTo(3);
		assertThat(shelfLife("파스타면")).isEqualTo(365);
		assertThat(shelfLife("파르메산 치즈")).isEqualTo(30);
		// 분류 출력: 들기름 소스 AMBIENT 14일(보관 방식 표라면 180) · 음료 AMBIENT 365
		assertThat(shelfLife("들기름 소스")).isEqualTo(14);
		assertThat(shelfLife("음료")).isEqualTo(365);
		assertThat(shelfLife("알리오 올리오 소스")).isEqualTo(7);
	}

	@Test
	void 질문_4개만_답하면_그_4개만_확인됨_OWNER_CONFIRMED_나머지_추정값() throws Exception {
		JsonNode questions = questions();
		assertThat(questions.size()).isGreaterThanOrEqualTo(4);
		ArrayNode answers = jsonMapper.createArrayNode();
		List<String> answeredNames = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			JsonNode q = questions.get(i);
			answers.addObject().put("questionId", q.get("id").asString())
					.put("value", q.get("options").get(1).asDouble()).put("unit", q.get("unit").asString());
			answeredNames.add(q.get("text").asString().replace(" 1인분", ""));
		}

		JsonNode book = confirm(jsonMapper.writeValueAsString(answers));

		List<String> confirmed = new ArrayList<>();
		List<String> estimated = new ArrayList<>();
		book.get("ingredients").forEach(i -> (i.get("estimated").asBoolean() ? estimated : confirmed)
				.add(i.get("name").asString()));
		assertThat(confirmed).containsExactlyInAnyOrderElementsOf(answeredNames);
		assertThat(estimated).isNotEmpty().doesNotContainAnyElementsOf(answeredNames);
		for (String name : answeredNames) {
			assertThat(jdbc.queryForObject("SELECT unit_status FROM ingredient WHERE store_id = ? AND name = ?",
					String.class, store.storeId(), name)).isEqualTo("CONFIRMED");
			assertThat(jdbc.queryForList("SELECT DISTINCT r.source FROM recipe r JOIN ingredient i"
					+ " ON i.id = r.ingredient_id WHERE r.store_id = ? AND i.name = ?", String.class,
					store.storeId(), name)).containsExactly("OWNER_CONFIRMED");
		}
		assertThat(jdbc.queryForList("SELECT DISTINCT r.source FROM recipe r JOIN ingredient i"
				+ " ON i.id = r.ingredient_id WHERE r.store_id = ? AND i.unit_status = 'ESTIMATED'", String.class,
				store.storeId())).containsExactly("AI");
	}

	@Test
	void 같은_확정을_두_번_해도_행_수가_그대로() throws Exception {
		JsonNode q = questions().get(0);
		String answers = "[{\"questionId\":\"" + q.get("id").asString() + "\",\"value\":"
				+ q.get("options").get(2).asDouble() + ",\"unit\":\"" + q.get("unit").asString() + "\"}]";

		confirm(answers);
		long menus = count("menu", store.storeId());
		long ingredients = count("ingredient", store.storeId());
		long recipes = count("recipe", store.storeId());
		confirm(answers);

		assertThat(count("menu", store.storeId())).isEqualTo(menus).isEqualTo(8);
		assertThat(count("ingredient", store.storeId())).isEqualTo(ingredients);
		assertThat(count("recipe", store.storeId())).isEqualTo(recipes);
	}
}
