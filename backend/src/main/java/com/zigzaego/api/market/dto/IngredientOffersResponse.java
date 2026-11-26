package com.zigzaego.api.market.dto;

import com.zigzaego.api.ingredient.entity.IngredientUnit;
import com.zigzaego.api.market.catalog.MarketItemGroup;
import com.zigzaego.api.market.delivery.DeliveryCoverage;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/** 재료 하나의 식자재몰 최저가. 단가는 원 / 기준 단위 1(G · ML 이면 원/g · 원/ml, EA 면 원/개), 소수 넷째 자리 (DECISIONS #57). */
@Schema(description = "재료별 식자재몰 최저가 · 구매 링크")
public record IngredientOffersResponse(
		@Schema(description = "재료 id (문자열)", example = "12")
		String ingredientId,

		@Schema(description = "시세를 찾은 검색어 = 재료 이름", example = "계란")
		String query,

		@Schema(description = "재료 이름이 매핑된 품목군(ADR-0012). 매핑이 없으면 null 이고 이름 검색 + 규칙으로 찾는다", nullable = true)
		ItemGroupInfo itemGroup,

		@Schema(description = "정확한 상품을 고른 방법. LABEL = 품목군 라벨(LLM 사전 분류), RULE = 이름 검색 + 규칙(ItemMatcher)", example = "LABEL",
				allowableValues = {"LABEL", "RULE"})
		String source,

		@Schema(description = "비교 단위. 품목군이 있으면 그 기준 단위, 없으면 정확한 상품이 가장 많은 단위 하나. 정확한 상품이 없으면 null", example = "EA",
				nullable = true)
		IngredientUnit unit,

		@Schema(description = "비교 단위가 재료 기준 단위와 같은지. 다르면(계란 재료는 G 인데 몰은 개 단위) toIngredientUnitFactor 로 바꿔 비교한다",
				example = "false")
		boolean matchesIngredientUnit,

		@Schema(description = "재료의 기준 단위 (ingredient.unit)", example = "G")
		IngredientUnit ingredientUnit,

		@Schema(description = "단가를 재료 단위로 바꾸는 계수: 원/재료 단위 1 = unitPrice × 이 값. 같은 단위면 1, 품목군 환산값으로 바꿀 수 없으면 null "
				+ "(계란 EA → G 는 1/60). 발주 화면의 '기존 거래처 단가' 와 나란히 놓을 때 쓴다", example = "0.0166667", nullable = true)
		java.math.BigDecimal toIngredientUnitFactor,

		@Schema(description = "시세 수집일(후보 중 가장 최근). 후보가 없으면 null", example = "2026-10-27", nullable = true)
		LocalDate collectedOn,

		@Schema(description = "비교 단위에서 이상치(같은 단위 중앙값의 5배 밖)로 뺀 상품 수", example = "2")
		int excludedOutliers,

		@ArraySchema(arraySchema = @Schema(description = "비교 단위의 사이트별 상위 3개. 사이트는 1위 상품 단가 순"))
		List<SiteOffers> sites,

		@ArraySchema(arraySchema = @Schema(description = "비교 단위로 값을 낼 수 없고 다른 단위로만 파는 사이트의 1위 (값은 비교하지 않는다)"))
		List<Offer> otherUnits,

		@Schema(description = "판정에 쓴 가게 시군구(PATCH /stores/me 의 sigungu). 없으면 null 이고 모든 판매처가 deliveryUnknown 에 들어간다",
				example = "서울 중구", nullable = true)
		String storeSigungu,

		@ArraySchema(arraySchema = @Schema(description = "'확인 필요' 판매처 — 배송 지역 정보가 없어 배달 가능 여부를 모르는 사이트(이름순). "
				+ "sites · otherUnits 에는 그대로 나온다"))
		List<String> deliveryUnknown,

		@ArraySchema(arraySchema = @Schema(description = "정확한 상품은 있지만 가게 시군구에 배달하지 않아 뺀 사이트(이름순)"))
		List<String> notDelivering
) {

	@Schema(description = "품목군 (market_item_group)")
	public record ItemGroupInfo(
			@Schema(description = "품목군 코드", example = "EGG") String code,
			@Schema(description = "품목군 이름", example = "계란") String name,
			@Schema(description = "값을 비교하는 기준 단위", example = "EA") IngredientUnit baseUnit) {

		public static ItemGroupInfo of(MarketItemGroup g) {
			return new ItemGroupInfo(g.code(), g.name(), g.baseUnit());
		}
	}

	@Schema(description = "사이트 하나의 상위 상품")
	public record SiteOffers(
			@Schema(description = "사이트", example = "식봄") String site,
			@Schema(description = "그 사이트 1위 상품의 단가", example = "189.6667") java.math.BigDecimal bestUnitPrice,
			@Schema(description = "배달 판정. AVAILABLE = 가게 시군구에 배달, UNKNOWN = 배송 지역 정보 없음('확인 필요'). BLOCKED 는 응답에 나오지 않는다",
					example = "AVAILABLE") DeliveryCoverage.Status delivery,
			@Schema(description = "새벽배송 여부. 모르면 null", example = "true", nullable = true) Boolean dawnDelivery,
			@Schema(description = "이 사이트 1위 상품의 수집일. 오늘이 아니면 화면이 '(n일 전)' 처럼 표시할 수 있다", example = "2026-10-27")
			LocalDate collectedOn,
			@ArraySchema(arraySchema = @Schema(description = "단가 오름차순, 같은 상품번호는 가장 싼 점포 하나")) List<Offer> offers,
			@Schema(description = "시연 합성 가격 여부") boolean synthetic) {
		public SiteOffers(String site, java.math.BigDecimal bestUnitPrice, DeliveryCoverage.Status delivery, Boolean dawnDelivery, LocalDate collectedOn, List<Offer> offers) {
			this(site, bestUnitPrice, delivery, dawnDelivery, collectedOn, offers, false);
		}
	}

	@Schema(description = "상품 하나")
	public record Offer(
			@Schema(description = "사이트", example = "식봄") String site,
			@Schema(description = "점포지역 (식자재왕몰 점포 · 식봄 배송지). 없으면 null", example = "서울 중구", nullable = true) String region,
			@Schema(description = "상품명", example = "계란(중란/30알/국내산)판") String productName,
			@Schema(description = "규격 글자 그대로. 없으면 null", example = "1판/30구 / 판", nullable = true) String spec,
			@Schema(description = "판매가 (원 정수)", example = "5690") long price,
			@Schema(description = "단가의 단위 (원 / 이 단위 1)", example = "EA") IngredientUnit unit,
			@Schema(description = "단가 (원 / 단위 1, 소수 넷째 자리)", example = "189.6667") java.math.BigDecimal unitPrice,
			@Schema(description = "상품 페이지 링크", example = "https://www.foodspring.co.kr/goods/detail/1") String url,
			@Schema(description = "라벨이 본 형태. RAW = 원물, PREPARED = 손질 · 절단 · 냉동 원물. 라벨이 없으면 null", example = "RAW", nullable = true)
			String form) {
	}
}
