package com.zigzaego.api.price.controller;

import com.zigzaego.api.global.response.ApiResponse;
import com.zigzaego.api.global.store.CurrentStoreProvider;
import com.zigzaego.api.price.dto.PriceHistoryResponse;
import com.zigzaego.api.price.service.PriceHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 재료 단가 이력 · 가격 지수. 재료 CRUD({@code IngredientController})와 주소 앞부분만 같고 경로는 겹치지 않는다. */
@Tag(name = "Price", description = "재료 단가 이력 · 가격 지수")
@RestController
@RequestMapping("/ingredients")
@RequiredArgsConstructor
public class PriceHistoryController {

	private final PriceHistoryService priceHistoryService;
	private final CurrentStoreProvider currentStoreProvider;

	@Operation(
			summary = "재료 단가 이력 · 가격 지수 조회",
			description = "로그인한 가게의 재료 하나에 연결된 모든 거래처 상품의 단가 이력(출처 RECEIVE · DOC · MANUAL, "
					+ "MARKET 제외)을 단위 단가(포장 1개 값 ÷ base_qty_per_pack, 원 / 재료 기준 단위)로 합쳐 준다. "
					+ "포장 환산(base_qty_per_pack)을 모르는 상품의 이력은 빠진다. "
					+ "observations = 최근 8주(오늘 포함 56일) 관측, 날짜 내림차순, 최대 200건. "
					+ "weekly = 7일 칸 8개(마지막 칸 = 오늘 − 6일 ~ 오늘)의 중앙값, 관측 없는 주는 null. "
					+ "index = 가격 지수(현재 단가 ÷ 최근 8주 기준 단가). 단가 · 지수는 소수 4자리 숫자(DECISIONS #57). "
					+ "오늘 = 서울 날짜. 이력이 없으면 weekly 8칸 null · observations [] · index NO_RECENT.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "단가 이력 · 가격 지수")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
			description = "재료가 없거나 다른 가게의 재료, 숫자 아닌 id (COMMON_NOT_FOUND)")
	@GetMapping("/{id}/price-history")
	public ApiResponse<PriceHistoryResponse> priceHistory(
			@Parameter(description = "재료 id (문자열)", example = "12") @PathVariable("id") String id) {
		return ApiResponse.ok(priceHistoryService.get(currentStoreProvider.currentStoreId(), id));
	}
}
