package com.zigzaego.api.closingcheck;

import static com.zigzaego.api.support.StoreFixtures.OTHER_CATEGORY_SQL;
import static com.zigzaego.api.support.StoreFixtures.insertIngredient;
import static com.zigzaego.api.support.StoreFixtures.insertMenu;
import static com.zigzaego.api.support.StoreFixtures.insertRecipe;
import static com.zigzaego.api.support.StoreFixtures.loginNewStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.support.FixedClockConfig;
import com.zigzaego.api.support.StoreFixtures.LoggedInStore;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
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
 * POST /closing-check (S15P31A308-252, DECISIONS #88 · #92). 오늘은 FixedClockConfig 의 서울 2026-10-14 12:00.
 * 결과 목록은 재료 id 오름차순 — [0] 양파, [1] 우유.
 */
@Import({TestcontainersConfiguration.class, FixedClockConfig.class})
@SpringBootTest
class ClosingCheckSaveTest {

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PasswordEncoder passwordEncoder;

	private MockMvc mockMvc;
	private LoggedInStore mine;
	private LoggedInStore other;
	private long onion;
	private long milk;
	private long otherOnion;

	@BeforeEach
	void setUp() throws Exception {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		mine = loginNewStore(jdbc, passwordEncoder, mockMvc, "마감가게");
		other = loginNewStore(jdbc, passwordEncoder, mockMvc, "남의가게");
		onion = insertIngredient(jdbc, mine.storeId(), "양파");
		milk = insertIngredient(jdbc, mine.storeId(), "우유");
		otherOnion = insertIngredient(jdbc, other.storeId(), "양파");
	}

	private void countAt(long ingredientId, String at, int qty) {
		jdbc.update("INSERT INTO stock_count (store_id, ingredient_id, counted_at, qty) VALUES (?, ?, ?, ?)",
				mine.storeId(), ingredientId, OffsetDateTime.parse(at), qty);
	}

	@Test
	void 실사_뒤_빼기를_넣으면_추정_재고가_그만큼_줄고_빼기_전후가_남는다() throws Exception {
		countAt(onion, "2026-10-14T09:00:00+09:00", 1450);

		save(mine, """
				{"adjustments": [{"ingredientId": "%d", "qty": 500, "reason": "SPOILED", "memo": "물렀음"}],
				 "sourceText": "양파 반 망 상했어"}
				""".formatted(onion))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data[0].ingredientId").value(String.valueOf(onion)))
				.andExpect(jsonPath("$.data[0].estimatedQty").value(950.0))
				.andExpect(jsonPath("$.data[0].needsCount").value(false));

		Map<String, Object> row = jdbc.queryForMap("SELECT * FROM stock_adjustment WHERE store_id = ?", mine.storeId());
		assertThat((BigDecimal) row.get("qty")).isEqualByComparingTo("500");
		assertThat((BigDecimal) row.get("before_qty")).isEqualByComparingTo("1450");
		assertThat((BigDecimal) row.get("after_qty")).isEqualByComparingTo("950");
		assertThat(row.get("reason")).isEqualTo("SPOILED");
		assertThat(row.get("memo")).isEqualTo("물렀음");
		assertThat(row.get("source_text")).isEqualTo("양파 반 망 상했어");
		assertThat(jdbc.queryForObject("SELECT adjusted_at FROM stock_adjustment WHERE store_id = ?",
				OffsetDateTime.class, mine.storeId()).toInstant()).hasToString("2026-10-14T02:59:59Z");
	}

	@Test
	void 빼기_뒤에_들어온_실사가_기준점이_되고_그_빼기는_다시_빠지지_않는다() throws Exception {
		countAt(onion, "2026-10-14T09:00:00+09:00", 1000);
		save(mine, """
				{"adjustments": [{"ingredientId": "%d", "qty": 300, "reason": "DISCARD"}]}
				""".formatted(onion)).andExpect(jsonPath("$.data[0].estimatedQty").value(700.0));

		save(mine, """
				{"counts": [{"ingredientId": "%d", "qty": 600, "estimatedQty": 700, "method": "CORRECTED"}]}
				""".formatted(onion))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data[0].estimatedQty").value(600.0));
		estimate(mine).andExpect(jsonPath("$.data[0].estimatedQty").value(600.0));
	}

	@Test
	void 같은_요청에_빼기와_실사가_함께_오면_실사가_최종값이다() throws Exception {
		countAt(onion, "2026-10-14T09:00:00+09:00", 1000);

		save(mine, """
				{"counts": [{"ingredientId": "%d", "qty": 400, "estimatedQty": 1000, "method": "CORRECTED"}],
				 "adjustments": [{"ingredientId": "%d", "qty": 300, "reason": "DAMAGED"}]}
				""".formatted(onion, onion))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data[0].estimatedQty").value(400.0));
	}

	@Test
	void 추정보다_많이_버려도_받고_남은_양은_0() throws Exception {
		countAt(onion, "2026-10-14T09:00:00+09:00", 100);

		save(mine, """
				{"adjustments": [{"ingredientId": "%d", "qty": 250, "reason": "SPILLED"}]}
				""".formatted(onion))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data[0].estimatedQty").value(0.0));
		assertThat(jdbc.queryForObject("SELECT after_qty FROM stock_adjustment WHERE store_id = ?", BigDecimal.class,
				mine.storeId())).isEqualByComparingTo("0");
	}

	@Test
	void 마감_확인_실사는_그날_판매가_끝난_뒤_값이라_오늘_판매를_다시_빼지_않는다() throws Exception {
		long menu = insertMenu(jdbc, mine.storeId(), "양파 볶음");
		insertRecipe(jdbc, mine.storeId(), menu, onion, "MANUAL"); // 1인분 100 g
		jdbc.update("INSERT INTO pos_sale (store_id, sold_date, menu_id, qty) VALUES (?, DATE '2026-10-14', ?, 3)",
				mine.storeId(), menu);

		save(mine, """
				{"counts": [{"ingredientId": "%d", "qty": 1000, "estimatedQty": 1000, "method": "CONFIRMED"}]}
				""".formatted(onion))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data[0].estimatedQty").value(1000.0));
		assertThat(jdbc.queryForObject("SELECT counted_after_close FROM stock_count WHERE ingredient_id = ?",
				Boolean.class, onion)).isTrue();
	}

	@Test
	void 추정치를_비운_실사는_빼기까지_반영한_서버_추정치를_넣는다() throws Exception {
		countAt(milk, "2026-10-14T09:00:00+09:00", 3000);

		save(mine, """
				{"counts": [{"ingredientId": "%d", "qty": 1500, "method": "CORRECTED"}],
				 "adjustments": [{"ingredientId": "%d", "qty": 1000, "reason": "SPOILED"}]}
				""".formatted(milk, milk)).andExpect(status().isCreated());

		assertThat(jdbc.queryForObject("SELECT estimated_qty FROM stock_count WHERE ingredient_id = ? AND qty = 1500",
				BigDecimal.class, milk)).isEqualByComparingTo("2000");
	}

	@Test
	void 남의_가게_재료가_하나라도_있으면_404_이고_아무것도_저장하지_않는다() throws Exception {
		save(mine, """
				{"counts": [{"ingredientId": "%d", "qty": 10, "estimatedQty": 10, "method": "CONFIRMED"}],
				 "adjustments": [{"ingredientId": "%d", "qty": 5, "reason": "DISCARD"}]}
				""".formatted(onion, otherOnion))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));

		assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_adjustment WHERE store_id IN (?, ?)", Integer.class,
				mine.storeId(), other.storeId())).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_count WHERE store_id IN (?, ?)", Integer.class,
				mine.storeId(), other.storeId())).isZero();
	}

	@Test
	void 빈_요청_주기형_재료_0_이하_빼기는_400_모르는_사유는_MALFORMED() throws Exception {
		save(mine, "{}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
		save(mine, """
				{"adjustments": [{"ingredientId": "%d", "qty": 0, "reason": "DISCARD"}]}
				""".formatted(onion)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
		save(mine, """
				{"adjustments": [{"ingredientId": "%d", "qty": 1, "reason": "STOLEN"}]}
				""".formatted(onion)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_MALFORMED_REQUEST"));
		long rice = jdbc.queryForObject(
				"INSERT INTO ingredient (store_id, name, unit, price_sensitive, sourcing_type, kind, storage_type, storage_location,"
						+ " category_id, category_source)"
						+ " VALUES (?, '쌀', 'G', false, 'GENERIC', 'CYCLE', 'AMBIENT', 'WAREHOUSE', " + OTHER_CATEGORY_SQL
						+ ", 'AUTO') RETURNING id",
				Long.class, mine.storeId(), mine.storeId());
		save(mine, """
				{"adjustments": [{"ingredientId": "%d", "qty": 1, "reason": "DISCARD"}]}
				""".formatted(rice)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));

		assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_adjustment WHERE store_id = ?", Integer.class,
				mine.storeId())).isZero();
	}

	@Test
	void 인증_없으면_401() throws Exception {
		mockMvc.perform(post("/closing-check").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isUnauthorized());
	}

	private ResultActions save(LoggedInStore store, String body) throws Exception {
		return mockMvc.perform(post("/closing-check")
				.header(HttpHeaders.AUTHORIZATION, store.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private ResultActions estimate(LoggedInStore store) throws Exception {
		return mockMvc.perform(get("/inventory/estimate").header(HttpHeaders.AUTHORIZATION, store.bearer()))
				.andExpect(status().isOk());
	}
}
