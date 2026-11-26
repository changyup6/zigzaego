package com.zigzaego.api.inventory;

import static com.zigzaego.api.support.StoreFixtures.OTHER_CATEGORY_SQL;
import static com.zigzaego.api.support.StoreFixtures.insertIngredient;
import static com.zigzaego.api.support.StoreFixtures.insertMenu;
import static com.zigzaego.api.support.StoreFixtures.insertRecipe;
import static com.zigzaego.api.support.StoreFixtures.loginNewStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.support.FixedClockConfig;
import com.zigzaego.api.support.StoreFixtures.LoggedInStore;
import java.math.BigDecimal;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
 * POST /stock-counts (S15P31A308-47). 완료 조건: 실사 입력 뒤 그 재료 needsCount=false, 실사일이 /stores/me 체크리스트에 반영.
 * 가게 2개로 남의 재료가 섞이지 않는지도 본다 (ADR-0002). 오늘은 FixedClockConfig 의 서울 2026-10-14 12:00.
 */
@Import({TestcontainersConfiguration.class, FixedClockConfig.class})
@SpringBootTest
class StockCountControllerTest {

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PasswordEncoder passwordEncoder;

	private MockMvc mockMvc;

	private LoggedInStore mine;

	private LoggedInStore other;

	private long chicken;

	private long onion;

	private long otherChicken;

	@BeforeEach
	void setUp() throws Exception {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		mine = loginNewStore(jdbc, passwordEncoder, mockMvc, "실사가게");
		other = loginNewStore(jdbc, passwordEncoder, mockMvc, "남의가게");
		chicken = insertIngredient(jdbc, mine.storeId(), "닭가슴살");
		onion = insertIngredient(jdbc, mine.storeId(), "양파");
		otherChicken = insertIngredient(jdbc, other.storeId(), "닭가슴살");
	}

	@Test
	void 실사_입력하면_그_재료만_needsCount_가_꺼지고_실사_시각이_기준점이_된다() throws Exception {
		estimate(mine)
				.andExpect(jsonPath("$.data[0].needsCount").value(true))
				.andExpect(jsonPath("$.data[0].lastCountedAt").isEmpty());

		count(mine, """
				{"counts": [{"ingredientId": "%d", "qty": 4250.5}]}
				""".formatted(chicken))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data", hasSize(2)))
				.andExpect(jsonPath("$.data[0].ingredientId").value(String.valueOf(chicken)))
				.andExpect(jsonPath("$.data[0].needsCount").value(false))
				.andExpect(jsonPath("$.data[0].estimatedQty").value(4250.5))
				.andExpect(jsonPath("$.data[0].lastCountedAt").value("2026-10-14T12:00:00+09:00"))
				.andExpect(jsonPath("$.data[0].basis").value("COUNT"))
				.andExpect(jsonPath("$.data[1].ingredientId").value(String.valueOf(onion)))
				.andExpect(jsonPath("$.data[1].needsCount").value(true));

		Map<String, Object> row = jdbc.queryForMap(
				"SELECT qty, estimated_qty, method FROM stock_count WHERE store_id = ? AND ingredient_id = ?",
				mine.storeId(), chicken);
		assertThat((BigDecimal) row.get("qty")).isEqualByComparingTo("4250.5");
		// 직접 다 센 실사도 서버의 직전 추정치(실사 없음 → 0)를 남긴다 (지라 253)
		assertThat((BigDecimal) row.get("estimated_qty")).isEqualByComparingTo("0");
		assertThat(row.get("method")).isEqualTo("FULL_COUNT");
	}

	@Test
	void 실사하면_가게_설정_체크리스트의_재고_실사가_끝난다() throws Exception {
		me(mine)
				.andExpect(jsonPath("$.data.checklist[?(@.step == 'STOCK_COUNT')].done").value(hasItem(false)));

		count(mine, """
				{"counts": [{"ingredientId": "%d", "qty": 0}]}
				""".formatted(onion))
				.andExpect(status().isCreated());

		me(mine)
				.andExpect(jsonPath("$.data.checklist[?(@.step == 'STOCK_COUNT')].done").value(hasItem(true)))
				.andExpect(jsonPath("$.data.checklist[?(@.step == 'STOCK_COUNT')].remaining").value(hasItem(0)));
	}

