package com.zigzaego.api.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.llm.LlmErrorCode;
import com.zigzaego.api.llm.LlmException;
import com.zigzaego.api.llm.LlmImage;
import com.zigzaego.api.llm.LlmRequest;
import com.zigzaego.api.onboarding.dto.CollectedMenu;
import com.zigzaego.api.onboarding.dto.OnboardingJobRequest;
import com.zigzaego.api.onboarding.exception.OnboardingErrorCode;
import com.zigzaego.api.onboarding.service.OnboardingJobService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** 온보딩 분석 job (지라 S15P31A308-95). 수집기 · GMS 는 목, DB · job 실행기는 실제. */
class OnboardingJobTest extends OnboardingTestSupport {

	@Autowired
	private OnboardingJobService onboardingJobService;

	private long storeId;

	@BeforeEach
	void setUp() {
		storeId = insertStore("온보딩-" + System.nanoTime());
		given(imageFetcher.fetch(anyString())).willReturn(Optional.empty());
	}

	private long start() {
		return Long.parseLong(onboardingJobService.startFromPlace(storeId,
				new OnboardingJobRequest("1668058454", null, false)).id());
	}

	@Test
	void 수집_사진을_분석_초안에_남기고_중복_메뉴의_사진도_합친다() {
		String photo = "https://images.example.com/dumpling.jpg";
		given(placeClient.collectMenus("1668058454")).willReturn(List.of(
				new CollectedMenu("고기만두", 6000, "돼지고기와 채소를 넣은 만두", List.of()),
				new CollectedMenu("고기만두", 6000, null, List.of(photo))));
		given(gmsClient.completeJson(any(LlmRequest.class)))
				.willAnswer(invocation -> mechanicalLlm(invocation.getArgument(0)));
		JsonNode result = resultOf(awaitFinished(start()));
		assertThat(result.get("draft").get("menus").get(0).get("photoUrl").asString()).isEqualTo(photo);
	}

	@Test
	void 위글리_실제_응답_32개로_DONE_메뉴_30개_재료_질문_채점용_원본() throws Exception {
		given(placeClient.collectMenus("1668058454")).willReturn(wigglyMenus());
		CountDownLatch release = new CountDownLatch(1);
		AtomicBoolean firstCall = new AtomicBoolean(true);
		given(gmsClient.completeJson(any(LlmRequest.class))).willAnswer(invocation -> {
			if (firstCall.getAndSet(false)) {
				release.await(10, TimeUnit.SECONDS); // 진행 중 중간 결과를 볼 수 있게 첫 LLM 호출에서 붙잡는다
			}
			return mechanicalLlm(invocation.getArgument(0));
		});

		long jobId = start();

		// 진행 중 (≥ 0.30): 메뉴 이름 30개가 이미 result 에 있다
		Map<String, Object> running = awaitJob(jobId,
				row -> ((BigDecimal) row.get("progress")).compareTo(new BigDecimal("0.30")) >= 0);
		assertThat(running.get("status")).isEqualTo("RUNNING");
		JsonNode partial = resultOf(running);
		assertThat(partial.get("menus")).hasSize(30);
		List<String> partialNames = new ArrayList<>();
		partial.get("menus").forEach(m -> partialNames.add(m.get("name").asString()));
		assertThat(partialNames).doesNotHaveDuplicates().contains("치킨 알리오 파스타볼", "새우 들기름 파스타볼");
		assertThat(partial.get("menus").get(0).get("id").asString()).isEqualTo("m1");
		release.countDown();

		Map<String, Object> done = awaitFinished(jobId);
		assertThat(done.get("status")).isEqualTo("DONE");
		assertThat(done.get("error")).isNull();
		JsonNode result = resultOf(done);
		assertThat(result.get("menus")).hasSize(30);
		result.get("menus").forEach(menu -> assertThat(menu.get("ingredientCount").asInt()).isPositive());
		assertThat(result.get("needsMenuPhoto").asBoolean()).isFalse();
		assertThat(result.get("questions").size()).isBetween(1, 10);
		JsonNode question = result.get("questions").get(0);
		assertThat(question.get("id").asString()).isEqualTo("q1");
		assertThat(question.get("ingredientId").asString()).startsWith("c");
		assertThat(question.get("unit").asString()).isIn("g", "ml", "개");
		assertThat(question.get("options")).hasSize(3);
		assertThat(result.get("draft").get("menus")).hasSize(30);
		assertThat(result.get("draft").get("ingredients").size()).isPositive();
		assertThat(result.get("extraction").get("menus")).hasSize(30);
		assertThat(result.get("classify").get("items").size()).isPositive(); // 표에 없는 "특제 소스" · "트러플 오일"
		// 묶음 10개씩 3번 + 분류 1번
		verify(gmsClient, times(4)).completeJson(any(LlmRequest.class));

		// DB 에는 job 표 말고 아무것도 쓰지 않는다
		assertThat(count("menu", storeId)).isZero();
		assertThat(count("ingredient", storeId)).isZero();
		assertThat(count("recipe", storeId)).isZero();
	}

	@Test
	void emptyMenus_메뉴_0개면_DONE_needsMenuPhoto_true() {
		given(placeClient.collectMenus(anyString())).willReturn(List.of());

		Map<String, Object> row = awaitFinished(start());

		assertThat(row.get("status")).isEqualTo("DONE");
		JsonNode result = resultOf(row);
		assertThat(result.get("menus")).isEmpty();
		assertThat(result.get("needsMenuPhoto").asBoolean()).isTrue();
		verify(gmsClient, never()).completeJson(any(LlmRequest.class));
	}

