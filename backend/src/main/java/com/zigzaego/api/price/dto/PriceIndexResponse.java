package com.zigzaego.api.price.dto;

import com.zigzaego.api.domain.price.PriceIndexCalculator.PriceIndex;
import com.zigzaego.api.domain.price.PriceIndexCalculator.PriceIndexReason;
import com.zigzaego.api.domain.price.PriceIndexCalculator.PriceLevel;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/** {@code PriceIndexCalculator} 결과 그대로. 금액 · 지수는 소수 4자리 반올림. */
@Schema(description = "가격 지수 (현재 단가 ÷ 최근 8주 기준 단가)")
public record PriceIndexResponse(
		@Schema(description = "가격 수준", example = "HIGH")
		PriceLevel level,

		@Schema(description = "가격 지수 (소수 4자리). 이력 부족 · 최근 이력 없음이면 1", example = "1.1429")
		BigDecimal index,

		@Schema(description = "기준 단가(원 / 기준 단위, 소수 4자리). 계산할 수 없으면 null", example = "4.2000", nullable = true)
		BigDecimal basePrice,

		@Schema(description = "현재 단가(원 / 기준 단위, 소수 4자리). 최근 8주에 없으면 null", example = "4.8000", nullable = true)
		BigDecimal currentPrice,

		@Schema(description = "기준 기간 표본 수 (같은 날은 1건)", example = "5")
		int sampleCount,

		@Schema(description = "OK = 계산함, TOO_FEW = 기준 기간 표본 3건 미만(지수 1), NO_RECENT = 최근 8주 단가 없음(지수 1)",
				example = "OK")
		PriceIndexReason reason
) {

	public static PriceIndexResponse of(PriceIndex index) {
		return new PriceIndexResponse(index.level(), UnitPrices.round(index.index()), UnitPrices.round(index.basePrice()),
				UnitPrices.round(index.currentPrice()), index.sampleCount(), index.reason());
	}
}
