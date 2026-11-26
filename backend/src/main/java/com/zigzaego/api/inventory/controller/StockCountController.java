package com.zigzaego.api.inventory.controller;

import com.zigzaego.api.global.response.ApiResponse;
import com.zigzaego.api.global.store.CurrentStoreProvider;
import com.zigzaego.api.inventory.dto.InventoryEstimateResponse;
import com.zigzaego.api.inventory.dto.StockCountRequest;
import com.zigzaego.api.inventory.service.StockCountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Inventory")
@RestController
@RequiredArgsConstructor
public class StockCountController {

	private final StockCountService stockCountService;
	private final CurrentStoreProvider currentStoreProvider;

	@Operation(
			summary = "재고 실사 입력",
			description = "재료마다 지금 실제로 남은 양(기준 단위 개 · g · ml, 소수 셋째 자리)을 기록한다. 덮어쓰지 않고 쌓이며 "
					+ "가장 최근 실사가 추정 재고의 기준점이 된다 — 입력한 재료는 needsCount 가 꺼지고(음수로 내려가 0 보정된 재료는 다시 켜짐), "
					+ "가게 설정 체크리스트의 STOCK_COUNT 가 끝남으로 바뀌고, 이 시각이 영수증 PAST · RECEIVE 판정 기준일이 된다. "
					+ "실사는 영업 시작 전 값으로 본다 — 실사한 날의 판매량부터 뺀다(마감 뒤에 셌으면 다음 날 영업 전에 입력). "
					+ "countedAt 은 보통 생략한다(지금). 주기형(CYCLE) 재료는 입고 기록으로만 계산하므로 받지 않는다. "
					+ "응답은 바뀐 추정 재고 전체(GET /inventory/estimate 와 같은 모양).")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "기록 뒤의 재료별 추정 재고 목록")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "빈 목록 · 같은 재료 두 번 · qty 음수 · 소수 넷째 자리 이상 · "
			+ "countedAt 미래 · 그 재료의 기존 최신 실사보다 이름 · 주기형 재료 · CONFIRMED/CORRECTED 인데 estimatedQty 없음 (COMMON_INVALID_INPUT), "
			+ "method 가 모르는 값 · countedAt 에 시간대 없음 (COMMON_MALFORMED_REQUEST)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "다른 가게 · 없는 · 숫자 아닌 재료 id 가 하나라도 있음 — 아무것도 기록하지 않는다 (COMMON_NOT_FOUND)")
	@PostMapping("/stock-counts")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<List<InventoryEstimateResponse>> count(@Valid @RequestBody StockCountRequest request) {
		return ApiResponse.ok(stockCountService.count(currentStoreProvider.currentStoreId(), request));
	}
}
