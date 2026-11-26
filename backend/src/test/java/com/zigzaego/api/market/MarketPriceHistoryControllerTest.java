package com.zigzaego.api.market;

import static com.zigzaego.api.support.StoreFixtures.insertIngredient;
import static com.zigzaego.api.support.StoreFixtures.loginNewStore;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.support.StoreFixtures.LoggedInStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
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

/**
 * GET /ingredients/{id}/market-price-history · 라벨(LABEL) 경로의 /offers (S15P31A308-229, ADR-0012).
 * 라벨이 품목군을 정하면 규칙이 못 거르던 가공품(구운계란)이 빠지고, 규칙이 버리던 진짜 상품(1등급란)이 들어오며, g 로 적힌 계란이 개로 환산된다.
 * 추이는 (사이트, 수집일)마다 최저가 한 점이고 수집이 없는 날은 점이 없다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class MarketPriceHistoryControllerTest {

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private Clock clock;

	private MockMvc mockMvc;

	private LoggedInStore mine;

	private long egg;

	private LocalDate today;

	@BeforeEach
	void setUp() throws Exception {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		mine = loginNewStore(jdbc, passwordEncoder, mockMvc, "추이내가게");
		egg = insertIngredient(jdbc, mine.storeId(), "계란");
		jdbc.update("DELETE FROM market_product");
		jdbc.update("DELETE FROM market_product_label");
		today = LocalDate.now(clock);
		LocalDate d1 = today.minusDays(2);
		LocalDate d2 = today.minusDays(1);
		// 라벨: 원물 2개(1등급란은 규칙이 버리는 이름) · 가공품 1개(구운계란은 규칙이 못 거르는 이름) · 확신 낮은 1개
		label("무항생제 1등급란 30구", "EGG", "RAW", "30", "EA", "0.95");
		label("계란(대란/30알/국내산)판", "EGG", "RAW", "30", "EA", "0.9");
		label("[푸른들] 구운계란 대란 30구", null, "PROCESSED", "30", "EA", "0.95");
		label("애매한 계란 30구", "EGG", "RAW", "30", "EA", "0.4");
		label("지킴이계란 대란", "EGG", "RAW", "30", "EA", "0.9");
		// d1
		insert("식봄", "서울 중구", "A1", "계란(대란/30알/국내산)판", 5990, 30, "개", "199.7", d1);
		insert("식자재왕몰", "상일점", "W1", "무항생제 1등급란 30구", 6000, 30, "개", "200.0", d1);
		insert("농협몰", null, "N1", "[푸른들] 구운계란 대란 30구", 3000, 30, "개", "100.0", d1);
		// d2: 식봄만 싸졌고, 식자재왕몰은 g 로 적힌 줄(1560g 30개입 → 46,800g 로 잘못 읽힘)이라 라벨 개수로 교차 검증
		insert("식봄", "서울 중구", "A1", "계란(대란/30알/국내산)판", 5690, 30, "개", "189.7", d2);
		insert("식자재왕몰", "상일점", "W2", "지킴이계란 대란", 6890, 46800, "g", "14.7", d2);
		insert("마켓컬리", "전국", "K1", "애매한 계란 30구", 1000, 30, "개", "33.3", d2);
		// 범위 밖(40일 전)
		insert("식봄", "서울 중구", "A1", "계란(대란/30알/국내산)판", 4000, 30, "개", "133.3", today.minusDays(40));
	}

	@Test
	void 라벨_경로의_최저가_는_품목군_기준_단위로_사이트별_최신_수집일() throws Exception {
		perform(get("/ingredients/{id}/offers", egg))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.source").value("LABEL"))
				.andExpect(jsonPath("$.data.itemGroup.code").value("EGG"))
				.andExpect(jsonPath("$.data.itemGroup.baseUnit").value("EA"))
				.andExpect(jsonPath("$.data.unit").value("EA"))
				.andExpect(jsonPath("$.data.ingredientUnit").value("G"))
				.andExpect(jsonPath("$.data.toIngredientUnitFactor").value(0.0166667))  // 1g = 1/60 개
				.andExpect(jsonPath("$.data.sites", hasSize(2)))
				.andExpect(jsonPath("$.data.sites[0].site").value("식봄"))
				.andExpect(jsonPath("$.data.sites[0].bestUnitPrice").value(189.6667))
				.andExpect(jsonPath("$.data.sites[0].collectedOn").value(today.minusDays(1).toString()))
				.andExpect(jsonPath("$.data.sites[0].offers[0].form").value("RAW"))
				.andExpect(jsonPath("$.data.sites[1].site").value("식자재왕몰"))
				.andExpect(jsonPath("$.data.sites[1].bestUnitPrice").value(229.6667))  // 6890 / 30개(라벨), g 해석(46,800g) 무시
				.andExpect(jsonPath("$.data.otherUnits", hasSize(0)))
				.andExpect(content().string(not(containsString("구운계란"))))
				.andExpect(content().string(not(containsString("애매한"))));
	}

	@Test
	void 일별_추이는_사이트_수집일마다_최저가_한_점() throws Exception {
		perform(get("/ingredients/{id}/market-price-history", egg).param("days", "7"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.source").value("LABEL"))
				.andExpect(jsonPath("$.data.unit").value("EA"))
				.andExpect(jsonPath("$.data.toIngredientUnitFactor").value(0.0166667))
				.andExpect(jsonPath("$.data.from").value(today.minusDays(6).toString()))
				.andExpect(jsonPath("$.data.to").value(today.toString()))
				.andExpect(jsonPath("$.data.sites", hasSize(2)))
				.andExpect(jsonPath("$.data.sites[0].site").value("식봄"))  // 마지막 점이 더 싸다
				.andExpect(jsonPath("$.data.sites[0].points", hasSize(2)))
				.andExpect(jsonPath("$.data.sites[0].points[0].date").value(today.minusDays(2).toString()))
				.andExpect(jsonPath("$.data.sites[0].points[0].unitPrice").value(199.6667))
				.andExpect(jsonPath("$.data.sites[0].points[1].unitPrice").value(189.6667))
				.andExpect(jsonPath("$.data.sites[1].site").value("식자재왕몰"))
				.andExpect(jsonPath("$.data.sites[1].points", hasSize(2)))
				.andExpect(jsonPath("$.data.sites[1].points[0].unitPrice").value(200.0))
				.andExpect(jsonPath("$.data.sites[1].points[1].unitPrice").value(229.6667))
				.andExpect(content().string(not(containsString("133.3"))))  // 40일 전 줄은 범위 밖
				.andExpect(content().string(not(containsString("구운계란"))));
	}

	@Test
	void 품목군이_없는_재료는_규칙_경로로_추이를_낸다() throws Exception {
		long truffle = insertIngredient(jdbc, mine.storeId(), "트러플");
		insert("식봄", "서울 중구", "T1", "이탈리아 트러플 50g", 50000, 50, "g", "100000", today.minusDays(1));
		perform(get("/ingredients/{id}/market-price-history", truffle))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.source").value("RULE"))
				.andExpect(jsonPath("$.data.itemGroup").value(nullValue()))
				.andExpect(jsonPath("$.data.unit").value("G"))
				.andExpect(jsonPath("$.data.toIngredientUnitFactor").value(1))
				.andExpect(jsonPath("$.data.sites", hasSize(1)))
				.andExpect(jsonPath("$.data.sites[0].points[0].unitPrice").value(1000.0));
	}

	@Test
	void days_가_범위_밖이면_400() throws Exception {
		perform(get("/ingredients/{id}/market-price-history", egg).param("days", "0"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
		perform(get("/ingredients/{id}/market-price-history", egg).param("days", "366"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void 남의_가게_재료는_404_인증_없으면_401() throws Exception {
		LoggedInStore other = loginNewStore(jdbc, passwordEncoder, mockMvc, "추이남의가게");
		long othersEgg = insertIngredient(jdbc, other.storeId(), "계란");
		perform(get("/ingredients/{id}/market-price-history", othersEgg))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));
		mockMvc.perform(get("/ingredients/{id}/market-price-history", egg))
				.andExpect(status().isUnauthorized());
	}

	private ResultActions perform(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) throws Exception {
		return mockMvc.perform(req.header(HttpHeaders.AUTHORIZATION, "Bearer " + mine.accessToken()));
	}

	private void label(String name, String code, String form, String qty, String unit, String confidence) {
		jdbc.update("INSERT INTO market_product_label (product_name, item_group_code, form, qty, qty_unit, confidence, reason, label_version)"
				+ " VALUES (?, ?, ?, ?, ?, ?, 'test', 'v1')", name, code, form, new BigDecimal(qty), unit, new BigDecimal(confidence));
	}

	private void insert(String site, String region, String no, String name, long price, long totalQty, String unit, String unitPrice100,
			LocalDate collectedOn) {
		jdbc.update("INSERT INTO market_product (site, store_region, product_no, product_name, price, total_qty, unit, unit_price_100, url, collected_on)"
				+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", site, region, no, name, price, BigDecimal.valueOf(totalQty), unit,
				new BigDecimal(unitPrice100), "https://x/" + no, collectedOn);
	}
}
