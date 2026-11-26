package com.zigzaego.api.onboarding.replay;

import com.zigzaego.api.onboarding.dto.CollectedMenu;
import com.zigzaego.api.onboarding.dto.PlaceCandidateResponse;
import com.zigzaego.api.onboarding.job.OnboardingResult;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 온보딩 저장본 표 {@code onboarding_replay} 읽기 · 쓰기 (S15P31A308-321, ADR-0016).
 * 가게 소속이 아닌 전역 표라 store 조건이 없다 — ADR-0002 의 예외. JPA 엔티티를 두지 않고 JdbcTemplate 으로만 접근한다(MarketProductJdbcRepository 선례).
 * 검색어 키는 {@link #normalizeQuery} 로 맞춘다 — 읽기 · 쓰기가 같은 함수를 쓴다.
 */
@Repository
@RequiredArgsConstructor
public class OnboardingReplayRepository {

	static final String SEARCH = "SEARCH";
	static final String MENUS = "MENUS";
	static final String ANALYSIS = "ANALYSIS";

	private static final TypeReference<List<PlaceCandidateResponse>> CANDIDATES = new TypeReference<>() {
	};
	private static final TypeReference<List<CollectedMenu>> MENU_LIST = new TypeReference<>() {
	};

	private static final String UPSERT = """
			INSERT INTO onboarding_replay (kind, replay_key, payload, captured_at)
			VALUES (?, ?, ?::jsonb, now())
			ON CONFLICT (kind, replay_key) DO UPDATE SET payload = EXCLUDED.payload, captured_at = EXCLUDED.captured_at
			""";

	private final JdbcTemplate jdbc;
	private final JsonMapper jsonMapper;

	/** 검색어 정규화 — 앞뒤 공백 제거 + 안쪽 연속 공백을 한 칸으로. 대소문자는 그대로. 읽기 · 쓰기 공용. */
	public static String normalizeQuery(String query) {
		return query == null ? "" : query.replaceAll("(?U)\\s+", " ").strip();
	}

	public Optional<List<PlaceCandidateResponse>> findSearch(String query) {
		return find(SEARCH, normalizeQuery(query)).map(json -> jsonMapper.readValue(json, CANDIDATES));
	}

	/** 저장본 검색어와 무관하게 같은 placeId의 최신 서버 수집 후보만 찾는다 (ADR-0023). */
	public Optional<PlaceCandidateResponse> findCandidate(String placeId) {
		return jdbc.query("""
				SELECT candidate::text FROM onboarding_replay r
				CROSS JOIN LATERAL jsonb_array_elements(r.payload) AS entries(candidate)
				WHERE r.kind = 'SEARCH' AND candidate ->> 'id' = ?
				ORDER BY r.captured_at DESC, r.id DESC LIMIT 1
				""", (rs, i) -> jsonMapper.readValue(rs.getString(1), PlaceCandidateResponse.class), placeId)
				.stream().findFirst();
	}

	public Optional<List<CollectedMenu>> findMenus(String placeId) {
		return find(MENUS, placeId).map(json -> jsonMapper.readValue(json, MENU_LIST));
	}

	/** 분석 job 결과 저장본. 키는 placeId. */
	public Optional<OnboardingResult> findAnalysis(String placeId) {
		return find(ANALYSIS, placeId).map(json -> jsonMapper.readValue(json, OnboardingResult.class));
	}

	public void saveSearch(String query, List<PlaceCandidateResponse> candidates) {
		jdbc.update(UPSERT, SEARCH, normalizeQuery(query), jsonMapper.writeValueAsString(candidates));
	}

	public void saveMenus(String placeId, List<CollectedMenu> menus) {
		jdbc.update(UPSERT, MENUS, placeId, jsonMapper.writeValueAsString(menus));
	}

	public void saveAnalysis(String placeId, OnboardingResult result) {
		jdbc.update(UPSERT, ANALYSIS, placeId, jsonMapper.writeValueAsString(result));
	}

	/** 그 검색어의 SEARCH 행과, 그 행에 든 후보들의 MENUS · ANALYSIS 행을 지운다. 지울 SEARCH 행이 없으면 false. */
	@Transactional
	public boolean deleteSearchWithMenus(String query) {
		String key = normalizeQuery(query);
		Optional<List<PlaceCandidateResponse>> candidates = findSearch(key);
		if (candidates.isEmpty()) {
			return false;
		}
		for (PlaceCandidateResponse candidate : candidates.get()) {
			jdbc.update("DELETE FROM onboarding_replay WHERE kind = ? AND replay_key = ?", MENUS, candidate.id());
			jdbc.update("DELETE FROM onboarding_replay WHERE kind = ? AND replay_key = ?", ANALYSIS, candidate.id());
		}
		jdbc.update("DELETE FROM onboarding_replay WHERE kind = ? AND replay_key = ?", SEARCH, key);
		return true;
	}

	private Optional<String> find(String kind, String key) {
		return jdbc.query("SELECT payload::text FROM onboarding_replay WHERE kind = ? AND replay_key = ?",
				(rs, i) -> rs.getString(1), kind, key).stream().findFirst();
	}
}
