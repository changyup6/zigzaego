package com.zigzaego.api.onboarding;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.onboarding.client.ApifyNaverPlaceClient;
import com.zigzaego.api.onboarding.dto.PlaceCandidateResponse;
import com.zigzaego.api.onboarding.exception.OnboardingErrorCode;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class OnboardingControllerTest {

	@Autowired
	private WebApplicationContext context;

	@MockitoBean
	private ApifyNaverPlaceClient placeClient;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.apply(springSecurity())
				.build();
	}

	@Test
	void 후보를_FE_PlaceCandidate_모양으로_돌려준다() throws Exception {
		given(placeClient.searchPlaces("위글리 파스타볼")).willReturn(List.of(new PlaceCandidateResponse(
				"1668058454", "위글리 파스타볼", "서울특별시 강남구 테헤란로10길 25 1층 104호 위글리", null, false)));

		mockMvc.perform(get("/onboarding/places").param("query", "  위글리 파스타볼 ").with(user("owner")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data[0].id").value("1668058454"))
				.andExpect(jsonPath("$.data[0].name").value("위글리 파스타볼"))
				.andExpect(jsonPath("$.data[0].address").value("서울특별시 강남구 테헤란로10길 25 1층 104호 위글리"))
				.andExpect(jsonPath("$.data[0].photoUrl").isEmpty())
				.andExpect(jsonPath("$.data[0].yogiyoMatch").value(false));
	}

	@Test
	void 후보가_없으면_빈_목록() throws Exception {
		given(placeClient.searchPlaces(anyString())).willReturn(List.of());

		mockMvc.perform(get("/onboarding/places").param("query", "없는가게").with(user("owner")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data").isEmpty());
	}

	@Test
	void 검색어가_비면_400이고_Apify를_부르지_않는다() throws Exception {
		mockMvc.perform(get("/onboarding/places").param("query", "   ").with(user("owner")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
		verify(placeClient, never()).searchPlaces(anyString());
	}

	@Test
	void Apify_실패는_502() throws Exception {
		given(placeClient.searchPlaces(anyString()))
				.willThrow(new BusinessException(OnboardingErrorCode.APIFY_UNAVAILABLE));

		mockMvc.perform(get("/onboarding/places").param("query", "위글리").with(user("owner")))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.error.code").value("ONBOARDING_APIFY_UNAVAILABLE"));
	}

	@Test
	void 로그인하지_않으면_401() throws Exception {
		mockMvc.perform(get("/onboarding/places").param("query", "위글리"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("COMMON_UNAUTHORIZED"));
		verify(placeClient, never()).searchPlaces(anyString());
	}
}
