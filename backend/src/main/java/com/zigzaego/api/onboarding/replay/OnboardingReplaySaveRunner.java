package com.zigzaego.api.onboarding.replay;

import com.zigzaego.api.job.service.JobContext;
import com.zigzaego.api.onboarding.client.ApifyNaverPlaceClient;
import com.zigzaego.api.onboarding.dto.CollectedMenu;
import com.zigzaego.api.onboarding.dto.PlaceCandidateResponse;
import com.zigzaego.api.onboarding.job.OnboardingAnalyzer;
import com.zigzaego.api.onboarding.job.OnboardingResult;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 운영자용 온보딩 저장본 저장 (S15P31A308-321, ADR-0016). {@code onboarding-replay-save} 프로필로 띄울 때만 돈다.
 * 웹 서버 없이 검색어 하나를 Apify 로 실제 검색하고, 후보 전부(최대 5곳)의 메뉴를 실제로 수집해 저장하고,
 * 그 메뉴로 분석(LLM, GMS 키 필요)을 실제로 돌려 분석 결과(ANALYSIS)도 저장하고 끝난다(S15P31A308-333).
 * 후보 0곳이면 아무것도 저장하지 않고, 수집 · 분석에 실패한 후보가 있으면 성공분은 남기고 실패한다 — 실패는 예외로 던져 종료 코드 1 이 된다.
 * 실행법은 {@code backend/README.md}.
 */
@Slf4j
@Component
@Profile("onboarding-replay-save")
@EnableConfigurationProperties(OnboardingReplayProperties.class)
@RequiredArgsConstructor
public class OnboardingReplaySaveRunner implements ApplicationRunner {

	/** 저장 실행은 진행률 · 중간 결과를 받을 job 이 없다. */
	private static final JobContext NO_OP = new JobContext() {
		@Override
		public long jobId() {
			return 0;
		}

		@Override
		public void progress(double progress) {
		}

		@Override
		public void partialResult(Object partial) {
		}
	};

	private final OnboardingReplayProperties properties;
	private final ApifyNaverPlaceClient placeClient;
	private final OnboardingReplayRepository replays;
	private final OnboardingAnalyzer analyzer;

	@Override
	public void run(ApplicationArguments args) {
		String query = properties.requireQuery();
		log.info("온보딩 저장본 저장 시작 — 검색어 '{}'", query);
		List<PlaceCandidateResponse> candidates = placeClient.searchPlaces(query);
		if (candidates.isEmpty()) {
			throw new IllegalStateException("검색 결과 0곳 — 아무것도 저장하지 않았습니다: '" + query + "'");
		}
		List<String> failed = new ArrayList<>();
		for (PlaceCandidateResponse candidate : candidates) {
			try {
				List<CollectedMenu> menus = placeClient.collectMenus(candidate.id());
				replays.saveMenus(candidate.id(), menus);
				log.info("메뉴 저장 — placeId={} ({}), {}개", candidate.id(), candidate.name(), menus.size());
				OnboardingResult analysis = analyzer.analyzePlaceFresh(NO_OP, candidate.id());
				replays.saveAnalysis(candidate.id(), analysis);
				log.info("분석 결과 저장 — placeId={} ({})", candidate.id(), candidate.name());
			} catch (RuntimeException e) {
				failed.add(candidate.id());
				log.error("수집 · 분석 실패 — placeId={} ({}): {}", candidate.id(), candidate.name(), e.toString());
			}
		}
		replays.saveSearch(query, candidates);
		log.info("검색 결과 저장 — 후보 {}곳", candidates.size());
		if (!failed.isEmpty()) {
			throw new IllegalStateException("메뉴 수집 · 분석에 실패한 후보가 있습니다(성공분은 저장됨) — placeId " + failed);
		}
		log.info("온보딩 저장본 저장 끝 — 검색어 '{}', 후보 {}곳 모두 저장", query, candidates.size());
	}
}
