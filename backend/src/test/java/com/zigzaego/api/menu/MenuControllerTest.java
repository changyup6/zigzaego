package com.zigzaego.api.menu;

import static com.zigzaego.api.support.StoreFixtures.insertIngredient;
import static com.zigzaego.api.support.StoreFixtures.insertMenu;
import static com.zigzaego.api.support.StoreFixtures.insertRecipe;
import static com.zigzaego.api.support.StoreFixtures.loginNewStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.support.StoreFixtures.LoggedInStore;
import java.math.BigDecimal;
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

/** 메뉴 CRUD (S15P31A308-44). 실제 로그인 토큰으로 부르고 가게 2개를 넣어 남의 가게 메뉴가 404 인지 본다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class MenuControllerTest {

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PasswordEncoder passwordEncoder;

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
	void 만들면_판매_중_MAIN_같은_이름은_409() throws Exception {
		call(post("/menus"), mine, """
				{"name": "김치찌개", "description": "묵은지", "source": "NAVER"}
				""")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.name").value("김치찌개"))
				.andExpect(jsonPath("$.data.kind").value("MAIN"))
				.andExpect(jsonPath("$.data.active").value(true))
				.andExpect(jsonPath("$.data.description").value("묵은지"))
				.andExpect(jsonPath("$.data.source").value("NAVER"))
				.andExpect(jsonPath("$.data.needsReview").value(false));

		call(post("/menus"), mine, """
				{"name": "김치찌개", "source": "POS"}
				""")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("MENU_DUPLICATE_NAME"));
		call(post("/menus"), mine, """
				{"name": "된장찌개"}
				""")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
	}

	@Test
	void 판매가는_원_단위_정수로_받고_준다_소수_음수는_400() throws Exception {
		String id = JsonPath.read(call(post("/menus"), mine, """
						{"name": "불고기", "source": "POS", "price": 12000}
						""")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.price").value(12000))
				.andReturn().getResponse().getContentAsString(), "$.data.id");

		call(patch("/menus/{id}", id), mine, """
				{"price": 13000}
				""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.price").value(13000));
		assertThat(jdbc.queryForObject("SELECT price FROM menu WHERE id = ?", BigDecimal.class, Long.parseLong(id)))
				.isEqualByComparingTo("13000");

		call(patch("/menus/{id}", id), mine, """
				{"price": 13000.5}
				""")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
		call(post("/menus"), mine, """
				{"name": "갈비", "source": "POS", "price": -1}
				""")
				.andExpect(status().isBadRequest());
		call(post("/menus"), mine, """
				{"name": "냉면", "source": "POS"}
				""")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.price").value(nullValue()));
	}

	@Test
	void AI_추정_레시피가_남은_메뉴는_확인_필요로_맨_위() throws Exception {
		long settled = insertMenu(jdbc, mine.storeId(), "샐러드");
		long estimated = insertMenu(jdbc, mine.storeId(), "파스타");
		long ingredient = insertIngredient(jdbc, mine.storeId(), "면");
		insertRecipe(jdbc, mine.storeId(), estimated, ingredient, "AI");
		insertRecipe(jdbc, mine.storeId(), settled, ingredient, "MANUAL");

		call(get("/menus"), mine, null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].id").value(String.valueOf(estimated)))
				.andExpect(jsonPath("$.data[0].needsReview").value(true))
				.andExpect(jsonPath("$.data[0].reviewReasons", contains("UNANSWERED")))
				.andExpect(jsonPath("$.data[1].id").value(String.valueOf(settled)))
				.andExpect(jsonPath("$.data[1].needsReview").value(false));
	}

	@Test
	void 보낸_칸만_고친다() throws Exception {
		long menu = insertMenu(jdbc, mine.storeId(), "라면");
		insertMenu(jdbc, mine.storeId(), "우동");

		call(patch("/menus/{id}", menu), mine, """
				{"active": false, "kind": "OPTION"}
				""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.name").value("라면"))
				.andExpect(jsonPath("$.data.kind").value("OPTION"))
				.andExpect(jsonPath("$.data.active").value(false))
				.andExpect(jsonPath("$.data.source").value("POS"));
		call(patch("/menus/{id}", menu), mine, """
				{"name": "우동"}
				""")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("MENU_DUPLICATE_NAME"));
	}

	@Test
	void 삭제하면_레시피_줄과_포스_이름도_지우고_판매_기록이_있으면_409() throws Exception {
		long menu = insertMenu(jdbc, mine.storeId(), "볶음밥");
		long sold = insertMenu(jdbc, mine.storeId(), "짜장면");
		long ingredient = insertIngredient(jdbc, mine.storeId(), "밥");
		insertRecipe(jdbc, mine.storeId(), menu, ingredient, "AI");
		jdbc.update("INSERT INTO menu_alias (store_id, menu_id, pos_name) VALUES (?, ?, '볶음밥(대)')", mine.storeId(), menu);
		jdbc.update("INSERT INTO pos_sale (store_id, sold_date, menu_id, qty) VALUES (?, DATE '2026-10-01', ?, 3)",
				mine.storeId(), sold);

		call(delete("/menus/{id}", menu), mine, null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM menu WHERE id = ?", Long.class, menu)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM recipe WHERE menu_id = ?", Long.class, menu)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM menu_alias WHERE menu_id = ?", Long.class, menu)).isZero();

		call(delete("/menus/{id}", sold), mine, null)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("MENU_IN_USE"));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM menu WHERE id = ?", Long.class, sold)).isOne();
	}

	@Test
	void 남의_가게_메뉴는_조회_수정_삭제_모두_404_목록에도_없다() throws Exception {
		long theirs = insertMenu(jdbc, other.storeId(), "남의메뉴");

		call(get("/menus/{id}", theirs), mine, null)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));
		call(patch("/menus/{id}", theirs), mine, """
				{"active": false}
				""")
				.andExpect(status().isNotFound());
		call(delete("/menus/{id}", theirs), mine, null).andExpect(status().isNotFound());
		call(get("/menus"), mine, null).andExpect(jsonPath("$.data", empty()));

		assertThat(jdbc.queryForObject("SELECT active FROM menu WHERE id = ?", Boolean.class, theirs)).isTrue();
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
