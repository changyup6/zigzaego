package com.zigzaego.api.account;

import static com.zigzaego.api.auth.AuthTestSupport.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.account.bootstrap.CreateStoreAccountRunner;
import com.zigzaego.api.auth.AuthUser;
import com.zigzaego.api.auth.dto.TokenResponse;
import com.zigzaego.api.auth.jwt.JwtTokenProvider;
import com.zigzaego.api.auth.service.AuthService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 초기 계정 생성 (S15P31A308-37). {@code create-account} 프로필로 띄우면 러너가 계정 · 가게 · 연결을 만들고
 * 그 계정으로 바로 로그인된다. 테스트 컨텍스트는 웹 서버 없이 뜬다 — 실제 실행과 같다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("create-account")
class CreateStoreAccountTest {

	private static final String EMAIL = uniqueEmail();

	private static final String PASSWORD = "first-password-1234";

	@DynamicPropertySource
	static void accountProperties(DynamicPropertyRegistry registry) {
		registry.add("app.create-account.email", () -> "  " + EMAIL + " ");
		registry.add("app.create-account.password", () -> PASSWORD);
		registry.add("app.create-account.owner-name", () -> "운영자");
		registry.add("app.create-account.store-name", () -> "테스트 가게");
	}

	@Autowired
	private CreateStoreAccountRunner runner;

	@Autowired
	private AuthService authService;

	@Autowired
	private JwtTokenProvider tokenProvider;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void 기동하면_계정_가게_OWNER_연결이_생기고_그_계정으로_로그인된다() {
		Map<String, Object> row = jdbc.queryForMap("""
				SELECT a.id AS account_id, a.name AS owner_name, a.password_hash, s.id AS store_id,
				       s.name, s.display_name, s.timezone, s.onboarding::text AS onboarding, m.role
				FROM account a
				JOIN store_member m ON m.account_id = a.id
				JOIN store s ON s.id = m.store_id
				WHERE a.email = ?
				""", EMAIL);

		assertThat(row)
				.containsEntry("owner_name", "운영자")
				.containsEntry("name", "테스트 가게")
				.containsEntry("display_name", "테스트 가게")
				.containsEntry("timezone", "Asia/Seoul")
				.containsEntry("onboarding", "{}")
				.containsEntry("role", "OWNER");
		assertThat((String) row.get("password_hash")).isNotEqualTo(PASSWORD).startsWith("$2");

		// 새 가게에 기본 재료 카테고리 11종 (S15P31A308-273)
		long storeId = ((Number) row.get("store_id")).longValue();
		assertThat(jdbc.queryForList("SELECT code FROM ingredient_category WHERE store_id = ? ORDER BY sort_order",
				String.class, storeId))
				.containsExactly("MEAT", "SEAFOOD", "VEGETABLE", "FRUIT", "EGG_DAIRY", "GRAIN_NOODLE", "TOFU_BEAN",
						"SAUCE", "PROCESSED", "BEVERAGE", "OTHER");

		TokenResponse token = authService.login(EMAIL, PASSWORD);
		assertThat(tokenProvider.parseAccessToken(token.accessToken())).isEqualTo(new AuthUser(
				((Number) row.get("account_id")).longValue(), ((Number) row.get("store_id")).longValue()));
	}

	@Test
	void 같은_이메일로_다시_돌리면_실패하고_아무것도_더_만들지_않는다() {
		long stores = jdbc.queryForObject("SELECT count(*) FROM store", Long.class);

		assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining(EMAIL);

		assertThat(jdbc.queryForObject("SELECT count(*) FROM account WHERE email = ?", Long.class, EMAIL))
				.isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM store", Long.class)).isEqualTo(stores);
	}
}
