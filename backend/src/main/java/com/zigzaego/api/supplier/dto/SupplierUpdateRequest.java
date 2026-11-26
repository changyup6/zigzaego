package com.zigzaego.api.supplier.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.zigzaego.api.supplier.entity.DeliveryDay;
import com.zigzaego.api.supplier.entity.SupplierChannel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

/** 보낸 칸만 바꾼다. null 이거나 빠진 칸은 그대로 둔다. */
@Schema(description = "거래처 고치기 — 보낸 칸만 바뀐다")
public record SupplierUpdateRequest(
		@Schema(description = "거래처 이름", example = "한빛농산", nullable = true)
		@Size(min = 1, max = 100) @Pattern(regexp = ".*\\S.*", message = "공백만으로 된 이름은 쓸 수 없습니다.")
		String name,

		@Schema(description = "주문 채널 KAKAO / PHONE / EMAIL / WEB / MALL", example = "PHONE", nullable = true)
		SupplierChannel channel,

		@Schema(description = "리드타임 L(일, 0 이상)", example = "1", nullable = true)
		@PositiveOrZero
		Integer leadTimeDays,

		@Schema(description = "발주 주기 R(일, 1 이상)", example = "3", nullable = true)
		@Positive
		Integer orderCycleDays,

		@Schema(description = "주문 마감 시각 HH:mm", example = "20:30", type = "string", nullable = true)
		@JsonFormat(pattern = "HH:mm")
		LocalTime cutoffTime,

		@Schema(description = "배송 요일 (MON ~ SUN). 빈 목록이면 모름으로 비운다", example = "[\"TUE\", \"THU\"]", nullable = true)
		List<@NotNull DeliveryDay> deliveryDays,

		@Schema(description = "최소 주문금액(원, 0 이상 정수)", example = "50000", nullable = true)
		@PositiveOrZero @Digits(integer = 10, fraction = 0, message = "금액은 원 단위 정수여야 합니다.")
		BigDecimal minOrderAmount,

		@Schema(description = "주문 메시지 템플릿. 자리표시자 {가게} · {배송일} · {품목} (SupplierCreateRequest 참고)", nullable = true)
		String messageTemplate,

		@Schema(description = "주문 이메일", example = "order@example.com", nullable = true)
		@Email @Size(max = 255)
		String email,

		@Schema(description = "배송비(원, 0 이상 정수)", example = "3000", nullable = true)
		@PositiveOrZero @Digits(integer = 10, fraction = 0, message = "금액은 원 단위 정수여야 합니다.")
		BigDecimal shippingFee,

		@Schema(description = "무료배송 최소 주문금액(원, 0 이상 정수)", example = "30000", nullable = true)
		@PositiveOrZero @Digits(integer = 10, fraction = 0, message = "금액은 원 단위 정수여야 합니다.")
		BigDecimal freeShippingMin
) {
}
