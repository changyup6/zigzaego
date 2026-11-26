package com.zigzaego.api.global.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 모든 API 응답의 공통 래퍼. 성공이면 {@code data}, 실패면 {@code error}만 채운다.
 */
@Schema(description = "공통 응답 래퍼")
public record ApiResponse<T>(
		@Schema(description = "요청 성공 여부", example = "true")
		boolean success,

		@Schema(description = "성공 시 응답 데이터. 실패 시 null")
		T data,

		@Schema(description = "실패 시 오류 정보. 성공 시 null")
		ErrorResponse error
) {

	public static <T> ApiResponse<T> ok(T data) {
		return new ApiResponse<>(true, data, null);
	}

	public static ApiResponse<Void> ok() {
		return new ApiResponse<>(true, null, null);
	}

	public static ApiResponse<Void> fail(ErrorResponse error) {
		return new ApiResponse<>(false, null, error);
	}
}
