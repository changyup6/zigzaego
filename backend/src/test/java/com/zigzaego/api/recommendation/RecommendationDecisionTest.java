package com.zigzaego.api.recommendation;

import static com.zigzaego.api.recommendation.RecommendationTestData.insertIngredient;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertRecommendation;
import static com.zigzaego.api.support.StoreFixtures.loginNewStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.support.FixedClockConfig;
import com.zigzaego.api.support.StoreFixtures.LoggedInStore;
import java.math.BigDecimal;
import java.time.LocalDate;
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
 * PATCH /recommendations/{id} — 승인 · 수정 · 거절 (S15P31A308-69). 실제 로그인 토큰으로 부른다 — decided_by 가 그 계정이어야 한다.
 * 상태 전이는 PENDING → APPROVED | REJECTED 한 번만(재변경 409), 남의 가게 추천은 404 (3주 계획 8절).
 */
@Import({TestcontainersConfiguration.class, FixedClockConfig.class})
@SpringBootTest
class RecommendationDecisionTest {

	private static final LocalDate RUN_DATE = LocalDate.of(2026, 10, 14);

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PasswordEncoder passwordEncoder;

	private MockMvc mockMvc;

	private LoggedInStore myStore;
	private LoggedInStore otherStore;
	private long pending;

	@BeforeEach
	void setUp() throws Exception {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		myStore = loginNewStore(jdbc, passwordEncoder, mockMvc, "결정내가게");
		otherStore = loginNewStore(jdbc, passwordEncoder, mockMvc, "결정남의가게");
		long 등심 = insertIngredient(jdbc, myStore.storeId(), "등심", "G", "BOM", "CHILLED", "2", false);
		pending = insertRecommendation(jdbc, myStore.storeId(), 등심, RUN_DATE, "4000");
	}

	@Test
	void approve_추천_수량_그대로_승인자와_시각_기록() throws Exception {
		decide(myStore, pending, "{\"action\":\"approve\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.id").value(String.valueOf(pending)))
				.andExpect(jsonPath("$.data.status").value("APPROVED"))
				.andExpect(jsonPath("$.data.finalQty").value(4000.0))
				.andExpect(jsonPath("$.data.decidedAt").value("2026-10-14T12:00:00+09:00"))
				.andExpect(jsonPath("$.data.rejectReason").value(nullValue()))
				.andExpect(content().string(containsString("\"finalQty\":4000.000,")));

		Map<String, Object> row = row(pending);
		assertThat(row.get("status")).isEqualTo("APPROVED");
		assertThat((BigDecimal) row.get("final_qty")).isEqualByComparingTo("4000");
		assertThat(row.get("decided_by")).isEqualTo(accountOf(myStore));
		assertThat(row.get("decided_at")).isNotNull();
		assertThat(row.get("reject_reason")).isNull();
	}

	@Test
	void approve_에_finalQty_를_보내면_그_값() throws Exception {
		decide(myStore, pending, "{\"action\":\"approve\",\"finalQty\":5000}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("APPROVED"))
				.andExpect(jsonPath("$.data.finalQty").value(5000.0));
	}

	@Test
	void modify_수량을_바꿔_APPROVED() throws Exception {
		decide(myStore, pending, "{\"action\":\"modify\",\"finalQty\":3000.5}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("APPROVED"))
				.andExpect(jsonPath("$.data.finalQty").value(3000.5))
				.andExpect(jsonPath("$.data.recommendedQty").value(4000.0));

		assertThat((BigDecimal) row(pending).get("final_qty")).isEqualByComparingTo("3000.5");
	}

	@Test
	void modify_에_finalQty_없으면_400_상태는_그대로() throws Exception {
		decide(myStore, pending, "{\"action\":\"modify\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("RECOMMENDATION_FINAL_QTY_REQUIRED"));

		assertThat(row(pending).get("status")).isEqualTo("PENDING");
	}

	@Test
	void reject_사유를_남기고_REJECTED_finalQty_는_없다() throws Exception {
		decide(myStore, pending, "{\"action\":\"reject\",\"finalQty\":1,\"reason\":\" 재고가 충분함 \"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("REJECTED"))
				.andExpect(jsonPath("$.data.finalQty").value(nullValue()))
				.andExpect(jsonPath("$.data.rejectReason").value("재고가 충분함"))
				.andExpect(jsonPath("$.data.decidedAt").value("2026-10-14T12:00:00+09:00"));

		Map<String, Object> row = row(pending);
		assertThat(row.get("final_qty")).isNull();
		assertThat(row.get("decided_by")).isEqualTo(accountOf(myStore));
	}

	@Test
	void reject_사유_없이도_된다() throws Exception {
		decide(myStore, pending, "{\"action\":\"REJECT\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("REJECTED"))
				.andExpect(jsonPath("$.data.rejectReason").value(nullValue()));
	}

	@Test
	void 이미_결정한_추천은_409_값은_그대로() throws Exception {
		decide(myStore, pending, "{\"action\":\"approve\"}").andExpect(status().isOk());

		for (String body : new String[] {"{\"action\":\"approve\"}", "{\"action\":\"modify\",\"finalQty\":1}",
				"{\"action\":\"reject\"}"}) {
			decide(myStore, pending, body)
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.error.code").value("RECOMMENDATION_ALREADY_DECIDED"));
		}
		Map<String, Object> row = row(pending);
		assertThat(row.get("status")).isEqualTo("APPROVED");
		assertThat((BigDecimal) row.get("final_qty")).isEqualByComparingTo("4000");
	}

	@Test
	void 남의_가게_추천은_404_없는_id_도_404() throws Exception {
		decide(otherStore, pending, "{\"action\":\"approve\"}")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));
		assertThat(row(pending).get("status")).isEqualTo("PENDING");

		for (String id : new String[] {"999999999", "abc"}) {
			mockMvc.perform(patch("/recommendations/{id}", id)
							.header(HttpHeaders.AUTHORIZATION, myStore.bearer())
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"action\":\"approve\"}"))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));
		}
	}

	@Test
	void action_이_없거나_모르는_값_finalQty_가_0_이하_소수_4자리면_400() throws Exception {
		for (String body : new String[] {"{}", "{\"action\":\"cancel\"}", "{\"action\":\"approve\",\"finalQty\":0}",
				"{\"action\":\"approve\",\"finalQty\":-1}", "{\"action\":\"modify\",\"finalQty\":1.0005}"}) {
			decide(myStore, pending, body)
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
		}
		assertThat(row(pending).get("status")).isEqualTo("PENDING");
	}

	@Test
	void 인증_없으면_401() throws Exception {
		mockMvc.perform(patch("/recommendations/{id}", pending)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"action\":\"approve\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("COMMON_UNAUTHORIZED"));
	}

	private ResultActions decide(LoggedInStore store, long id, String body) throws Exception {
		return mockMvc.perform(patch("/recommendations/{id}", id)
				.header(HttpHeaders.AUTHORIZATION, store.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private Map<String, Object> row(long id) {
		return jdbc.queryForMap(
				"SELECT status, final_qty, decided_by, decided_at, reject_reason FROM recommendation WHERE id = ?", id);
	}

	private Long accountOf(LoggedInStore store) {
		return jdbc.queryForObject("SELECT account_id FROM store_member WHERE store_id = ?", Long.class, store.storeId());
	}
}
