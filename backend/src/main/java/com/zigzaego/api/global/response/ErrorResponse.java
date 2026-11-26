package com.zigzaego.api.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.zigzaego.api.global.exception.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "오류 정보")
public record ErrorResponse(
		@Schema(description = "오류 코드 (UPPER_SNAKE_CASE)", example = "COMMON_INVALID_INPUT")
		String code,

		@Schema(description = "사용자에게 보여줄 수 있는 메시지", example = "입력값이 올바르지 않습니다.")
		String message,

		@Schema(description = "필드별 검증 오류. 검증 실패일 때만 포함")
		@JsonInclude(JsonInclude.Include.NON_EMPTY)
		List<FieldError> fieldErrors
) {

	public static ErrorResponse of(ErrorCode errorCode) {
		return new ErrorResponse(errorCode.getCode(), errorCode.getMessage(), List.of());
	}

	public static ErrorResponse of(ErrorCode errorCode, String message) {
		return new ErrorResponse(errorCode.getCode(), message, List.of());
	}

	public static ErrorResponse of(ErrorCode errorCode, List<FieldError> fieldErrors) {
		return new ErrorResponse(errorCode.getCode(), errorCode.getMessage(), fieldErrors);
	}

	@Schema(description = "필드 검증 오류")
	public record FieldError(
			@Schema(description = "필드 이름", example = "quantity")
			String field,

			@Schema(description = "거부된 값", example = "-1")
			String rejectedValue,

			@Schema(description = "오류 사유", example = "0 이상이어야 합니다")
			String reason
	) {
	}
}
