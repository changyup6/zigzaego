package com.zigzaego.api.onboarding;

import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.zigzaego.api.job.service.JobContext;
import com.zigzaego.api.job.service.JobFailedException;
import com.zigzaego.api.llm.GmsClient;
import com.zigzaego.api.llm.GmsProperties;
import com.zigzaego.api.onboarding.client.ApifyNaverPlaceClient;
import com.zigzaego.api.onboarding.dto.CollectedMenu;
import com.zigzaego.api.onboarding.job.MenuImageFetcher;
import com.zigzaego.api.onboarding.job.OnboardingAnalyzer;
import com.zigzaego.api.onboarding.job.OnboardingResult;
import com.zigzaego.api.onboarding.replay.OnboardingPlaceSource;
import com.zigzaego.api.onboarding.replay.OnboardingReplayRepository;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 실제 GMS 로 만돈 27개 메뉴를 3번 돌려 429 · 누락 · 메뉴당 재료 수를 잰다 (지라 S15P31A308-237 [429 · 누락 실측]).
 * 환경변수 {@code GMS_KEY} · {@code GMS_BASE_URL} · {@code GMS_MODEL} · {@code ONBOARDING_LIVE_MENUS}(메뉴 JSON 경로)가
 * 모두 있을 때만 돈다. 결과는 {@code ONBOARDING_LIVE_OUT}(없으면 메뉴 JSON 옆 {@code ../237_사진상한}) 의 run_N.json.
 * 재시도 간격을 비워 첫 429 가 곧 실패가 되게 한다. 입력 · 사진은 저장소에 넣지 않는다.
 */
class OnboardingPhotoLiveTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final int RUNS = 3;

	@Test
	void 만돈_27개_메뉴를_실제_GMS_로_3번_돌린다() throws Exception {
		String key = System.getenv("GMS_KEY");
		String baseUrl = System.getenv("GMS_BASE_URL");
		String model = System.getenv("GMS_MODEL");
		String menusPath = System.getenv("ONBOARDING_LIVE_MENUS");
		assumeThat(key).isNotBlank();
		assumeThat(baseUrl).isNotBlank();
		assumeThat(model).isNotBlank();
		assumeThat(menusPath).isNotBlank();

		List<CollectedMenu> menus = readMenus(Path.of(menusPath));
		Set<String> inputNames = new LinkedHashSet<>();
		menus.forEach(m -> inputNames.add(m.name().strip()));
		String outEnv = System.getenv("ONBOARDING_LIVE_OUT");
		Path outDir = outEnv != null && !outEnv.isBlank() ? Path.of(outEnv)
				: Path.of(menusPath).toAbsolutePath().getParent().getParent().resolve("237_사진상한");
		Files.createDirectories(outDir);

		for (int run = 1; run <= RUNS; run++) {
			AtomicInteger http429 = new AtomicInteger();
			HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
			JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
			factory.setReadTimeout(Duration.ofSeconds(120));
			RestClient.Builder builder = RestClient.builder().requestFactory(factory).requestInterceptor((req, body, exec) -> {
				var response = exec.execute(req, body);
				if (response.getStatusCode().value() == 429) {
					http429.incrementAndGet();
				}
				return response;
			});
			GmsProperties properties = new GmsProperties(key, baseUrl, model, Duration.ofSeconds(120), List.of());
			GmsClient gms = new GmsClient(builder, properties, JSON, Thread::sleep);
			ApifyNaverPlaceClient placeClient = mock(ApifyNaverPlaceClient.class);
			given(placeClient.collectMenus("live")).willReturn(menus);
			OnboardingAnalyzer analyzer = new OnboardingAnalyzer(new OnboardingPlaceSource(placeClient, mock(OnboardingReplayRepository.class)), gms, new MenuImageFetcher(), JSON);

			ObjectNode out = JSON.createObjectNode();
			out.put("run", run);
			try {
				OnboardingResult result = analyzer.analyzePlace(ctx(), "live");
				Set<String> returned = new LinkedHashSet<>();
				for (JsonNode m : result.extraction().path("menus")) {
					returned.add(m.path("menu").asString().strip());
				}
				List<String> missing = new ArrayList<>();
				inputNames.stream().filter(n -> !returned.contains(n)).forEach(missing::add);
				double avg = result.menus().stream().mapToInt(m -> m.ingredientCount() == null ? 0 : m.ingredientCount())
						.average().orElse(0);
				out.put("menus", returned.size());
				out.put("missing", missing.size());
				out.put("http429", http429.get());
				out.put("avgIngredients", Math.round(avg * 100) / 100.0);
				out.put("failed", false);
				missing.forEach(n -> out.withArray("missingNames").add(n));
			} catch (JobFailedException e) {
				out.put("menus", 0);
				out.put("missing", inputNames.size());
				out.put("http429", http429.get());
				out.put("failed", true);
				out.put("error", e.getMessage());
			}
			Files.writeString(outDir.resolve("run_" + run + ".json"), JSON.writerWithDefaultPrettyPrinter()
					.writeValueAsString(out));
		}
	}

	private static List<CollectedMenu> readMenus(Path path) throws IOException {
		List<CollectedMenu> menus = new ArrayList<>();
		for (JsonNode place : JSON.readTree(Files.readString(path))) {
			for (JsonNode menu : place.path("menus")) {
				List<String> images = new ArrayList<>();
				menu.path("images").forEach(image -> images.add(image.asString()));
				String digits = menu.path("price").asString("").replaceAll("[^0-9]", "");
				String description = menu.path("description").isString() ? menu.path("description").asString() : null;
				menus.add(new CollectedMenu(menu.path("name").asString(), digits.isEmpty() ? null : Integer.valueOf(digits),
						description, images));
			}
		}
		return menus;
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
}
