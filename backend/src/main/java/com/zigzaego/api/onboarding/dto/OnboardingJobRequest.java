package com.zigzaego.api.onboarding.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** {@code POST /onboarding/jobs} 본문. FE {@code OnboardingJobRequest} 와 같은 모양. placeId · naverMapUrl 중 하나는 있어야 한다. */
@Schema(description = "가게 분석 시작 — 가게 찾기 후보 id 또는 네이버 지도 링크")
public record OnboardingJobRequest(
		@Schema(description = "가게 찾기 후보 id (네이버 플레이스 id). 있으면 이것을 쓴다", example = "1668058454", nullable = true)
		String placeId,

		@Schema(description = "네이버 지도 가게 링크. placeId 가 없을 때만 쓴다 — place/숫자 · restaurant/숫자 꼴, naver.me 짧은 링크",
				example = "https://map.naver.com/p/entry/place/1668058454", nullable = true)
		String naverMapUrl,

		@Schema(description = "요기요도 함께 수집할지 (사장님 동의). 요기요 수집(S15P31A308-98) 전까지는 읽기만 하고 쓰지 않는다",
				example = "false")
		boolean includeYogiyo,

		@Schema(description = "고른 후보의 가게 이름. 있으면 가게 이름 · 화면 이름으로 쓴다(가입 때는 이메일 앞부분이라). 100자에서 자른다",
				example = "예시식당 잠실점", nullable = true)
		String placeName
) {

	/** 가게 이름 없이 부르는 쪽(테스트 · 링크 붙여넣기)용. */
	public OnboardingJobRequest(String placeId, String naverMapUrl, boolean includeYogiyo) {
		this(placeId, naverMapUrl, includeYogiyo, null);
	}
}
