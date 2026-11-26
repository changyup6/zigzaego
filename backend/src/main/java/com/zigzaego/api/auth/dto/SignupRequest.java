package com.zigzaego.api.auth.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 공개 가입 요청. email · storeName 의 앞뒤 공백은 검증 전에 자른다(로그인과 같이 소문자로 바꾸지는 않는다).
 * 비밀번호(비었음 · 공백뿐 · 8자 미만 · UTF-8 72바이트 초과)와 약관 값은 서비스가 검사한다.
 * 비밀번호를 Bean Validation 으로 거르면 400 응답 fieldErrors 의 rejectedValue 에 원문이 실리므로 쓰지 않는다.
 */
@Schema(description = "회원가입 요청")
public record SignupRequest(
		@Schema(description = "로그인 이메일. 앞뒤 공백은 잘라서 저장한다", example = "owner@example.com", maxLength = 255)
		@NotBlank
		@Email
		@Size(max = 255)
		String email,

		@Schema(description = "비밀번호. 8자 이상, UTF-8 72바이트 이하", example = "password1234", minLength = 8,
				requiredMode = Schema.RequiredMode.REQUIRED)
		String password,

		@Schema(description = "가게 이름(선택). 비었거나 공백뿐이면 이메일의 @ 앞부분을 쓴다", example = "위글리 역삼점",
				maxLength = 100, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 100)
		String storeName,

		@ArraySchema(
				arraySchema = @Schema(description = "동의한 약관. TERMS · PRIVACY · COLLECTION 셋 모두 있어야 한다",
						example = "[\"TERMS\", \"PRIVACY\", \"COLLECTION\"]"),
				schema = @Schema(implementation = SignupAgreement.class))
		@NotNull
		List<String> agreements
) {

	public SignupRequest {
		email = email == null ? null : email.trim();
		storeName = storeName == null ? null : storeName.trim();
	}
}
