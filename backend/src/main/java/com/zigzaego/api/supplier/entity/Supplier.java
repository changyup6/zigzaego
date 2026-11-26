package com.zigzaego.api.supplier.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 가게의 거래처 하나. 표는 V1 {@code supplier}.
 * 채널 · 리드타임 · 발주 주기는 영수증으로 처음 생긴 거래처면 모를 수 있어 null 이다 (설계 6절).
 * 금액 칸은 NUMERIC(12,2)지만 원 단위 정수만 넣는다 (DECISIONS #55).
 */
@Entity
@Table(name = "supplier")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Supplier {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "store_id", nullable = false)
	private Long storeId;

	@Column(nullable = false, length = 100)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(length = 10)
	private SupplierChannel channel;

	/** 리드타임 L(일). */
	@Column(name = "lead_time_days")
	private Integer leadTimeDays;

	/** 발주 주기 R(일). */
	@Column(name = "order_cycle_days")
	private Integer orderCycleDays;

	/** 주문 마감 시각. */
	@Column(name = "cutoff_time")
	private LocalTime cutoffTime;

	/** 배송 요일 코드 배열 ({@link DeliveryDay} 이름). 모르면 null. */
	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "delivery_days", columnDefinition = "text[]")
	private String[] deliveryDays;

	/** 배송 요일을 {@link java.time.DayOfWeek} 집합으로. 모르면(null · 빈 배열) 빈 집합 = 매일 배송으로 본다. */
	public java.util.Set<java.time.DayOfWeek> deliveryDaysOfWeek() {
		if (deliveryDays == null) {
			return java.util.Set.of();
		}
		java.util.EnumSet<java.time.DayOfWeek> days = java.util.EnumSet.noneOf(java.time.DayOfWeek.class);
		for (String code : deliveryDays) {
			days.add(DeliveryDay.valueOf(code).dayOfWeek());
		}
		return days;
	}

	@Column(name = "min_order_amount", precision = 12, scale = 2)
	private BigDecimal minOrderAmount;

	@Column(name = "message_template", columnDefinition = "TEXT")
	private String messageTemplate;

	@Column(length = 255)
	private String email;

	@Column(name = "shipping_fee", precision = 12, scale = 2)
	private BigDecimal shippingFee;

	@Column(name = "free_shipping_min", precision = 12, scale = 2)
	private BigDecimal freeShippingMin;

	public static Supplier create(long storeId, String name) {
		Supplier supplier = new Supplier();
		supplier.storeId = storeId;
		supplier.name = name;
		return supplier;
	}

	/** 요일 순서로. 모르면 빈 목록. */
	public List<DeliveryDay> deliveryDayList() {
		return deliveryDays == null ? List.of()
				: Arrays.stream(deliveryDays).map(DeliveryDay::valueOf).sorted().toList();
	}

	public void rename(String name) {
		this.name = name;
	}

	public void changeChannel(SupplierChannel channel) {
		this.channel = channel;
	}

	public void changeLeadTimeDays(Integer leadTimeDays) {
		this.leadTimeDays = leadTimeDays;
	}

	public void changeOrderCycleDays(Integer orderCycleDays) {
		this.orderCycleDays = orderCycleDays;
	}

	public void changeCutoffTime(LocalTime cutoffTime) {
		this.cutoffTime = cutoffTime;
	}

	/** 요일 순서로, 겹치지 않게 저장한다. 빈 목록은 null(모름). */
	public void changeDeliveryDays(Collection<DeliveryDay> days) {
		this.deliveryDays = days.isEmpty() ? null
				: days.stream().distinct().sorted().map(DeliveryDay::name).toArray(String[]::new);
	}

	public void changeMinOrderAmount(BigDecimal minOrderAmount) {
		this.minOrderAmount = minOrderAmount;
	}

	public void changeMessageTemplate(String messageTemplate) {
		this.messageTemplate = messageTemplate;
	}

	public void changeEmail(String email) {
		this.email = email;
	}

	public void changeShippingFee(BigDecimal shippingFee) {
		this.shippingFee = shippingFee;
	}

	public void changeFreeShippingMin(BigDecimal freeShippingMin) {
		this.freeShippingMin = freeShippingMin;
	}
}
