package com.zigzaego.api.global.web;

import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.exception.CommonErrorCode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/** 쿼리 파라미터의 날짜(YYYY-MM-DD, docs/API.md 4절)를 읽는다. */
public final class RequestDates {

	/** 정확히 YYYY-MM-DD. ISO 확장 연도(+10000-01-01, -0001-01-01)는 받지 않는다. */
	private static final Pattern DATE_PATTERN = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");

	private RequestDates() {
	}

	/**
	 * YYYY-MM-DD 형식이고 실제 있는 날짜만 받는다(2026-02-30 은 거절). null 은 null.
	 *
	 * @throws BusinessException 그 밖은 400 COMMON_INVALID_INPUT
	 */
	public static LocalDate parseOrNull(String text) {
		if (text == null) {
			return null;
		}
		if (!DATE_PATTERN.matcher(text).matches()) {
			throw new BusinessException(CommonErrorCode.INVALID_INPUT, "날짜는 YYYY-MM-DD 형식이어야 합니다.");
		}
		try {
			return LocalDate.parse(text);
		} catch (DateTimeParseException e) {
			throw new BusinessException(CommonErrorCode.INVALID_INPUT, "없는 날짜입니다: " + text);
		}
	}
}
