package com.zigzaego.api.market;

import static com.zigzaego.api.support.StoreFixtures.insertIngredient;
import static com.zigzaego.api.support.StoreFixtures.loginNewStore;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * GET /ingredients/{id}/offers (S15P31A308-231 · 230 · 229). 가게 2개로 남의 가게 재료가 404 인지, '계란' 에 계란찜 · 깐 계란 · 볶음밥이 없는지,
 * 지역이 다른 가게 2개에서 배달 가능 판매처가 달라지는지 본다. 시세 표는 전역(ADR-0010)이라 테스트마다 비우고 다시 넣는다.
 * 배송 지역은 V6 시드 그대로(식봄 = 서울 중구, 식자재왕몰 상일점 · 천호점 = 서울 강동구, 마켓컬리 = 전국, 그리팅몰 · 농협몰 = 모름).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class MarketOfferControllerTest {

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PasswordEncoder passwordEncoder;

	private MockMvc mockMvc;

	private LoggedInStore mine;

	private LoggedInStore other;

	private long egg;

	@BeforeEach
	void setUp() throws Exception {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		mine = loginNewStore(jdbc, passwordEncoder, mockMvc, "최저가내가게");
		other = loginNewStore(jdbc, passwordEncoder, mockMvc, "최저가남의가게");
		egg = insertIngredient(jdbc, mine.storeId(), "계란");
		jdbc.update("DELETE FROM market_product");
		// 식봄 날계란 4개 → 상위 3개만 (왕란이 빠진다)
		insert("식봄", "서울 중구", "A1", "계란(중란/30알/국내산)판", "1판/30구 / 판", 5690, "개", "189.7", "2026-10-26");
		insert("식봄", "서울 중구", "A2", "계란(특란/30알/국내산)판", "30알 / 판", 6190, "개", "206.3", "2026-10-26");
		insert("식봄", "서울 중구", "A3", "계란(왕란/30알/국내산)판", "30알 / 판", 7080, "개", "236.0", "2026-10-26");
		insert("식봄", "서울 중구", "A4", "계란(대란/30알/국내산)판", "30알 / 판", 5990, "개", "199.7", "2026-10-26");
		// 같은 상품번호 점포 2곳 → 가장 싼 점포 하나
		insert("식자재왕몰", "상일점", "53780", "지킴이계란 대란", "1560g (30개입)", 6890, "개", "229.7", "2026-10-27");
		insert("식자재왕몰", "천호점", "53780", "지킴이계란 대란", "1560g (30개입)", 8290, "개", "276.3", "2026-10-27");
		insert("마켓컬리", "전국", "K1", "[KF365] 무항생제 달걀 L(대란) 20구", null, 7320, "개", "366.0", "2026-10-27");
		// 가공품 · 조리란 · 도구 — 정확한 품목이 아니다
		insert("마켓컬리", "전국", "K2", "[KF365] 깐 계란 700g", null, 7890, "g", "1127.1", "2026-10-27");
		insert("식봄", "서울 중구", "A5", "계란찜 1kg", "1kg / EA", 3000, "g", "300.0", "2026-10-26");
		insert("식봄", "서울 중구", "A6", "새우 계란 볶음밥 250g", "250g / EA", 2700, "g", "1080.0", "2026-10-26");
		insert("농협몰", null, "N1", "[유니맥스] 다용도 2단 계란 찜기 UM-EDG2135", null, 31200, "개", "15600.0", "2026-10-27");
		// 5판 묶음인데 1판 값 → 중앙값의 1/5 아래라 이상치
		insert("식봄", "서울 중구", "A7", "★계란 핫★ 계란 (대란 / 30알 / 국내산) 5판/묶음", "30알*5판 / 묶음", 5950, "개", "39.7", "2026-10-26");
		// 비교 단위(개)에 없고 g 로만 파는 사이트 → otherUnits
		insert("그리팅몰", null, "G1", "[제주애월아빠들] 제주품은 동물복지 유정란 (15구) 750g", null, 10500, "g", "1400.0", "2026-10-28");
		// 단위가격이 없는 줄은 후보가 아니다
		insert("농협몰", null, "N2", "동물복지 유정란 1호 자연방사 난각번호 1번 유정란 계란 달걀", null, 19900, null, null, "2026-10-27");
	}

	@Test
	void 내_가게_재료는_정확한_품목만_사이트별_단가_순() throws Exception {
		offers(mine, String.valueOf(egg))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.ingredientId").value(String.valueOf(egg)))
				.andExpect(jsonPath("$.data.query").value("계란"))
				.andExpect(jsonPath("$.data.source").value("LABEL"))  // 계란은 품목군 EGG — 라벨이 없으면 규칙으로 임시 판정
				.andExpect(jsonPath("$.data.itemGroup.code").value("EGG"))
				.andExpect(jsonPath("$.data.unit").value("EA"))
				.andExpect(jsonPath("$.data.matchesIngredientUnit").value(false))  // 재료는 G, 몰은 개 단위
				.andExpect(jsonPath("$.data.ingredientUnit").value("G"))
				.andExpect(jsonPath("$.data.toIngredientUnitFactor").value(0.0166667))  // 1g = 1/60 개
				.andExpect(jsonPath("$.data.collectedOn").value("2026-10-28"))
				.andExpect(jsonPath("$.data.excludedOutliers").value(1))
				.andExpect(jsonPath("$.data.sites", hasSize(4)))
				.andExpect(jsonPath("$.data.sites[0].site").value("식봄"))
				.andExpect(jsonPath("$.data.sites[0].bestUnitPrice").value(189.7))
				.andExpect(jsonPath("$.data.sites[0].offers", hasSize(3)))
				.andExpect(jsonPath("$.data.sites[0].offers[0].productName").value("계란(중란/30알/국내산)판"))
				.andExpect(jsonPath("$.data.sites[0].offers[0].region").value("서울 중구"))
				.andExpect(jsonPath("$.data.sites[0].offers[0].spec").value("1판/30구 / 판"))
				.andExpect(jsonPath("$.data.sites[0].offers[0].price").value(5690))
				.andExpect(jsonPath("$.data.sites[0].offers[0].unit").value("EA"))
				.andExpect(jsonPath("$.data.sites[0].offers[0].url").value("https://x/A1"))
				.andExpect(jsonPath("$.data.sites[0].offers[1].productName").value("계란(대란/30알/국내산)판"))
				.andExpect(jsonPath("$.data.sites[0].offers[2].productName").value("계란(특란/30알/국내산)판"))
				.andExpect(jsonPath("$.data.sites[1].site").value("식자재왕몰"))
				.andExpect(jsonPath("$.data.sites[1].offers", hasSize(1)))
				.andExpect(jsonPath("$.data.sites[1].offers[0].region").value("상일점"))
				.andExpect(jsonPath("$.data.sites[1].offers[0].price").value(6890))
				.andExpect(jsonPath("$.data.sites[2].site").value("마켓컬리"))
				.andExpect(jsonPath("$.data.sites[2].offers[0].region").value("전국"))
				.andExpect(jsonPath("$.data.sites[2].offers[0].spec").value(nullValue()))
				// g 로만 파는 그리팅몰은 품목군 환산(1개 = 60g)으로 개 단위에 들어온다: 14원/g × 60 = 840원/개
				.andExpect(jsonPath("$.data.sites[3].site").value("그리팅몰"))
				.andExpect(jsonPath("$.data.sites[3].bestUnitPrice").value(840.0))
				.andExpect(jsonPath("$.data.sites[3].offers[0].unit").value("EA"))
				.andExpect(jsonPath("$.data.sites[3].collectedOn").value("2026-10-28"))
				.andExpect(jsonPath("$.data.otherUnits", hasSize(0)))
				// 가게 시군구가 없으면 아무 판매처도 빼지 않고 전부 '확인 필요'
				.andExpect(jsonPath("$.data.storeSigungu").value(nullValue()))
				.andExpect(jsonPath("$.data.notDelivering", hasSize(0)))
				.andExpect(jsonPath("$.data.deliveryUnknown", contains("그리팅몰", "마켓컬리", "식봄", "식자재왕몰")))
				.andExpect(jsonPath("$.data.sites[0].delivery").value("UNKNOWN"))
				.andExpect(jsonPath("$.data.sites[0].dawnDelivery").value(nullValue()))
				.andExpect(content().string(containsString("\"unitPrice\":189.7000")))
				.andExpect(content().string(not(containsString("계란찜"))))
				.andExpect(content().string(not(containsString("깐 계란"))))
				.andExpect(content().string(not(containsString("볶음밥"))))
				.andExpect(content().string(not(containsString("찜기"))))
				.andExpect(content().string(not(containsString("5판/묶음"))))
				.andExpect(content().string(not(containsString("왕란"))));
	}

	@Test
	void 지역이_다른_가게_2개는_배달_가능_판매처가_다르다() throws Exception {
		setSigungu(mine, "서울 중구");
		setSigungu(other, "서울 강동구");
		long othersEgg = insertIngredient(jdbc, other.storeId(), "계란");

		// 서울 중구: 식봄 · 마켓컬리 배달, 식자재왕몰(상일 · 천호점)은 강동구만 → 뺀다. 그리팅몰은 모름 → 남기고 '확인 필요'
		offers(mine, String.valueOf(egg))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.storeSigungu").value("서울 중구"))
				.andExpect(jsonPath("$.data.sites", hasSize(3)))
				.andExpect(jsonPath("$.data.sites[0].site").value("식봄"))
				.andExpect(jsonPath("$.data.sites[0].delivery").value("AVAILABLE"))
				.andExpect(jsonPath("$.data.sites[0].dawnDelivery").value(true))
				.andExpect(jsonPath("$.data.sites[1].site").value("마켓컬리"))
				.andExpect(jsonPath("$.data.sites[1].delivery").value("AVAILABLE"))
				.andExpect(jsonPath("$.data.sites[1].dawnDelivery").value(nullValue()))
				.andExpect(jsonPath("$.data.excludedOutliers").value(1))
				.andExpect(jsonPath("$.data.notDelivering", contains("식자재왕몰")))
				.andExpect(jsonPath("$.data.deliveryUnknown", contains("그리팅몰")))
				.andExpect(jsonPath("$.data.sites[2].site").value("그리팅몰"))
				.andExpect(jsonPath("$.data.sites[2].delivery").value("UNKNOWN"))
				.andExpect(jsonPath("$.data.otherUnits", hasSize(0)))
				.andExpect(content().string(not(containsString("지킴이계란"))));  // 식자재왕몰 상품은 없다

		// 서울 강동구: 식자재왕몰 상일점 · 마켓컬리 배달, 식봄은 중구만 → 뺀다(이상치였던 식봄 5판 묶음도 같이 사라진다)
		offers(other, String.valueOf(othersEgg))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.storeSigungu").value("서울 강동구"))
				.andExpect(jsonPath("$.data.sites", hasSize(3)))
				.andExpect(jsonPath("$.data.sites[0].site").value("식자재왕몰"))
				.andExpect(jsonPath("$.data.sites[0].delivery").value("AVAILABLE"))
				.andExpect(jsonPath("$.data.sites[0].offers", hasSize(1)))
				.andExpect(jsonPath("$.data.sites[0].offers[0].region").value("상일점"))
				.andExpect(jsonPath("$.data.sites[1].site").value("마켓컬리"))
				.andExpect(jsonPath("$.data.excludedOutliers").value(0))
				.andExpect(jsonPath("$.data.notDelivering", contains("식봄")))
				.andExpect(jsonPath("$.data.deliveryUnknown", contains("그리팅몰")))
				.andExpect(content().string(not(containsString("국내산)판"))));  // 식봄 상품은 없다
	}

	@Test
	void 배달_점포가_다른_시군구면_그_점포만_남는다() throws Exception {
		// 성남점 줄을 더 넣고 가게를 성남시로 → 식자재왕몰은 성남점 가격만, 상일 · 천호점은 뺀다
		insert("식자재왕몰", "성남점", "53780", "지킴이계란 대란", "1560g (30개입)", 7490, "개", "249.7", "2026-10-27");
		setSigungu(mine, "경기 성남시");

		offers(mine, String.valueOf(egg))
				.andExpect(jsonPath("$.data.sites[0].site").value("식자재왕몰"))
				.andExpect(jsonPath("$.data.sites[0].offers[0].region").value("성남점"))
				.andExpect(jsonPath("$.data.sites[0].offers[0].price").value(7490))
				.andExpect(jsonPath("$.data.notDelivering", contains("식봄")))
				.andExpect(content().string(not(containsString("상일점"))));
	}

	@Test
	void 배달_판매처가_하나도_없으면_빈_결과에_notDelivering만() throws Exception {
		jdbc.update("DELETE FROM market_product WHERE site <> '식봄'");
		setSigungu(mine, "부산 해운대구");

		offers(mine, String.valueOf(egg))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.unit").value(nullValue()))
				.andExpect(jsonPath("$.data.sites", hasSize(0)))
				.andExpect(jsonPath("$.data.otherUnits", hasSize(0)))
				.andExpect(jsonPath("$.data.notDelivering", contains("식봄")))
				.andExpect(jsonPath("$.data.deliveryUnknown", hasSize(0)));
	}

	@Test
	void 시군구를_지우면_다시_전부_확인_필요() throws Exception {
		setSigungu(mine, "서울 중구");
		setSigungu(mine, "");

		offers(mine, String.valueOf(egg))
				.andExpect(jsonPath("$.data.storeSigungu").value(nullValue()))
				.andExpect(jsonPath("$.data.sites", hasSize(4)))
				.andExpect(jsonPath("$.data.deliveryUnknown", containsInAnyOrder("그리팅몰", "마켓컬리", "식봄", "식자재왕몰")));
	}

	@Test
	void 정확한_상품이_없으면_빈_결과() throws Exception {
		long truffle = insertIngredient(jdbc, mine.storeId(), "트러플");
		offers(mine, String.valueOf(truffle))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.query").value("트러플"))
				.andExpect(jsonPath("$.data.source").value("RULE"))  // 품목군 표에 없는 재료
				.andExpect(jsonPath("$.data.itemGroup").value(nullValue()))
				.andExpect(jsonPath("$.data.unit").value(nullValue()))
				.andExpect(jsonPath("$.data.matchesIngredientUnit").value(false))
				.andExpect(jsonPath("$.data.collectedOn").value(nullValue()))
				.andExpect(jsonPath("$.data.excludedOutliers").value(0))
				.andExpect(jsonPath("$.data.sites", hasSize(0)))
				.andExpect(jsonPath("$.data.otherUnits", hasSize(0)));
	}

	@Test
	void 재료_이름의_다른_이름으로도_찾는다() throws Exception {
		long egg2 = insertIngredient(jdbc, mine.storeId(), "달걀");
		offers(mine, String.valueOf(egg2))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.unit").value("EA"))
				.andExpect(jsonPath("$.data.sites[0].site").value("식봄"));
	}

	@Test
	void 남의_가게_재료_id는_404() throws Exception {
		long othersEgg = insertIngredient(jdbc, other.storeId(), "계란");
		expectNotFound(offers(mine, String.valueOf(othersEgg)));
		expectNotFound(offers(other, String.valueOf(egg)));
	}

	@Test
	void 없는_id_숫자_아닌_id는_404() throws Exception {
		expectNotFound(offers(mine, "999999"));
		expectNotFound(offers(mine, "abc"));
	}

	@Test
	void 인증_없으면_401() throws Exception {
		mockMvc.perform(get("/ingredients/{id}/offers", egg))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("COMMON_UNAUTHORIZED"));
	}

	private void setSigungu(LoggedInStore store, String sigungu) throws Exception {
		mockMvc.perform(patch("/stores/me").header(HttpHeaders.AUTHORIZATION, store.bearer())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"sigungu\": \"" + sigungu + "\"}"))
				.andExpect(status().isOk());
	}

	private ResultActions offers(LoggedInStore store, String id) throws Exception {
		return mockMvc.perform(get("/ingredients/{id}/offers", id).header(HttpHeaders.AUTHORIZATION, store.bearer()));
	}

	private static void expectNotFound(ResultActions actions) throws Exception {
		actions.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));
	}

	private void insert(String site, String region, String productNo, String name, String spec, long price, String unit,
			String unitPrice100, String collectedOn) {
		jdbc.update("INSERT INTO market_product (site, store_region, product_no, product_name, spec, price, unit, "
						+ "unit_price_100, url, collected_on) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::date)",
				site, region, productNo, name, spec, price, unit, unitPrice100 == null ? null : new BigDecimal(unitPrice100),
				"https://x/" + productNo, collectedOn);
	}
}
