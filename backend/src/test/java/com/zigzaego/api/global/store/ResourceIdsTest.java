package com.zigzaego.api.global.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.exception.CommonErrorCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ResourceIdsTest {

	@ParameterizedTest
	@ValueSource(strings = {"0", "15", "999999999999999999"})
	void 숫자_id는_그대로(String raw) {
		assertThat(ResourceIds.parse(raw)).isEqualTo(Long.parseLong(raw));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"abc", "-1", "+1", "1.0", " 1", "1e3", "9999999999999999999"})
	void 숫자가_아니면_404(String raw) {
		assertThatThrownBy(() -> ResourceIds.parse(raw))
				.isInstanceOfSatisfying(BusinessException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND));
	}
}
