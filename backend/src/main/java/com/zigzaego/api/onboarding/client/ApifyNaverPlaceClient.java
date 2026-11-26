package com.zigzaego.api.onboarding.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.onboarding.dto.CollectedMenu;
import com.zigzaego.api.onboarding.dto.PlaceCandidateResponse;
import com.zigzaego.api.onboarding.exception.OnboardingErrorCode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Apify {@code zen-studio/naver-place-scraper} 를 run-sync-get-dataset-items 로 부른다 (설계 2.1 · 2.2, S15P31A308-94).
 *
 * <ul>
 *   <li>가게 검색: 사용자가 화면에서 기다리므로 재시도하지 않는다. 실패하면 바로 {@code ONBOARDING_APIFY_UNAVAILABLE}</li>
 *   <li>메뉴 수집: 온보딩 job 안에서 돈다. 타임아웃 · 5xx 는 {@code retryDelays}(30초 · 2분) 간격으로 다시 부른다</li>
 *   <li>결과가 0곳이면 액터가 FAILED 로 끝나 HTTP 400 {@code run-failed} 가 온다 → 오류가 아니라 빈 목록</li>
 * </ul>
 */
@Slf4j
public class ApifyNaverPlaceClient {

	static final int MAX_CANDIDATES = 5;
	static final String PLACE_URL = "https://pcmap.place.naver.com/place/%s/home";
	private static final String RUN_PATH = "/v2/acts/{actorId}/run-sync-get-dataset-items";
	private static final String RUN_FAILED = "run-failed";

	/** 재시도 사이 대기. 테스트에서 실제로 기다리지 않게 바꿔 끼운다. */
	@FunctionalInterface
	public interface Sleeper {
		void sleep(Duration duration) throws InterruptedException;
	}

	private final RestClient restClient;
	private final ApifyProperties properties;
	private final Sleeper sleeper;

	public ApifyNaverPlaceClient(RestClient.Builder builder, ApifyProperties properties, Sleeper sleeper) {
		this.restClient = builder.baseUrl(properties.baseUrl()).build();
		this.properties = properties;
		this.sleeper = sleeper;
	}

	/** 상호명으로 네이버 플레이스 후보를 최대 5곳 찾는다. 상세(메뉴)는 받지 않는다 — 느리고 과금된다. */
	public List<PlaceCandidateResponse> searchPlaces(String query) {
		Map<String, Object> input = Map.of(
				"searchQueries", List.of(query),
				"maxPlaces", MAX_CANDIDATES,
				"includeDetail", false);
		try {
			return run(input).stream().limit(MAX_CANDIDATES).map(RawPlace::toCandidate).toList();
		} catch (RetryableApifyException e) {
			throw unavailable(e);
		}
	}

	/**
	 * 플레이스 한 곳의 메뉴를 받은 그대로 돌려준다. 같은 이름 중복(대표 메뉴가 두 번 나옴) 제거는 job 정규화 단계에서 한다.
	 * 재시도를 다 써도 실패하면 {@code ONBOARDING_APIFY_UNAVAILABLE}.
	 */
	public List<CollectedMenu> collectMenus(String placeId) {
		Map<String, Object> input = Map.of(
				"placeUrls", List.of(PLACE_URL.formatted(placeId)),
				"includeDetail", true);
		List<Duration> delays = properties.retryDelays();
		for (int attempt = 0; ; attempt++) {
			try {
				return run(input).stream().flatMap(RawPlace::menuStream).map(RawMenu::toMenu).toList();
			} catch (RetryableApifyException e) {
				if (attempt >= delays.size()) {
					throw unavailable(e);
				}
				log.warn("Apify 메뉴 수집 실패, {} 뒤 다시 시도 ({}/{}): {}", delays.get(attempt), attempt + 1, delays.size(),
						e.getMessage());
				sleep(delays.get(attempt), e);
			}
		}
	}

	private List<RawPlace> run(Map<String, Object> input) {
		if (!properties.hasToken()) {
			throw new BusinessException(OnboardingErrorCode.APIFY_NOT_CONFIGURED);
		}
		try {
			List<RawPlace> places = restClient.post()
					.uri(uri -> uri.path(RUN_PATH)
							.queryParam("timeout", properties.runTimeout().toSeconds())
							.build(properties.actorId()))
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.token())
					.contentType(MediaType.APPLICATION_JSON)
					.body(input)
					.retrieve()
					.body(new ParameterizedTypeReference<List<RawPlace>>() {
					});
			return places == null ? List.of() : places;
		} catch (HttpClientErrorException e) {
			if (e.getStatusCode().isSameCodeAs(HttpStatus.BAD_REQUEST) && e.getResponseBodyAsString().contains(RUN_FAILED)) {
				return List.of();
			}
			if (e.getStatusCode().isSameCodeAs(HttpStatus.REQUEST_TIMEOUT)
					|| e.getStatusCode().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)) {
				throw new RetryableApifyException(e);
			}
			log.error("Apify 호출 거절 {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
			throw new BusinessException(OnboardingErrorCode.APIFY_UNAVAILABLE);
		} catch (HttpServerErrorException | ResourceAccessException e) {
			throw new RetryableApifyException(e);
		}
	}

	private void sleep(Duration delay, RetryableApifyException cause) {
		try {
			sleeper.sleep(delay);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw unavailable(cause);
		}
	}

	private static BusinessException unavailable(RetryableApifyException e) {
		log.error("Apify 호출 실패: {}", e.getMessage());
		return new BusinessException(OnboardingErrorCode.APIFY_UNAVAILABLE);
	}

	/** 타임아웃 · 5xx · 408 · 429 — 다시 부르면 될 수도 있는 실패. */
	static final class RetryableApifyException extends RuntimeException {
		RetryableApifyException(Exception cause) {
			super(cause.getMessage(), cause);
		}
	}

	/** 데이터셋 한 줄. 필요한 칸만 읽는다 — 리뷰 · 전화 · 블로그 같은 나머지는 버린다. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	record RawPlace(String id, String name, String fullAddress, String roadAddress, String address, String imageUrl,
			List<RawMenu> menus) {

		PlaceCandidateResponse toCandidate() {
			return new PlaceCandidateResponse(id, name, firstText(fullAddress, roadAddress, address), blankToNull(imageUrl), false);
		}

		Stream<RawMenu> menuStream() {
			return menus == null ? Stream.empty() : menus.stream();
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record RawMenu(String name, String price, String description, List<String> images) {

		CollectedMenu toMenu() {
			return new CollectedMenu(name, parsePrice(price), blankToNull(description), images);
		}
	}

	/** "8,900" · "8900원" → 8900. 숫자가 없거나("변동") 9자리를 넘으면 null. */
	static Integer parsePrice(String price) {
		if (price == null) {
			return null;
		}
		String digits = price.replaceAll("[^0-9]", "");
		if (digits.isEmpty() || digits.length() > 9) {
			return null;
		}
		return Integer.valueOf(digits);
	}

	private static String firstText(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return null;
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}
}
