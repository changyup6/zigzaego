package com.zigzaego.api.supplier.entity;

/** 배송 요일 코드. {@code supplier.delivery_days} 배열에 이 이름으로 저장한다. 선언 순서 = 요일 순서. */
public enum DeliveryDay {
	MON, TUE, WED, THU, FRI, SAT, SUN;

	/** 선언 순서가 요일 순서라 ISO 요일(월=1)과 1:1. */
	public java.time.DayOfWeek dayOfWeek() {
		return java.time.DayOfWeek.of(ordinal() + 1);
	}
}
