package com.zigzaego.api.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.onboarding.client.ApifyNaverPlaceClient;
import com.zigzaego.api.onboarding.client.ApifyProperties;
import com.zigzaego.api.onboarding.dto.CollectedMenu;
import com.zigzaego.api.onboarding.dto.PlaceCandidateResponse;
import com.zigzaego.api.onboarding.exception.OnboardingErrorCode;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 픽스처는 2026-10-16 실제 응답('위글리 파스타볼' 검색 · 위글리 메뉴 수집)에서 읽는 칸만 남긴 것.
 */
class ApifyNaverPlaceClientTest {

	private static final String RUN_URL =
			"https://api.apify.test/v2/acts/zen-studio~naver-place-scraper/run-sync-get-dataset-items";
	private static final List<Duration> DELAYS = List.of(Duration.ofSeconds(30), Duration.ofMinutes(2));

	private final List<Duration> slept = new ArrayList<>();

	private MockRestServiceServer server;

	private ApifyNaverPlaceClient client(String token) {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		ApifyProperties properties = new ApifyProperties(token, "https://api.apify.test",
				"zen-studio~naver-place-scraper", Duration.ofSeconds(240), DELAYS);
		return new ApifyNaverPlaceClient(builder, properties, slept::add);
	}

	private ApifyNaverPlaceClient client() {
		return client("test-token");
	}

	private static ClassPathResource fixture(String name) {
		return new ClassPathResource("apify/" + name);
	}

	@Test
	void 검색은_후보를_FE_모양으로_바꾸고_상세는_요청하지_않는다() {
		ApifyNaverPlaceClient client = client();
		server.expect(once(), requestTo(startsWith(RUN_URL + "?timeout=240")))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header("Authorization", "Bearer test-token"))
				.andExpect(jsonPath("$.searchQueries", contains("위글리 파스타볼")))
				.andExpect(jsonPath("$.maxPlaces", is(5)))
				.andExpect(jsonPath("$.includeDetail", is(false)))
				.andRespond(withSuccess(fixture("search-wiggly.json"), MediaType.APPLICATION_JSON));

		List<PlaceCandidateResponse> candidates = client.searchPlaces("위글리 파스타볼");

		server.verify();
		assertThat(candidates).hasSize(1);
		PlaceCandidateResponse wiggly = candidates.get(0);
		assertThat(wiggly.id()).isEqualTo("1668058454");
		assertThat(wiggly.name()).isEqualTo("위글리 파스타볼");
		assertThat(wiggly.address()).contains("테헤란로10길 25");
		assertThat(wiggly.photoUrl()).startsWith("https://");
		assertThat(wiggly.yogiyoMatch()).isFalse();
	}

	@Test
	void 결과가_0곳이라_액터가_실패하면_빈_목록() {
		ApifyNaverPlaceClient client = client();
		server.expect(once(), requestTo(startsWith(RUN_URL)))
				.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
						.body("{\"error\":{\"type\":\"run-failed\",\"message\":\"Actor run did not succeed\"}}"));

		assertThat(client.searchPlaces("없는가게")).isEmpty();
		server.verify();
	}

	@Test
	void 검색은_5xx에서_재시도하지_않고_바로_실패() {
		ApifyNaverPlaceClient client = client();
		server.expect(once(), requestTo(startsWith(RUN_URL))).andRespond(withServerError());

		assertThatThrownBy(() -> client.searchPlaces("위글리"))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(OnboardingErrorCode.APIFY_UNAVAILABLE));
		server.verify();
		assertThat(slept).isEmpty();
	}

	@Test
	void 메뉴_수집은_받은_그대로_DTO로_바꾼다() {
		ApifyNaverPlaceClient client = client();
		server.expect(once(), requestTo(startsWith(RUN_URL)))
				.andExpect(jsonPath("$.placeUrls", contains("https://pcmap.place.naver.com/place/1668058454/home")))
				.andExpect(jsonPath("$.includeDetail", is(true)))
				.andRespond(withSuccess(fixture("menus-wiggly.json"), MediaType.APPLICATION_JSON));

		List<CollectedMenu> menus = client.collectMenus("1668058454");

		server.verify();
		assertThat(menus).hasSize(32); // 대표 메뉴 중복 포함 — 중복 제거는 job 정규화 단계
		CollectedMenu first = menus.get(0);
		assertThat(first.name()).isEqualTo("치킨 알리오 파스타볼");
		assertThat(first.price()).isEqualTo(8900);
		assertThat(first.description()).startsWith("로스트 치킨");
		assertThat(first.imageUrls()).hasSize(1);
		assertThat(menus).filteredOn(m -> m.description() == null).hasSize(6); // 음료
		assertThat(menus).allSatisfy(m -> assertThat(m.price()).isNotNull());
	}

	@Test
	void 메뉴_수집은_5xx를_30초_2분_간격으로_2번_다시_부른_뒤_실패() {
		ApifyNaverPlaceClient client = client();
		server.expect(times(3), requestTo(startsWith(RUN_URL))).andRespond(withServerError());

		assertThatThrownBy(() -> client.collectMenus("1668058454"))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(OnboardingErrorCode.APIFY_UNAVAILABLE));
		server.verify();
		assertThat(slept).containsExactly(Duration.ofSeconds(30), Duration.ofMinutes(2));
	}

	@Test
	void 메뉴_수집은_타임아웃_뒤_다시_불러_성공하면_결과를_돌려준다() {
		ApifyNaverPlaceClient client = client();
		server.expect(once(), requestTo(startsWith(RUN_URL))).andRespond(request -> {
			throw new SocketTimeoutException("read timed out");
		});
		server.expect(once(), requestTo(startsWith(RUN_URL)))
				.andRespond(withSuccess(fixture("menus-wiggly.json"), MediaType.APPLICATION_JSON));

		assertThat(client.collectMenus("1668058454")).hasSize(32);
		server.verify();
		assertThat(slept).containsExactly(Duration.ofSeconds(30));
	}

	@Test
	void 메뉴_수집은_408도_다시_부른다() {
		ApifyNaverPlaceClient client = client();
		server.expect(once(), requestTo(startsWith(RUN_URL))).andRespond(withStatus(HttpStatus.REQUEST_TIMEOUT));
		server.expect(once(), requestTo(startsWith(RUN_URL)))
				.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		assertThat(client.collectMenus("1668058454")).isEmpty();
		server.verify();
		assertThat(slept).hasSize(1);
	}

	@Test
	void 토큰이_없으면_호출하지_않고_설정_오류() {
		ApifyNaverPlaceClient client = client(" ");

		assertThatThrownBy(() -> client.searchPlaces("위글리"))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(OnboardingErrorCode.APIFY_NOT_CONFIGURED));
		server.verify();
	}

	@Test
	void 인증_실패_같은_4xx는_재시도하지_않는다() {
		ApifyNaverPlaceClient client = client();
		server.expect(once(), requestTo(startsWith(RUN_URL))).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

		assertThatThrownBy(() -> client.collectMenus("1668058454"))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(OnboardingErrorCode.APIFY_UNAVAILABLE));
		server.verify();
		assertThat(slept).isEmpty();
	}
}
