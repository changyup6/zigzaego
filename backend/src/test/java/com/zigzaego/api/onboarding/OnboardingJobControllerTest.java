package com.zigzaego.api.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.zigzaego.api.llm.LlmRequest;
import com.zigzaego.api.support.StoreFixtures.LoggedInStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

/** {@code POST /onboarding/jobs} · {@code /onboarding/jobs/menu-photo} (지라 S15P31A308-95). */
class OnboardingJobControllerTest extends OnboardingTestSupport {

	private LoggedInStore store;

	@BeforeEach
	void setUp() throws Exception {
		store = loginNewStore("온보딩API");
		given(placeClient.collectMenus(anyString())).willReturn(List.of());
	}

	private long jobCount() {
		return count("job", store.storeId());
	}

	@Test
	void placeId_로_시작하면_200_QUEUED_id_는_문자열() throws Exception {
		MvcResult result = mockMvc.perform(post("/onboarding/jobs")
						.header(HttpHeaders.AUTHORIZATION, store.bearer())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"placeId\":\"1668058454\",\"includeYogiyo\":false}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.id").isString())
				.andExpect(jsonPath("$.data.type").value("ONBOARDING"))
				.andExpect(jsonPath("$.data.status").value("QUEUED"))
				.andExpect(jsonPath("$.data.progress").value(0.0))
				.andReturn();

		long jobId = Long.parseLong(JsonPath.read(result.getResponse().getContentAsString(), "$.data.id"));
		Map<String, Object> row = awaitFinished(jobId);
		assertThat(row.get("status")).isEqualTo("DONE");
		verify(placeClient).collectMenus("1668058454");
		assertThat(jdbc.queryForObject("SELECT store_id FROM job WHERE id = ?", Long.class, jobId))
				.isEqualTo(store.storeId());
	}

	@Test
	void placeId_naverMapUrl_둘_다_없으면_400_ONBOARDING_PLACE_REQUIRED() throws Exception {
		mockMvc.perform(post("/onboarding/jobs")
						.header(HttpHeaders.AUTHORIZATION, store.bearer())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"includeYogiyo\":true}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("ONBOARDING_PLACE_REQUIRED"));
		assertThat(jobCount()).isZero();
	}

	@Test
	void mapUrl_entry_place_링크에서_placeId_를_뽑는다() throws Exception {
		MvcResult result = mockMvc.perform(post("/onboarding/jobs")
						.header(HttpHeaders.AUTHORIZATION, store.bearer())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"naverMapUrl\":\"https://map.naver.com/p/entry/place/1668058454\",\"includeYogiyo\":false}"))
				.andExpect(status().isOk())
				.andReturn();

		awaitFinished(Long.parseLong(JsonPath.read(result.getResponse().getContentAsString(), "$.data.id")));
		verify(placeClient).collectMenus("1668058454");
	}

	@Test
	void mapUrl_가게_링크가_아니면_400_ONBOARDING_PLACE_URL_INVALID_job_없음() throws Exception {
		mockMvc.perform(post("/onboarding/jobs")
						.header(HttpHeaders.AUTHORIZATION, store.bearer())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"naverMapUrl\":\"https://example.com\",\"includeYogiyo\":false}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("ONBOARDING_PLACE_URL_INVALID"));
		assertThat(jobCount()).isZero();
		verify(placeClient, never()).collectMenus(anyString());
	}

	@Test
	void mapUrl_restaurant_꼴과_쿼리가_붙은_place_꼴도_뽑는다() throws Exception {
		for (String url : List.of("https://m.place.naver.com/restaurant/1668058454/home",
				"https://pcmap.place.naver.com/place/1668058454?entry=pll")) {
			MvcResult result = mockMvc.perform(post("/onboarding/jobs")
							.header(HttpHeaders.AUTHORIZATION, store.bearer())
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"naverMapUrl\":\"" + url + "\",\"includeYogiyo\":false}"))
					.andExpect(status().isOk())
					.andReturn();
			awaitFinished(Long.parseLong(JsonPath.read(result.getResponse().getContentAsString(), "$.data.id")));
		}
		verify(placeClient, org.mockito.Mockito.times(2)).collectMenus("1668058454");
	}

	@Test
	void 인증_없으면_401() throws Exception {
		mockMvc.perform(post("/onboarding/jobs")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"placeId\":\"1668058454\",\"includeYogiyo\":false}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("COMMON_UNAUTHORIZED"));
		verify(placeClient, never()).collectMenus(anyString());
	}

	@Test
	void 메뉴판_사진이_10MB_를_넘으면_400() throws Exception {
		byte[] big = new byte[10 * 1024 * 1024 + 1];
		mockMvc.perform(multipart("/onboarding/jobs/menu-photo")
						.file(new MockMultipartFile("file", "menu.jpg", "image/jpeg", big))
						.header(HttpHeaders.AUTHORIZATION, store.bearer()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("ONBOARDING_PHOTO_TOO_LARGE"));
		assertThat(jobCount()).isZero();
	}

	@Test
	void 메뉴판_사진이_이미지가_아니면_400() throws Exception {
		mockMvc.perform(multipart("/onboarding/jobs/menu-photo")
						.file(new MockMultipartFile("file", "menu.txt", "text/plain", new byte[] {1, 2, 3}))
						.header(HttpHeaders.AUTHORIZATION, store.bearer()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("ONBOARDING_PHOTO_NOT_IMAGE"));
		assertThat(jobCount()).isZero();
	}

	@Test
	void 메뉴판_사진이_없으면_400() throws Exception {
		mockMvc.perform(multipart("/onboarding/jobs/menu-photo")
						.header(HttpHeaders.AUTHORIZATION, store.bearer()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("ONBOARDING_PHOTO_REQUIRED"));
	}

	@Test
	void 메뉴판_사진은_새_job_으로_LLM_에_사진을_넣는다() throws Exception {
		given(gmsClient.completeJson(any(LlmRequest.class))).willAnswer(invocation -> {
			LlmRequest request = invocation.getArgument(0);
			if ("menu_extraction".equals(request.schemaName())) {
				assertThat(request.images()).hasSize(1);
				return jsonMapper.readTree("{\"menus\":[{\"menu\":\"김치볶음밥\",\"kind\":\"MAIN\",\"duplicateOf\":null,"
						+ "\"components\":[{\"name\":\"쌀\",\"role\":\"BASE\",\"mult\":1},"
						+ "{\"name\":\"김치\",\"role\":\"VEGETABLE\",\"mult\":1}]}]}");
			}
			return jsonMapper.readTree("{\"items\":[]}");
		});

		MvcResult result = mockMvc.perform(multipart("/onboarding/jobs/menu-photo")
						.file(new MockMultipartFile("file", "menu.png", "image/png", new byte[] {1, 2, 3}))
						.header(HttpHeaders.AUTHORIZATION, store.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("QUEUED"))
				.andReturn();

		Map<String, Object> row = awaitFinished(
				Long.parseLong(JsonPath.read(result.getResponse().getContentAsString(), "$.data.id")));
		assertThat(row.get("status")).isEqualTo("DONE");
		var json = resultOf(row);
		assertThat(json.get("menus").get(0).get("name").asString()).isEqualTo("김치볶음밥");
		assertThat(json.get("menus").get(0).get("ingredientCount").asInt()).isEqualTo(2);
		assertThat(json.get("draft").get("menus").get(0).get("source").asString()).isEqualTo("MENU_PHOTO");
		verify(placeClient, never()).collectMenus(anyString());
	}
}
