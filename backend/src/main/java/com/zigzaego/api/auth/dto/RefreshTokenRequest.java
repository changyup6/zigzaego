package com.zigzaego.api.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "refresh token을 담는 요청 (재발급 · 로그아웃)")
public record RefreshTokenRequest(
		@Schema(description = "로그인 · 재발급 응답으로 받은 refresh token")
		@NotBlank
		String refreshToken
) {
}
