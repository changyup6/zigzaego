package com.zigzaego.api.onboarding.replay;

import com.zigzaego.api.menu.entity.MenuPhotoUrl;
import com.zigzaego.api.onboarding.client.ApifyNaverPlaceClient;
import com.zigzaego.api.onboarding.dto.CollectedMenu;
import com.zigzaego.api.onboarding.dto.PlaceCandidateResponse;
import com.zigzaego.api.onboarding.job.OnboardingResult;
import com.zigzaego.api.onboarding.job.OnboardingResult.Draft;
import com.zigzaego.api.onboarding.job.OnboardingResult.DraftMenu;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.DoubleConsumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 가게 찾기 · 메뉴 수집의 앞단 (S15P31A308-321, ADR-0016). 운영자가 미리 받아 둔 저장본이 있으면 그것을 돌려주고
 * {@link ApifyNaverPlaceClient} 를 부르지 않는다. 없으면 클라이언트를 그대로 부른다 — 일반 요청은 저장본을 만들지 않는다.
 *
 * <p>저장본으로 답할 때는 실제 호출처럼 보이게 정해진 시간만큼 기다린다(가게 찾기 {@code searchDelay},
 * 분석 {@code analysisDelay}). 저장본이 없으면 기다리지 않는다.
 */
@Service
public class OnboardingPlaceSource {

	/** 분석 저장본을 돌려주는 동안 진행률을 몇 번에 나눠 알릴지. */
	static final int PACE_STEPS = 30;
	private static final int MAX_RECENT_CANDIDATES = 500;
	private static final long CANDIDATE_TTL_NANOS = Duration.ofMinutes(30).toNanos();

	private final ApifyNaverPlaceClient placeClient;
	private final OnboardingReplayRepository replays;
	private final Duration searchDelay;
	private final Duration analysisDelay;
	private final Map<String, RecentCandidate> recentCandidates = new LinkedHashMap<>();

	@Autowired
	public OnboardingPlaceSource(ApifyNaverPlaceClient placeClient, OnboardingReplayRepository replays,
			@Value("${app.onboarding-replay.search-delay}") Duration searchDelay,
			@Value("${app.onboarding-replay.analysis-delay}") Duration analysisDelay) {
		this.placeClient = placeClient;
		this.replays = replays;
		this.searchDelay = searchDelay;
		this.analysisDelay = analysisDelay;
	}

	/** 기다리지 않는 앞단 (테스트 · 저장본을 쓰지 않는 조립용). */
	public OnboardingPlaceSource(ApifyNaverPlaceClient placeClient, OnboardingReplayRepository replays) {
		this(placeClient, replays, Duration.ZERO, Duration.ZERO);
	}

	public List<PlaceCandidateResponse> searchPlaces(String query) {
		Optional<List<PlaceCandidateResponse>> saved = replays.findSearch(query);
		if (saved.isEmpty()) {
			List<PlaceCandidateResponse> candidates = placeClient.searchPlaces(query);
			rememberCandidates(candidates);
			return candidates;
		}
		pause(searchDelay);
		return saved.get();
	}

	/** 본문 주소를 신뢰하지 않고 서버가 실제 받은 후보에서만 선택 주소를 읽는다. 재수집하지 않는다. */
	public Optional<PlaceCandidateResponse> findCandidate(String placeId) {
		Optional<PlaceCandidateResponse> recent = recentCandidate(placeId);
		return recent.isPresent() ? recent : replays.findCandidate(placeId);
	}

	private synchronized void rememberCandidates(List<PlaceCandidateResponse> candidates) {
		long now = System.nanoTime();
		recentCandidates.values().removeIf(candidate -> now - candidate.receivedAt() >= CANDIDATE_TTL_NANOS);
		for (PlaceCandidateResponse candidate : candidates) {
			if (candidate.id() == null) {
				continue;
			}
			recentCandidates.remove(candidate.id());
			recentCandidates.put(candidate.id(), new RecentCandidate(candidate, now));
			if (recentCandidates.size() > MAX_RECENT_CANDIDATES) {
				recentCandidates.remove(recentCandidates.keySet().iterator().next());
			}
		}
	}

	private synchronized Optional<PlaceCandidateResponse> recentCandidate(String placeId) {
		RecentCandidate candidate = recentCandidates.get(placeId);
		if (candidate == null) {
			return Optional.empty();
		}
		if (System.nanoTime() - candidate.receivedAt() >= CANDIDATE_TTL_NANOS) {
			recentCandidates.remove(placeId);
			return Optional.empty();
		}
		return Optional.of(candidate.place());
	}

	private record RecentCandidate(PlaceCandidateResponse place, long receivedAt) {
	}

	public List<CollectedMenu> collectMenus(String placeId) {
		return replays.findMenus(placeId).orElseGet(() -> placeClient.collectMenus(placeId));
	}

	/** 분석 결과 저장본 (S15P31A308-333). 있으면 수집 · LLM 모두 건너뛴다. */
	public Optional<OnboardingResult> findAnalysis(String placeId) {
		return replays.findAnalysis(placeId).map(result -> withSavedPhotos(placeId, result));
	}

	/** 예전 분석 저장본에서 빠진 사진은 같은 placeId의 메뉴 저장본으로만 보완한다. 외부 수집은 하지 않는다. */
	private OnboardingResult withSavedPhotos(String placeId, OnboardingResult result) {
		if (result.draft() == null || result.draft().menus() == null) {
			return result;
		}
		Map<String, String> photos = new HashMap<>();
		for (CollectedMenu menu : replays.findMenus(placeId).orElse(List.of())) {
			String photo = MenuPhotoUrl.first(menu.imageUrls());
			if (menu.name() != null && photo != null) {
				photos.putIfAbsent(menu.name().strip(), photo);
			}
		}
		if (photos.isEmpty()) {
			return result;
		}
		List<DraftMenu> menus = result.draft().menus().stream().map(menu -> {
			String photo = MenuPhotoUrl.normalize(menu.photoUrl());
			return new DraftMenu(menu.id(), menu.name(), menu.price(), menu.kind(), menu.source(), menu.lines(),
					photo != null ? photo : photos.get(menu.name().strip()));
		}).toList();
		return new OnboardingResult(result.menus(), result.needsMenuPhoto(),
				new Draft(menus, result.draft().ingredients()), result.questions(), result.extraction(), result.classify());
	}

	/**
	 * 분석 저장본을 돌려주기 전에 {@code analysisDelay} 동안 진행률(0 초과 1 미만, 오름차순)을 {@link #PACE_STEPS} 번 알린다.
	 * 지연이 0 이면 알리지 않고 바로 돌아온다. 끝난 뒤 1.0 은 부르는 쪽이 남긴다.
	 */
	public void paceAnalysis(DoubleConsumer progress) {
		Duration step = analysisDelay.dividedBy(PACE_STEPS);
		if (step.isZero() || step.isNegative()) {
			return;
		}
		for (int i = 1; i <= PACE_STEPS; i++) {
			if (!pause(step)) {
				return;
			}
			progress.accept((double) i / (PACE_STEPS + 1));
		}
	}

	/** 끝까지 기다렸으면 true. 중단되면 인터럽트 표시를 되살리고 false. */
	private static boolean pause(Duration delay) {
		if (delay.isZero() || delay.isNegative()) {
			return true;
		}
		try {
			Thread.sleep(delay);
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}
}
