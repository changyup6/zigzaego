package com.zigzaego.api.purchasedoc.entity;

/** 매입 문서 종류. V1 {@code purchase_doc.doc_type} CHECK 와 같다(사장님 사용 빈도 순). */
public enum DocType {
	STATEMENT, RECEIPT, KAKAO, MALL_ORDER, TAX_INVOICE, ORDER_FORM, LEDGER, QUOTE, STOCK_SHEET, POS_ERP
}
