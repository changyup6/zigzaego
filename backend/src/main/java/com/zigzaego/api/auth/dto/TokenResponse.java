package com.zigzaego.api.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "토큰 한 쌍. refresh token은 한 번 쓰면 무효가 되므로 재발급 응답의 새 값으로 바꿔 저장한다")
public record TokenResponse(
		@Schema(description = "API 호출용. Authorization: Bearer <accessToken>")
		String accessToken,

		@Schema(description = "access token 만료까지 남은 초", example = "1800")
		long expiresIn,

		@Schema(description = "POST /auth/refresh · /auth/logout 에 보낼 값. 한 번만 쓸 수 있다")
		String refreshToken
) {
}
