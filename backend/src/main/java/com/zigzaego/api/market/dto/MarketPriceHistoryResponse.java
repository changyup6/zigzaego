package com.zigzaego.api.market.dto;

import com.zigzaego.api.ingredient.entity.IngredientUnit;
import com.zigzaego.api.market.delivery.DeliveryCoverage;
import com.zigzaego.api.market.dto.IngredientOffersResponse.ItemGroupInfo;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 재료 하나의 식자재몰 일별 최저가 추이 (품목군 × 사이트 × 수집일). 단가는 원 / 단위 1, 소수 넷째 자리 (DECISIONS #57). */
@Schema(description = "재료별 식자재몰 일별 최저가 추이")
public record MarketPriceHistoryResponse(
		@Schema(description = "재료 id (문자열)", example = "12") String ingredientId,
		@Schema(description = "검색어 = 재료 이름", example = "계란") String query,
		@Schema(description = "매핑된 품목군. 없으면 null (이름 검색 + 규칙으로 집계)", nullable = true) ItemGroupInfo itemGroup,
		@Schema(description = "LABEL = 품목군 라벨, RULE = 이름 검색 + 규칙", example = "LABEL", allowableValues = {"LABEL", "RULE"}) String source,
		@Schema(description = "단가 단위. 품목군이 있으면 기준 단위, 없으면 정확한 상품이 가장 많은 단위. 점이 하나도 없으면 null", example = "EA",
				nullable = true) IngredientUnit unit,
		@Schema(description = "재료의 기준 단위", example = "G") IngredientUnit ingredientUnit,
		@Schema(description = "원/재료 단위 1 = unitPrice × 이 값. 같은 단위면 1, 바꿀 수 없으면 null", example = "0.0166667", nullable = true)
		BigDecimal toIngredientUnitFactor,
		@Schema(description = "조회 시작일(포함)", example = "2026-10-01") LocalDate from,
		@Schema(description = "조회 끝일(포함) = 오늘", example = "2026-10-31") LocalDate to,
		@Schema(description = "판정에 쓴 가게 시군구. 없으면 null", example = "서울 중구", nullable = true) String storeSigungu,
		@ArraySchema(arraySchema = @Schema(description = "'확인 필요' 사이트(이름순)")) List<String> deliveryUnknown,
		@ArraySchema(arraySchema = @Schema(description = "가게 시군구에 배달하지 않아 뺀 사이트(이름순)")) List<String> notDelivering,
		@ArraySchema(arraySchema = @Schema(description = "사이트별 선. 마지막 점 단가 순(싼 사이트 먼저)")) List<SiteSeries> sites
) {

	@Schema(description = "사이트 하나의 일별 최저가")
	public record SiteSeries(
			@Schema(description = "사이트", example = "식봄") String site,
			@Schema(description = "배달 판정", example = "AVAILABLE") DeliveryCoverage.Status delivery,
			@Schema(description = "새벽배송 여부. 모르면 null", nullable = true) Boolean dawnDelivery,
			@ArraySchema(arraySchema = @Schema(description = "날짜 오름차순. 수집이 없는 날은 점이 없다(0 으로 채우지 않는다)")) List<Point> points,
			@Schema(description = "시연 합성 가격 여부") boolean synthetic) {
		public SiteSeries(String site, DeliveryCoverage.Status delivery, Boolean dawnDelivery, List<Point> points) {
			this(site, delivery, dawnDelivery, points, false);
		}
	}

	@Schema(description = "하루의 최저가")
	public record Point(
			@Schema(description = "수집일", example = "2026-10-27") LocalDate date,
			@Schema(description = "그날 그 사이트의 최저 단가 (원 / 단위 1)", example = "189.6667") BigDecimal unitPrice,
			@Schema(description = "그 상품의 판매가 (원)", example = "5690") long price,
			@Schema(description = "그 상품명", example = "계란(중란/30알/국내산)판") String productName) {
	}
}
