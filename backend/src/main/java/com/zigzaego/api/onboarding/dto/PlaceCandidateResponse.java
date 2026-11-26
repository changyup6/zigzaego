package com.zigzaego.api.onboarding.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 가게 찾기 후보 (설계 2.1 ①). 모양은 FE {@code PlaceCandidate} 타입과 같다.
 */
@Schema(description = "가게 찾기 후보 — 네이버 플레이스 검색 결과")
public record PlaceCandidateResponse(
		@Schema(description = "네이버 플레이스 id. 메뉴 수집 · POST /onboarding/jobs 의 placeId", example = "1668058454")
		String id,

		@Schema(description = "상호명", example = "위글리 파스타볼")
		String name,

		@Schema(description = "도로명 전체 주소. 없으면 지번", example = "서울특별시 강남구 테헤란로10길 25 1층 104호 위글리")
		String address,

		@Schema(description = "후보 카드에 보여줄 대표 사진 URL. 없으면 null", nullable = true)
		String photoUrl,

		@Schema(description = "요기요에서 같은 가게를 찾았는지. 요기요 매칭(S15P31A308-98) 전까지 항상 false", example = "false")
		boolean yogiyoMatch
) {
}
