package com.zigzaego.api.onboarding.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zigzaego.api.global.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 네이버 지도 링크 → 플레이스 id (지라 S15P31A308-95). 네트워크를 타는 naver.me 는 여기서 보지 않는다. */
class PlaceIdResolverTest {

	private final PlaceIdResolver resolver = new PlaceIdResolver();

	@Test
	void mapUrl_네이버가_아닌_주소는_place_꼴이어도_400_ONBOARDING_PLACE_URL_INVALID() {
		for (String url : new String[] {"https://example.com/place/123", "https://naver.com.evil.io/place/123",
				"https://evilnaver.com/place/123"}) {
			assertThatThrownBy(() -> resolver.fromUrl(url))
					.as(url)
					.isInstanceOfSatisfying(BusinessException.class, e -> {
						assertThat(e.getErrorCode().getCode()).isEqualTo("ONBOARDING_PLACE_URL_INVALID");
						assertThat(e.getErrorCode().getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
					});
		}
	}

	@Test
	void mapUrl_공백이_든_네이버_링크도_뽑는다() {
		assertThat(resolver.fromUrl("https://map.naver.com/p/search/위글리 성수/place/1668058454"))
				.isEqualTo("1668058454");
	}

	@Test
	void mapUrl_네이버_도메인과_하위_도메인은_받는다() {
		assertThat(resolver.fromUrl("https://map.naver.com/p/entry/place/1668058454")).isEqualTo("1668058454");
		assertThat(resolver.fromUrl("https://m.place.naver.com/restaurant/1668058454/home")).isEqualTo("1668058454");
		assertThat(resolver.fromUrl("https://naver.com/place/1668058454")).isEqualTo("1668058454");
	}
}
