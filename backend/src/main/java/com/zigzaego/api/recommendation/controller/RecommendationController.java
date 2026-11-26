package com.zigzaego.api.recommendation.controller;

import com.zigzaego.api.auth.AuthUser;
import com.zigzaego.api.auth.LoginUser;
import com.zigzaego.api.global.response.ApiResponse;
import com.zigzaego.api.global.store.CurrentStoreProvider;
import com.zigzaego.api.global.web.RequestDates;
import com.zigzaego.api.recommendation.dto.RecommendationDecisionRequest;
import com.zigzaego.api.recommendation.dto.RecommendationResponse;
import com.zigzaego.api.recommendation.dto.RecommendationRunResponse;
import com.zigzaego.api.recommendation.service.RecommendationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Recommendation", description = "발주 추천 — 매일 04:00 배치 · 수동 실행 · 조회 · 승인 · 수정 · 거절")
@RestController
@RequestMapping("/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

	private final RecommendationService recommendationService;
	private final CurrentStoreProvider currentStoreProvider;

	@Operation(
			summary = "추천 생성 (수동 실행)",
			description = "로그인한 가게의 BOM · 1:1 재료마다 추정 재고 · 예상 소비 · 안전재고 · 가격 지수로 발주 판단을 하고, "
					+ "발주가 필요한 재료만 PENDING 추천으로 만든다. 주기형(CYCLE) 재료는 아직 만들지 않는다. "
					+ "(store, ingredient, run_date) 는 하루 한 건 — 같은 날 다시 실행하면 이미 있는 건은 그대로 두고(existingCount) "
					+ "새 재료만 만든다. 매일 04:00 배치가 같은 계산을 모든 가게에 돌린다. "
					+ "그날 끝난 실행 기록이 있으면 재고 추정 · 예측 · 소비량을 다시 계산하지 않고 기존 추천만 돌려준다"
					+ "(createdCount · noOrderCount 0). 실사 · 입고 · 레시피가 바뀐 뒤 다시 계산하려면 force=true — "
					+ "그래도 기존 건은 두고 없는 재료만 새로 계산한다. "
					+ "계산에 쓴 예측 모델(forecastModel: timesfm · dow_avg)과 안전재고 식(ssMethod: STAT · DAYS_N)을 함께 남긴다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "생성 결과와 그날 추천 전체")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
			description = "date 형식 틀림 · 없는 날짜 (COMMON_INVALID_INPUT)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@PostMapping("/run")
	public ApiResponse<RecommendationRunResponse> run(
			@Parameter(description = "생성 기준일 (YYYY-MM-DD). 없으면 가게 시간대의 오늘", example = "2026-10-10")
			@RequestParam(value = "date", required = false) String dateText,
			@Parameter(description = "그날 끝난 실행이 있어도 없는 재료를 다시 계산한다. 기본 false", example = "false")
			@RequestParam(value = "force", required = false, defaultValue = "false") boolean force) {
		return ApiResponse.ok(recommendationService.run(currentStoreProvider.currentStoreId(),
				RequestDates.parseOrNull(dateText), force));

	}

	@Operation(
			summary = "추천 목록 조회",
			description = "로그인한 가게의 그날 추천을 재료 id 오름차순으로 돌려준다. "
					+ "추정값이 섞인 추천은 needsReview=true 와 이유 코드 — UNANSWERED(AI 추정 레시피 줄이 남음) · "
					+ "NEEDS_COUNT(실사 기록 없음) · SUPPLIER_UNSET(거래처 리드타임 · 발주 주기가 없어 1일로 계산). "
					+ "수량은 재료 기준 단위, 소수 3자리.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "추천 목록")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
			description = "date 형식 틀림 · 없는 날짜 (COMMON_INVALID_INPUT)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@GetMapping
	public ApiResponse<List<RecommendationResponse>> list(
			@Parameter(description = "생성일 (YYYY-MM-DD). 없으면 가게 시간대의 오늘", example = "2026-10-10")
			@RequestParam(value = "date", required = false) String dateText) {
		return ApiResponse.ok(recommendationService.list(currentStoreProvider.currentStoreId(),
				RequestDates.parseOrNull(dateText)));
	}

	@Operation(
			summary = "추천 승인 · 수정 · 거절",
			description = "상태 전이는 PENDING → APPROVED | REJECTED 한 번만 — 이미 결정한 추천은 409, 다시 바꾸려면 새 추천. "
					+ "approve=추천 수량대로 승인(finalQty 를 보내면 그 값), modify=finalQty 로 수량을 바꿔 승인(APPROVED), "
					+ "reject=거절(reason 은 rejectReason 에 남는다). 결정한 계정(decided_by)과 시각(decidedAt)을 기록한다. "
					+ "승인 건의 finalQty 가 발주서 · 입고 예정 줄의 주문 수량이 된다 (POST /purchase-orders/from-recommendations).")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "결정이 반영된 추천")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
			description = "action 이 없거나 모르는 값 · finalQty 가 0 이하 · 소수 4자리 이상 (COMMON_INVALID_INPUT), "
					+ "modify 에 finalQty 없음 (RECOMMENDATION_FINAL_QTY_REQUIRED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
			description = "없거나 다른 가게의 추천 · 숫자가 아닌 id (COMMON_NOT_FOUND)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
			description = "이미 승인 · 거절한 추천 (RECOMMENDATION_ALREADY_DECIDED)")
	@PatchMapping("/{id}")
	public ApiResponse<RecommendationResponse> decide(
			@LoginUser AuthUser user,
			@Parameter(description = "추천 id", example = "12") @PathVariable String id,
			@Valid @RequestBody RecommendationDecisionRequest request) {
		return ApiResponse.ok(recommendationService.decide(user.storeId(), user.accountId(), id, request));
	}
}
