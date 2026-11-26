package com.zigzaego.api.consumption;

import static com.zigzaego.api.consumption.ConsumptionTestData.insertIngredient;
import static com.zigzaego.api.consumption.ConsumptionTestData.insertMenu;
import static com.zigzaego.api.consumption.ConsumptionTestData.insertRecipe;
import static com.zigzaego.api.consumption.ConsumptionTestData.insertSale;
import static com.zigzaego.api.consumption.ConsumptionTestData.insertStore;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.support.FakeStoreConfig;
import com.zigzaego.api.support.FakeStoreConfig.FakeCurrentStoreProvider;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@Import({TestcontainersConfiguration.class, FakeStoreConfig.class})
@SpringBootTest
class ConsumptionControllerTest {

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private FakeCurrentStoreProvider currentStore;

	private MockMvc mockMvc;

	private long 등심;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.apply(springSecurity())
				.build();
		long myStore = insertStore(jdbc, "API내가게");
		long otherStore = insertStore(jdbc, "API남의가게");
		currentStore.storeId = myStore;

		// 내 가게: 10-01 돈까스 D2 + H2 = 4인분 × 등심 1500 = 6000 G
		등심 = insertIngredient(jdbc, myStore, "등심", "G", "BOM");
		long 돈까스 = insertMenu(jdbc, myStore, "돈까스", "MAIN");
		insertRecipe(jdbc, myStore, 돈까스, 등심, "1500");
		insertSale(jdbc, myStore, LocalDate.of(2026, 10, 1), 돈까스, "DELIVERY", "2");
		insertSale(jdbc, myStore, LocalDate.of(2026, 10, 1), 돈까스, "HALL", "2");

		// 남의 가게: 같은 날 판매 — 응답에 섞이면 안 된다
		long 등심B = insertIngredient(jdbc, otherStore, "등심", "G", "BOM");
		long 돈까스B = insertMenu(jdbc, otherStore, "돈까스", "MAIN");
		insertRecipe(jdbc, otherStore, 돈까스B, 등심B, "100");
		insertSale(jdbc, otherStore, LocalDate.of(2026, 10, 1), 돈까스B, "HALL", "7");
	}

	@Test
	void 정상_기간은_200과_소비량_줄_모양() throws Exception {
		mockMvc.perform(get("/consumption/daily").param("from", "2026-10-01").param("to", "2026-10-03")
						.with(user("owner")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.error").value(nullValue()))
				.andExpect(jsonPath("$.data", hasSize(1)))
				.andExpect(jsonPath("$.data[0].date").value("2026-10-01"))
				.andExpect(jsonPath("$.data[0].ingredientId").isString())
				.andExpect(jsonPath("$.data[0].ingredientId").value(String.valueOf(등심)))
				.andExpect(jsonPath("$.data[0].ingredientName").value("등심"))
				.andExpect(jsonPath("$.data[0].unit").value("G"))
				.andExpect(jsonPath("$.data[0].qty").isNumber())
				.andExpect(jsonPath("$.data[0].qty").value(6000.0))
				.andExpect(content().string(containsString("\"qty\":6000.000}")));
	}

	@Test
	void 판매_없는_기간은_200과_빈_목록() throws Exception {
		mockMvc.perform(get("/consumption/daily").param("from", "2026-11-01").param("to", "2026-11-30")
						.with(user("owner")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data", hasSize(0)));
	}

	@Test
	void 기간_92일은_200() throws Exception {
		// 2026-01-01 ~ 2026-04-02 = 31 + 28 + 31 + 2 = 92일
		mockMvc.perform(get("/consumption/daily").param("from", "2026-01-01").param("to", "2026-04-02")
						.with(user("owner")))
				.andExpect(status().isOk());
	}

	@Test
	void 기간_93일은_400() throws Exception {
		// 2026-01-01 ~ 2026-04-03 = 93일
		expectInvalid(mockMvc.perform(get("/consumption/daily").param("from", "2026-01-01").param("to", "2026-04-03")
				.with(user("owner"))));
	}

	@Test
	void from이_to보다_늦으면_400() throws Exception {
		expectInvalid(mockMvc.perform(get("/consumption/daily").param("from", "2026-10-03").param("to", "2026-10-01")
				.with(user("owner"))));
	}

	@Test
	void 날짜_형식이_틀리면_400() throws Exception {
		expectInvalid(mockMvc.perform(get("/consumption/daily").param("from", "2026/10/01").param("to", "2026-10-03")
				.with(user("owner"))));
		expectInvalid(mockMvc.perform(get("/consumption/daily").param("from", "2026-10-01").param("to", "2026-02-30")
				.with(user("owner"))));
		// 기간 · 순서는 맞게 둬서 형식 검사만으로 400 이 나는지 본다
		expectInvalid(mockMvc.perform(get("/consumption/daily").param("from", "+10000-01-01").param("to", "+10000-01-02")
				.with(user("owner"))));
		expectInvalid(mockMvc.perform(get("/consumption/daily").param("from", "-0001-01-01").param("to", "-0001-01-02")
				.with(user("owner"))));
		expectInvalid(mockMvc.perform(get("/consumption/daily").param("from", "2026-10-01").param("to", "+2026-10-02")
				.with(user("owner"))));
	}

	@Test
	void 파라미터가_빠지면_400() throws Exception {
		expectInvalid(mockMvc.perform(get("/consumption/daily").param("from", "2026-10-01").with(user("owner"))));
		expectInvalid(mockMvc.perform(get("/consumption/daily").param("to", "2026-10-01").with(user("owner"))));
		expectInvalid(mockMvc.perform(get("/consumption/daily").param("from", "").param("to", "2026-10-01")
				.with(user("owner"))));
	}

	@Test
	void 인증_없으면_401() throws Exception {
		mockMvc.perform(get("/consumption/daily").param("from", "2026-10-01").param("to", "2026-10-03"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("COMMON_UNAUTHORIZED"));
	}

	private static void expectInvalid(ResultActions actions) throws Exception {
		actions.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
	}

}
