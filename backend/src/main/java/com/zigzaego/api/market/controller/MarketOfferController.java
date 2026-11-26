package com.zigzaego.api.market.controller;

import com.zigzaego.api.global.response.ApiResponse;
import com.zigzaego.api.global.store.CurrentStoreProvider;
import com.zigzaego.api.market.dto.IngredientOffersResponse;
import com.zigzaego.api.market.dto.MarketPriceHistoryResponse;
import com.zigzaego.api.market.service.MarketOfferService;
import com.zigzaego.api.market.service.MarketPriceHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 재료별 식자재몰 최저가 · 일별 추이. 재료 CRUD · 단가 이력과 주소 앞부분만 같고 경로는 겹치지 않는다. */
@Tag(name = "Market", description = "재료별 식자재몰 최저가 · 구매 링크 · 일별 추이")
@RestController
@RequestMapping("/ingredients")
@RequiredArgsConstructor
public class MarketOfferController {

	private final MarketOfferService marketOfferService;
	private final MarketPriceHistoryService marketPriceHistoryService;
	private final CurrentStoreProvider currentStoreProvider;

	@Operation(
			summary = "재료별 식자재몰 최저가 · 구매 링크 조회",
			description = "로그인한 가게의 재료 하나를 식자재몰 시세(market_product)에서 찾아 정확히 같은 품목만(계란 검색에 계란찜 · 깐 계란 · 계란 찜기 없음) "
					+ "플랫폼(사이트)별 단가 순으로 준다. 재료 이름이 품목군(market_item_group)에 매핑되면 LLM 사전 분류 라벨(source LABEL)로, "
					+ "아니면 이름 검색 + 규칙(source RULE)으로 고른다(ADR-0012). "
					+ "unit = 품목군 기준 단위(없으면 정확한 상품이 가장 많은 단위). 개 ↔ g · ml ↔ g 는 품목군 환산값(계란 1개 60g, 간장 밀도 1.2)으로 맞춘다. "
					+ "sites = 사이트마다 가장 최근 수집일의 상위 3개(같은 상품번호는 가장 싼 점포 하나), 사이트 순서는 1위 상품 단가 순. "
					+ "같은 단위 중앙값의 5배 밖은 이상치로 빼고 excludedOutliers 에 센다. "
					+ "otherUnits = 비교 단위로 값을 낼 수 없는(환산값 없음) 사이트의 1위. "
					+ "단가는 원 / 단위 1(G · ML 은 원/g · 원/ml, EA 는 원/개) 소수 4자리(DECISIONS #57). 정확한 상품이 없으면 unit null · sites []. "
					+ "배달 가능 판매처(S15P31A308-230): 가게 시군구에 배달하지 않는 사이트 · 점포는 빼고 notDelivering 에, "
					+ "배송 지역 정보가 없는 사이트는 그대로 두고 deliveryUnknown 에 적는다('확인 필요').")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "식자재몰 최저가")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
			description = "재료가 없거나 다른 가게의 재료, 숫자 아닌 id (COMMON_NOT_FOUND)")
	@GetMapping("/{id}/offers")
	public ApiResponse<IngredientOffersResponse> offers(
			@Parameter(description = "재료 id (문자열)", example = "12") @PathVariable("id") String id) {
		return ApiResponse.ok(marketOfferService.offers(currentStoreProvider.currentStoreId(), id));
	}

	@Operation(
			summary = "재료별 식자재몰 일별 최저가 추이 조회",
			description = "최근 days 일(오늘 포함, 기본 30 · 최대 365) 동안 품목군 × 사이트 × 수집일의 최저 단가를 사이트별 선으로 준다. "
					+ "대상 · 단위 · 이상치 · 배달 규칙은 /offers 와 같고 날짜마다 적용한다. 수집이 없는 날은 점이 없다. "
					+ "사이트는 마지막 점 단가 순(싼 곳 먼저).")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "일별 최저가 추이")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "days 가 1~365 밖 (COMMON_INVALID_INPUT)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
			description = "재료가 없거나 다른 가게의 재료, 숫자 아닌 id (COMMON_NOT_FOUND)")
	@GetMapping("/{id}/market-price-history")
	public ApiResponse<MarketPriceHistoryResponse> marketPriceHistory(
			@Parameter(description = "재료 id (문자열)", example = "12") @PathVariable("id") String id,
			@Parameter(description = "오늘 포함 며칠 (1~365, 기본 30)", example = "30") @RequestParam(name = "days", required = false) Integer days) {
		return ApiResponse.ok(marketPriceHistoryService.history(currentStoreProvider.currentStoreId(), id, days));
	}
}
