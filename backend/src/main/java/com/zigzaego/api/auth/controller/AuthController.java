package com.zigzaego.api.auth.controller;

import com.zigzaego.api.auth.dto.LoginRequest;
import com.zigzaego.api.auth.dto.RefreshTokenRequest;
import com.zigzaego.api.auth.dto.SignupRequest;
import com.zigzaego.api.auth.dto.TokenResponse;
import com.zigzaego.api.auth.service.AuthService;
import com.zigzaego.api.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "인증", description = "회원가입 · 로그인 · 토큰 재발급 · 로그아웃")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@SecurityRequirements
public class AuthController {

	private final AuthService authService;

	@Operation(
			summary = "회원가입",
			description = "계정 · 가게 · 가게 연결(OWNER)을 한 번에 만들고 로그인과 같은 토큰 한 쌍을 준다. 인증 없이 부른다. "
					+ "약관 TERMS · PRIVACY · COLLECTION 셋 모두에 동의해야 하며 동의 기록은 저장하지 않는다. "
					+ "storeName 이 없거나 공백뿐이면 이메일의 @ 앞부분이 가게 이름이 된다. 가게 시간대는 Asia/Seoul. "
					+ "시연 계정 이메일(DEMO_ACCOUNT_EMAIL)은 이미 가입돼 있어도 다시 가입된다 — 옛 계정은 이메일만 보관용으로 바뀐다 (ADR-0018).")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "가입됨. 토큰 한 쌍")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
			description = "이메일 형식 · 255자 초과, 비밀번호 8자 미만 · UTF-8 72바이트 초과, storeName 100자 초과, "
					+ "약관 누락 · 모르는 값 (COMMON_INVALID_INPUT)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 가입된 이메일 (AUTH_EMAIL_TAKEN)")
	@PostMapping("/signup")
	@ResponseStatus(HttpStatus.CREATED)
	public ApiResponse<TokenResponse> signup(@Valid @RequestBody SignupRequest request) {
		return ApiResponse.ok(authService.signup(request));
	}

	@Operation(
			summary = "로그인",
			description = "이메일 · 비밀번호로 access token과 refresh token을 받는다. "
					+ "access token은 계정에 연결된 가게로 스코프된다. 이후 요청은 Authorization: Bearer <accessToken>.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "토큰 한 쌍")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "이메일 · 비밀번호 누락 (COMMON_INVALID_INPUT)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "이메일 또는 비밀번호가 틀림 (AUTH_INVALID_CREDENTIALS)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "연결된 가게 없음 (AUTH_NO_STORE)")
	@PostMapping("/login")
	public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
		return ApiResponse.ok(authService.login(request.email(), request.password()));
	}

	@Operation(
			summary = "토큰 재발급",
			description = "access token이 만료(AUTH_TOKEN_EXPIRED)되면 refresh token으로 새 토큰 한 쌍을 받는다. "
					+ "보낸 refresh token은 즉시 무효가 되므로 응답의 새 refresh token으로 바꿔 저장한다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "새 토큰 한 쌍")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "없거나 이미 쓴 · 만료 · 로그아웃된 토큰 (AUTH_INVALID_REFRESH_TOKEN)")
	@PostMapping("/refresh")
	public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
		return ApiResponse.ok(authService.refresh(request.refreshToken()));
	}

	@Operation(
			summary = "로그아웃",
			description = "refresh token을 무효로 만든다. 이미 없는 토큰이어도 200. "
					+ "access token은 만료 전까지 서버에서 끊을 수 없으니 FE가 버린다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그아웃됨")
	@PostMapping("/logout")
	public ApiResponse<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
		authService.logout(request.refreshToken());
		return ApiResponse.ok();
	}
}
