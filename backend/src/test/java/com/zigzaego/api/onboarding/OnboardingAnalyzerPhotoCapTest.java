package com.zigzaego.api.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.zigzaego.api.job.service.JobContext;
import com.zigzaego.api.llm.GmsClient;
import com.zigzaego.api.llm.GmsProperties;
import com.zigzaego.api.llm.LlmImage;
import com.zigzaego.api.onboarding.client.ApifyNaverPlaceClient;
import com.zigzaego.api.onboarding.dto.CollectedMenu;
import com.zigzaego.api.onboarding.job.MenuImageFetcher;
import com.zigzaego.api.onboarding.job.OnboardingAnalyzer;
import com.zigzaego.api.onboarding.job.OnboardingResult;
import com.zigzaego.api.onboarding.replay.OnboardingPlaceSource;
import com.zigzaego.api.onboarding.replay.OnboardingReplayRepository;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 묶음당 사진 원본 합 상한 (지라 S15P31A308-237). 실제 GmsClient 에 MockRestServiceServer 를 물려
 * 묶음마다 GMS 로 간 요청 본문 길이 · 사진 수 · 입력 JSON 의 photo 번호를 잰다.
 */
class OnboardingAnalyzerPhotoCapTest {

	private static final int MIB = 1024 * 1024;
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** GMS 로 간 구성 추출 요청 한 번. */
	private record Sent(long bodyLength, List<Integer> imageSizes, JsonNode menus) {
		long imageSum() {
			return imageSizes.stream().mapToLong(Integer::longValue).sum();
		}
	}

