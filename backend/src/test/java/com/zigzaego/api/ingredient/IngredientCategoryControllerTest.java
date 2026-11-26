package com.zigzaego.api.ingredient;

import static com.zigzaego.api.support.StoreFixtures.insertIngredient;
import static com.zigzaego.api.support.StoreFixtures.loginNewStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.llm.GmsClient;
import com.zigzaego.api.support.StoreFixtures.LoggedInStore;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 재료 카테고리 CRUD · 재료의 카테고리 고르기 (S15P31A308-275). 가게 2개를 넣어 남의 가게 카테고리가 404 인지 본다.
 * 사장님이 고른 카테고리는 출처 OWNER 로 남아 자동 분류가 덮어쓰지 않는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.ingredient-category.backup-dir=build/test-data/ingredient-categories-crud")
class IngredientCategoryControllerTest {

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@MockitoBean
	private GmsClient gmsClient;

	private MockMvc mockMvc;

	private LoggedInStore mine;

	private LoggedInStore other;

	@BeforeEach
	void setUp() throws Exception {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		mine = loginNewStore(jdbc, passwordEncoder, mockMvc, "내가게");
		other = loginNewStore(jdbc, passwordEncoder, mockMvc, "남의가게");
	}

	@Test
	void 목록은_기본_11종이_순서대로_오고_재료_수가_붙는다() throws Exception {
		insertIngredient(jdbc, mine.storeId(), "양파");
		insertIngredient(jdbc, mine.storeId(), "대파");
		insertIngredient(jdbc, other.storeId(), "남의양파");

		call(get("/ingredient-categories"), mine, null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data", hasSize(11)))
				.andExpect(jsonPath("$.data[0].code").value("MEAT"))
				.andExpect(jsonPath("$.data[0].name").value("육류"))
				.andExpect(jsonPath("$.data[0].sortOrder").value(1))
				.andExpect(jsonPath("$.data[0].ingredientCount").value(0))
				.andExpect(jsonPath("$.data[10].code").value("OTHER"))
				.andExpect(jsonPath("$.data[10].sortOrder").value(99))
				.andExpect(jsonPath("$.data[10].ingredientCount").value(2));
	}

	@Test
	void 만들면_기타_앞_순서로_생기고_공백만_다른_이름은_409_30개를_넘으면_400() throws Exception {
		String created = call(post("/ingredient-categories"), mine, "{\"name\": \" 디저트 \"}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.name").value("디저트"))
				.andExpect(jsonPath("$.data.code").doesNotExist())
				.andExpect(jsonPath("$.data.sortOrder").value(11))
				.andExpect(jsonPath("$.data.ingredientCount").value(0))
				.andReturn().getResponse().getContentAsString();
		long id = Long.parseLong(JsonPath.read(created, "$.data.id"));
		assertThat(jdbc.queryForMap("SELECT store_id, code FROM ingredient_category WHERE id = ?", id))
				.containsEntry("store_id", mine.storeId()).containsEntry("code", null);

		call(post("/ingredient-categories"), mine, "{\"name\": \"디 저트\"}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("INGREDIENT_CATEGORY_DUPLICATE_NAME"));
		call(post("/ingredient-categories"), mine, "{\"name\": \"육류\"}")
				.andExpect(status().isConflict());
		call(post("/ingredient-categories"), mine, "{\"name\": \"   \"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
		// 남의 가게에는 같은 이름을 만들 수 있다 (가게별 표)
		call(post("/ingredient-categories"), other, "{\"name\": \"디저트\"}").andExpect(status().isCreated());

		// 기본 11 + 디저트 1 = 12. 18개를 더 만들면 30, 그다음은 400
		for (int i = 0; i < 18; i++) {
			call(post("/ingredient-categories"), mine, "{\"name\": \"카테고리" + i + "\"}").andExpect(status().isCreated());
		}
		call(post("/ingredient-categories"), mine, "{\"name\": \"서른한번째\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INGREDIENT_CATEGORY_LIMIT_EXCEEDED"));
		call(get("/ingredient-categories"), mine, null).andExpect(jsonPath("$.data", hasSize(30)));
	}

	@Test
	void 이름_순서를_고치고_기타는_이름을_못_바꾸고_순서도_99_그대로() throws Exception {
		long dessert = create(mine, "디저트");
		long otherId = otherCategoryId(mine);

		call(patch("/ingredient-categories/{id}", dessert), mine, "{\"name\": \"수제 디저트\", \"sortOrder\": 3}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.name").value("수제 디저트"))
				.andExpect(jsonPath("$.data.sortOrder").value(3));
		// 기본 카테고리 이름도 바꿀 수 있고 코드는 그대로
		call(patch("/ingredient-categories/{id}", categoryId(mine, "MEAT")), mine, "{\"name\": \"고기\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.name").value("고기"))
				.andExpect(jsonPath("$.data.code").value("MEAT"));
		call(patch("/ingredient-categories/{id}", dessert), mine, "{\"name\": \"고 기\"}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("INGREDIENT_CATEGORY_DUPLICATE_NAME"));
		call(patch("/ingredient-categories/{id}", dessert), mine, "{\"name\": \"수제 디저트\"}")  // 같은 이름 그대로는 괜찮다
				.andExpect(status().isOk());

		call(patch("/ingredient-categories/{id}", otherId), mine, "{\"name\": \"미분류\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INGREDIENT_CATEGORY_OTHER_IMMUTABLE"));
		call(patch("/ingredient-categories/{id}", otherId), mine, "{\"sortOrder\": 1}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.sortOrder").value(99));
		call(patch("/ingredient-categories/{id}", dessert), mine, "{\"sortOrder\": -1}")
				.andExpect(status().isBadRequest());
		call(patch("/ingredient-categories/{id}", dessert), mine, "{\"sortOrder\": 99}")  // 기타(99) 뒤로는 못 간다
				.andExpect(status().isBadRequest());
		// 새 카테고리는 목록에서 기타 앞
		create(mine, "음료2");
		call(get("/ingredient-categories"), mine, null)
				.andExpect(jsonPath("$.data[11].name").value("음료2"))
				.andExpect(jsonPath("$.data[12].code").value("OTHER"));
	}

	@Test
	void 지우면_그_재료는_기타로_가고_기타는_못_지운다() throws Exception {
		long dessert = create(mine, "디저트");
		long cake = insertIngredient(jdbc, mine.storeId(), "케이크");
		long pudding = insertIngredient(jdbc, mine.storeId(), "푸딩");
		long onion = insertIngredient(jdbc, mine.storeId(), "양파");
		jdbc.update("UPDATE ingredient SET category_id = ?, category_source = 'OWNER' WHERE id IN (?, ?)", dessert, cake, pudding);
		jdbc.update("UPDATE ingredient SET category_id = ?, category_source = 'OWNER' WHERE id = ?", categoryId(mine, "VEGETABLE"), onion);

		call(delete("/ingredient-categories/{id}", dessert), mine, null).andExpect(status().isOk());

		assertThat(jdbc.queryForObject("SELECT count(*) FROM ingredient_category WHERE id = ?", Long.class, dessert)).isZero();
		for (long id : new long[] {cake, pudding}) {
			Map<String, Object> row = jdbc.queryForMap("SELECT category_id, category_source FROM ingredient WHERE id = ?", id);
			assertThat(row).containsEntry("category_id", otherCategoryId(mine)).containsEntry("category_source", "AUTO");
		}
		assertThat(jdbc.queryForMap("SELECT category_id, category_source FROM ingredient WHERE id = ?", onion))
				.containsEntry("category_id", categoryId(mine, "VEGETABLE")).containsEntry("category_source", "OWNER");

		call(delete("/ingredient-categories/{id}", otherCategoryId(mine)), mine, null)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INGREDIENT_CATEGORY_OTHER_IMMUTABLE"));
		call(delete("/ingredient-categories/{id}", dessert), mine, null).andExpect(status().isNotFound());
	}

	@Test
	void 내_카테고리를_지워도_남의_가게_같은_이름_카테고리의_재료는_그대로() throws Exception {
		long mineDessert = create(mine, "디저트");
		long theirDessert = create(other, "디저트");
		long theirCake = insertIngredient(jdbc, other.storeId(), "케이크");
		jdbc.update("UPDATE ingredient SET category_id = ? WHERE id = ?", theirDessert, theirCake);

		call(delete("/ingredient-categories/{id}", mineDessert), mine, null).andExpect(status().isOk());

		assertThat(jdbc.queryForMap("SELECT category_id FROM ingredient WHERE id = ?", theirCake))
				.containsEntry("category_id", theirDessert);
		call(get("/ingredient-categories"), other, null)   // 기본 10종(1~10) · 디저트(11) · 기타(99)
				.andExpect(jsonPath("$.data[10].name").value("디저트"))
				.andExpect(jsonPath("$.data[10].ingredientCount").value(1))
				.andExpect(jsonPath("$.data[11].code").value("OTHER"));
	}

	@Test
	void 재료의_카테고리를_고르면_OWNER_가_되고_자동_분류가_덮어쓰지_않는다() throws Exception {
		long ingredient = insertIngredient(jdbc, mine.storeId(), "정체불명");
		long meat = categoryId(mine, "MEAT");

		call(patch("/ingredients/{id}", ingredient), mine, "{\"categoryId\": \"" + meat + "\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.category.id").value(String.valueOf(meat)))
				.andExpect(jsonPath("$.data.category.code").value("MEAT"));
		assertThat(jdbc.queryForMap("SELECT category_source FROM ingredient WHERE id = ?", ingredient))
				.containsEntry("category_source", "OWNER");

		// 사장님이 고른 뒤 기타로 돌려도 OWNER — auto-assign 대상이 아니다
		call(patch("/ingredients/{id}", ingredient), mine, "{\"categoryId\": \"" + otherCategoryId(mine) + "\"}")
				.andExpect(status().isOk());
		call(post("/ingredient-categories/auto-assign"), mine, null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.assignedCount").value(0))
				.andExpect(jsonPath("$.data.otherCount").value(1));
		verifyNoInteractions(gmsClient);

		// 만들 때 categoryId 를 보내면 자동 분류를 건너뛰고 OWNER
		call(post("/ingredients"), mine, "{\"name\": \"특제 소스\", \"unit\": \"G\", \"kind\": \"BOM\", \"storageType\": \"CHILLED\","
				+ " \"categoryId\": \"" + categoryId(mine, "SAUCE") + "\"}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.category.code").value("SAUCE"));
		assertThat(jdbc.queryForObject("SELECT category_source FROM ingredient WHERE store_id = ? AND name = '특제 소스'",
				String.class, mine.storeId())).isEqualTo("OWNER");
		verifyNoInteractions(gmsClient);
	}

	@Test
	void 남의_가게_카테고리_id_는_재료에_붙이지_못하고_고치기_지우기도_404() throws Exception {
		long ingredient = insertIngredient(jdbc, mine.storeId(), "양파");
		long theirMeat = categoryId(other, "MEAT");
		long theirDessert = create(other, "디저트");

		call(patch("/ingredients/{id}", ingredient), mine, "{\"categoryId\": \"" + theirMeat + "\"}")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));
		call(patch("/ingredients/{id}", ingredient), mine, "{\"categoryId\": \"abc\"}")
				.andExpect(status().isNotFound());
		// 다른 칸과 함께 보내도 카테고리가 404 면 전부 되돌린다
		call(patch("/ingredients/{id}", ingredient), mine, "{\"name\": \"바뀐양파\", \"categoryId\": \"" + theirMeat + "\"}")
				.andExpect(status().isNotFound());
		assertThat(jdbc.queryForObject("SELECT name FROM ingredient WHERE id = ?", String.class, ingredient)).isEqualTo("양파");
		call(post("/ingredients"), mine, "{\"name\": \"새재료\", \"unit\": \"G\", \"kind\": \"BOM\", \"storageType\": \"CHILLED\","
				+ " \"categoryId\": \"" + theirMeat + "\"}")
				.andExpect(status().isNotFound());
		assertThat(jdbc.queryForMap("SELECT category_id, category_source FROM ingredient WHERE id = ?", ingredient))
				.containsEntry("category_id", otherCategoryId(mine)).containsEntry("category_source", "AUTO");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM ingredient WHERE store_id = ?", Long.class, mine.storeId()))
				.isEqualTo(1L);

		call(patch("/ingredient-categories/{id}", theirDessert), mine, "{\"name\": \"훔친 이름\"}").andExpect(status().isNotFound());
		call(delete("/ingredient-categories/{id}", theirDessert), mine, null).andExpect(status().isNotFound());
		call(delete("/ingredient-categories/{id}", "abc"), mine, null).andExpect(status().isNotFound());
		assertThat(jdbc.queryForObject("SELECT name FROM ingredient_category WHERE id = ?", String.class, theirDessert))
				.isEqualTo("디저트");
		// 목록에도 남의 가게 것은 없다
		call(get("/ingredient-categories"), mine, null).andExpect(jsonPath("$.data", hasSize(11)));
	}

	@Test
	void 인증_없으면_401() throws Exception {
		mockMvc.perform(get("/ingredient-categories")).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/ingredient-categories").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
				.andExpect(status().isUnauthorized());
	}

	private long create(LoggedInStore store, String name) throws Exception {
		String body = call(post("/ingredient-categories"), store, "{\"name\": \"" + name + "\"}")
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return Long.parseLong(JsonPath.read(body, "$.data.id"));
	}

	private long categoryId(LoggedInStore store, String code) {
		return jdbc.queryForObject("SELECT id FROM ingredient_category WHERE store_id = ? AND code = ?", Long.class,
				store.storeId(), code);
	}

	private long otherCategoryId(LoggedInStore store) {
		return categoryId(store, "OTHER");
	}

	private ResultActions call(MockHttpServletRequestBuilder request, LoggedInStore store, String body) throws Exception {
		request.header(HttpHeaders.AUTHORIZATION, store.bearer());
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mockMvc.perform(request);
	}
}
