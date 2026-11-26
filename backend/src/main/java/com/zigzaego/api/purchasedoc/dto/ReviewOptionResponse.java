package com.zigzaego.api.purchasedoc.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 확인 목록에서 고를 수 있는 재료 하나 (S15P31A308-113). */
@Schema(description = "확인 목록에서 고를 수 있는 재료 하나")
public record ReviewOptionResponse(
		@Schema(description = "재료 id (문자열)")
		String id,

		@Schema(description = "재료 이름")
		String name
) {
}
