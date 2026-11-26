package com.zigzaego.api.push.controller;

import com.zigzaego.api.auth.AuthUser;
import com.zigzaego.api.auth.LoginUser;
import com.zigzaego.api.global.response.ApiResponse;
import com.zigzaego.api.push.dto.PushSubscriptionRequest;
import com.zigzaego.api.push.dto.PushUnsubscribeRequest;
import com.zigzaego.api.push.service.PushSubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 마감 확인 웹 푸시 구독 (S15P31A308-248, DECISIONS #88). */
@Tag(name = "Push", description = "웹 푸시 구독")
@RestController
@RequiredArgsConstructor
public class PushSubscriptionController {

	private final PushSubscriptionService pushSubscriptionService;

	@Operation(
			summary = "웹 푸시 구독 등록",
			description = "브라우저 PushManager.subscribe 결과(PushSubscription.toJSON())를 그대로 보낸다. 같은 endpoint 면 가게 · 계정 · 키를 "
					+ "이 요청 것으로 바꾼다. 마감 시각에 \"오늘 재고 확인\" 알림 하나를 보내는 데만 쓴다. endpoint 는 https 의 알려진 푸시 서비스"
					+ "(FCM · Mozilla · Apple · Windows)만 받는다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "등록 · 갱신됨 (data: null)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "endpoint 가 https 푸시 서비스가 아님 · "
			+ "p256dh 가 65바이트 · auth 가 16바이트 base64url 이 아님 · 빈 값 (COMMON_INVALID_INPUT)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@PostMapping("/push-subscriptions")
	public ApiResponse<Void> subscribe(@LoginUser AuthUser user, @Valid @RequestBody PushSubscriptionRequest request) {
		pushSubscriptionService.subscribe(user.storeId(), user.accountId(), request);
		return ApiResponse.ok(null);
	}

	@Operation(
			summary = "웹 푸시 구독 해지",
			description = "내 가게의 그 endpoint 구독을 지운다. 없으면 아무 일도 없이 200 (두 번 보내도 된다). 남의 가게 구독은 지우지 않는다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "해지됨 (data: null)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "endpoint 없음 (COMMON_INVALID_INPUT)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@DeleteMapping("/push-subscriptions")
	public ApiResponse<Void> unsubscribe(@LoginUser AuthUser user, @Valid @RequestBody PushUnsubscribeRequest request) {
		pushSubscriptionService.unsubscribe(user.storeId(), request.endpoint());
		return ApiResponse.ok(null);
	}
}
