package com.zigzaego.api.global.security;

import com.zigzaego.api.auth.jwt.JwtAuthenticationFilter;
import com.zigzaego.api.auth.jwt.JwtProperties;
import com.zigzaego.api.auth.jwt.JwtTokenProvider;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * JWT 기반 stateless 보안 설정 (ADR-0005). 세션 · 폼 로그인 · HTTP Basic을 쓰지 않는다.
 * 요청마다 {@link JwtAuthenticationFilter}가 access token을 검증해 {@code AuthUser}를 SecurityContext 에 넣는다.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(JwtProperties.class)
@RequiredArgsConstructor
public class SecurityConfig {

	/** 인증 없이 열어 두는 경로. Swagger(ADR-0003)는 반드시 열려 있어야 한다. */
	private static final String[] PUBLIC_PATHS = {
			"/v3/api-docs/**",
			"/swagger-ui/**",
			"/swagger-ui.html",
			"/actuator/health",
			// 배포 환경은 actuator를 관리 포트(8081)로 분리한다. 관리 포트는 Service · Ingress에 연결하지 않아 클러스터 밖에서 닿지 않는다.
			// 이 필터 체인은 관리 포트에도 적용되므로 상태 확인 · 메트릭 경로를 열어 둔다. 공개 포트에는 /livez · /readyz만 남는다(infra/k8s/README.md)
			"/actuator/health/**",
			"/actuator/prometheus",
			"/livez",
			"/readyz",
			// 공개 가입(S15P31A308-204). 누구나 계정 · 가게를 만들 수 있다 — 앱 단 레이트 리밋은 없고 인프라 IP 제한뿐이다
			"/auth/signup",
			"/auth/login",
			"/auth/refresh",
			// access token이 이미 만료됐어도 로그아웃할 수 있어야 한다. refresh token 자체가 증명이다
			"/auth/logout",
			"/error"
	};

	private final JsonAuthenticationEntryPoint authenticationEntryPoint;
	private final JsonAccessDeniedHandler accessDeniedHandler;
	private final JwtTokenProvider jwtTokenProvider;

	@Value("${app.cors.allowed-origins}")
	private List<String> allowedOrigins;

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
				.csrf(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable)
				.cors(cors -> cors.configurationSource(corsConfigurationSource()))
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(PUBLIC_PATHS).permitAll()
						.anyRequest().authenticated())
				.addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider), UsernamePasswordAuthenticationFilter.class)
				.exceptionHandling(ex -> ex
						.authenticationEntryPoint(authenticationEntryPoint)
						.accessDeniedHandler(accessDeniedHandler));
		return http.build();
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	public CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOrigins(allowedOrigins);
		config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		config.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE));
		config.setAllowCredentials(true);
		config.setMaxAge(3600L);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", config);
		return source;
	}
}
