package com.zigzaego.api.auth.jwt;

import static com.zigzaego.api.auth.AuthTestSupport.insertStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.auth.AuthUser;
import com.zigzaego.api.auth.LoginUser;
import com.zigzaego.api.global.response.ApiResponse;
import com.zigzaego.api.global.store.CurrentStoreProvider;
import com.zigzaego.api.global.store.JwtCurrentStoreProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

/** JWT 필터가 토큰을 {@link AuthUser}로 바꿔 컨트롤러 · {@link CurrentStoreProvider}까지 전달하는지. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class JwtAuthenticationFilterTest {

	@Autowired
	private WebApplicationContext webContext;

	@Autowired
	private ApplicationContext context;

	@Autowired
	private JwtTokenProvider tokenProvider;

	@Autowired
	private JwtProperties properties;

	@Autowired
	private JdbcTemplate jdbc;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(webContext).apply(springSecurity()).build();
	}

	@Test
	void 유효한_토큰이면_컨트롤러가_LoginUser로_계정과_가게를_받는다() throws Exception {
		String token = tokenProvider.createAccessToken(new AuthUser(7, 42));

		mockMvc.perform(get("/test/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.accountId").value(7))
				.andExpect(jsonPath("$.data.storeId").value(42));
	}

	@Test
	void 토큰이_없으면_401_COMMON_UNAUTHORIZED() throws Exception {
		mockMvc.perform(get("/test/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("COMMON_UNAUTHORIZED"));
	}

	@Test
	void 만료된_토큰이면_401_AUTH_TOKEN_EXPIRED() throws Exception {
		JwtTokenProvider past = new JwtTokenProvider(properties,
				Clock.fixed(Instant.now().minus(properties.accessTtl()).minusSeconds(60), ZoneOffset.UTC));
		String expired = past.createAccessToken(new AuthUser(7, 42));

		mockMvc.perform(get("/test/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_EXPIRED"));
	}

	@Test
	void 위조된_토큰이면_401_AUTH_INVALID_TOKEN() throws Exception {
		String token = tokenProvider.createAccessToken(new AuthUser(7, 42));
		String tampered = token.substring(0, token.length() - 4) + "AAAA";

		mockMvc.perform(get("/test/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("AUTH_INVALID_TOKEN"));
	}

	@Test
	void 공개_경로는_잘못된_토큰이_붙어도_열린다() throws Exception {
		mockMvc.perform(get("/actuator/health").header(HttpHeaders.AUTHORIZATION, "Bearer garbage"))
				.andExpect(status().isOk());
	}

	@Test
	void CurrentStoreProvider는_JWT_구현_하나뿐이다() {
		Map<String, CurrentStoreProvider> providers = context.getBeansOfType(CurrentStoreProvider.class);

		assertThat(providers.values()).singleElement().isInstanceOf(JwtCurrentStoreProvider.class);
	}

	@Test
	void 기존_API도_토큰의_가게로_스코프된다() throws Exception {
		long myStore = insertStore(jdbc, "내가게-" + System.nanoTime());
		long otherStore = insertStore(jdbc, "남의가게-" + System.nanoTime());
		long myJob = insertJob(myStore);
		long otherJob = insertJob(otherStore);
		String bearer = "Bearer " + tokenProvider.createAccessToken(new AuthUser(1, myStore));

		mockMvc.perform(get("/jobs/{id}", myJob).header(HttpHeaders.AUTHORIZATION, bearer))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.id").value(String.valueOf(myJob)));
		mockMvc.perform(get("/jobs/{id}", otherJob).header(HttpHeaders.AUTHORIZATION, bearer))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));
	}

	private long insertJob(long storeId) {
		return jdbc.queryForObject(
				"INSERT INTO job (store_id, type, status, progress) VALUES (?, 'ONBOARDING', 'RUNNING', 0.5) RETURNING id",
				Long.class, storeId);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class MeControllerConfig {

		@Bean
		MeController meController() {
			return new MeController();
		}
	}

	@RestController
	static class MeController {

		@GetMapping("/test/me")
		ApiResponse<AuthUser> me(@LoginUser AuthUser user) {
			return ApiResponse.ok(user);
		}
	}
}
