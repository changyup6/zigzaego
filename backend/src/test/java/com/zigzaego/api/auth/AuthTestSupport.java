package com.zigzaego.api.auth;

import com.jayway.jsonpath.JsonPath;
import com.zigzaego.api.ingredient.entity.DefaultIngredientCategory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;

/** 인증 테스트 공용: 가게 · 계정 · 가게 연결 넣기, 응답에서 토큰 꺼내기. */
public final class AuthTestSupport {

	public static final String PASSWORD = "password1234";

	private AuthTestSupport() {
	}

	/**
	 * store 표의 NOT NULL 칸만 채운 가게 하나 + 기본 재료 카테고리 11종. 가게가 생기면 카테고리가 반드시 있다
	 * (V11 · StoreAccountCreator, S15P31A308-273) — 재료 INSERT 의 category_id 가 이것을 가리킨다.
	 */
	public static long insertStore(JdbcTemplate jdbc, String name) {
		long storeId = jdbc.queryForObject(
				"INSERT INTO store (name, display_name) VALUES (?, ?) RETURNING id", Long.class, name, name);
		for (DefaultIngredientCategory category : DefaultIngredientCategory.values()) {
			jdbc.update("INSERT INTO ingredient_category (store_id, code, name, sort_order) VALUES (?, ?, ?, ?)",
					storeId, category.name(), category.getDisplayName(), category.getSortOrder());
		}
		return storeId;
	}

	/** 비밀번호가 {@link #PASSWORD}인 계정. 이메일은 테스트마다 겹치지 않게 만든다. */
	public static long insertAccount(JdbcTemplate jdbc, PasswordEncoder encoder, String email) {
		return jdbc.queryForObject(
				"INSERT INTO account (email, password_hash, name) VALUES (?, ?, ?) RETURNING id", Long.class,
				email, encoder.encode(PASSWORD), "사장님");
	}

	public static void insertMember(JdbcTemplate jdbc, long accountId, long storeId) {
		jdbc.update("INSERT INTO store_member (account_id, store_id, role) VALUES (?, ?, 'OWNER')", accountId, storeId);
	}

	public static String uniqueEmail() {
		return "owner-" + System.nanoTime() + "@example.com";
	}

	public static String loginJson(String email, String password) {
		return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
	}

	public static String refreshJson(String refreshToken) {
		return "{\"refreshToken\":\"" + refreshToken + "\"}";
	}

	public static String read(MvcResult result, String path) throws Exception {
		return JsonPath.read(result.getResponse().getContentAsString(), path);
	}
}
