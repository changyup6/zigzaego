package com.zigzaego.api.account;

import static com.zigzaego.api.support.StoreFixtures.insertIngredient;
import static com.zigzaego.api.support.StoreFixtures.insertMenu;
import static com.zigzaego.api.support.StoreFixtures.loginNewStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.support.StoreFixtures.LoggedInStore;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
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

/** {@code GET /stores/me} · {@code PATCH /stores/me} — 실제 로그인 토큰으로 부르고, 가게 2개를 넣어 남의 가게 데이터가 섞이지 않는지 본다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class StoreControllerTest {

	/** 질문 6개 중 4개 답함 (answer 가 null 이거나 없는 2개가 남음). */
	private static final String SIX_QUESTIONS_TWO_LEFT = """
			{"questions": [
			  {"id": "q1", "answer": 200}, {"id": "q2", "answer": 90}, {"id": "q3", "answer": "CREAM"},
			  {"id": "q4", "answer": false}, {"id": "q5", "answer": null}, {"id": "q6"}
			]}
			""";

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private DataSource dataSource;

	private MockMvc mockMvc;

	private LoggedInStore mine;

	private LoggedInStore other;

	@BeforeEach
	void setUp() throws Exception {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		mine = loginNewStore(jdbc, passwordEncoder, mockMvc, "내가게");
		other = loginNewStore(jdbc, passwordEncoder, mockMvc, "남의가게");
	}

	@Test
	void 온보딩_전_가게는_모든_단계가_남는다() throws Exception {
		me(mine)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.id").value(String.valueOf(mine.storeId())))
				.andExpect(jsonPath("$.data.timezone").value("Asia/Seoul"))
				.andExpect(jsonPath("$.data.remainingSteps").value(6))
				.andExpect(jsonPath("$.data.checklist", hasSize(6)))
				.andExpect(jsonPath("$.data.checklist[0].step").value("ONBOARDING"))
				.andExpect(jsonPath("$.data.checklist[0].done").value(false))
				.andExpect(jsonPath("$.data.checklist[0].remaining").value(1))
				.andExpect(jsonPath("$.data.checklist[1].step").value("RECIPE"))
				.andExpect(jsonPath("$.data.checklist[2].step").value("POS"))
				.andExpect(jsonPath("$.data.checklist[3].step").value("SUPPLIER"))
				.andExpect(jsonPath("$.data.checklist[4].step").value("FIRST_RECEIVE"))
				.andExpect(jsonPath("$.data.checklist[5].step").value("STOCK_COUNT"));
	}

	@Test
	void 질문_6개_중_2개가_남으면_recipe는_done_false_remaining_2() throws Exception {
		insertOnboardingJob(mine.storeId(), SIX_QUESTIONS_TWO_LEFT);

		me(mine)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.checklist[1].step").value("RECIPE"))
				.andExpect(jsonPath("$.data.checklist[1].done").value(false))
				.andExpect(jsonPath("$.data.checklist[1].remaining").value(2));
	}

	@Test
	void 질문을_다_답하면_recipe는_끝() throws Exception {
		insertOnboardingJob(mine.storeId(), """
				{"questions": [{"id": "q1", "answer": 200}, {"id": "q2", "answer": 90}]}
				""");

		me(mine)
				.andExpect(jsonPath("$.data.checklist[1].done").value(true))
				.andExpect(jsonPath("$.data.checklist[1].remaining").value(0));
	}

	@Test
	void 가장_최근_끝난_온보딩_job의_질문만_센다() throws Exception {
		insertOnboardingJob(mine.storeId(), SIX_QUESTIONS_TWO_LEFT);
		insertOnboardingJob(mine.storeId(), """
				{"questions": [{"id": "q1", "answer": null}, {"id": "q2", "answer": 1}, {"id": "q3", "answer": 2}]}
				""");
		jdbc.update("INSERT INTO job (store_id, type, status, result_json) VALUES (?, 'ONBOARDING', 'RUNNING', NULL)",
				mine.storeId());

		me(mine).andExpect(jsonPath("$.data.checklist[1].remaining").value(1));
	}

	@Test
	void 데이터가_다_있으면_체크리스트가_비고_remainingSteps는_0() throws Exception {
		long storeId = mine.storeId();
		long menu = insertMenu(jdbc, storeId, "메뉴");
		long ingredient = insertIngredient(jdbc, storeId, "재료");
		jdbc.update("INSERT INTO recipe (store_id, menu_id, ingredient_id, qty_per_serving) VALUES (?, ?, ?, 100)",
				storeId, menu, ingredient);
		jdbc.update("INSERT INTO pos_sale (store_id, sold_date, menu_id, qty) VALUES (?, DATE '2026-10-01', ?, 3)",
				storeId, menu);
		jdbc.update("INSERT INTO supplier (store_id, name) VALUES (?, '거래처')", storeId);
		jdbc.update("INSERT INTO purchase_doc (store_id, parse_status, kind) VALUES (?, 'CONFIRMED', 'RECEIVE')", storeId);
		jdbc.update("INSERT INTO stock_count (store_id, ingredient_id, counted_at, qty) VALUES (?, ?, now(), 5)",
				storeId, ingredient);

		me(mine)
				.andExpect(jsonPath("$.data.remainingSteps").value(0))
				.andExpect(jsonPath("$.data.checklist[*].done").value(everyItem(is(true))))
				.andExpect(jsonPath("$.data.checklist[*].remaining").value(everyItem(is(0))));
	}

	@Test
	void 확인_전_입고_문서는_첫_입고로_세지_않는다() throws Exception {
		jdbc.update("INSERT INTO purchase_doc (store_id, parse_status, kind) VALUES (?, 'PARSED', 'RECEIVE')",
				mine.storeId());
		jdbc.update("INSERT INTO purchase_doc (store_id, parse_status, kind) VALUES (?, 'CONFIRMED', 'PAST')",
				mine.storeId());

		me(mine).andExpect(jsonPath("$.data.checklist[4].done").value(false));
	}

	@Test
	void 포스기_업체가_설정되면_pos는_끝() throws Exception {
		jdbc.update("UPDATE store SET pos_vendor = 'OKPOS' WHERE id = ?", mine.storeId());

		me(mine).andExpect(jsonPath("$.data.checklist[2].done").value(true));
	}

	@Test
	void 남의_가게_데이터는_내_체크리스트에_섞이지_않는다() throws Exception {
		long otherStore = other.storeId();
		long menu = insertMenu(jdbc, otherStore, "남의메뉴");
		long ingredient = insertIngredient(jdbc, otherStore, "남의재료");
		jdbc.update("INSERT INTO supplier (store_id, name) VALUES (?, '남의거래처')", otherStore);
		jdbc.update("INSERT INTO stock_count (store_id, ingredient_id, counted_at, qty) VALUES (?, ?, now(), 5)",
				otherStore, ingredient);
		jdbc.update("INSERT INTO pos_sale (store_id, sold_date, menu_id, qty) VALUES (?, DATE '2026-10-01', ?, 3)",
				otherStore, menu);
		insertOnboardingJob(otherStore, SIX_QUESTIONS_TWO_LEFT);

		me(mine)
				.andExpect(jsonPath("$.data.id").value(String.valueOf(mine.storeId())))
				.andExpect(jsonPath("$.data.remainingSteps").value(6))
				.andExpect(jsonPath("$.data.checklist[1].remaining").value(1));
		me(other)
				.andExpect(jsonPath("$.data.id").value(String.valueOf(otherStore)))
				.andExpect(jsonPath("$.data.checklist[0].done").value(true))
				.andExpect(jsonPath("$.data.checklist[1].remaining").value(2));
	}

	@Test
	void 시군구를_고치면_내_가게만_바뀐다() throws Exception {
		me(mine).andExpect(jsonPath("$.data.sigungu").value(nullValue()));

		patchMe(mine, "{\"sigungu\": \"  서울   중구 \"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.id").value(String.valueOf(mine.storeId())))
				.andExpect(jsonPath("$.data.sigungu").value("서울 중구"))
				.andExpect(jsonPath("$.data.remainingSteps").value(6));
		me(mine).andExpect(jsonPath("$.data.sigungu").value("서울 중구"));
		me(other).andExpect(jsonPath("$.data.sigungu").value(nullValue()));

		// 안 보낸 칸은 그대로, 빈 글자는 지움
		patchMe(mine, "{}").andExpect(jsonPath("$.data.sigungu").value("서울 중구"));
		patchMe(mine, "{\"sigungu\": \"\"}").andExpect(jsonPath("$.data.sigungu").value(nullValue()));
	}

	@Test
	void 시군구_형식이_틀리면_400() throws Exception {
		patchMe(mine, "{\"sigungu\": \"Seoul Jung-gu\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
		patchMe(mine, "{\"sigungu\": \"서울 중구 명동\"}")
				.andExpect(status().isBadRequest());
		patchMe(mine, "{\"sigungu\": \"" + "가".repeat(51) + "\"}")
				.andExpect(status().isBadRequest());
		me(mine).andExpect(jsonPath("$.data.sigungu").value(nullValue()));
	}

	@Test
	void 인증_없으면_401() throws Exception {
		mockMvc.perform(patch("/stores/me").contentType(MediaType.APPLICATION_JSON).content("{\"sigungu\": \"서울 중구\"}"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/stores/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("COMMON_UNAUTHORIZED"));
	}

	// ---------------------------------------------------------------- 가격 비교 켬/끔 (S15P31A308-205)

	@Test
	void 새_가게는_가격_비교가_켜져_있고_끄고_다시_켤_수_있다() throws Exception {
		me(mine).andExpect(jsonPath("$.data.priceCompare").value(true));

		patchMe(mine, "{\"priceCompare\": false}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.id").value(String.valueOf(mine.storeId())))
				.andExpect(jsonPath("$.data.priceCompare").value(false));
		me(mine).andExpect(jsonPath("$.data.priceCompare").value(false));

		patchMe(mine, "{\"priceCompare\": true}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.priceCompare").value(true));
		me(mine).andExpect(jsonPath("$.data.priceCompare").value(true));
	}

	@Test
	void 시군구만_보내면_가격_비교는_그대로() throws Exception {
		patchMe(mine, "{\"priceCompare\": false}").andExpect(status().isOk());

		patchMe(mine, "{\"sigungu\": \"서울 중구\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.sigungu").value("서울 중구"))
				.andExpect(jsonPath("$.data.priceCompare").value(false));
		me(mine).andExpect(jsonPath("$.data.priceCompare").value(false));
	}

	@Test
	void 가격_비교만_보내면_시군구는_그대로() throws Exception {
		patchMe(mine, "{\"sigungu\": \"서울 중구\"}").andExpect(status().isOk());

		patchMe(mine, "{\"priceCompare\": false}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.sigungu").value("서울 중구"))
				.andExpect(jsonPath("$.data.priceCompare").value(false));
		me(mine)
				.andExpect(jsonPath("$.data.sigungu").value("서울 중구"))
				.andExpect(jsonPath("$.data.priceCompare").value(false));
	}

	@Test
	void 빈_PATCH와_null_가격_비교는_둘_다_그대로() throws Exception {
		patchMe(mine, "{\"sigungu\": \"서울 중구\", \"priceCompare\": false}").andExpect(status().isOk());

		patchMe(mine, "{}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.sigungu").value("서울 중구"))
				.andExpect(jsonPath("$.data.priceCompare").value(false));
		patchMe(mine, "{\"priceCompare\": null}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.priceCompare").value(false));
		me(mine)
				.andExpect(jsonPath("$.data.sigungu").value("서울 중구"))
				.andExpect(jsonPath("$.data.priceCompare").value(false));
	}

	@Test
	void 가격_비교에_불리언이_아닌_값을_보내면_400_이고_그대로() throws Exception {
		// 본문을 못 읽은 것이라 전역 처리(HttpMessageNotReadable)의 400 COMMON_MALFORMED_REQUEST 를 따른다
		patchMe(mine, "{\"priceCompare\": \"yes\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_MALFORMED_REQUEST"));
		patchMe(mine, "{\"priceCompare\": 1}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_MALFORMED_REQUEST"));
		patchMe(mine, "{\"priceCompare\": \"false\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_MALFORMED_REQUEST"));
		me(mine).andExpect(jsonPath("$.data.priceCompare").value(true));
	}

	@Test
	void 가격_비교를_토큰_없이_고치면_401() throws Exception {
		mockMvc.perform(patch("/stores/me").contentType(MediaType.APPLICATION_JSON).content("{\"priceCompare\": false}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("COMMON_UNAUTHORIZED"));
		me(mine).andExpect(jsonPath("$.data.priceCompare").value(true));
	}

	@Test
	void 내_가게가_가격_비교를_꺼도_남의_가게는_켜져_있다() throws Exception {
		patchMe(mine, "{\"priceCompare\": false}").andExpect(status().isOk());

		me(mine).andExpect(jsonPath("$.data.priceCompare").value(false));
		me(other)
				.andExpect(jsonPath("$.data.id").value(String.valueOf(other.storeId())))
				.andExpect(jsonPath("$.data.priceCompare").value(true));
	}

	@Test
	void V7은_V6_뒤에_적용되고_기존_가게_행은_가격_비교가_켜진다() {
		// 빈 스키마에 V6 까지 올리고 가게 행을 넣은 뒤 V7 을 적용한다 — 운영 DB 처럼 행이 있는 상태에서의 적용
		String schema = "v7_price_compare_check";
		jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
		try {
			Flyway.configure().dataSource(dataSource).schemas(schema).target("6").load().migrate();
			jdbc.update("INSERT INTO " + schema + ".store (name, display_name) VALUES ('기존가게', '기존가게')");

			Flyway v7 = Flyway.configure().dataSource(dataSource).schemas(schema).target("7").load();
			v7.migrate();

			assertThat(v7.info().current().getVersion().getVersion()).isEqualTo("7");
			assertThat(jdbc.queryForObject("SELECT price_compare FROM " + schema + ".store WHERE name = '기존가게'",
					Boolean.class)).isTrue();
			assertThat(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns"
					+ " WHERE table_schema = ? AND table_name = 'store' AND column_name = 'price_compare'",
					String.class, schema)).isEqualTo("NO");
		} finally {
			jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
		}
	}

	@Test
	void swagger_의_StoreMeResponse_와_StoreUpdateRequest_에_priceCompare_가_있다() throws Exception {
		String docs = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		Map<String, Object> response = JsonPath.read(docs, "$.components.schemas.StoreMeResponse.properties.priceCompare");
		Map<String, Object> request = JsonPath.read(docs, "$.components.schemas.StoreUpdateRequest.properties.priceCompare");
		assertThat(response).containsEntry("type", "boolean");
		assertThat(String.valueOf(request.get("type"))).contains("boolean");
	}

	private ResultActions patchMe(LoggedInStore store, String body) throws Exception {
		return mockMvc.perform(patch("/stores/me").header(HttpHeaders.AUTHORIZATION, store.bearer())
				.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	@Test
	void 질문_답이_전부_null이어도_레시피_행이_있으면_recipe는_끝() throws Exception {
		insertOnboardingJob(mine.storeId(), SIX_QUESTIONS_TWO_LEFT);
		insertRecipeRow(mine.storeId());

		me(mine)
				.andExpect(jsonPath("$.data.checklist[1].step").value("RECIPE"))
				.andExpect(jsonPath("$.data.checklist[1].done").value(true))
				.andExpect(jsonPath("$.data.checklist[1].remaining").value(0));
	}

	@Test
	void 레시피_행이_없고_질문이_있으면_미답_수가_remaining() throws Exception {
		insertOnboardingJob(mine.storeId(), SIX_QUESTIONS_TWO_LEFT);

		me(mine)
				.andExpect(jsonPath("$.data.checklist[1].done").value(false))
				.andExpect(jsonPath("$.data.checklist[1].remaining").value(2));
	}

	@Test
	void 남의_가게_레시피_행은_내_recipe_판정에_쓰이지_않는다() throws Exception {
		insertOnboardingJob(mine.storeId(), SIX_QUESTIONS_TWO_LEFT);
		insertRecipeRow(other.storeId());

		me(mine)
				.andExpect(jsonPath("$.data.checklist[1].done").value(false))
				.andExpect(jsonPath("$.data.checklist[1].remaining").value(2));
		me(other)
				.andExpect(jsonPath("$.data.checklist[1].done").value(true))
				.andExpect(jsonPath("$.data.checklist[1].remaining").value(0));
	}

	private void insertRecipeRow(long storeId) {
		long menu = insertMenu(jdbc, storeId, "메뉴");
		long ingredient = insertIngredient(jdbc, storeId, "재료");
		jdbc.update("INSERT INTO recipe (store_id, menu_id, ingredient_id, qty_per_serving) VALUES (?, ?, ?, 100)",
				storeId, menu, ingredient);
	}

	private ResultActions me(LoggedInStore store) throws Exception {
		return mockMvc.perform(get("/stores/me").header(HttpHeaders.AUTHORIZATION, store.bearer()));
	}

	private void insertOnboardingJob(long storeId, String resultJson) {
		jdbc.update("INSERT INTO job (store_id, type, status, progress, result_json, finished_at)"
						+ " VALUES (?, 'ONBOARDING', 'DONE', 1, CAST(? AS JSONB), clock_timestamp())",
				storeId, resultJson);
	}
}
