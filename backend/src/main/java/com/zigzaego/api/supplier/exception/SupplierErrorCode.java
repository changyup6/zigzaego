package com.zigzaego.api.supplier.exception;

import com.zigzaego.api.global.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum SupplierErrorCode implements ErrorCode {

	/** 가게 안에서 거래처 이름은 하나 (uq_supplier_store_name). */
	DUPLICATE_NAME(HttpStatus.CONFLICT, "SUPPLIER_DUPLICATE_NAME", "같은 이름의 거래처가 이미 있습니다."),
	/** 거래처 상품 · 발주 · 매입 문서가 달린 거래처는 지우지 않는다. */
	IN_USE(HttpStatus.CONFLICT, "SUPPLIER_IN_USE", "상품 · 발주 · 매입 기록이 있는 거래처는 삭제할 수 없습니다."),
	/** 한 거래처 안에서 상품명은 하나 (uq_supplier_product_supplier_name). 영수증 상품명 사전의 키다. */
	DUPLICATE_PRODUCT_NAME(HttpStatus.CONFLICT, "SUPPLIER_PRODUCT_DUPLICATE_NAME", "이 거래처에 같은 이름의 상품이 이미 있습니다."),
	/** 추천 · 발주 · 매입 줄에 쓰인 상품은 지우지 않는다. */
	PRODUCT_IN_USE(HttpStatus.CONFLICT, "SUPPLIER_PRODUCT_IN_USE", "추천 · 발주 · 매입 기록이 있는 상품은 삭제할 수 없습니다.");

	private final HttpStatus status;
	private final String code;
	private final String message;
}
