package com.zigzaego.api.menu.dto;

import com.zigzaego.api.menu.entity.MenuKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** 보낸 칸만 바꾼다. null 이거나 빠진 칸은 그대로 둔다. */
@Schema(description = "메뉴 고치기 — 보낸 칸만 바뀐다")
public record MenuUpdateRequest(
		@Schema(description = "메뉴 이름", example = "묵은지 김치찌개", nullable = true)
		@Size(min = 1, max = 100) @Pattern(regexp = ".*\\S.*", message = "공백만으로 된 이름은 쓸 수 없습니다.")
		String name,

		@Schema(description = "MAIN / OPTION / SET", example = "MAIN", nullable = true)
		MenuKind kind,

		@Schema(description = "판매 중인지. 판매 기록이 있는 메뉴는 삭제 대신 false 로 바꾼다", example = "false", nullable = true)
		Boolean active,

		@Schema(description = "메뉴 설명", example = "돼지고기와 묵은지", nullable = true)
		String description,

		@Schema(description = "판매가(원, 0 이상 정수). 소수면 400", example = "13000", nullable = true)
		@PositiveOrZero @Digits(integer = 10, fraction = 0, message = "금액은 원 단위 정수여야 합니다.")
		BigDecimal price
) {
}
