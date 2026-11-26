package com.zigzaego.api.market;

import static com.zigzaego.api.support.StoreFixtures.insertIngredient;
import static com.zigzaego.api.support.StoreFixtures.loginNewStore;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@Import({TestcontainersConfiguration.class, FixedClockConfig.class})
@SpringBootTest
class StoreMarketQuoteTest {
	@Autowired WebApplicationContext context;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder encoder;
	MockMvc mvc;
	LoggedInStore mine;
	LoggedInStore other;
	long ingredient;
	long otherIngredient;

	@BeforeEach void setup() throws Exception {
		mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		mine = loginNewStore(jdbc, encoder, mvc, "시세내가게");
		other = loginNewStore(jdbc, encoder, mvc, "시세남의가게");
		ingredient = insertIngredient(jdbc, mine.storeId(), "전용시연재료");
		otherIngredient = insertIngredient(jdbc, other.storeId(), "전용시연재료");
		quote("2026-10-14", "쿠팡", "3", 6000, 2000);
		quote("2026-10-07", "쿠팡", "4", 8000, 2000);
		quote("2026-10-15", "마켓봄", "1", 1000, 1000); // 미래 가격은 쓰지 않는다
	}
	void quote(String date, String site, String unitPrice, long price, int qty) {
		jdbc.update("INSERT INTO store_market_quote(store_id,ingredient_id,site,collected_on,unit_price,pack_qty,pack_price,product_name,spec,url,synthetic) VALUES (?,?,?,?::date,?::numeric,?,?,?,?,'https://example.invalid/demo',true)",
				mine.storeId(), ingredient, site, date, unitPrice, qty, price, "시연 2kg 포장", "2kg");
	}
	@Test void 가게시세는_재료단위로_포장과_수집일을_보존한다() throws Exception {
		mvc.perform(get("/ingredients/{id}/offers", ingredient).header(HttpHeaders.AUTHORIZATION, mine.bearer()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.unit").value("G"))
				.andExpect(jsonPath("$.data.toIngredientUnitFactor").value(1))
				.andExpect(jsonPath("$.data.sites", hasSize(1)))
				.andExpect(jsonPath("$.data.sites[0].bestUnitPrice").value(3))
				.andExpect(jsonPath("$.data.sites[0].offers[0].price").value(6000))
				.andExpect(jsonPath("$.data.sites[0].synthetic").value(true));
		mvc.perform(get("/ingredients/{id}/market-price-history?days=56", ingredient).header(HttpHeaders.AUTHORIZATION, mine.bearer()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.to").value("2026-10-14"))
				.andExpect(jsonPath("$.data.sites[0].points", hasSize(2)))
				.andExpect(jsonPath("$.data.sites[0].points[1].unitPrice").value(3));
	}
	@Test void 다른가게의_같은이름재료에도_가게시세가_새지않는다() throws Exception {
		mvc.perform(get("/ingredients/{id}/offers", otherIngredient).header(HttpHeaders.AUTHORIZATION, other.bearer()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.sites", hasSize(0)));
		mvc.perform(get("/ingredients/{id}/offers", ingredient).header(HttpHeaders.AUTHORIZATION, other.bearer()))
				.andExpect(status().isNotFound());
	}
}
