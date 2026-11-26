package com.zigzaego.api.global.store;

import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.exception.CommonErrorCode;
import java.util.regex.Pattern;

/** URL 의 리소스 id(문자열)를 숫자로 바꾼다. 숫자가 아닌 id 는 없는 리소스와 같게 404 로 답한다. */
public final class ResourceIds {

	private static final Pattern NUMERIC_ID = Pattern.compile("\\d{1,18}");

	private ResourceIds() {
	}

	/**
	 * @throws BusinessException 숫자가 아니거나 비었으면 404 COMMON_NOT_FOUND
	 */
	public static long parse(String rawId) {
		if (rawId == null || !NUMERIC_ID.matcher(rawId).matches()) {
			throw new BusinessException(CommonErrorCode.NOT_FOUND);
		}
		return Long.parseLong(rawId);
	}
}