	private final List<Sent> sent = new ArrayList<>();
	private final Map<String, Integer> photoSizes = new ConcurrentHashMap<>();
	private final MenuImageFetcher imageFetcher = mock(MenuImageFetcher.class);
	private final ApifyNaverPlaceClient placeClient = mock(ApifyNaverPlaceClient.class);
	private OnboardingAnalyzer analyzer;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(ExpectedCount.manyTimes(), anything()).andRespond(this::respond);
		GmsProperties properties = new GmsProperties("k", "http://gms.test/v1", "m", Duration.ofSeconds(5), List.of());
		GmsClient gms = new GmsClient(builder, properties, JSON, d -> {
		});
		analyzer = new OnboardingAnalyzer(new OnboardingPlaceSource(placeClient, mock(OnboardingReplayRepository.class)), gms, imageFetcher, JSON);
		given(imageFetcher.fetch(anyString())).willAnswer(invocation -> {
			Integer size = photoSizes.get(invocation.<String>getArgument(0));
			return size == null ? Optional.empty() : Optional.of(new LlmImage("image/jpeg", new byte[size]));
		});
	}

	private ClientHttpResponse respond(ClientHttpRequest req) throws IOException {
		String body = ((MockClientHttpRequest) req).getBodyAsString();
		JsonNode root = JSON.readTree(body);
		JsonNode content = root.path("messages").path(1).path("content");
		String user = content.isString() ? content.asString() : content.path(0).path("text").asString();
		JsonNode input = JSON.readTree(user.substring(user.indexOf('['), user.lastIndexOf(']') + 1));
		ObjectNode out = JSON.createObjectNode();
		if ("menu_extraction".equals(root.path("response_format").path("json_schema").path("name").asString())) {
			List<Integer> sizes = new ArrayList<>();
			if (content.isArray()) {
				for (int i = 1; i < content.size(); i++) {
					String url = content.path(i).path("image_url").path("url").asString();
					sizes.add(Base64.getDecoder().decode(url.substring(url.indexOf(',') + 1)).length);
				}
			}
			sent.add(new Sent(body.length(), sizes, input));
			ArrayNode menus = out.putArray("menus");
			for (JsonNode menu : input) {
				ObjectNode row = menus.addObject();
				row.put("menu", menu.path("name").asString());
				row.put("kind", "MAIN");
				row.putNull("duplicateOf");
				row.putArray("components").addObject().put("name", "파스타면").put("role", "BASE").put("mult", 1);
			}
		} else {
			ArrayNode items = out.putArray("items");
			for (JsonNode ingredient : input) {
				items.addObject().put("name", ingredient.path("name").asString()).put("kind", "BOM").put("unit", "G")
						.put("storageType", "CHILLED").put("perServing", 30).put("confidence", 0.5);
			}
		}
		String reply = JSON.writeValueAsString(JSON.createObjectNode().set("choices", JSON.createArrayNode()
				.add(JSON.createObjectNode().set("message",
						JSON.createObjectNode().put("content", JSON.writeValueAsString(out))))));
		return withSuccess(reply, MediaType.APPLICATION_JSON).createResponse(req);
	}

	private static JobContext ctx() {
		return new JobContext() {
			public long jobId() {
				return 1;
			}

			public void progress(double progress) {
			}

			public void partialResult(Object partial) {
			}
		};
	}

	/** 메뉴 n 개. withPhoto 가 true 인 번호만 사진 URL(장당 bytes)이 있고, 짝수 번호는 설명이 있다. */
	private List<CollectedMenu> menus(int n, IntPredicate withPhoto, int bytes) {
		List<CollectedMenu> menus = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			List<String> urls = List.of();
			if (withPhoto.test(i)) {
				String url = "https://img.test/" + i + ".jpg";
				photoSizes.put(url, bytes);
				urls = List.of(url);
			}
			menus.add(new CollectedMenu("메뉴" + i, 5000 + i, i % 2 == 0 ? "설명 " + i : null, urls));
		}
		return menus;
	}

	private OnboardingResult run(List<CollectedMenu> menus) {
		given(placeClient.collectMenus("p")).willReturn(menus);
		return analyzer.analyzePlace(ctx(), "p");
	}

	private static List<Integer> photoNumbers(JsonNode menus) {
		List<Integer> numbers = new ArrayList<>();
		for (JsonNode menu : menus) {
			numbers.add(menu.has("photo") ? menu.path("photo").asInt() : null);
		}
		return numbers;
	}

	@Test
	void 사진_있는_메뉴_12개_첫_묶음은_6장_둘째_묶음은_2장_본문_16MiB_미만_합_10MiB_이하() {
		OnboardingResult result = run(menus(12, i -> true, 3 * MIB / 2));

		assertThat(sent).hasSize(2);
		assertThat(sent.get(0).imageSizes()).hasSize(6);
		assertThat(sent.get(0).imageSum()).isEqualTo(9L * MIB);
		assertThat(sent.get(1).imageSizes()).hasSize(2);
		for (Sent s : sent) {
			assertThat(s.bodyLength()).isLessThan(16L * MIB);
			assertThat(s.imageSum()).isLessThanOrEqualTo(10L * MIB);
		}
		// 설명 있는 메뉴(짝수)도 사진을 받고, 상한 뒤 메뉴(6~9)는 photo 없이 글자만 간다
		assertThat(photoNumbers(sent.get(0).menus())).containsExactly(1, 2, 3, 4, 5, 6, null, null, null, null);
		assertThat(photoNumbers(sent.get(1).menus())).containsExactly(1, 2);
		// 상한 때문에 사진을 뺀 메뉴도 결과에는 남는다
		assertThat(result.draft().menus()).hasSize(12);
	}

	@Test
	void 사진_받기_실패가_섞이면_photo_번호는_images_순번과_같다() {
		List<CollectedMenu> menus = menus(5, i -> true, MIB);
		photoSizes.remove("https://img.test/1.jpg"); // 1번 메뉴는 URL 은 있지만 받기 실패(빈 값)

		run(menus);

		assertThat(sent).hasSize(1);
		assertThat(sent.get(0).imageSizes()).hasSize(4);
		assertThat(photoNumbers(sent.get(0).menus())).containsExactly(1, null, 2, 3, 4);
	}

	@Test
	void 사진_URL_이_하나도_없는_묶음은_images_빈_목록() {
		run(menus(3, i -> false, MIB));

		assertThat(sent).hasSize(1);
		assertThat(sent.get(0).imageSizes()).isEmpty();
		assertThat(photoNumbers(sent.get(0).menus())).containsExactly(null, null, null);
	}

	@Test
	void 사진_한_장이_혼자_10MiB_를_넘으면_그_사진만_빠지고_job_은_끝까지_간다() {
		OnboardingResult result = run(menus(1, i -> true, 11 * MIB));

		assertThat(sent).hasSize(1);
		assertThat(sent.get(0).imageSizes()).isEmpty();
		assertThat(photoNumbers(sent.get(0).menus())).containsExactly((Integer) null);
		assertThat(result.draft().menus()).hasSize(1);
	}

	@Test
	void 혼자_10MiB_초과_사진_뒤에_작은_사진이_있으면_작은_사진은_들어간다() {
		List<CollectedMenu> menus = menus(3, i -> true, MIB);
		photoSizes.put("https://img.test/0.jpg", 11 * MIB);
		run(menus);

		assertThat(sent).hasSize(1);
		assertThat(sent.get(0).imageSizes()).containsExactly(MIB, MIB);
		assertThat(photoNumbers(sent.get(0).menus())).containsExactly(null, 1, 2);
	}

	@Test
	void 다음_묶음은_합을_0부터_다시_센다() {
		run(menus(20, i -> true, 3 * MIB));

		assertThat(sent).hasSize(2);
		assertThat(sent.get(0).imageSizes()).hasSize(3);
		assertThat(sent.get(1).imageSizes()).hasSize(3);
	}
}
