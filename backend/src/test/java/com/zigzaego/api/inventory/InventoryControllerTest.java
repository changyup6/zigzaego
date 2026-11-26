package com.zigzaego.api.inventory;

import static com.zigzaego.api.inventory.InventoryTestData.insertCount;
import static com.zigzaego.api.inventory.InventoryTestData.insertIngredient;
import static com.zigzaego.api.inventory.InventoryTestData.insertMenu;
import static com.zigzaego.api.inventory.InventoryTestData.insertRecipe;
import static com.zigzaego.api.inventory.InventoryTestData.insertSale;
import static com.zigzaego.api.inventory.InventoryTestData.insertStore;
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
class InventoryControllerTest {

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

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.apply(springSecurity())
				.build();
		myStore = insertStore(jdbc, "API내가게");
		otherStore = insertStore(jdbc, "API남의가게");
		currentStore.storeId = myStore;

		// 내 가게: 어제 실사 1000, 오늘 돈까스 2 × 150 = 300 → 700
		LocalDate today = LocalDate.now(SEOUL);
		등심 = insertIngredient(jdbc, myStore, "등심", "G", "BOM");
		long 돈까스 = insertMenu(jdbc, myStore, "돈까스");
		insertRecipe(jdbc, myStore, 돈까스, 등심, "150");
		insertCount(jdbc, myStore, 등심, OffsetDateTime.now(SEOUL).minusDays(1), "1000");
		insertSale(jdbc, myStore, today, 돈까스, "2");

		// 남의 가게: 재료 2개 — 응답에 섞이면 안 된다
		insertIngredient(jdbc, otherStore, "등심", "G", "BOM");
		insertIngredient(jdbc, otherStore, "콜라", "EA", "ONE_TO_ONE");
	}

	@Test
	void 내_가게_재료만_200과_줄_모양() throws Exception {
		mockMvc.perform(get("/inventory/estimate").with(user("owner")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.error").value(nullValue()))
				.andExpect(jsonPath("$.data", hasSize(1)))
				.andExpect(jsonPath("$.data[0].ingredientId").isString())
				.andExpect(jsonPath("$.data[0].ingredientId").value(String.valueOf(등심)))
				.andExpect(jsonPath("$.data[0].ingredientName").value("등심"))
				.andExpect(jsonPath("$.data[0].unit").value("G"))
				.andExpect(jsonPath("$.data[0].kind").value("BOM"))
				.andExpect(jsonPath("$.data[0].estimatedQty").isNumber())
				.andExpect(jsonPath("$.data[0].estimatedQty").value(700.0))
				.andExpect(jsonPath("$.data[0].needsCount").value(false))
				.andExpect(jsonPath("$.data[0].basis").value("COUNT"))
				.andExpect(jsonPath("$.data[0].lastCountedAt").isString())
				.andExpect(content().string(containsString("\"estimatedQty\":700.000,")));
	}

	@Test
	void 다른_가게로_바꾸면_그_가게_재료만() throws Exception {
		currentStore.storeId = otherStore;

		mockMvc.perform(get("/inventory/estimate").with(user("owner")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data", hasSize(2)))
				.andExpect(jsonPath("$.data[0].needsCount").value(true))
				.andExpect(jsonPath("$.data[0].basis").value("NO_COUNT"))
				.andExpect(jsonPath("$.data[0].estimatedQty").value(0.0));
	}

	@Test
	void 인증_없으면_401() throws Exception {
		mockMvc.perform(get("/inventory/estimate"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("COMMON_UNAUTHORIZED"));
	}

}
