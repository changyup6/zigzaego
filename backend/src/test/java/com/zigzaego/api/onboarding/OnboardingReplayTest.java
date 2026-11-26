package com.zigzaego.api.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.llm.LlmErrorCode;
import com.zigzaego.api.llm.LlmException;
import com.zigzaego.api.llm.LlmRequest;
import com.zigzaego.api.onboarding.dto.CollectedMenu;
import com.zigzaego.api.onboarding.dto.OnboardingJobRequest;
import com.zigzaego.api.onboarding.dto.PlaceCandidateResponse;
import com.zigzaego.api.onboarding.exception.OnboardingErrorCode;
import com.zigzaego.api.onboarding.job.OnboardingAnalyzer;
import com.zigzaego.api.onboarding.job.OnboardingResult;
import com.zigzaego.api.onboarding.job.OnboardingResult.AnalysisMenu;
import com.zigzaego.api.onboarding.replay.OnboardingReplayDeleteRunner;
import com.zigzaego.api.onboarding.replay.OnboardingReplayProperties;
import com.zigzaego.api.onboarding.replay.OnboardingReplayRepository;
import com.zigzaego.api.onboarding.replay.OnboardingReplaySaveRunner;
import com.zigzaego.api.onboarding.service.OnboardingJobService;
import com.zigzaego.api.support.StoreFixtures.LoggedInStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/** 온보딩 저장본 재생 (지라 S15P31A308-321, ADR-0016). 수집기 · GMS 는 목, DB 는 실제. */
class OnboardingReplayTest extends OnboardingTestSupport {

	private static final String QUERY = "위글리 파스타볼";

	@Autowired
	private OnboardingReplayRepository repository;

	@Autowired
	private OnboardingJobService onboardingJobService;

