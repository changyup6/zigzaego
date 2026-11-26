package com.zigzaego.api.push;

import static com.zigzaego.api.support.StoreFixtures.loginNewStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.push.sender.WebPushCrypto;
import com.zigzaego.api.support.StoreFixtures.LoggedInStore;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** POST · DELETE /push-subscriptions 와 PUT /stores/me/closing-times (S15P31A308-248). 가게 2개로 남의 것이 안 바뀌는지 본다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PushSubscriptionAndClosingTimeTest {

	private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PasswordEncoder passwordEncoder;

	private MockMvc mockMvc;
	private LoggedInStore mine;
	private LoggedInStore other;
	private String endpoint;

	@BeforeEach
	void setUp() throws Exception {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		mine = loginNewStore(jdbc, passwordEncoder, mockMvc, "푸시가게");
		other = loginNewStore(jdbc, passwordEncoder, mockMvc, "남의가게");
		endpoint = "https://fcm.googleapis.com/fcm/send/" + System.nanoTime();
	}

	private static String p256dh() {
		return B64.encodeToString(WebPushCrypto.encodePoint((ECPublicKey) WebPushCrypto.generateKeyPair().getPublic()));
	}

	private static String subscription(String endpoint, String p256dh, String auth) {
		return "{\"endpoint\": \"" + endpoint + "\", \"keys\": {\"p256dh\": \"" + p256dh + "\", \"auth\": \"" + auth + "\"}}";
	}

	@Test
	void 구독하고_같은_endpoint_로_다시_구독하면_갱신되고_해지하면_지워진다() throws Exception {
		String first = p256dh();
		call(mine, post("/push-subscriptions"), subscription(endpoint, first, "AAAAAAAAAAAAAAAAAAAAAA"))
				.andExpect(status().isOk());
		String second = p256dh();
		call(mine, post("/push-subscriptions"), subscription(endpoint, second, "BBBBBBBBBBBBBBBBBBBBBA"))
				.andExpect(status().isOk());

		Map<String, Object> row = jdbc.queryForMap("SELECT store_id, p256dh, auth FROM push_subscription WHERE endpoint = ?",
				endpoint);
		assertThat(((Number) row.get("store_id")).longValue()).isEqualTo(mine.storeId());
		assertThat(row.get("p256dh")).isEqualTo(second);
		assertThat(row.get("auth")).isEqualTo("BBBBBBBBBBBBBBBBBBBBBA");

		// 남의 가게는 내 구독을 지우지 못한다
		call(other, delete("/push-subscriptions"), "{\"endpoint\": \"" + endpoint + "\"}").andExpect(status().isOk());
		assertThat(count(endpoint)).isEqualTo(1);

		call(mine, delete("/push-subscriptions"), "{\"endpoint\": \"" + endpoint + "\"}").andExpect(status().isOk());
		assertThat(count(endpoint)).isZero();
		call(mine, delete("/push-subscriptions"), "{\"endpoint\": \"" + endpoint + "\"}").andExpect(status().isOk());
	}

	@Test
	void 푸시_서비스가_아닌_주소와_틀린_키는_400() throws Exception {
		String key = p256dh();
		for (String bad : new String[] {"http://fcm.googleapis.com/x", "https://169.254.169.254/latest",
				"https://evil.example.com/fcm.googleapis.com", "https://fcm.googleapis.com.evil.com/x", "not a url"}) {
			call(mine, post("/push-subscriptions"), subscription(bad, key, "AAAAAAAAAAAAAAAAAAAAAA"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
		}
		call(mine, post("/push-subscriptions"), subscription(endpoint, "AAAA", "AAAAAAAAAAAAAAAAAAAAAA"))
				.andExpect(status().isBadRequest());
		call(mine, post("/push-subscriptions"), subscription(endpoint, key, "AAAA")).andExpect(status().isBadRequest());
		assertThat(count(endpoint)).isZero();
	}

	@Test
	void 요일별_마감_시각을_통째로_바꾸고_내_가게에서_읽는다() throws Exception {
		call(mine, put("/stores/me/closing-times"), """
				{"closingTimes": [{"dayOfWeek": 1, "closeTime": "22:00"}, {"dayOfWeek": 5, "closeTime": "01:30"}]}
				""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.closingTimes", hasSize(2)))
				.andExpect(jsonPath("$.data.closingTimes[0].dayOfWeek").value(1))
				.andExpect(jsonPath("$.data.closingTimes[0].closeTime").value("22:00"))
				.andExpect(jsonPath("$.data.closingTimes[1].closeTime").value("01:30"));

		call(mine, put("/stores/me/closing-times"), """
				{"closingTimes": [{"dayOfWeek": 7, "closeTime": "21:00"}]}
				""").andExpect(status().isOk());
		mockMvc.perform(get("/stores/me").header(HttpHeaders.AUTHORIZATION, mine.bearer()))
				.andExpect(jsonPath("$.data.closingTimes", hasSize(1)))
				.andExpect(jsonPath("$.data.closingTimes[0].dayOfWeek").value(7));
		mockMvc.perform(get("/stores/me").header(HttpHeaders.AUTHORIZATION, other.bearer()))
				.andExpect(jsonPath("$.data.closingTimes", hasSize(0)));
	}

	@Test
	void 같은_요일_두_번_요일_범위_밖_시각_형식_오류는_400() throws Exception {
		for (String body : new String[] {
				"{\"closingTimes\": [{\"dayOfWeek\": 1, \"closeTime\": \"22:00\"}, {\"dayOfWeek\": 1, \"closeTime\": \"23:00\"}]}",
				"{\"closingTimes\": [{\"dayOfWeek\": 0, \"closeTime\": \"22:00\"}]}",
				"{\"closingTimes\": [{\"dayOfWeek\": 8, \"closeTime\": \"22:00\"}]}",
				"{\"closingTimes\": [{\"dayOfWeek\": 1, \"closeTime\": \"24:00\"}]}",
				"{\"closingTimes\": [{\"dayOfWeek\": 1, \"closeTime\": \"9:00\"}]}"}) {
			call(mine, put("/stores/me/closing-times"), body)
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM store_closing_time WHERE store_id = ?", Integer.class,
				mine.storeId())).isZero();
	}

	@Test
	void 인증_없으면_401() throws Exception {
		mockMvc.perform(post("/push-subscriptions").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isUnauthorized());
	}

	private int count(String endpoint) {
		return jdbc.queryForObject("SELECT count(*) FROM push_subscription WHERE endpoint = ?", Integer.class, endpoint);
	}

	private ResultActions call(LoggedInStore store, MockHttpServletRequestBuilder request, String body) throws Exception {
		return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, store.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}
}
