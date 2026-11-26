package com.zigzaego.api.auth.jwt;

import com.zigzaego.api.auth.AuthUser;
import com.zigzaego.api.global.exception.BusinessException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code Authorization: Bearer <access token>}을 검증해 {@link AuthUser}를 SecurityContext 에 넣는다 (ADR-0005).
 *
 * <p>여기서는 <b>인증만 하고 요청을 막지 않는다.</b> 토큰이 틀리면 사유({@link #AUTH_ERROR_ATTRIBUTE})만 남기고
 * 다음 필터로 넘긴다. 인증이 필요한 경로면 인가 단계가 막고 {@code JsonAuthenticationEntryPoint}가
 * 그 사유로 401을 쓴다. 공개 경로는 잘못된 토큰이 붙어 와도 그대로 열린다.
 *
 * <p>{@code @Component}로 만들지 않는다 — 서블릿 필터로 자동 등록돼 두 번 돈다. {@code SecurityConfig}가 직접 붙인다.
 */
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	/** 토큰 검증 실패 사유({@link com.zigzaego.api.global.exception.ErrorCode})를 담는 request attribute. */
	public static final String AUTH_ERROR_ATTRIBUTE = JwtAuthenticationFilter.class.getName() + ".error";

	private static final String BEARER_PREFIX = "Bearer ";

	private final JwtTokenProvider tokenProvider;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header != null && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
			authenticate(request, header.substring(BEARER_PREFIX.length()).trim());
		}
		chain.doFilter(request, response);
	}

	private void authenticate(HttpServletRequest request, String token) {
		try {
			AuthUser user = tokenProvider.parseAccessToken(token);
			SecurityContext context = SecurityContextHolder.createEmptyContext();
			context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
			SecurityContextHolder.setContext(context);
		} catch (BusinessException e) {
			SecurityContextHolder.clearContext();
			request.setAttribute(AUTH_ERROR_ATTRIBUTE, e.getErrorCode());
		}
	}
}
