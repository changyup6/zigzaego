package com.zigzaego.api.auth;

import static com.zigzaego.api.auth.AuthTestSupport.insertAccount;
import static com.zigzaego.api.auth.AuthTestSupport.loginJson;
import static com.zigzaego.api.auth.AuthTestSupport.read;
import static com.zigzaego.api.auth.AuthTestSupport.uniqueEmail;
import static com.zigzaego.api.support.StoreFixtures.insertIngredient;
import static com.zigzaego.api.support.StoreFixtures.loginNewStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.auth.jwt.JwtTokenProvider;
import com.zigzaego.api.support.StoreFixtures.LoggedInStore;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** 공개 회원가입 {@code POST /auth/signup} (S15P31A308-204). Postgres · Redis는 Testcontainers 실물. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SignupTest {

	private static final String PASSWORD = "password1234";

	private static final String ALL_AGREEMENTS = "[\"TERMS\",\"PRIVACY\",\"COLLECTION\"]";

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JwtTokenProvider tokenProvider;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
	}

	// ---- [가입] ----

	@Test
	void 가입하면_201과_토큰을_받고_그_토큰으로_내_가게를_보며_같은_비밀번호로_로그인된다() throws Exception {
		String email = uniqueEmail();
		MvcResult result = signup(json(quote(email), quote(PASSWORD), quote("새가게 역삼점"), ALL_AGREEMENTS))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.accessToken").isString())
				.andExpect(jsonPath("$.data.refreshToken").isString())
				.andExpect(jsonPath("$.data.expiresIn").value(1800))
				.andReturn();

		String access = read(result, "$.data.accessToken");
		AuthUser user = tokenProvider.parseAccessToken(access);
		assertThat(jdbc.queryForObject("SELECT email FROM account WHERE id = ?", String.class, user.accountId()))
				.isEqualTo(email);
		assertThat(jdbc.queryForObject("SELECT role FROM store_member WHERE account_id = ? AND store_id = ?",
				String.class, user.accountId(), user.storeId())).isEqualTo("OWNER");
		assertThat(jdbc.queryForObject("SELECT name FROM account WHERE id = ?", String.class, user.accountId()))
				.isEqualTo("새가게 역삼점");
		assertThat(jdbc.queryForObject("SELECT onboarding::text FROM store WHERE id = ?", String.class,
				user.storeId())).isEqualTo("{}");
		String hash = jdbc.queryForObject("SELECT password_hash FROM account WHERE id = ?", String.class,
				user.accountId());
		assertThat(hash).isNotEqualTo(PASSWORD);
		assertThat(passwordEncoder.matches(PASSWORD, hash)).isTrue();

		mockMvc.perform(get("/stores/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.id").value(String.valueOf(user.storeId())))
				.andExpect(jsonPath("$.data.name").value("새가게 역삼점"))
				.andExpect(jsonPath("$.data.displayName").value("새가게 역삼점"))
				.andExpect(jsonPath("$.data.timezone").value("Asia/Seoul"));

		login(email, PASSWORD).andExpect(status().isOk());

		// refresh token 도 로그인과 같은 방식(Redis)으로 저장돼 재발급된다
		mockMvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
						.content(AuthTestSupport.refreshJson(read(result, "$.data.refreshToken"))))
				.andExpect(status().isOk());
	}

	@Test
	void 이메일_앞뒤_공백은_잘라서_저장한다() throws Exception {
		String email = uniqueEmail();
		signup(json(quote("  " + email + " "), quote(PASSWORD), quote("가게"), ALL_AGREEMENTS))
				.andExpect(status().isCreated());

		assertThat(jdbc.queryForObject("SELECT count(*) FROM account WHERE email = ?", Long.class, email))
				.isEqualTo(1L);
		login(email, PASSWORD).andExpect(status().isOk());
	}

	@Test
	void 비밀번호_72바이트는_받는다() throws Exception {
		String password72 = "가".repeat(24);
		assertThat(password72.getBytes(StandardCharsets.UTF_8)).hasSize(72);
		String email = uniqueEmail();

		signup(json(quote(email), quote(password72), quote("가게"), ALL_AGREEMENTS)).andExpect(status().isCreated());
		login(email, password72).andExpect(status().isOk());
	}

	@Test
	void storeName이_100자면_받는다() throws Exception {
		String name = "가".repeat(100);
		MvcResult result = signup(json(quote(uniqueEmail()), quote(PASSWORD), quote(name), ALL_AGREEMENTS))
				.andExpect(status().isCreated())
				.andReturn();

		meOf(read(result, "$.data.accessToken")).andExpect(jsonPath("$.data.name").value(name));
	}

	// ---- [경계] ----

	@Test
	void 약관이_하나_빠지면_400() throws Exception {
		assertRejected(json(quote(uniqueEmail()), quote(PASSWORD), quote("가게"), "[\"TERMS\",\"PRIVACY\"]"),
				400, "COMMON_INVALID_INPUT");
	}

	@Test
	void 약관이_없거나_비면_400() throws Exception {
		assertRejected(json(quote(uniqueEmail()), quote(PASSWORD), quote("가게"), "[]"), 400, "COMMON_INVALID_INPUT");
		assertRejected("{\"email\":" + quote(uniqueEmail()) + ",\"password\":" + quote(PASSWORD) + "}",
				400, "COMMON_INVALID_INPUT");
	}

	@Test
	void 모르는_약관_값이_있으면_400() throws Exception {
		assertRejected(json(quote(uniqueEmail()), quote(PASSWORD), quote("가게"),
				"[\"TERMS\",\"PRIVACY\",\"COLLECTION\",\"MARKETING\"]"), 400, "COMMON_INVALID_INPUT");
		assertRejected(json(quote(uniqueEmail()), quote(PASSWORD), quote("가게"),
				"[\"TERMS\",\"PRIVACY\",\"COLLECTION\",null]"), 400, "COMMON_INVALID_INPUT");
	}

	@Test
	void 이미_있는_이메일이면_409_AUTH_EMAIL_TAKEN() throws Exception {
		String email = uniqueEmail();
		insertAccount(jdbc, passwordEncoder, email);

		assertRejected(json(quote(email), quote(PASSWORD), quote("가게"), ALL_AGREEMENTS), 409, "AUTH_EMAIL_TAKEN");
		// 앞뒤 공백을 붙여도 같은 이메일이다
		assertRejected(json(quote(" " + email + " "), quote(PASSWORD), quote("가게"), ALL_AGREEMENTS),
				409, "AUTH_EMAIL_TAKEN");
	}

	@Test
	void 가입한_이메일로_다시_가입하면_409() throws Exception {
		String email = uniqueEmail();
		signup(json(quote(email), quote(PASSWORD), quote("가게"), ALL_AGREEMENTS)).andExpect(status().isCreated());

		assertRejected(json(quote(email), quote("otherpassword"), quote("다른가게"), ALL_AGREEMENTS),
				409, "AUTH_EMAIL_TAKEN");
	}

	@Test
	void 시연_계정_이메일은_다시_가입되고_옛_계정은_보관용_이메일로_남는다() throws Exception {
		String email = "demo-resignup@example.com"; // 테스트 설정의 app.demo-account.email
		String firstAccess = read(signup(json(quote(email), quote(PASSWORD), quote("시연가게"), ALL_AGREEMENTS))
				.andExpect(status().isCreated()).andReturn(), "$.data.accessToken");
		AuthUser first = tokenProvider.parseAccessToken(firstAccess);

		String secondAccess = read(signup(json(quote(email), quote("otherpassword"), quote("시연가게"), ALL_AGREEMENTS))
				.andExpect(status().isCreated()).andReturn(), "$.data.accessToken");
		AuthUser second = tokenProvider.parseAccessToken(secondAccess);

		assertThat(second.accountId()).isNotEqualTo(first.accountId());
		assertThat(second.storeId()).isNotEqualTo(first.storeId());
		login(email, "otherpassword").andExpect(status().isOk());
		login(email, PASSWORD).andExpect(status().isUnauthorized());
		assertThat(jdbc.queryForObject("SELECT email FROM account WHERE id = ?", String.class, first.accountId()))
				.isEqualTo("archived-" + first.accountId() + "-" + email);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM store WHERE id = ?", Long.class, first.storeId()))
				.isEqualTo(1L);
		// 시연 가게만 오늘이 고정일이다 (ADR-0019). 보관된 옛 계정의 가게는 실제 날짜
		mockMvc.perform(get("/stores/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + secondAccess))
				.andExpect(jsonPath("$.data.today").value("2026-11-12"));
		mockMvc.perform(get("/stores/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + firstAccess))
				.andExpect(jsonPath("$.data.today").value(not("2026-11-12")));
	}

	@Test
	void 이메일_형식이_틀리거나_비거나_255자를_넘으면_400() throws Exception {
		assertRejected(json(quote("not-an-email"), quote(PASSWORD), quote("가게"), ALL_AGREEMENTS),
				400, "COMMON_INVALID_INPUT");
		assertRejected(json(quote(""), quote(PASSWORD), quote("가게"), ALL_AGREEMENTS), 400, "COMMON_INVALID_INPUT");
		assertRejected(json(quote("   "), quote(PASSWORD), quote("가게"), ALL_AGREEMENTS), 400, "COMMON_INVALID_INPUT");
		String tooLong = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(63);
		assertThat(tooLong).hasSize(256);
		assertRejected(json(quote(tooLong), quote(PASSWORD), quote("가게"), ALL_AGREEMENTS), 400, "COMMON_INVALID_INPUT");
	}

	@Test
	void 비밀번호가_7자면_400() throws Exception {
		assertRejected(json(quote(uniqueEmail()), quote("pass123"), quote("가게"), ALL_AGREEMENTS),
				400, "COMMON_INVALID_INPUT");
	}

	@Test
	void 비밀번호_글자_수는_코드_포인트로_세서_이모지_4개는_400() throws Exception {
		// UTF-16 길이로는 8 이지만 글자는 4자
		assertRejected(json(quote(uniqueEmail()), quote("😀😀😀😀"), quote("가게"), ALL_AGREEMENTS),
				400, "COMMON_INVALID_INPUT");
	}

	@Test
	void 비밀번호가_비거나_공백뿐이거나_없으면_400() throws Exception {
		assertRejected(json(quote(uniqueEmail()), quote(""), quote("가게"), ALL_AGREEMENTS), 400, "COMMON_INVALID_INPUT");
		assertRejected(json(quote(uniqueEmail()), quote("          "), quote("가게"), ALL_AGREEMENTS),
				400, "COMMON_INVALID_INPUT");
		assertRejected("{\"email\":" + quote(uniqueEmail()) + ",\"agreements\":" + ALL_AGREEMENTS + "}",
				400, "COMMON_INVALID_INPUT");
	}

	@Test
	void 비밀번호_검증_실패_응답에_비밀번호_원문이_없다() throws Exception {
		String password7 = "Secr3t!";
		String blank = "          ";
		String password73 = "가".repeat(24) + "a";
		assertNoPasswordEcho(json(quote(uniqueEmail()), quote(password7), quote("가게"), ALL_AGREEMENTS), password7);
		// 빈 문자열은 어디에나 들어 있으므로 필드 값 형태("rejectedValue":"")로 본다
		assertNoPasswordEcho(json(quote(uniqueEmail()), quote(""), quote("가게"), ALL_AGREEMENTS),
				"\"rejectedValue\":\"\"");
		assertNoPasswordEcho(json(quote(uniqueEmail()), quote(blank), quote("가게"), ALL_AGREEMENTS), blank);
		assertNoPasswordEcho(json(quote(uniqueEmail()), quote(password73), quote("가게"), ALL_AGREEMENTS), password73);
		// 다른 필드가 Bean Validation 으로 걸려도 비밀번호는 실리지 않는다
		assertNoPasswordEcho(json(quote("not-an-email"), quote(password7), quote("가게"), ALL_AGREEMENTS), password7);
	}

	@Test
	void 비밀번호가_73바이트면_400() throws Exception {
		String password73 = "가".repeat(24) + "a";
		assertThat(password73.getBytes(StandardCharsets.UTF_8)).hasSize(73);

		assertRejected(json(quote(uniqueEmail()), quote(password73), quote("가게"), ALL_AGREEMENTS),
				400, "COMMON_INVALID_INPUT");
	}

	@Test
	void storeName을_빼면_가게_이름은_이메일_앞부분() throws Exception {
		String local = "owner" + System.nanoTime();
		MvcResult result = signup("{\"email\":" + quote(local + "@example.com") + ",\"password\":" + quote(PASSWORD)
				+ ",\"agreements\":" + ALL_AGREEMENTS + "}")
				.andExpect(status().isCreated())
				.andReturn();

		meOf(read(result, "$.data.accessToken"))
				.andExpect(jsonPath("$.data.name").value(local))
				.andExpect(jsonPath("$.data.displayName").value(local));
		AuthUser user = tokenProvider.parseAccessToken(read(result, "$.data.accessToken"));
		assertThat(jdbc.queryForObject("SELECT name FROM account WHERE id = ?", String.class, user.accountId()))
				.isEqualTo(local);
	}

	@Test
	void storeName이_공백뿐이면_가게_이름은_이메일_앞부분() throws Exception {
		String local = "blank" + System.nanoTime();
		MvcResult result = signup(json(quote(local + "@example.com"), quote(PASSWORD), quote("   "), ALL_AGREEMENTS))
				.andExpect(status().isCreated())
				.andReturn();

		meOf(read(result, "$.data.accessToken")).andExpect(jsonPath("$.data.name").value(local));
	}

	@Test
	void storeName이_101자면_400() throws Exception {
		assertRejected(json(quote(uniqueEmail()), quote(PASSWORD), quote("가".repeat(101)), ALL_AGREEMENTS),
				400, "COMMON_INVALID_INPUT");
	}

	// ---- [인가] ----

	@Test
	void signup은_토큰_없이_열리고_다른_보호_경로는_여전히_401() throws Exception {
		signup(json(quote(uniqueEmail()), quote(PASSWORD), quote("가게"), ALL_AGREEMENTS))
				.andExpect(status().isCreated());

		mockMvc.perform(get("/stores/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("COMMON_UNAUTHORIZED"));
	}

	@Test
	void 새로_가입한_가게의_토큰으로_기존_가게_데이터를_못_본다() throws Exception {
		LoggedInStore existing = loginNewStore(jdbc, passwordEncoder, mockMvc, "기존가게");
		long existingIngredient = insertIngredient(jdbc, existing.storeId(), "기존가게재료-" + System.nanoTime());

		String access = read(signup(json(quote(uniqueEmail()), quote(PASSWORD), quote("새가게"), ALL_AGREEMENTS))
				.andExpect(status().isCreated())
				.andReturn(), "$.data.accessToken");
		AuthUser user = tokenProvider.parseAccessToken(access);
		assertThat(user.storeId()).isNotEqualTo(existing.storeId());

		mockMvc.perform(get("/ingredients/" + existingIngredient).header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));
		mockMvc.perform(get("/ingredients").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].id", not(hasItem(String.valueOf(existingIngredient)))));
		meOf(access).andExpect(jsonPath("$.data.id").value(String.valueOf(user.storeId())));
		// 기존 가게는 그대로 자기 재료를 본다
		mockMvc.perform(get("/ingredients/" + existingIngredient).header(HttpHeaders.AUTHORIZATION, existing.bearer()))
				.andExpect(status().isOk());
	}

	// ---- [Swagger · 문서] ----

	@Test
	void api_docs에_signup의_201_400_409가_보인다() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/auth/signup'].post.responses['201']").exists())
				.andExpect(jsonPath("$.paths['/auth/signup'].post.responses['400']").exists())
				.andExpect(jsonPath("$.paths['/auth/signup'].post.responses['409']").exists())
				.andExpect(jsonPath("$.paths['/auth/signup'].post.security").isEmpty());
	}

	// ---- 도움 ----

	/** 거절 응답과 함께 account · store · store_member 행 수가 그대로인지 본다. */
	private void assertRejected(String body, int status, String code) throws Exception {
		List<Long> before = rowCounts();
		signup(body)
				.andExpect(status().is(status))
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value(code));
		assertThat(rowCounts()).as("실패한 요청 뒤 행 수").isEqualTo(before);
	}

	/** 400 COMMON_INVALID_INPUT 이면서 응답 본문(UTF-8)에 보낸 비밀번호가 없는지 본다. */
	private void assertNoPasswordEcho(String body, String password) throws Exception {
		assertRejected(body, 400, "COMMON_INVALID_INPUT");
		String response = signup(body).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		assertThat(response).as("거절 응답 본문").doesNotContain(password);
	}

	private List<Long> rowCounts() {
		return List.of(
				jdbc.queryForObject("SELECT count(*) FROM account", Long.class),
				jdbc.queryForObject("SELECT count(*) FROM store", Long.class),
				jdbc.queryForObject("SELECT count(*) FROM store_member", Long.class));
	}

	private ResultActions signup(String body) throws Exception {
		return mockMvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions login(String email, String password) throws Exception {
		return mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(loginJson(email, password)));
	}

	private ResultActions meOf(String access) throws Exception {
		return mockMvc.perform(get("/stores/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
				.andExpect(status().isOk());
	}

	private static String json(String email, String password, String storeName, String agreements) {
		return "{\"email\":" + email + ",\"password\":" + password + ",\"storeName\":" + storeName
				+ ",\"agreements\":" + agreements + "}";
	}

	private static String quote(String value) {
		return "\"" + value + "\"";
	}
}
