package com.zigzaego.api.recommendation;

import static com.zigzaego.api.recommendation.RecommendationTestData.insertCount;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertIngredient;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertMenu;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertRecipe;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertSale;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertStore;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.support.FakeStoreConfig;
import com.zigzaego.api.support.FakeStoreConfig.FakeCurrentStoreProvider;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@Import({TestcontainersConfiguration.class, FakeStoreConfig.class})
@SpringBootTest
class RecommendationControllerTest {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private FakeCurrentStoreProvider currentStore;

	private MockMvc mockMvc;

	private long myStore;
	private long otherStore;
	private long 등심;
	private LocalDate today;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.apply(springSecurity())
				.build();
		myStore = insertStore(jdbc, "API내가게");
		otherStore = insertStore(jdbc, "API남의가게");
		currentStore.storeId = myStore;
		today = LocalDate.now(SEOUL);

		// 내 가게: 어제까지 7일 매일 10 판매, 어제 실사 4000 → 2500 ≤ ROP 4500 → 발주 4000(포장 없음 → 3500 그대로)
		등심 = insertIngredient(jdbc, myStore, "등심", "G", "BOM", "CHILLED", "2", false);
		long 돈까스 = insertMenu(jdbc, myStore, "돈까스");
		insertRecipe(jdbc, myStore, 돈까스, 등심, "150", "MANUAL");
		for (int i = 1; i <= 7; i++) {
			insertSale(jdbc, myStore, today.minusDays(i), 돈까스, "10");
		}
		insertCount(jdbc, myStore, 등심, OffsetDateTime.now(SEOUL).minusDays(1).withHour(0).withMinute(0), "4000");

		// 남의 가게: 재료 하나 — 내 응답에 섞이면 안 된다
		long 남의 = insertIngredient(jdbc, otherStore, "등심", "G", "BOM", "CHILLED", "2", false);
		long 남의돈까스 = insertMenu(jdbc, otherStore, "돈까스");
		insertRecipe(jdbc, otherStore, 남의돈까스, 남의, "150", "MANUAL");
	}

	@Test
	void 수동_실행_200과_결과_모양() throws Exception {
		mockMvc.perform(post("/recommendations/run").with(user("owner")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.error").value(nullValue()))
				.andExpect(jsonPath("$.data.runDate").value(today.toString()))
				.andExpect(jsonPath("$.data.createdCount").value(1))
				.andExpect(jsonPath("$.data.existingCount").value(0))
				.andExpect(jsonPath("$.data.recommendations", hasSize(1)))
				.andExpect(jsonPath("$.data.recommendations[0].id").isString())
				.andExpect(jsonPath("$.data.recommendations[0].ingredientId").value(String.valueOf(등심)))
				.andExpect(jsonPath("$.data.recommendations[0].ingredientName").value("등심"))
				.andExpect(jsonPath("$.data.recommendations[0].unit").value("G"))
				.andExpect(jsonPath("$.data.recommendations[0].status").value("PENDING"))
				.andExpect(jsonPath("$.data.recommendations[0].forecastModel").value("dow_avg"))
				.andExpect(jsonPath("$.data.recommendations[0].ssMethod").value("DAYS_N"))
				.andExpect(jsonPath("$.data.recommendations[0].recommendedQty").value(3500.0))
				.andExpect(jsonPath("$.data.recommendations[0].needsReview").value(true))
				.andExpect(jsonPath("$.data.recommendations[0].reviewReasons[0]").value("SUPPLIER_UNSET"))
				.andExpect(content().string(containsString("\"recommendedQty\":3500.000,")));

		// 두 번째 실행: 새로 만들지 않는다
		mockMvc.perform(post("/recommendations/run").with(user("owner")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.createdCount").value(0))
				.andExpect(jsonPath("$.data.existingCount").value(1))
				.andExpect(jsonPath("$.data.recommendations", hasSize(1)));
	}

	@Test
	void 같은_날_재실행은_force_없이_건너뛰고_force면_없는_재료만_계산한다() throws Exception {
		mockMvc.perform(post("/recommendations/run").with(user("owner"))).andExpect(status().isOk());

		// 실행 뒤 재료가 늘었다 — force 없이는 그날 끝난 실행이 있어 건너뛴다
		long 양배추 = insertIngredient(jdbc, myStore, "양배추", "G", "BOM", "CHILLED", "2", false);
		long 돈까스2 = insertMenu(jdbc, myStore, "돈까스2");
		insertRecipe(jdbc, myStore, 돈까스2, 양배추, "80", "MANUAL");
		for (int i = 1; i <= 7; i++) {
			insertSale(jdbc, myStore, today.minusDays(i), 돈까스2, "10");
		}
		insertCount(jdbc, myStore, 양배추, OffsetDateTime.now(SEOUL).minusDays(1).withHour(0).withMinute(0), "100");

		mockMvc.perform(post("/recommendations/run").with(user("owner")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.createdCount").value(0))
				.andExpect(jsonPath("$.data.existingCount").value(1))
				.andExpect(jsonPath("$.data.recommendations", hasSize(1)));

		mockMvc.perform(post("/recommendations/run").param("force", "true").with(user("owner")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.createdCount").value(1))
				.andExpect(jsonPath("$.data.existingCount").value(1))
				.andExpect(jsonPath("$.data.recommendations", hasSize(2)))
				.andExpect(jsonPath("$.data.recommendations[0].ingredientId").value(String.valueOf(등심)))
				.andExpect(jsonPath("$.data.recommendations[1].ingredientId").value(String.valueOf(양배추)));
	}

	@Test
	void 조회는_날짜_없으면_오늘_있으면_그날() throws Exception {

		mockMvc.perform(post("/recommendations/run").with(user("owner"))).andExpect(status().isOk());

		mockMvc.perform(get("/recommendations").with(user("owner")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data", hasSize(1)))
				.andExpect(jsonPath("$.data[0].runDate").value(today.toString()));

		mockMvc.perform(get("/recommendations").param("date", today.minusDays(1).toString()).with(user("owner")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data", hasSize(0)));
	}

	@Test
	void 다른_가게로_바꾸면_그_가게_추천만() throws Exception {
		mockMvc.perform(post("/recommendations/run").with(user("owner"))).andExpect(status().isOk());

		currentStore.storeId = otherStore;
		mockMvc.perform(get("/recommendations").with(user("owner")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data", hasSize(0)));
	}

	@Test
	void 날짜_형식_틀리면_400() throws Exception {
		mockMvc.perform(get("/recommendations").param("date", "20261010").with(user("owner")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));

		mockMvc.perform(post("/recommendations/run").param("date", "2026-02-30").with(user("owner")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
	}

	@Test
	void 인증_없으면_401() throws Exception {
		mockMvc.perform(get("/recommendations"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("COMMON_UNAUTHORIZED"));

		mockMvc.perform(post("/recommendations/run"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("COMMON_UNAUTHORIZED"));
	}

}
