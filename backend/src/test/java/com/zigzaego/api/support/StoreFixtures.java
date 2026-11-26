package com.zigzaego.api.support;

import static com.zigzaego.api.auth.AuthTestSupport.PASSWORD;
import static com.zigzaego.api.auth.AuthTestSupport.insertAccount;
import static com.zigzaego.api.auth.AuthTestSupport.insertMember;
import static com.zigzaego.api.auth.AuthTestSupport.insertStore;
import static com.zigzaego.api.auth.AuthTestSupport.loginJson;
import static com.zigzaego.api.auth.AuthTestSupport.read;
import static com.zigzaego.api.auth.AuthTestSupport.uniqueEmail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 가게 스코프(ADR-0002) 통합 테스트 공용: 사장님 계정이 달린 가게를 만들고 실제 {@code POST /auth/login}으로 토큰을 받는다.
 * 가게 소속 행은 NOT NULL 칸만 채워 넣는다. 가게 2개는 {@link #loginNewStore}를 두 번 부르면 된다.
 */
public final class StoreFixtures {

	private StoreFixtures() {
	}

	/** 로그인까지 마친 가게 하나. */
	public record LoggedInStore(long storeId, String accessToken) {

		public String bearer() {
			return "Bearer " + accessToken;
		}
	}

	public static LoggedInStore loginNewStore(JdbcTemplate jdbc, PasswordEncoder encoder, MockMvc mockMvc, String label)
			throws Exception {
		long storeId = insertStore(jdbc, label + "-" + System.nanoTime());
		String email = uniqueEmail();
		insertMember(jdbc, insertAccount(jdbc, encoder, email), storeId);
		String token = read(mockMvc.perform(post("/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(loginJson(email, PASSWORD)))
				.andExpect(status().isOk())
				.andReturn(), "$.data.accessToken");
		return new LoggedInStore(storeId, token);
	}

	public static long insertMenu(JdbcTemplate jdbc, long storeId, String name) {
		return jdbc.queryForObject(
				"INSERT INTO menu (store_id, name, source) VALUES (?, ?, 'POS') RETURNING id", Long.class, storeId, name);
	}

	/** 재료 INSERT 의 category_id 값 — 그 가게의 '기타' (S15P31A308-273). ? 자리에 storeId 를 한 번 더 넣는다. */
	public static final String OTHER_CATEGORY_SQL = "(SELECT id FROM ingredient_category WHERE store_id = ? AND code = 'OTHER')";

	public static long insertIngredient(JdbcTemplate jdbc, long storeId, String name) {
		return jdbc.queryForObject(
				"INSERT INTO ingredient (store_id, name, unit, price_sensitive, sourcing_type, kind, storage_type, storage_location,"
						+ " category_id, category_source)"
						+ " VALUES (?, ?, 'G', false, 'GENERIC', 'BOM', 'CHILLED', 'FRIDGE', " + OTHER_CATEGORY_SQL + ", 'AUTO') RETURNING id",
				Long.class, storeId, name, storeId);
	}

	/** 레시피 한 줄. source 는 MANUAL · AI 등 (AI = 추정값). */
	public static long insertRecipe(JdbcTemplate jdbc, long storeId, long menuId, long ingredientId, String source) {
		return jdbc.queryForObject(
				"INSERT INTO recipe (store_id, menu_id, ingredient_id, qty_per_serving, source) VALUES (?, ?, ?, 100, ?)"
						+ " RETURNING id",
				Long.class, storeId, menuId, ingredientId, source);
	}

	public static void insertStockCount(JdbcTemplate jdbc, long storeId, long ingredientId) {
		jdbc.update("INSERT INTO stock_count (store_id, ingredient_id, counted_at, qty) VALUES (?, ?, now(), 5)",
				storeId, ingredientId);
	}

	public static long insertJob(JdbcTemplate jdbc, long storeId) {
		return jdbc.queryForObject(
				"INSERT INTO job (store_id, type) VALUES (?, 'RECEIPT') RETURNING id", Long.class, storeId);
	}

	/** 영수증 job 이 만든, 아직 인식 전인 매입 문서 한 장. */
	public static long insertReceipt(JdbcTemplate jdbc, long storeId, long jobId) {
		return jdbc.queryForObject(
				"INSERT INTO purchase_doc (store_id, parse_status, job_id) VALUES (?, 'PENDING', ?) RETURNING id",
				Long.class, storeId, jobId);
	}
}
