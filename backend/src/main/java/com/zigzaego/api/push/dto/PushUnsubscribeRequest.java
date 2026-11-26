package com.zigzaego.api.push.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "웹 푸시 구독 해지")
public record PushUnsubscribeRequest(
		@Schema(description = "해지할 구독의 푸시 서비스 주소", example = "https://fcm.googleapis.com/fcm/send/abc")
		@NotBlank @Size(max = 2000) String endpoint
) {
}
