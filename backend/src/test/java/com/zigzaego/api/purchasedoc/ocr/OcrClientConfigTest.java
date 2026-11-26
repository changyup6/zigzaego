package com.zigzaego.api.purchasedoc.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 설정값으로 실제 CLOVA · 가짜를 고른다 (지라 S15P31A308-111, 사용자 10/19 "가짜 응답으로 시작"). */
class OcrClientConfigTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static ClovaOcrProperties properties(String url, String secret) {
		return new ClovaOcrProperties(url, secret, Duration.ofSeconds(5), List.of(), Duration.ZERO);
	}

	@Test
	void url_시크릿_둘_다_있으면_ClovaOcrClient() {
		assertThat(OcrClientConfig.create(properties("https://clova.example/general", "secret"), JSON))
				.isInstanceOf(ClovaOcrClient.class);
	}

	@Test
	void 하나라도_비면_FakeOcrClient() {
		assertThat(OcrClientConfig.create(properties("", "secret"), JSON)).isInstanceOf(FakeOcrClient.class);
		assertThat(OcrClientConfig.create(properties("https://clova.example/general", null), JSON))
				.isInstanceOf(FakeOcrClient.class);
		assertThat(OcrClientConfig.create(properties(null, null), JSON)).isInstanceOf(FakeOcrClient.class);
	}

	@Test
	void 가짜_결과는_raw_에_fake_true_글자에_대파() {
		OcrResult result = new FakeOcrClient(JSON).recognize(new OcrImage("a.jpg", "image/jpeg", new byte[] {1}));

		assertThat(result.raw().get("fake").asBoolean()).isTrue();
		assertThat(result.text()).contains("대파").contains("키친타올");
	}
}
