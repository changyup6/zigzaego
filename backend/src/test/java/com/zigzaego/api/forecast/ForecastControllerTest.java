package com.zigzaego.api.forecast;

import static com.zigzaego.api.forecast.ForecastTestData.insertForecast;
import static com.zigzaego.api.forecast.ForecastTestData.insertMenu;
import static com.zigzaego.api.forecast.ForecastTestData.insertStore;
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
class ForecastControllerTest {

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
	private long 돈까스;
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

		// 내 가게: 오늘 timesfm 배달 3 + 홀 2 = 5
		돈까스 = insertMenu(jdbc, myStore, "돈까스");
		insertForecast(jdbc, myStore, 돈까스, today, "timesfm", "DELIVERY", "3");
		insertForecast(jdbc, myStore, 돈까스, today, "timesfm", "HALL", "2");

		// 남의 가게: 메뉴 2개, 기록 없음 — 응답에 섞이면 안 된다
		insertMenu(jdbc, otherStore, "콜라");
		insertMenu(jdbc, otherStore, "김밥");
	}

	@Test
	void 날짜_없으면_오늘_내_가게_메뉴만_200과_줄_모양() throws Exception {
		mockMvc.perform(get("/forecast").with(user("owner")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.error").value(nullValue()))
				.andExpect(jsonPath("$.data", hasSize(1)))
				.andExpect(jsonPath("$.data[0].menuId").isString())
				.andExpect(jsonPath("$.data[0].menuId").value(String.valueOf(돈까스)))
				.andExpect(jsonPath("$.data[0].menuName").value("돈까스"))
				.andExpect(jsonPath("$.data[0].targetDate").value(today.toString()))
				.andExpect(jsonPath("$.data[0].predictedQty").isNumber())
				.andExpect(jsonPath("$.data[0].predictedQty").value(5.0))
				.andExpect(jsonPath("$.data[0].model").value("TIMESFM"))
				.andExpect(content().string(containsString("\"predictedQty\":5.000,")));
	}

	@Test
	void 날짜를_주면_그날_timesfm_없으면_요일평균() throws Exception {
		mockMvc.perform(get("/forecast").param("date", today.plusDays(1).toString()).with(user("owner")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data", hasSize(1)))
				.andExpect(jsonPath("$.data[0].targetDate").value(today.plusDays(1).toString()))
				.andExpect(jsonPath("$.data[0].predictedQty").value(0.0))
				.andExpect(jsonPath("$.data[0].model").value("DOW_AVG"));
	}

	@Test
	void 다른_가게로_바꾸면_그_가게_메뉴만() throws Exception {
		currentStore.storeId = otherStore;

		mockMvc.perform(get("/forecast").with(user("owner")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data", hasSize(2)))
				.andExpect(jsonPath("$.data[0].menuName").value("콜라"))
				.andExpect(jsonPath("$.data[0].predictedQty").value(0.0))
				.andExpect(jsonPath("$.data[0].model").value("DOW_AVG"));
	}

	@Test
	void 날짜_형식_틀리면_400() throws Exception {
		mockMvc.perform(get("/forecast").param("date", "20261010").with(user("owner")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));

		mockMvc.perform(get("/forecast").param("date", "2026-02-30").with(user("owner")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
	}

	@Test
	void 인증_없으면_401() throws Exception {
		mockMvc.perform(get("/forecast"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("COMMON_UNAUTHORIZED"));
	}

}
