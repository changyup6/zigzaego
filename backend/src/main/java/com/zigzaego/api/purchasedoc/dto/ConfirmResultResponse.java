package com.zigzaego.api.purchasedoc.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 영수증 줄 확정 결과 (S15P31A308-112). id 는 모두 문자열, 오름차순. */
@Schema(description = "영수증 줄 확정 결과")
public record ConfirmResultResponse(
		@Schema(description = "CONFIRMED 로 바꾼 줄")
		List<String> confirmedLineIds,

		@Schema(description = "IGNORED 로 바꾼 줄")
		List<String> ignoredLineIds,

		@Schema(description = "이번에 parse_status CONFIRMED 가 된 문서 (남은 PENDING 줄 0)")
		List<String> confirmedDocIds,

		@Schema(description = "새로 만든 주기형 재료")
		List<String> createdIngredientIds,

		@Schema(description = "새로 만든 사전(거래처 상품)")
		List<String> createdProductIds,

		@Schema(description = "새로 쌓은 가격 이력 행 수")
		int priceHistoryCount
) {
}
