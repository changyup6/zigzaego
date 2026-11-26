package com.zigzaego.api.onboarding.job;

import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.exception.CommonErrorCode;
import com.zigzaego.api.onboarding.dto.OnboardingJobRequest;
import com.zigzaego.api.onboarding.exception.OnboardingJobErrorCode;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@code POST /onboarding/jobs} 의 placeId · naverMapUrl 에서 네이버 플레이스 id 를 정한다.
 * 링크는 {@code place/숫자} · {@code restaurant/숫자} · {@code entry/place/숫자} 꼴에서 숫자를 뽑고,
 * {@code naver.me} 짧은 링크는 리다이렉트를 한 번 따라가 같은 규칙을 쓴다. 네이버가 아닌 주소 · 못 뽑으면 job 을 만들지 않고 400.
 */
@Slf4j
@Component
public class PlaceIdResolver {

	private static final Pattern PLACE_PATH = Pattern.compile("(?:^|/)(?:place|restaurant)/(\\d{1,20})(?:[/?#]|$)");
	private static final Pattern DIGITS = Pattern.compile("\\d{1,20}");
	private static final String NAVER_HOST = "naver.com";
	private static final String SHORT_HOST = "naver.me";
	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private final RestClient restClient;

	public PlaceIdResolver() {
		// 리다이렉트를 따라가지 않는다 — Location 헤더 한 번만 읽는다
		HttpClient httpClient = HttpClient.newBuilder()
				.connectTimeout(TIMEOUT)
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(TIMEOUT);
		this.restClient = RestClient.builder().requestFactory(requestFactory).build();
	}

	/**
	 * @throws BusinessException 둘 다 없으면 {@code ONBOARDING_PLACE_REQUIRED}, 링크에서 못 뽑으면 {@code ONBOARDING_PLACE_URL_INVALID},
	 *                           placeId 가 숫자가 아니면 {@code COMMON_INVALID_INPUT}
	 */
	public String resolve(OnboardingJobRequest request) {
		String placeId = request == null ? null : request.placeId();
		if (placeId != null && !placeId.isBlank()) {
			String trimmed = placeId.strip();
			if (!DIGITS.matcher(trimmed).matches()) {
				throw new BusinessException(CommonErrorCode.INVALID_INPUT, "placeId 는 숫자입니다.");
			}
			return trimmed;
		}
		String url = request == null ? null : request.naverMapUrl();
		if (url == null || url.isBlank()) {
			throw new BusinessException(OnboardingJobErrorCode.PLACE_REQUIRED);
		}
		return fromUrl(url.strip());
	}

	/** 링크 하나에서 플레이스 id. 네이버 주소(naver.com · 그 하위 도메인 · naver.me)만 받는다. */
	public String fromUrl(String url) {
		URI uri = parse(url).filter(PlaceIdResolver::isNaverHost).orElseThrow(PlaceIdResolver::invalid);
		Optional<String> direct = extract(uri);
		if (direct.isPresent()) {
			return direct.get();
		}
		if (SHORT_HOST.equalsIgnoreCase(uri.getHost())) {
			return followOnce(uri).flatMap(PlaceIdResolver::extract).orElseThrow(PlaceIdResolver::invalid);
		}
		throw invalid();
	}

	static boolean isNaverHost(URI uri) {
		String host = uri.getHost().toLowerCase(Locale.ROOT);
		return host.equals(NAVER_HOST) || host.endsWith("." + NAVER_HOST) || host.equals(SHORT_HOST);
	}

	private Optional<URI> followOnce(URI uri) {
		try {
			URI location = restClient.get().uri(uri).retrieve().toBodilessEntity().getHeaders().getLocation();
			return Optional.ofNullable(location).map(uri::resolve)
					.filter(target -> target.getHost() != null && isNaverHost(target));
		} catch (RuntimeException e) {
			log.info("naver.me 링크를 따라가지 못했다: {}", e.getMessage());
			return Optional.empty();
		}
	}

	static Optional<String> extract(URI uri) {
		String path = uri.getRawPath();
		if (path == null) {
			return Optional.empty();
		}
		Matcher matcher = PLACE_PATH.matcher(path);
		return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
	}

	private static Optional<URI> parse(String url) {
		try {
			// 검색어가 든 지도 링크(.../search/위글리 성수/place/...)는 공백이 그대로 붙어 온다 — URI 가 받도록 바꿔 둔다
			URI uri = URI.create(url.replace(" ", "%20"));
			if (uri.getScheme() == null || uri.getHost() == null
					|| !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
				return Optional.empty();
			}
			return Optional.of(uri);
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	private static BusinessException invalid() {
		return new BusinessException(OnboardingJobErrorCode.PLACE_URL_INVALID);
	}
}
