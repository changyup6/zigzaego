package com.zigzaego.api.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.zigzaego.api.onboarding.dto.PlaceCandidateResponse;
import com.zigzaego.api.onboarding.replay.OnboardingReplayRepository;
import com.zigzaego.api.support.StoreFixtures.LoggedInStore;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** 실제 검색/선택 API부터 저장과 가게별 조회까지 주소 전달을 검증한다 (S15P31A308-357). */
class StoreAddressTest extends OnboardingTestSupport {

	@Autowired
	private OnboardingReplayRepository replays;

	@Autowired
	private DataSource dataSource;

	private LoggedInStore mine;
	private LoggedInStore other;

	@BeforeEach
	void setUp() throws Exception {
		mine = loginNewStore("주소가게");
		other = loginNewStore("다른주소가게");
		given(placeClient.collectMenus(anyString())).willReturn(List.of());
	}

	@Test
	void liveSearchCandidateAddressIsStoredWithoutChangingDeliveryRegion() throws Exception {
		String address = "서울특별시 중구 예시로 12 2층";
		given(placeClient.searchPlaces("주소 검색"))
				.willReturn(List.of(new PlaceCandidateResponse("35701", "검색 가게", address, null, false)));
		jdbc.update("UPDATE store SET sigungu = '서울 중구' WHERE id = ?", mine.storeId());

		mockMvc.perform(get("/onboarding/places").param("query", "주소 검색")
				.header(HttpHeaders.AUTHORIZATION, mine.bearer()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data[0].address").value(address));
		select("35701");

		me(mine).andExpect(jsonPath("$.data.address").value(address))
				.andExpect(jsonPath("$.data.sigungu").value("서울 중구"));
		assertThat(jdbc.queryForObject("SELECT address FROM store WHERE id = ?", String.class, mine.storeId()))
				.isEqualTo(address);
		me(other).andExpect(jsonPath("$.data.address").value(nullValue()));
	}

	@Test
	void savedApifyCandidateIsResolvedByIdWithoutSearchingAgain() throws Exception {
		String query = "주소 저장본 " + mine.storeId();
		replays.saveSearch(query, List.of(
				new PlaceCandidateResponse("35702", "다른 후보", "서울특별시 강남구 다른길 7", null, false),
				new PlaceCandidateResponse("35703", "선택 후보", "서울특별시 마포구 선택길 9", null, false)));
		try {
			select("35703");
			me(mine).andExpect(jsonPath("$.data.address").value("서울특별시 마포구 선택길 9"));
			me(other).andExpect(jsonPath("$.data.address").value(nullValue()));
		} finally {
			replays.deleteSearchWithMenus(query);
		}
	}

	@Test
	void unknownPlaceDoesNotKeepPreviousPlaceAddress() throws Exception {
		jdbc.update("UPDATE store SET address = '이전 장소 주소', sigungu = '서울 중구' WHERE id = ?", mine.storeId());
		jdbc.update("UPDATE store SET address = '남의 가게 주소' WHERE id = ?", other.storeId());

		select("35704");

		me(mine).andExpect(jsonPath("$.data.address").value(nullValue()))
				.andExpect(jsonPath("$.data.sigungu").value("서울 중구"));
		me(other).andExpect(jsonPath("$.data.address").value("남의 가게 주소"));
	}

	@Test
	void newlyCreatedStoreHasNoAddress() throws Exception {
		me(mine).andExpect(jsonPath("$.data.address").value(nullValue()));
		assertThat(jdbc.queryForObject("SELECT address FROM store WHERE id = ?", String.class, mine.storeId())).isNull();
	}

	@Test
	void clientCannotSubmitAddressAsTrustedCandidateData() throws Exception {
		String response = mockMvc.perform(post("/onboarding/jobs")
				.header(HttpHeaders.AUTHORIZATION, mine.bearer()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"placeId\":\"35705\",\"includeYogiyo\":false,\"address\":\"조작한 주소\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		awaitFinished(Long.parseLong(JsonPath.read(response, "$.data.id")));
		me(mine).andExpect(jsonPath("$.data.address").value(nullValue()));
		assertThat(jdbc.queryForObject("SELECT address FROM store WHERE id = ?", String.class, mine.storeId())).isNull();
	}

	@Test
	void migrationPreservesExistingDeliveryRegionAndLeavesAddressUnknown() {
		String schema = "v25_store_address_check";
		jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
		try {
			Flyway.configure().dataSource(dataSource).schemas(schema).target("24").load().migrate();
			jdbc.update("INSERT INTO " + schema + ".store (name, display_name, sigungu)"
					+ " VALUES ('기존 가게', '기존 가게', '서울 중구')");
			jdbc.update("INSERT INTO " + schema + ".store (name, display_name, naver_place_id, sigungu) VALUES"
					+ " ('일치 가게', '일치 가게', '35790', '서울 중구'),"
					+ " ('빈 주소 가게', '빈 주소 가게', '35791', NULL),"
					+ " ('불일치 가게', '불일치 가게', '35792', NULL)");
			jdbc.update("INSERT INTO " + schema + ".onboarding_replay (kind, replay_key, payload, captured_at) VALUES"
					+ " ('SEARCH', 'old', '[{\"id\":\"35790\",\"address\":\"과거 주소\"}]'::jsonb, now() - interval '1 day'),"
					+ " ('SEARCH', 'new', '[{\"id\":\"35790\",\"address\":\" 최신 주소 \"},"
					+ "{\"id\":\"35791\",\"address\":\"  \"},{\"id\":\"35793\",\"address\":\"다른 장소 주소\"}]'::jsonb, now()),"
					+ " ('ANALYSIS', '35792', '{\"address\":\"분석 주소\"}'::jsonb, now())");
			Flyway v25 = Flyway.configure().dataSource(dataSource).schemas(schema).target("25").load();
			v25.migrate();
			assertThat(v25.info().current().getVersion().getVersion()).isEqualTo("25");
			assertThat(jdbc.queryForMap("SELECT address, sigungu FROM " + schema + ".store WHERE name = '기존 가게'"))
					.containsEntry("address", null).containsEntry("sigungu", "서울 중구");
			assertThat(jdbc.queryForMap("SELECT address, sigungu FROM " + schema + ".store WHERE naver_place_id = '35790'"))
					.containsEntry("address", "최신 주소").containsEntry("sigungu", "서울 중구");
			assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".store"
					+ " WHERE naver_place_id IN ('35791', '35792') AND address IS NULL", Integer.class)).isEqualTo(2);
		} finally {
			jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
		}
	}

	private void select(String id) throws Exception {
		String response = mockMvc.perform(post("/onboarding/jobs")
				.header(HttpHeaders.AUTHORIZATION, mine.bearer()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"placeId\":\"" + id + "\",\"includeYogiyo\":false,\"placeName\":\"선택 가게\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		awaitFinished(Long.parseLong(JsonPath.read(response, "$.data.id")));
	}

	private ResultActions me(LoggedInStore store) throws Exception {
		return mockMvc.perform(get("/stores/me").header(HttpHeaders.AUTHORIZATION, store.bearer()))
				.andExpect(status().isOk());
	}
}
