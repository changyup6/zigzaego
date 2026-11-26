package com.zigzaego.api.purchaseorder.dto;

import com.zigzaego.api.purchaseorder.entity.PurchaseOrderSource;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * POST /purchase-orders/from-recommendations 본문. 본문 자체를 생략해도 된다 — 그러면 가게 시간대 오늘의 승인 건 전부.
 *
 * @param date              추천 생성일(YYYY-MM-DD). recommendationIds 가 없을 때만 쓴다. 없으면 오늘
 * @param recommendationIds 발주서로 만들 승인 건. 비우면 그날의 승인 건 중 아직 발주서가 안 된 것 전부
 * @param items             추천마다 주문처를 고른 목록 (S15P31A308-201). recommendationIds 와 같이 줄 수 없다
 */
@Schema(description = "승인 건 → 발주서. 본문을 생략하면 오늘의 승인 건 전부")
public record PurchaseOrderCreateRequest(
		@Schema(description = "추천 생성일 (YYYY-MM-DD). recommendationIds 가 없을 때만. 없으면 가게 시간대의 오늘",
				example = "2026-10-14", nullable = true)
		String date,

		@Schema(description = "발주서로 만들 승인 건 id 목록(문자열). 비우면 그날 승인 건 중 아직 발주서가 안 된 것 전부",
				example = "[\"12\", \"13\"]", nullable = true)
		@Size(max = 200)
		List<@NotBlank String> recommendationIds,

		@Schema(description = "추천마다 주문처를 고른 목록. 주면 그 추천만 발주서로 만든다 (recommendationIds 와 같이 못 줌 → 400). "
				+ "source 를 빼면 SUPPLIER", nullable = true)
		@Size(max = 200)
		List<@jakarta.validation.constraints.NotNull @Valid Item> items
) {

	/** 추천 하나와 그 줄의 주문처. */
	@Schema(description = "추천 하나와 주문처")
	public record Item(
			@Schema(description = "승인 건 id (문자열)", example = "12")
			@NotBlank
			String recommendationId,

			@Schema(description = "주문처. SUPPLIER = 등록 거래처(추천 상품의 거래처), SIKBOM = 식봄, COUPANG = 쿠팡, MARKETBOM = 마켓봄, ORDERLINK = 오더링크. 없으면 SUPPLIER",
					example = "SIKBOM", nullable = true)
			PurchaseOrderSource source
	) {
	}
}
