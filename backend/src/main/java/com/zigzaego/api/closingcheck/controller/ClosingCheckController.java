package com.zigzaego.api.closingcheck.controller;

import com.zigzaego.api.closingcheck.dto.ClosingCheckRequest;
import com.zigzaego.api.closingcheck.dto.ClosingItemResponse;
import com.zigzaego.api.closingcheck.service.ClosingCheckService;
import com.zigzaego.api.closingcheck.service.ClosingItemService;
import com.zigzaego.api.global.response.ApiResponse;
import com.zigzaego.api.global.store.CurrentStoreProvider;
import com.zigzaego.api.inventory.dto.InventoryEstimateResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 마감 재고 확인 (DECISIONS #88). */
@Tag(name = "ClosingCheck")
@RestController
@RequiredArgsConstructor
public class ClosingCheckController {

	private final ClosingCheckService closingCheckService;
	private final ClosingItemService closingItemService;
	private final CurrentStoreProvider currentStoreProvider;

	@Operation(
			summary = "마감 확인 대상 재료",
			description = "오늘 마감 때 확인할 재료를 최대 5개 돌려준다(DECISIONS #88 ②). 주기형 재료 · 오늘 이미 실사한 재료는 빼고, "
					+ "① 실사 필요(needsCount) ② 실사 차이 반복(#93) ③ 소진 예상일 3일 이내 ④ 마지막 실사 7일 넘음 중 하나에 걸리는 재료만, "
					+ "그 순서로. 같은 순위 안에서는 포장 단위가 없는 재료가 뒤로 간다. 포장은 그 재료의 가장 최근 입고 상품(없으면 대표 상품) 기준이고 "
					+ "pack_unit · base_qty_per_pack 이 둘 다 있어야 포장 단위(estimatedPacks 0.5 단위 반올림, step 0.5)로 묻는다. "
					+ "걸리는 재료가 없으면 빈 목록.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "확인할 재료 목록(0~5개)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@GetMapping("/closing-check/items")
	public ApiResponse<List<ClosingItemResponse>> items() {
		return ApiResponse.ok(closingItemService.items(currentStoreProvider.currentStoreId()));
	}

	@Operation(
			summary = "마감 확인 저장 (실사 + 빼기 기록)",
			description = "마감 때 확인한 남은 양(counts)과 버린 양(adjustments)을 한 트랜잭션에 기록한다. 빼기를 먼저(지금 − 1초) 넣고 실사를 "
					+ "지금 시각으로 넣는다 — 같은 재료에 둘 다 오면 실사가 최종값이다. 빼기는 추정 재고에서 바로 빠지고(남은 양 0 아래면 0), "
					+ "다음 실사가 들어오면 그 앞 빼기는 기준점에 흡수된다. 여기서 넣은 실사는 마감 뒤 값이라 추정 재고가 다음 날 판매부터 뺀다"
					+ "(DECISIONS #92). counts 줄의 estimatedQty 를 생략하면 서버의 지금 추정 재고를 넣는다. 주기형 재료는 받지 않는다. "
					+ "응답은 바뀐 추정 재고 전체(GET /inventory/estimate 와 같은 모양).")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "기록 뒤의 재료별 추정 재고 목록")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "counts · adjustments 둘 다 빔 · 같은 재료 실사 두 번 · "
			+ "qty 0 이하(빼기) · 음수(실사) · 소수 넷째 자리 이상 · 주기형 재료 · 메모 500자 · 원문 2000자 초과 (COMMON_INVALID_INPUT), "
			+ "reason · method 가 모르는 값 (COMMON_MALFORMED_REQUEST)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "다른 가게 · 없는 · 숫자 아닌 재료 id 가 하나라도 있음 — "
			+ "아무것도 기록하지 않는다 (COMMON_NOT_FOUND)")
	@PostMapping("/closing-check")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<List<InventoryEstimateResponse>> save(@Valid @RequestBody ClosingCheckRequest request) {
		return ApiResponse.ok(closingCheckService.save(currentStoreProvider.currentStoreId(), request));
	}
}
