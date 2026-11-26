package com.zigzaego.api.auth;

import static com.zigzaego.api.auth.AuthTestSupport.PASSWORD;
import static com.zigzaego.api.auth.AuthTestSupport.insertAccount;
import static com.zigzaego.api.auth.AuthTestSupport.insertMember;
import static com.zigzaego.api.auth.AuthTestSupport.insertStore;
import static com.zigzaego.api.auth.AuthTestSupport.loginJson;
import static com.zigzaego.api.auth.AuthTestSupport.read;
import static com.zigzaego.api.auth.AuthTestSupport.refreshJson;
import static com.zigzaego.api.auth.AuthTestSupport.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.auth.jwt.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** 로그인 · 재발급 · 로그아웃 (S15P31A308-34). Redis · Postgres는 Testcontainers 실물. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AuthControllerTest {

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JwtTokenProvider tokenProvider;

	private MockMvc mockMvc;

	private String email;

	private long accountId;

	private long storeId;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		email = uniqueEmail();
		accountId = insertAccount(jdbc, passwordEncoder, email);
		storeId = insertStore(jdbc, "가게-" + System.nanoTime());
		insertMember(jdbc, accountId, storeId);
	}

	@Test
	void 로그인하면_토큰_한_쌍을_받고_access_token에_계정과_가게가_든다() throws Exception {
		MvcResult result = login(email, PASSWORD)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.accessToken").isString())
				.andExpect(jsonPath("$.data.refreshToken").isString())
				.andExpect(jsonPath("$.data.expiresIn").value(1800))
				.andExpect(jsonPath("$.error").value(nullValue()))
				.andReturn();

		assertThat(tokenProvider.parseAccessToken(read(result, "$.data.accessToken")))
				.isEqualTo(new AuthUser(accountId, storeId));
	}

	@Test
	void 비밀번호가_틀리면_401_공통_응답() throws Exception {
		login(email, "wrong-password")
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.data").value(nullValue()))
				.andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"))
				.andExpect(jsonPath("$.error.message").isString());
	}

	@Test
	void 없는_이메일도_비밀번호_틀림과_같은_401() throws Exception {
		login("nobody-" + System.nanoTime() + "@example.com", PASSWORD)
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("AUTH_INVALID_CREDENTIALS"));
	}

	@Test
	void 이메일이_비면_400_필드_오류() throws Exception {
		login("", PASSWORD)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"))
				.andExpect(jsonPath("$.error.fieldErrors[0].field").value("email"));
	}

	@Test
	void 연결된_가게가_없으면_403() throws Exception {
		String lonely = uniqueEmail();
		insertAccount(jdbc, passwordEncoder, lonely);

		login(lonely, PASSWORD)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error.code").value("AUTH_NO_STORE"));
	}

	@Test
	void refresh하면_새_토큰_한_쌍을_받고_쓴_refresh_token은_다시_못_쓴다() throws Exception {
		String oldRefresh = read(login(email, PASSWORD).andReturn(), "$.data.refreshToken");

		MvcResult refreshed = refresh(oldRefresh)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.accessToken").isString())
				.andReturn();
		String newRefresh = read(refreshed, "$.data.refreshToken");
		assertThat(newRefresh).isNotEqualTo(oldRefresh);
		assertThat(tokenProvider.parseAccessToken(read(refreshed, "$.data.accessToken")))
				.isEqualTo(new AuthUser(accountId, storeId));

		refresh(oldRefresh)
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("AUTH_INVALID_REFRESH_TOKEN"));
		refresh(newRefresh).andExpect(status().isOk());
	}

	@Test
	void 없는_refresh_token은_401() throws Exception {
		refresh("no-such-token")
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("AUTH_INVALID_REFRESH_TOKEN"));
	}

	@Test
	void 가게_연결이_끊긴_계정은_refresh가_안_된다() throws Exception {
		String refreshToken = read(login(email, PASSWORD).andReturn(), "$.data.refreshToken");
		jdbc.update("DELETE FROM store_member WHERE account_id = ?", accountId);

		refresh(refreshToken)
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("AUTH_INVALID_REFRESH_TOKEN"));
	}

	@Test
	void 로그아웃하면_그_refresh_token은_401() throws Exception {
		String refreshToken = read(login(email, PASSWORD).andReturn(), "$.data.refreshToken");

		mockMvc.perform(post("/auth/logout").contentType(MediaType.APPLICATION_JSON).content(refreshJson(refreshToken)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true));

		refresh(refreshToken)
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("AUTH_INVALID_REFRESH_TOKEN"));
	}

	@Test
	void 로그아웃은_다른_기기의_로그인을_끊지_않는다() throws Exception {
		String phone = read(login(email, PASSWORD).andReturn(), "$.data.refreshToken");
		String pc = read(login(email, PASSWORD).andReturn(), "$.data.refreshToken");

		mockMvc.perform(post("/auth/logout").contentType(MediaType.APPLICATION_JSON).content(refreshJson(phone)))
				.andExpect(status().isOk());

		refresh(pc).andExpect(status().isOk());
	}

	private ResultActions login(String email, String password) throws Exception {
		return mockMvc.perform(post("/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginJson(email, password)));
	}

	private ResultActions refresh(String refreshToken) throws Exception {
		return mockMvc.perform(post("/auth/refresh")
				.contentType(MediaType.APPLICATION_JSON)
				.content(refreshJson(refreshToken)));
	}
}
