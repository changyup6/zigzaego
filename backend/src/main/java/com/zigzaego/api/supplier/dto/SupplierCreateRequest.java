package com.zigzaego.api.supplier.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.zigzaego.api.supplier.entity.DeliveryDay;
import com.zigzaego.api.supplier.entity.SupplierChannel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

/** 이름 말고는 모두 비워도 된다 — 모르는 값은 나중에 채운다 (설계 6절). */
@Schema(description = "거래처 만들기")
public record SupplierCreateRequest(
		@Schema(description = "거래처 이름. 가게 안에서 겹치지 않는다", example = "한빛농산")
		@NotBlank @Size(max = 100)
		String name,

		@Schema(description = "주문 채널 KAKAO / PHONE / EMAIL / WEB / MALL(온라인 식자재몰)", example = "KAKAO", nullable = true)
		SupplierChannel channel,

		@Schema(description = "리드타임 L(일, 0 이상)", example = "1", nullable = true)
		@PositiveOrZero
		Integer leadTimeDays,

		@Schema(description = "발주 주기 R(일, 1 이상)", example = "2", nullable = true)
		@Positive
		Integer orderCycleDays,

		@Schema(description = "주문 마감 시각 HH:mm", example = "21:00", type = "string", nullable = true)
		@JsonFormat(pattern = "HH:mm")
		LocalTime cutoffTime,

		@Schema(description = "배송 요일 (MON ~ SUN)", example = "[\"MON\", \"WED\", \"FRI\"]", nullable = true)
		List<@NotNull DeliveryDay> deliveryDays,

		@Schema(description = "최소 주문금액(원, 0 이상 정수). 소수면 400", example = "50000", nullable = true)
		@PositiveOrZero @Digits(integer = 10, fraction = 0, message = "금액은 원 단위 정수여야 합니다.")
		BigDecimal minOrderAmount,

		@Schema(description = "주문 메시지 템플릿. 자리표시자 {가게} 가게 이름 · {배송일} 입고 예정일 · {품목} 품목 줄(\"- 상품명 수량단위\"). "
				+ "없으면 발주서를 만들 때 기본 문구를 쓴다 (3주 계획 4.7절)",
				example = "안녕하세요, {가게}입니다.\n{배송일} 배송으로 아래 부탁드립니다.\n{품목}\n감사합니다.", nullable = true)
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
