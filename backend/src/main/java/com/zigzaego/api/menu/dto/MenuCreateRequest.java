package com.zigzaego.api.menu.dto;

import com.zigzaego.api.menu.entity.MenuKind;
import com.zigzaego.api.menu.entity.MenuSource;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

@Schema(description = "메뉴 만들기")
public record MenuCreateRequest(
		@Schema(description = "메뉴 이름. 가게 안에서 겹치지 않는다", example = "김치찌개")
		@NotBlank @Size(max = 100)
		String name,

		@Schema(description = "MAIN=일반 / OPTION=유료 추가 옵션 / SET=세트. 없으면 MAIN", example = "MAIN", nullable = true)
		MenuKind kind,

		@Schema(description = "메뉴 설명", example = "돼지고기와 묵은지", nullable = true)
		String description,

		@Schema(description = "가져온 곳 (NAVER / YOGIYO / POS / MENU_PHOTO)", example = "NAVER")
		@NotNull
		MenuSource source,

		@Schema(description = "판매가(원, 0 이상 정수). 소수면 400", example = "12000", nullable = true)
		@PositiveOrZero @Digits(integer = 10, fraction = 0, message = "금액은 원 단위 정수여야 합니다.")
		BigDecimal price
) {
}
