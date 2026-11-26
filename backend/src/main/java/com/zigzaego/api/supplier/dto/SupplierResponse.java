package com.zigzaego.api.supplier.dto;

import static com.zigzaego.api.global.response.Amounts.won;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.zigzaego.api.supplier.entity.DeliveryDay;
import com.zigzaego.api.supplier.entity.Supplier;
import com.zigzaego.api.supplier.entity.SupplierChannel;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalTime;
import java.util.List;

/** 거래처 하나. 목록 · 조회 · 생성 · 수정 응답이 같다. 모르는 값은 null. */
@Schema(description = "거래처 하나")
public record SupplierResponse(
		@Schema(description = "거래처 id (문자열)", example = "4")
		String id,

		@Schema(description = "거래처 이름", example = "한빛농산")
		String name,

		@Schema(description = "주문 채널. 모르면 null", example = "KAKAO", nullable = true)
		SupplierChannel channel,

		@Schema(description = "리드타임 L(일). 모르면 null", example = "1", nullable = true)
		Integer leadTimeDays,

		@Schema(description = "발주 주기 R(일). 모르면 null", example = "2", nullable = true)
		Integer orderCycleDays,

		@Schema(description = "주문 마감 시각 HH:mm. 모르면 null", example = "21:00", type = "string", nullable = true)
		@JsonFormat(pattern = "HH:mm")
		LocalTime cutoffTime,

		@Schema(description = "배송 요일 (요일 순). 모르면 빈 목록", example = "[\"MON\", \"WED\", \"FRI\"]")
		List<DeliveryDay> deliveryDays,

		@Schema(description = "최소 주문금액(원, 정수)", example = "50000", nullable = true)
		Long minOrderAmount,

		@Schema(description = "주문 메시지 템플릿", nullable = true)
		String messageTemplate,

		@Schema(description = "주문 이메일", example = "order@example.com", nullable = true)
		String email,

		@Schema(description = "배송비(원, 정수)", example = "3000", nullable = true)
		Long shippingFee,

		@Schema(description = "무료배송 최소 주문금액(원, 정수)", example = "30000", nullable = true)
		Long freeShippingMin
) {

	public static SupplierResponse of(Supplier supplier) {
		return new SupplierResponse(String.valueOf(supplier.getId()), supplier.getName(), supplier.getChannel(),
				supplier.getLeadTimeDays(), supplier.getOrderCycleDays(), supplier.getCutoffTime(),
				supplier.deliveryDayList(), won(supplier.getMinOrderAmount()), supplier.getMessageTemplate(),
				supplier.getEmail(), won(supplier.getShippingFee()), won(supplier.getFreeShippingMin()));
	}
}