	@Autowired
	private OnboardingAnalyzer analyzer;

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM onboarding_replay");
		given(imageFetcher.fetch(anyString())).willReturn(Optional.empty());
	}

	@AfterEach
	void cleanUp() {
		jdbc.update("DELETE FROM onboarding_replay");
	}

	private static PlaceCandidateResponse candidate(String id, String name) {
		return new PlaceCandidateResponse(id, name, "서울특별시 강남구 테헤란로10길 25", null, false);
	}

	private OnboardingReplaySaveRunner saveRunner(String query) {
		return new OnboardingReplaySaveRunner(new OnboardingReplayProperties(query), placeClient, repository, analyzer);
	}

	private OnboardingReplayDeleteRunner deleteRunner(String query) {
		return new OnboardingReplayDeleteRunner(new OnboardingReplayProperties(query), repository);
	}

	private long rows(String kind) {
		return jdbc.queryForObject("SELECT count(*) FROM onboarding_replay WHERE kind = ?", Long.class, kind);
	}

	@Test
	void searchReplayHitSkipsApify() throws Exception {
		repository.saveSearch(QUERY, List.of(candidate("1668058454", QUERY)));

		mockMvc.perform(get("/onboarding/places").param("query", "  위글리   파스타볼 ").with(user("owner")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].id").value("1668058454"))
				.andExpect(jsonPath("$.data[0].name").value(QUERY))
				.andExpect(jsonPath("$.data[0].yogiyoMatch").value(false));

		verify(placeClient, never()).searchPlaces(anyString());

		// 탭 · 전각 공백(U+3000)이 섞인 검색어도 같은 저장본에 맞는다
		mockMvc.perform(get("/onboarding/places").param("query", "	위글리　 	파스타볼　").with(user("owner")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].id").value("1668058454"));
		verify(placeClient, never()).searchPlaces(anyString());

		// 앞뒤 NBSP(U+00A0) 는 strip() 이 못 지우므로 공백을 먼저 합친 뒤 다듬는다
		assertThat(OnboardingReplayRepository.normalizeQuery(" 위글리 파스타볼 ")).isEqualTo(QUERY);
	}

	@Test
	void menusReplayHitSkipsApify() throws Exception {
		repository.saveMenus("1668058454", wigglyMenus());
		given(gmsClient.completeJson(any(LlmRequest.class)))
				.willAnswer(invocation -> mechanicalLlm(invocation.getArgument(0)));
		long storeId = insertStore("재생-" + System.nanoTime());

		long jobId = Long.parseLong(onboardingJobService
				.startFromPlace(storeId, new OnboardingJobRequest("1668058454", null, false)).id());

		Map<String, Object> done = awaitFinished(jobId);
		assertThat(done.get("status")).isEqualTo("DONE");
		assertThat(resultOf(done).get("menus")).hasSize(30);
		verify(placeClient, never()).collectMenus(anyString());
	}

	@Test
	void analysisReplayHitSkipsApifyAndGms() throws Exception {
		OnboardingResult saved = new OnboardingResult(
				List.of(new AnalysisMenu("m1", "저장된 파스타", 12000, 3), new AnalysisMenu("m2", "저장된 피자", 15000, 2)),
				false, null, null, null, null);
		repository.saveAnalysis("1668058454", saved);
		LoggedInStore store = loginNewStore("분석저장본");

		long start = System.nanoTime();
		MvcResult created = mockMvc.perform(post("/onboarding/jobs")
						.header(HttpHeaders.AUTHORIZATION, store.bearer())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"placeId\":\"1668058454\",\"includeYogiyo\":false}"))
				.andExpect(status().isOk())
				.andReturn();
		long jobId = Long.parseLong(jsonMapper.readTree(created.getResponse().getContentAsString())
				.get("data").get("id").asString());
		Map<String, Object> done = awaitFinished(jobId);
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;

		assertThat(done.get("status")).isEqualTo("DONE");
		assertThat(elapsedMs).isLessThan(1000);
		JsonNode result = resultOf(done);
		assertThat(result).isEqualTo(jsonMapper.readTree(jsonMapper.writeValueAsString(saved)));
		assertThat(result.get("menus").get(0).get("name").asString()).isEqualTo("저장된 파스타");
		assertThat(((Number) done.get("progress")).doubleValue()).isEqualTo(1.0);
		verify(placeClient, never()).collectMenus(anyString());
		verify(gmsClient, never()).completeJson(any(LlmRequest.class));
	}

	@Test
	void saveRunnerAnalysisFailureKeepsOthers() throws Exception {
		given(placeClient.searchPlaces(QUERY)).willReturn(List.of(candidate("111", "가게 A"), candidate("222", "가게 B")));
		given(placeClient.collectMenus("111")).willReturn(demoMenus());
		given(placeClient.collectMenus("222")).willReturn(wigglyMenus());
		List<String> demoNames = demoMenus().stream().map(CollectedMenu::name).toList();
		String failName = wigglyMenus().stream().map(CollectedMenu::name).filter(n -> !demoNames.contains(n))
				.findFirst().orElseThrow();
		given(gmsClient.completeJson(any(LlmRequest.class))).willAnswer(invocation -> {
			LlmRequest request = invocation.getArgument(0);
			if (request.user().contains(failName)) {
				throw new LlmException(LlmErrorCode.FAILED);
			}
			return mechanicalLlm(request);
		});

		assertThatThrownBy(() -> saveRunner(QUERY).run(null)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("222");

		assertThat(rows("SEARCH")).isEqualTo(1);
		assertThat(rows("MENUS")).isEqualTo(2);
		assertThat(rows("ANALYSIS")).isEqualTo(1);
		assertThat(repository.findAnalysis("111")).isPresent();
		assertThat(repository.findAnalysis("222")).isEmpty();
	}

	@Test
	void saveRunnerStoresSearchAndAllCandidates() throws Exception {
		given(placeClient.searchPlaces(QUERY)).willReturn(List.of(candidate("111", "가게 A"), candidate("222", "가게 B")));
		given(placeClient.collectMenus("111")).willReturn(demoMenus());
		given(placeClient.collectMenus("222")).willReturn(wigglyMenus());
		given(gmsClient.completeJson(any(LlmRequest.class)))
				.willAnswer(invocation -> mechanicalLlm(invocation.getArgument(0)));

		saveRunner("  위글리   파스타볼 ").run(null);

		assertThat(rows("SEARCH")).isEqualTo(1);
		assertThat(rows("MENUS")).isEqualTo(2);
		assertThat(rows("ANALYSIS")).isEqualTo(2);
		assertThat(repository.findAnalysis("111")).get().extracting(r -> r.menus().size())
				.isEqualTo(demoMenus().size());
		assertThat(repository.findAnalysis("222")).isPresent();
		assertThat(repository.findSearch(QUERY)).get().asList().hasSize(2);
		assertThat(repository.findMenus("111")).get().asList().hasSize(demoMenus().size());
		assertThat(repository.findMenus("222")).get().asList().hasSize(wigglyMenus().size());
		// 다시 돌리면 덮어쓴다 — 행이 늘지 않는다
		saveRunner(QUERY).run(null);
		assertThat(rows("SEARCH")).isEqualTo(1);
		assertThat(rows("MENUS")).isEqualTo(2);
		assertThat(rows("ANALYSIS")).isEqualTo(2);
	}

	@Test
	void deleteRunnerRemovesSearchAndMenus() throws Exception {
		given(placeClient.searchPlaces(QUERY)).willReturn(List.of(candidate("111", "가게 A"), candidate("222", "가게 B")));
		given(placeClient.collectMenus(anyString())).willReturn(demoMenus());
		given(gmsClient.completeJson(any(LlmRequest.class)))
				.willAnswer(invocation -> mechanicalLlm(invocation.getArgument(0)));
		saveRunner(QUERY).run(null);
		repository.saveMenus("999", List.of(new CollectedMenu("남의 메뉴", 1000, null, List.of())));
		repository.saveAnalysis("999", OnboardingResult.noMenus());
		assertThat(rows("SEARCH") + rows("MENUS") + rows("ANALYSIS")).isEqualTo(7);

		deleteRunner(" 위글리  파스타볼").run(null);

		assertThat(rows("SEARCH")).isZero();
		assertThat(rows("MENUS")).isEqualTo(1); // 그 검색어 후보의 메뉴만 지운다
		assertThat(rows("ANALYSIS")).isEqualTo(1);
		assertThat(repository.findAnalysis("999")).isPresent();
		assertThat(repository.findAnalysis("111")).isEmpty();
		assertThat(repository.findMenus("999")).isPresent();
		deleteRunner("없는 검색어").run(null); // 지울 것이 없어도 실패하지 않는다
	}

	@Test
	void saveRunnerZeroCandidatesStoresNothing() {
		given(placeClient.searchPlaces(QUERY)).willReturn(List.of());

		assertThatThrownBy(() -> saveRunner(QUERY).run(null)).isInstanceOf(IllegalStateException.class);

		assertThat(rows("SEARCH") + rows("MENUS")).isZero();
	}

	@Test
	void saveRunnerPartialFailureKeepsSucceeded() throws Exception {
		given(placeClient.searchPlaces(QUERY)).willReturn(List.of(candidate("111", "가게 A"), candidate("222", "가게 B")));
		given(placeClient.collectMenus("111")).willReturn(demoMenus());
		given(placeClient.collectMenus("222")).willThrow(new BusinessException(OnboardingErrorCode.APIFY_UNAVAILABLE));

		assertThatThrownBy(() -> saveRunner(QUERY).run(null)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("222");

		assertThat(rows("SEARCH")).isEqualTo(1);
		assertThat(rows("MENUS")).isEqualTo(1);
		assertThat(repository.findMenus("111")).isPresent();
		assertThat(repository.findMenus("222")).isEmpty();
	}

	@Test
	void saveRunnerBlankQueryRejected() {
		assertThatThrownBy(() -> saveRunner("   ").run(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> saveRunner(null).run(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> deleteRunner("").run(null)).isInstanceOf(IllegalArgumentException.class);
		verify(placeClient, never()).searchPlaces(anyString());
	}
}
