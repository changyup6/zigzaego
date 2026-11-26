package com.zigzaego.api.account.dto;

import com.zigzaego.api.account.entity.Store;
import com.zigzaego.api.closingcheck.dto.ClosingTimeDto;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/** {@code GET /stores/me} 응답. */
@Schema(description = "내 가게와 설정 체크리스트")
public record StoreMeResponse(
		@Schema(description = "가게 id (문자열)", example = "1")
		String id,

		@Schema(description = "가게 이름", example = "위글리 역삼점")
		String name,

		@Schema(description = "화면에 보이는 가게 이름", example = "위글리")
		String displayName,

		@Schema(description = "가게 시간대", example = "Asia/Seoul")
		String timezone,

		@Schema(description = "가게 시간대의 오늘. 화면은 기기 날짜 대신 이 값을 오늘로 쓴다 (ADR-0019)", example = "2026-11-12")
		LocalDate today,

		@Schema(description = "가게가 있는 시군구(\"<시도> <시군구>\"). 식자재몰 배달 가능 판정에 쓴다. 아직 안 받았으면 null",
				example = "서울 중구", nullable = true)
		String sigungu,

		@Schema(description = "선택한 네이버 플레이스의 전체 주소. 배송 시군구와 별도이며 미확인 시 null",
				example = "서울특별시 중구 예시로 12", nullable = true)
		String address,

		@Schema(description = "식자재몰 가격 비교(발주 화면 품목 상세의 최저가)를 보일지. 기본 true", example = "true")
		boolean priceCompare,

		@Schema(description = "아직 끝나지 않은 단계 수. 0 이면 홈의 \"가게 설정 N개 남음\" 띠를 숨긴다", example = "4")
		int remainingSteps,

		@Schema(description = "체크리스트. 순서는 ONBOARDING · RECIPE · POS · SUPPLIER · FIRST_RECEIVE · STOCK_COUNT 고정")
		List<SetupStepResponse> checklist,

		@Schema(description = "요일별 마감 시각(요일 순). 빠진 요일은 휴무 또는 미설정 — 그날은 마감 확인 알림을 보내지 않는다. 비어 있으면 [] (DECISIONS #88)")
		List<ClosingTimeDto> closingTimes
) {

	public static StoreMeResponse of(Store store, LocalDate today, List<SetupStepResponse> checklist, List<ClosingTimeDto> closingTimes) {
		int remainingSteps = (int) checklist.stream().filter(step -> !step.done()).count();
		return new StoreMeResponse(
				String.valueOf(store.getId()),
				store.getName(),
				store.getDisplayName(),
				store.getTimezone(),
				today,
				store.getSigungu(),
				store.getAddress(),
				store.isPriceCompare(),
				remainingSteps,
				checklist,
				closingTimes);
	}
}