	@Test
	void 추정치를_확인한_실사는_추정치와_방법을_같이_남기고_과거_시각도_받는다() throws Exception {
		count(mine, """
				{"countedAt": "2026-10-13T21:00:00+09:00",
				 "counts": [{"ingredientId": "%d", "qty": 4000, "estimatedQty": 4000, "method": "CONFIRMED"},
				            {"ingredientId": "%d", "qty": 300.25, "estimatedQty": 500, "method": "CORRECTED"}]}
				""".formatted(chicken, onion))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data[0].needsCount").value(false))
				.andExpect(jsonPath("$.data[0].lastCountedAt").value("2026-10-13T21:00:00+09:00"))
				.andExpect(jsonPath("$.data[1].needsCount").value(false))
				.andExpect(jsonPath("$.data[1].estimatedQty").value(300.25));

		Map<String, Object> row = jdbc.queryForMap(
				"SELECT qty, estimated_qty, method FROM stock_count WHERE store_id = ? AND ingredient_id = ?",
				mine.storeId(), onion);
		assertThat((BigDecimal) row.get("qty")).isEqualByComparingTo("300.25");
		assertThat((BigDecimal) row.get("estimated_qty")).isEqualByComparingTo("500");
		assertThat(row.get("method")).isEqualTo("CORRECTED");
	}

	@Test
	void 다시_실사하면_덮어쓰지_않고_쌓이며_최신_것이_기준점이다() throws Exception {
		jdbc.update("INSERT INTO stock_count (store_id, ingredient_id, counted_at, qty) VALUES (?, ?, ?, 9000)",
				mine.storeId(), chicken, java.time.OffsetDateTime.parse("2026-10-10T09:00:00+09:00"));

		count(mine, """
				{"counts": [{"ingredientId": "%d", "qty": 1200}]}
				""".formatted(chicken))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data[0].estimatedQty").value(1200.0))
				.andExpect(jsonPath("$.data[0].lastCountedAt").value("2026-10-14T12:00:00+09:00"));

		assertThat(countRows(chicken)).isEqualTo(2);
	}

