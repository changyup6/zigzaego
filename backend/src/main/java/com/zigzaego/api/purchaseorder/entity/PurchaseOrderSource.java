package com.zigzaego.api.purchaseorder.entity;

/** 발주서 주문처. 가격 비교는 스냅샷만 사용하며 외부 주문은 하지 않는다. */
public enum PurchaseOrderSource {
	SUPPLIER(null), SIKBOM("식봄"), COUPANG("쿠팡"), MARKETBOM("마켓봄"), ORDERLINK("오더링크");
	private final String site;
	PurchaseOrderSource(String site) { this.site = site; }
	public String site() { return site; }
}
