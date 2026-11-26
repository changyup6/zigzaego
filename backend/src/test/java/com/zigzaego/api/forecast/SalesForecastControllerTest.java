package com.zigzaego.api.forecast;

import static com.zigzaego.api.forecast.ForecastTestData.insertStore;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.support.FakeStoreConfig;
import com.zigzaego.api.support.FakeStoreConfig.FakeCurrentStoreProvider;
import java.sql.Date;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
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
class SalesForecastControllerTest {

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private FakeCurrentStoreProvider currentStore;

	private MockMvc mockMvc;
	private long myStore;
	private long otherStore;
	private LocalDate today;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		myStore = insertStore(jdbc, "매출내가게");
		otherStore = insertStore(jdbc, "매출남의가게");
		currentStore.storeId = myStore;
		today = LocalDate.now(ZoneId.of("Asia/Seoul"));
		LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
		String days = "[{\"date\":\"" + monday.minusDays(1) + "\",\"label\":\"일\",\"value\":98},{\"date\":\"" + today
				+ "\",\"label\":\"x\",\"value\":121}]";
		jdbc.update("INSERT INTO sales_forecast_week (store_id, week_start, model, pct, reason, days, factors) "
				+ "VALUES (?, ?, 'seoulevent-v2', 38, '일 11.15 잠실 마라톤', ?::jsonb, ?::jsonb)",
				myStore, Date.valueOf(monday), days,
				"[{\"name\":\"잠실 마라톤\",\"note\":\"일 11.15 · 잠실\",\"pct\":26}]");
	}

	@Test
	void 내_가게_이번_주_예측_FE_모양() throws Exception {
		mockMvc.perform(get("/sales-forecast/this-week").with(user("owner")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.pct").value(38))
				.andExpect(jsonPath("$.data.reason").value("일 11.15 잠실 마라톤"))
				.andExpect(jsonPath("$.data.range").isString())
				.andExpect(jsonPath("$.data.days", hasSize(2)))
				.andExpect(jsonPath("$.data.days[0].today").value(false))
				.andExpect(jsonPath("$.data.days[1].label").value("오늘"))
				.andExpect(jsonPath("$.data.days[1].value").value(121))
				.andExpect(jsonPath("$.data.days[1].today").value(true))
				.andExpect(jsonPath("$.data.factors[0].name").value("잠실 마라톤"))
				.andExpect(jsonPath("$.data.factors[0].pct").value(26))
				.andExpect(jsonPath("$.data.analysis").value(nullValue()));
	}

	@Test
	void 남의_가게_예측은_안_섞이고_없으면_빈_응답() throws Exception {
		currentStore.storeId = otherStore;

		mockMvc.perform(get("/sales-forecast/this-week").with(user("owner")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.pct").value(nullValue()))
				.andExpect(jsonPath("$.data.reason").value(nullValue()))
				.andExpect(jsonPath("$.data.range").isString())
				.andExpect(jsonPath("$.data.days", hasSize(0)))
				.andExpect(jsonPath("$.data.factors", hasSize(0)));
	}

	@Test
	void 인증_없으면_401() throws Exception {
		mockMvc.perform(get("/sales-forecast/this-week"))
				.andExpect(status().isUnauthorized());
	}
}
