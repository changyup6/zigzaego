package com.zigzaego.api.recipe;

import static com.zigzaego.api.support.StoreFixtures.OTHER_CATEGORY_SQL;
import static com.zigzaego.api.support.StoreFixtures.insertIngredient;
import static com.zigzaego.api.support.StoreFixtures.insertMenu;
import static com.zigzaego.api.support.StoreFixtures.insertRecipe;
import static com.zigzaego.api.support.StoreFixtures.loginNewStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.support.StoreFixtures.LoggedInStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** 레시피 CRUD (S15P31A308-44). 실제 로그인 토큰으로 부르고 가게 2개를 넣어 남의 가게 메뉴 · 재료 · 줄이 404 인지 본다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RecipeControllerTest {

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PasswordEncoder passwordEncoder;

	private MockMvc mockMvc;

	private LoggedInStore mine;

	private LoggedInStore other;

	private long menu;

	private long ingredient;

	@BeforeEach
	void setUp() throws Exception {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		mine = loginNewStore(jdbc, passwordEncoder, mockMvc, "내가게");
		other = loginNewStore(jdbc, passwordEncoder, mockMvc, "남의가게");
		menu = insertMenu(jdbc, mine.storeId(), "등심 스테이크");
		ingredient = insertIngredient(jdbc, mine.storeId(), "등심");
	}

	@Test
	void 사장님이_넣은_줄은_MANUAL_추정_아님() throws Exception {
		call(post("/recipes"), mine, """
				{"menuId": "%d", "ingredientId": "%d", "qtyPerServing": 150.5}
				""".formatted(menu, ingredient))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.menuId").value(String.valueOf(menu)))
				.andExpect(jsonPath("$.data.ingredientId").value(String.valueOf(ingredient)))
				.andExpect(jsonPath("$.data.ingredientName").value("등심"))
				.andExpect(jsonPath("$.data.unit").value("G"))
				.andExpect(jsonPath("$.data.qtyPerServing").value(150.5))
				.andExpect(jsonPath("$.data.source").value("MANUAL"))
				.andExpect(jsonPath("$.data.estimated").value(false))
				.andExpect(jsonPath("$.data.needsReview").value(false));

		call(post("/recipes"), mine, """
				{"menuId": "%d", "ingredientId": "%d", "qtyPerServing": 100}
				""".formatted(menu, ingredient))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("RECIPE_DUPLICATE"));
		call(post("/recipes"), mine, """
				{"menuId": "%d", "ingredientId": "%d", "qtyPerServing": 0}
				""".formatted(menu, ingredient))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
	}

	@Test
	void estimated_true_줄은_needsReview_UNANSWERED로_맨_위() throws Exception {
		long other1 = insertIngredient(jdbc, mine.storeId(), "버터");
		long manual = insertRecipe(jdbc, mine.storeId(), menu, other1, "MANUAL");
		long estimated = insertRecipe(jdbc, mine.storeId(), menu, ingredient, "AI");

		call(get("/recipes").param("menuId", String.valueOf(menu)), mine, null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data", hasSize(2)))
				.andExpect(jsonPath("$.data[0].id").value(String.valueOf(estimated)))
				.andExpect(jsonPath("$.data[0].estimated").value(true))
				.andExpect(jsonPath("$.data[0].needsReview").value(true))
				.andExpect(jsonPath("$.data[0].reviewReasons", contains("UNANSWERED")))
				.andExpect(jsonPath("$.data[1].id").value(String.valueOf(manual)))
				.andExpect(jsonPath("$.data[1].needsReview").value(false))
				.andExpect(jsonPath("$.data[1].reviewReasons", empty()));
	}

	@Test
	void 추정_줄을_사장님이_고치면_OWNER_CONFIRMED_확인_필요가_풀린다() throws Exception {
		long estimated = insertRecipe(jdbc, mine.storeId(), menu, ingredient, "AI");

		call(patch("/recipes/{id}", estimated), mine, """
				{"qtyPerServing": 180}
				""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.qtyPerServing").value(180.000))
				.andExpect(jsonPath("$.data.source").value("OWNER_CONFIRMED"))
				.andExpect(jsonPath("$.data.estimated").value(false))
				.andExpect(jsonPath("$.data.needsReview").value(false));
		call(get("/menus/{id}", menu), mine, null).andExpect(jsonPath("$.data.needsReview").value(false));

		// 다시 고쳐도 AI 에서 왔다는 표시는 남는다
		call(patch("/recipes/{id}", estimated), mine, """
				{"qtyPerServing": 175}
				""")
				.andExpect(jsonPath("$.data.source").value("OWNER_CONFIRMED"));
	}

	@Test
	void 주기형_재료는_레시피에_넣을_수_없다() throws Exception {
		long cycle = jdbc.queryForObject(
				"INSERT INTO ingredient (store_id, name, unit, price_sensitive, sourcing_type, kind, storage_location, category_id, category_source)"
						+ " VALUES (?, '토마토 홀', 'G', false, 'GENERIC', 'CYCLE', 'WAREHOUSE', " + OTHER_CATEGORY_SQL + ", 'AUTO') RETURNING id",
				Long.class, mine.storeId(), mine.storeId());

		call(post("/recipes"), mine, """
				{"menuId": "%d", "ingredientId": "%d", "qtyPerServing": 30}
				""".formatted(menu, cycle))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("RECIPE_CYCLE_INGREDIENT"));
	}

	@Test
	void 삭제() throws Exception {
		long recipe = insertRecipe(jdbc, mine.storeId(), menu, ingredient, "MANUAL");

		call(delete("/recipes/{id}", recipe), mine, null).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM recipe WHERE id = ?", Long.class, recipe)).isZero();
	}

	@Test
	void 남의_가게_메뉴_재료_줄은_모두_404() throws Exception {
		long theirMenu = insertMenu(jdbc, other.storeId(), "남의메뉴");
		long theirIngredient = insertIngredient(jdbc, other.storeId(), "남의재료");
		long theirRecipe = insertRecipe(jdbc, other.storeId(), theirMenu, theirIngredient, "AI");

		call(post("/recipes"), mine, """
				{"menuId": "%d", "ingredientId": "%d", "qtyPerServing": 10}
				""".formatted(theirMenu, ingredient))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));
		call(post("/recipes"), mine, """
				{"menuId": "%d", "ingredientId": "%d", "qtyPerServing": 10}
				""".formatted(menu, theirIngredient))
				.andExpect(status().isNotFound());
		call(get("/recipes/{id}", theirRecipe), mine, null).andExpect(status().isNotFound());
		call(patch("/recipes/{id}", theirRecipe), mine, """
				{"qtyPerServing": 1}
				""")
				.andExpect(status().isNotFound());
		call(delete("/recipes/{id}", theirRecipe), mine, null).andExpect(status().isNotFound());
		call(get("/recipes").param("menuId", String.valueOf(theirMenu)), mine, null).andExpect(status().isNotFound());
		call(get("/recipes"), mine, null).andExpect(jsonPath("$.data", empty()));

		assertThat(jdbc.queryForObject("SELECT source FROM recipe WHERE id = ?", String.class, theirRecipe))
				.isEqualTo("AI");
	}

	private ResultActions call(MockHttpServletRequestBuilder request, LoggedInStore store, String body)
			throws Exception {
		request.header(HttpHeaders.AUTHORIZATION, store.bearer());
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mockMvc.perform(request);
	}
}
