package com.zigzaego.api.account.controller;

import com.zigzaego.api.account.dto.StoreMeResponse;
import com.zigzaego.api.account.dto.StoreUpdateRequest;
import com.zigzaego.api.account.service.StoreService;
import com.zigzaego.api.closingcheck.dto.ClosingTimesRequest;
import com.zigzaego.api.global.response.ApiResponse;
import com.zigzaego.api.global.store.CurrentStoreProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Store", description = "내 가게")
@RestController
@RequestMapping("/stores")
@RequiredArgsConstructor
public class StoreController {

	private final StoreService storeService;
	private final CurrentStoreProvider currentStoreProvider;

	@Operation(
			summary = "내 가게와 설정 체크리스트",
			description = "로그인한 계정의 가게 정보와 가게 설정 체크리스트. 체크리스트는 조회 때 데이터로 판정한다. "
					+ "ONBOARDING=메뉴가 저장돼 있음 / RECIPE=가장 최근 끝난 온보딩 job 의 result_json.questions[] 중 "
					+ "answer 가 null 이거나 없는 질문 수가 remaining (질문이 없으면 레시피 줄이 있을 때 끝) / "
					+ "POS=포스기 업체 설정 또는 판매 기록 / SUPPLIER=거래처 1곳 이상 / "
					+ "FIRST_RECEIVE=발주 입고 확인 또는 사장님이 확인한 입고 문서 / STOCK_COUNT=재고 실사 기록. "
					+ "RECIPE 외 단계의 remaining 은 끝났으면 0, 아니면 1.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "내 가게와 체크리스트")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "토큰의 가게가 없음 (COMMON_NOT_FOUND)")
	@GetMapping("/me")
	public ApiResponse<StoreMeResponse> me() {
		return ApiResponse.ok(storeService.getMine(currentStoreProvider.currentStoreId()));
	}

	@Operation(
			summary = "내 가게 고치기",
			description = "보낸 칸만 바꾼다. sigungu = 가게가 있는 시군구(\"<시도> <시군구>\", 예 \"서울 중구\"). "
					+ "식자재몰 최저가(GET /ingredients/{id}/offers)에서 배달 가능한 판매처만 남기는 데 쓴다. "
					+ "빈 글자를 보내면 지운다(모든 판매처가 '확인 필요'로 돌아간다). "
					+ "priceCompare = 식자재몰 가격 비교를 보일지(true/false, 다른 API 응답은 바꾸지 않는다). 응답은 GET /stores/me 와 같다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "고친 뒤의 내 가게와 체크리스트")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "시군구 형식 오류 (COMMON_INVALID_INPUT), priceCompare 가 true/false 가 아님 (COMMON_MALFORMED_REQUEST)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "토큰의 가게가 없음 (COMMON_NOT_FOUND)")
	@PatchMapping("/me")
	public ApiResponse<StoreMeResponse> updateMe(@Valid @RequestBody StoreUpdateRequest request) {
		return ApiResponse.ok(storeService.updateMine(currentStoreProvider.currentStoreId(), request));
	}

	@Operation(
			summary = "요일별 마감 시각 바꾸기",
			description = "영업하는 요일의 마감 시각 전체를 보낸다(요일 월=1 … 일=7, HH:mm, 가게 시간대). 빠진 요일은 휴무 — 그날은 마감 확인 알림을 "
					+ "보내지 않는다. 종료가 자정을 넘으면 영업을 시작한 요일에 넣는다(05:00 전 시각은 다음 날 새벽으로 본다). 빈 목록이면 전부 지운다. "
					+ "응답은 GET /stores/me 와 같다 (DECISIONS #88 ①).")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "바꾼 뒤의 내 가게")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "요일이 1~7 밖 · 시각이 HH:mm 아님 · 같은 요일 두 번 · 8줄 이상 (COMMON_INVALID_INPUT)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@PutMapping("/me/closing-times")
	public ApiResponse<StoreMeResponse> replaceClosingTimes(@Valid @RequestBody ClosingTimesRequest request) {
		return ApiResponse.ok(storeService.replaceClosingTimes(currentStoreProvider.currentStoreId(), request.closingTimes()));
	}
}
