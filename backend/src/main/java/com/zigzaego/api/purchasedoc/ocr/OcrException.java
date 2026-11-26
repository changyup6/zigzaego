package com.zigzaego.api.purchasedoc.ocr;

import com.zigzaego.api.global.exception.BusinessException;

/** 글자 인식이 재시도까지 다 실패했을 때. 코드는 {@link OcrErrorCode}. */
public class OcrException extends BusinessException {

	public OcrException(OcrErrorCode errorCode) {
		super(errorCode);
	}
}
