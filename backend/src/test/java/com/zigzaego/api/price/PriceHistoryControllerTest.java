package com.zigzaego.api.price;

import static com.zigzaego.api.price.PriceHistoryTestData.insertPrice;
import static com.zigzaego.api.price.PriceHistoryTestData.insertProduct;
import static com.zigzaego.api.price.PriceHistoryTestData.insertSupplier;
import static com.zigzaego.api.support.FixedClockConfig.TODAY;
import static com.zigzaego.api.support.StoreFixtures.insertIngredient;
import static com.zigzaego.api.support.StoreFixtures.loginNewStore;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.support.FixedClockConfig;
import com.zigzaego.api.support.StoreFixtures.LoggedInStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** GET /ingredients/{id}/price-history (S15P31A308-64). 가게 2개로 남의 가게 재료가 404 인지 본다. 오늘 = 2026-10-14 고정. */
@Import({TestcontainersConfiguration.class, FixedClockConfig.class})
@SpringBootTest
class PriceHistoryControllerTest {

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PasswordEncoder passwordEncoder;

	private MockMvc mockMvc;

	private LoggedInStore mine;

	private LoggedInStore other;

	private long greenOnion;

	private long product;

	@BeforeEach
	void setUp() throws Exception {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		mine = loginNewStore(jdbc, passwordEncoder, mockMvc, "단가API내가게");
		other = loginNewStore(jdbc, passwordEncoder, mockMvc, "단가API남의가게");
		greenOnion = insertIngredient(jdbc, mine.storeId(), "대파");
		long supplier = insertSupplier(jdbc, mine.storeId(), "한빛농산");
		product = insertProduct(jdbc, mine.storeId(), supplier, greenOnion, "대파 1kg", 1000);
		insertPrice(jdbc, mine.storeId(), product, 4200, TODAY.minusDays(30), "MANUAL");
		insertPrice(jdbc, mine.storeId(), product, 4200, TODAY.minusDays(20), "MANUAL");
		insertPrice(jdbc, mine.storeId(), product, 4200, TODAY.minusDays(10), "MANUAL");
		insertPrice(jdbc, mine.storeId(), product, 4800, TODAY, "MANUAL");
	}

	@Test
	void 내_가게_재료는_200과_단가_이력_모양() throws Exception {
		get(mine, String.valueOf(greenOnion))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.error").value(nullValue()))
				.andExpect(jsonPath("$.data.ingredientId").isString())
				.andExpect(jsonPath("$.data.ingredientId").value(String.valueOf(greenOnion)))
				.andExpect(jsonPath("$.data.unit").value("G"))
				.andExpect(jsonPath("$.data.weekly", hasSize(8)))
				.andExpect(jsonPath("$.data.weekly[0]").value(nullValue()))
				.andExpect(jsonPath("$.data.weekly[7]").isNumber())
				.andExpect(jsonPath("$.data.weekly[7]").value(4.8))
				.andExpect(jsonPath("$.data.observations", hasSize(4)))
				.andExpect(jsonPath("$.data.observations[0].observedOn").value("2026-10-14"))
				.andExpect(jsonPath("$.data.observations[0].unitPrice").isNumber())
				.andExpect(jsonPath("$.data.observations[0].unitPrice").value(4.8))
				.andExpect(jsonPath("$.data.observations[0].source").value("MANUAL"))
				.andExpect(jsonPath("$.data.observations[0].supplierProductId").isString())
				.andExpect(jsonPath("$.data.observations[0].supplierProductId").value(String.valueOf(product)))
				.andExpect(jsonPath("$.data.observations[0].productName").value("대파 1kg"))
				.andExpect(jsonPath("$.data.index.level").value("HIGH"))
				.andExpect(jsonPath("$.data.index.index").isNumber())
				.andExpect(jsonPath("$.data.index.index").value(1.1429))
				.andExpect(jsonPath("$.data.index.basePrice").value(4.2))
				.andExpect(jsonPath("$.data.index.currentPrice").value(4.8))
				.andExpect(jsonPath("$.data.index.sampleCount").value(3))
				.andExpect(jsonPath("$.data.index.reason").value("OK"))
				.andExpect(content().string(containsString("\"unitPrice\":4.8000")));
	}

	@Test
	void 남의_가게_재료_id는_404() throws Exception {
		long othersIngredient = insertIngredient(jdbc, other.storeId(), "대파");
		expectNotFound(get(mine, String.valueOf(othersIngredient)));
		// 거꾸로 남의 가게 토큰으로 내 재료도 404
		expectNotFound(get(other, String.valueOf(greenOnion)));
	}

	@Test
	void 없는_id는_404() throws Exception {
		expectNotFound(get(mine, "999999"));
	}

	@Test
	void 숫자_아닌_id는_404() throws Exception {
		expectNotFound(get(mine, "abc"));
	}

	@Test
	void 인증_없으면_401() throws Exception {
		expectMineOk();
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
						.get("/ingredients/{id}/price-history", greenOnion))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("COMMON_UNAUTHORIZED"));
	}

	private ResultActions get(LoggedInStore store, String rawId) throws Exception {
		return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.get("/ingredients/{id}/price-history", rawId)
				.header(HttpHeaders.AUTHORIZATION, store.bearer()));
	}

	/**
	 * 404 · 401 검사의 대조군: 같은 주소에 내 토큰 · 내 재료면 200. 주소가 아예 없어도 404 가 나므로
	 * 이게 없으면 404 테스트가 아무것도 안 잡는다.
	 */
	private void expectMineOk() throws Exception {
		get(mine, String.valueOf(greenOnion)).andExpect(status().isOk());
	}

	private void expectNotFound(ResultActions actions) throws Exception {
		expectMineOk();
		actions.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));
	}
}
