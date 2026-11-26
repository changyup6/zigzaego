package com.zigzaego.api.inventory.controller;

import com.zigzaego.api.global.response.ApiResponse;
import com.zigzaego.api.global.store.CurrentStoreProvider;
import com.zigzaego.api.inventory.dto.InventoryEstimateResponse;
import com.zigzaego.api.inventory.service.InventoryEstimateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Inventory", description = "실사 · 입고 · 판매로 계산한 재료별 추정 재고")
@RestController
@RequestMapping("/inventory")
@RequiredArgsConstructor
public class InventoryController {

	private final InventoryEstimateService inventoryEstimateService;
	private final CurrentStoreProvider currentStoreProvider;

	@Operation(
			summary = "재료별 추정 재고 · 소진 예상일 조회",
			description = "로그인한 가게의 모든 재료를 재료 id 오름차순으로 돌려준다. "
					+ "BOM · 1:1 = 마지막 실사 + 그 뒤 입고(입고 확인된 발주 줄 + 확인된 RECEIVE 매입 문서 줄, PAST 문서 제외) "
					+ "− 그 뒤 소비(판매 × 레시피). 실사 기록이 없으면 0 기준(NO_COUNT). "
					+ "주기형(CYCLE)은 입고 기록으로만 계산한다 — 입고 2건 이상이면 그릇당 소비량(CYCLE_SALES) 또는 평균 입고 간격(CYCLE_INTERVAL), "
					+ "1건 이하면 수집 중(CYCLE_COLLECTING). "
					+ "음수는 0 으로 보정하고 needsCount=true. 소진 예상일은 최근 4주 같은 요일 평균 소비(휴무일 제외)로 하루씩 뺀 날. "
					+ "수량은 재료 기준 단위, 소수 3자리.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "재료별 추정 재고 목록")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@GetMapping("/estimate")
	public ApiResponse<List<InventoryEstimateResponse>> estimate() {
		return ApiResponse.ok(inventoryEstimateService.estimate(currentStoreProvider.currentStoreId()));
	}
}
