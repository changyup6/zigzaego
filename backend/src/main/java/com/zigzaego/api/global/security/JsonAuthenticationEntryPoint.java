package com.zigzaego.api.global.security;

import com.zigzaego.api.auth.jwt.JwtAuthenticationFilter;
import com.zigzaego.api.global.exception.CommonErrorCode;
import com.zigzaego.api.global.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * 인증되지 않은 요청 → 401. 토큰이 없으면 {@code COMMON_UNAUTHORIZED},
 * 토큰이 있는데 틀렸으면 JWT 필터가 남긴 사유({@code AUTH_TOKEN_EXPIRED} · {@code AUTH_INVALID_TOKEN}).
 */
@Component
@RequiredArgsConstructor
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private final SecurityErrorResponseWriter writer;

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {
		Object tokenError = request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE);
		writer.write(response, tokenError instanceof ErrorCode errorCode ? errorCode : CommonErrorCode.UNAUTHORIZED);
	}
}
