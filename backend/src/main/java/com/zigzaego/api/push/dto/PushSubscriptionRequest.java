package com.zigzaego.api.push.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 브라우저 {@code PushSubscription.toJSON()} 그대로 (S15P31A308-248). */
@Schema(description = "웹 푸시 구독")
public record PushSubscriptionRequest(
		@Schema(description = "푸시 서비스 주소. https 이고 알려진 푸시 서비스(FCM · Mozilla · Apple · Windows)만 받는다",
				example = "https://fcm.googleapis.com/fcm/send/abc")
		@NotBlank @Size(max = 2000) String endpoint,

		@Schema(description = "구독 키") @NotNull @Valid Keys keys
) {

	@Schema(description = "구독 키 (base64url)")
	public record Keys(
			@Schema(description = "P-256 공개키 65바이트 (base64url)") @NotBlank @Size(max = 200) String p256dh,

			@Schema(description = "인증 비밀값 16바이트 (base64url)") @NotBlank @Size(max = 100) String auth
	) {
	}
}
