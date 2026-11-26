package com.zigzaego.api.global.security;

import com.zigzaego.api.global.exception.ErrorCode;
import com.zigzaego.api.global.response.ApiResponse;
import com.zigzaego.api.global.response.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 필터 체인에서 생긴 인증 · 인가 오류는 {@code GlobalExceptionHandler}를 거치지 않으므로,
 * 여기서 같은 {@link ApiResponse} 형식으로 직접 쓴다.
 */
@Component
@RequiredArgsConstructor
public class SecurityErrorResponseWriter {

	private final JsonMapper jsonMapper;

	public void write(HttpServletResponse response, ErrorCode errorCode) throws IOException {
		response.setStatus(errorCode.getStatus().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		jsonMapper.writeValue(response.getWriter(), ApiResponse.fail(ErrorResponse.of(errorCode)));
	}
}