	@Test
	void apifyUnavailable_수집기가_실패하면_FAILED_APIFY_UNAVAILABLE_수집은_1번만() {
		given(placeClient.collectMenus(anyString()))
				.willThrow(new BusinessException(OnboardingErrorCode.APIFY_UNAVAILABLE));

		Map<String, Object> row = awaitFinished(start());

		assertThat(row.get("status")).isEqualTo("FAILED");
		assertThat(row.get("error")).isEqualTo("APIFY_UNAVAILABLE");
		verify(placeClient, times(1)).collectMenus(anyString());
		verify(gmsClient, never()).completeJson(any(LlmRequest.class));
	}

	@Test
	void apifyNotConfigured_토큰이_없으면_FAILED_APIFY_NOT_CONFIGURED() {
		given(placeClient.collectMenus(anyString()))
				.willThrow(new BusinessException(OnboardingErrorCode.APIFY_NOT_CONFIGURED));

		Map<String, Object> row = awaitFinished(start());

		assertThat(row.get("status")).isEqualTo("FAILED");
		assertThat(row.get("error")).isEqualTo("APIFY_NOT_CONFIGURED");
		verify(placeClient, times(1)).collectMenus(anyString());
	}

	@Test
	void llmFails_LLM_재시도가_다_실패하면_FAILED_LLM_FAILED() throws Exception {
		given(placeClient.collectMenus(anyString())).willReturn(wigglyMenus());
		given(gmsClient.completeJson(any(LlmRequest.class))).willThrow(new LlmException(LlmErrorCode.FAILED));

		Map<String, Object> row = awaitFinished(start());

		assertThat(row.get("status")).isEqualTo("FAILED");
		assertThat(row.get("error")).isEqualTo("LLM_FAILED");
		assertThat(count("menu", storeId)).isZero();
	}

	@Test
	void llmFails_분류에서_재료가_빠지면_FAILED_LLM_BAD_OUTPUT() throws Exception {
		given(placeClient.collectMenus(anyString())).willReturn(wigglyMenus());
		given(gmsClient.completeJson(any(LlmRequest.class))).willAnswer(invocation -> {
			LlmRequest request = invocation.getArgument(0);
			if ("ingredient_classify".equals(request.schemaName())) {
				return jsonMapper.readTree("{\"items\":[]}");
			}
			return mechanicalLlm(request);
		});

		Map<String, Object> row = awaitFinished(start());

		assertThat(row.get("status")).isEqualTo("FAILED");
		assertThat(row.get("error")).isEqualTo("LLM_BAD_OUTPUT");
	}

	@Test
	void 사진_URL_이_있는_메뉴는_설명이_있어도_사진을_받아_LLM_에_넣는다() throws Exception {
		given(placeClient.collectMenus(anyString())).willReturn(wigglyMenus());
		List<LlmRequest> requests = new ArrayList<>();
		given(gmsClient.completeJson(any(LlmRequest.class))).willAnswer(invocation -> {
			requests.add(invocation.getArgument(0));
			return mechanicalLlm(invocation.getArgument(0));
		});
		given(imageFetcher.fetch(anyString())).willReturn(Optional.of(new LlmImage("image/jpeg", new byte[] {1})));

		awaitFinished(start());

		long withPhotoUrl = wigglyMenus().stream().filter(m -> !m.imageUrls().isEmpty()).map(m -> m.name().strip())
				.distinct().count();
		long withDescriptionAndPhotoUrl = wigglyMenus().stream()
				.filter(m -> m.description() != null && !m.description().isBlank() && !m.imageUrls().isEmpty())
				.map(m -> m.name().strip()).distinct().count();
		assertThat(withDescriptionAndPhotoUrl).isPositive(); // 설명 있는 메뉴가 fixture 에 있어야 이 시험이 뜻이 있다
		verify(imageFetcher, times((int) withPhotoUrl)).fetch(anyString());
		long photoRows = 0;
		for (LlmRequest request : requests) {
			if ("menu_extraction".equals(request.schemaName())) {
				String user = request.user();
				for (JsonNode row : jsonMapper.readTree(user.substring(user.indexOf('['), user.lastIndexOf(']') + 1))) {
					photoRows += row.has("photo") ? 1 : 0;
				}
			}
		}
		assertThat(photoRows).isEqualTo(withPhotoUrl);
	}

	@Test
	void 후보_가게_이름이_오면_가게_이름과_화면_이름을_바꾸고_없으면_그대로_둔다() throws Exception {
		given(placeClient.collectMenus(anyString())).willReturn(wigglyMenus());
		given(gmsClient.completeJson(any(LlmRequest.class)))
				.willAnswer(invocation -> mechanicalLlm(invocation.getArgument(0)));
		String before = jdbc.queryForObject("SELECT name FROM store WHERE id = ?", String.class, storeId);

		awaitFinished(start());
		assertThat(jdbc.queryForObject("SELECT name FROM store WHERE id = ?", String.class, storeId)).isEqualTo(before);

		awaitFinished(Long.parseLong(onboardingJobService.startFromPlace(storeId,
				new OnboardingJobRequest("1668058454", null, false, "  예시식당 잠실점 ")).id()));
		Map<String, Object> store = jdbc.queryForMap(
				"SELECT name, display_name, naver_place_id FROM store WHERE id = ?", storeId);
		assertThat(store.get("name")).isEqualTo("예시식당 잠실점");
		assertThat(store.get("display_name")).isEqualTo("예시식당 잠실점");
		assertThat(store.get("naver_place_id")).isEqualTo("1668058454");
	}
}