	@Test
	void 남의_가게_재료가_하나라도_섞이면_404_이고_아무것도_쓰지_않는다() throws Exception {
		count(mine, """
				{"counts": [{"ingredientId": "%d", "qty": 10}, {"ingredientId": "%d", "qty": 20}]}
				""".formatted(chicken, otherChicken))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));

		assertThat(countRows(chicken)).isZero();
		assertThat(countRows(otherChicken)).isZero();
		estimate(other).andExpect(jsonPath("$.data[0].needsCount").value(true));
	}

	@Test
	void 없는_재료_id_와_숫자_아닌_id_는_404() throws Exception {
		count(mine, """
				{"counts": [{"ingredientId": "999999999", "qty": 10}]}
				""").andExpect(status().isNotFound());
		count(mine, """
				{"counts": [{"ingredientId": "abc", "qty": 10}]}
				""").andExpect(status().isNotFound());
	}

	@Test
	void 빈_목록_같은_재료_두_번_음수_소수_넷째_자리는_400() throws Exception {
		count(mine, """
				{"counts": []}
				""").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
		count(mine, """
				{"counts": [{"ingredientId": "%d", "qty": 1}, {"ingredientId": "%d", "qty": 2}]}
				""".formatted(chicken, chicken)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
		count(mine, """
				{"counts": [{"ingredientId": "%d", "qty": -1}]}
				""".formatted(chicken)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"))
				.andExpect(jsonPath("$.error.fieldErrors[0].field").value("counts[0].qty"));
		count(mine, """
				{"counts": [{"ingredientId": "%d", "qty": 1.2345}]}
				""".formatted(chicken)).andExpect(status().isBadRequest());
		count(mine, """
				{"counts": [null]}
				""").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
		String lines201 = IntStream.rangeClosed(1, 201)
				.mapToObj(i -> "{\"ingredientId\": \"" + i + "\", \"qty\": 1}")
				.collect(Collectors.joining(","));
		count(mine, "{\"counts\": [" + lines201 + "]}").andExpect(status().isBadRequest());
		// 끝자리 0 은 자릿수로 세지 않는다 (입고 확인과 같은 규칙)
		count(mine, """
				{"counts": [{"ingredientId": "%d", "qty": 1.2000}]}
				""".formatted(chicken)).andExpect(status().isCreated());

		assertThat(countRows(chicken)).isEqualTo(1);
	}

	@Test
	void 미래_시각과_추정치_없는_CONFIRMED_는_400() throws Exception {
		count(mine, """
				{"countedAt": "2026-10-14T12:03:00+09:00", "counts": [{"ingredientId": "%d", "qty": 1}]}
				""".formatted(chicken)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
		count(mine, """
				{"counts": [{"ingredientId": "%d", "qty": 1, "method": "CONFIRMED"}]}
				""".formatted(chicken)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
		count(mine, """
				{"counts": [{"ingredientId": "%d", "qty": 1, "method": "CORRECTED"}]}
				""".formatted(chicken)).andExpect(status().isBadRequest());
		// 2분 안의 미래는 시계 오차로 보고 받는다
		count(mine, """
				{"countedAt": "2026-10-14T12:01:30+09:00", "counts": [{"ingredientId": "%d", "qty": 1}]}
				""".formatted(chicken)).andExpect(status().isCreated());

		assertThat(countRows(chicken)).isEqualTo(1);
	}

	@Test
	void 모르는_method_와_시간대_없는_시각은_본문을_못_읽은_것이라_MALFORMED() throws Exception {
		count(mine, """
				{"counts": [{"ingredientId": "%d", "qty": 1, "method": "GUESS"}]}
				""".formatted(chicken)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_MALFORMED_REQUEST"));
		count(mine, """
				{"countedAt": "2026-10-14T09:00:00", "counts": [{"ingredientId": "%d", "qty": 1}]}
				""".formatted(chicken)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_MALFORMED_REQUEST"));
		assertThat(countRows(chicken)).isZero();
	}

	@Test
	void 기존_최신_실사보다_이른_시각은_기준점이_못_되므로_400() throws Exception {
		count(mine, """
				{"counts": [{"ingredientId": "%d", "qty": 500}]}
				""".formatted(chicken)).andExpect(status().isCreated());

		count(mine, """
				{"countedAt": "2026-10-14T08:00:00+09:00", "counts": [{"ingredientId": "%d", "qty": 1}]}
				""".formatted(chicken)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
		// 다른 재료는 그 시각에 실사한 적이 없으니 된다
		count(mine, """
				{"countedAt": "2026-10-14T08:00:00+09:00", "counts": [{"ingredientId": "%d", "qty": 1}]}
				""".formatted(onion)).andExpect(status().isCreated());

		assertThat(countRows(chicken)).isEqualTo(1);
		assertThat(countRows(onion)).isEqualTo(1);
	}

	@Test
	void 주기형_재료는_입고_기록으로만_계산하므로_실사를_받지_않는다() throws Exception {
		long rice = jdbc.queryForObject(
				"INSERT INTO ingredient (store_id, name, unit, price_sensitive, sourcing_type, kind, storage_type, storage_location,"
						+ " category_id, category_source)"
						+ " VALUES (?, '쌀', 'G', false, 'GENERIC', 'CYCLE', 'AMBIENT', 'WAREHOUSE', " + OTHER_CATEGORY_SQL
						+ ", 'AUTO') RETURNING id",
				Long.class, mine.storeId(), mine.storeId());

		count(mine, """
				{"counts": [{"ingredientId": "%d", "qty": 10000}, {"ingredientId": "%d", "qty": 1}]}
				""".formatted(rice, chicken)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));

		assertThat(countRows(rice)).isZero();
		assertThat(countRows(chicken)).isZero();
	}

	@Test
	void 실사는_영업_전_값이라_실사한_날의_판매량부터_뺀다() throws Exception {
		long menu = insertMenu(jdbc, mine.storeId(), "닭가슴살 샐러드");
		insertRecipe(jdbc, mine.storeId(), menu, chicken, "MANUAL"); // 1인분 100 g
		jdbc.update("INSERT INTO pos_sale (store_id, sold_date, menu_id, qty) VALUES (?, DATE '2026-10-14', ?, 3)",
				mine.storeId(), menu);

		// 오늘(10-14) 영업 전 1000 g → 오늘 300 g 팔림 → 700 g, 실사 필요 아님
		count(mine, """
				{"counts": [{"ingredientId": "%d", "qty": 1000}]}
				""".formatted(chicken))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data[0].estimatedQty").value(700.0))
				.andExpect(jsonPath("$.data[0].needsCount").value(false));
	}

	@Test
	void 남의_가게가_실사해도_내_체크리스트와_needsCount_는_그대로다() throws Exception {
		count(other, """
				{"counts": [{"ingredientId": "%d", "qty": 10}]}
				""".formatted(otherChicken)).andExpect(status().isCreated());

		estimate(mine).andExpect(jsonPath("$.data[0].needsCount").value(true));
		me(mine).andExpect(jsonPath("$.data.checklist[?(@.step == 'STOCK_COUNT')].done").value(hasItem(false)));
		assertThat(countRows(chicken)).isZero();
	}

	@Test
	void 인증_없으면_401() throws Exception {
		mockMvc.perform(post("/stock-counts")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"counts\": [{\"ingredientId\": \"1\", \"qty\": 1}]}"))
				.andExpect(status().isUnauthorized());
	}

	private ResultActions count(LoggedInStore store, String body) throws Exception {
		return mockMvc.perform(post("/stock-counts")
				.header(HttpHeaders.AUTHORIZATION, store.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private ResultActions estimate(LoggedInStore store) throws Exception {
		return mockMvc.perform(get("/inventory/estimate").header(HttpHeaders.AUTHORIZATION, store.bearer()))
				.andExpect(status().isOk());
	}

	private ResultActions me(LoggedInStore store) throws Exception {
		return mockMvc.perform(get("/stores/me").header(HttpHeaders.AUTHORIZATION, store.bearer()))
				.andExpect(status().isOk());
	}

	private int countRows(long ingredientId) {
		return jdbc.queryForObject("SELECT count(*) FROM stock_count WHERE ingredient_id = ?", Integer.class, ingredientId);
	}
}
