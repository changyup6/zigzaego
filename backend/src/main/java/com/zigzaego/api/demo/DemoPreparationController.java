package com.zigzaego.api.demo;

import com.zigzaego.api.global.response.ApiResponse;
import com.zigzaego.api.global.store.CurrentStoreProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "DemoPreparation", description = "내 가게 시연 자료 준비")
@RestController
@RequestMapping("/stores/me/demo-preparation")
@RequiredArgsConstructor
public class DemoPreparationController {

	private final DemoPreparationService service;
	private final CurrentStoreProvider currentStore;

	@Operation(summary = "시연 준비 상태 조회", description = "읽기 전용. 준비를 시작하지 않는다. 일반 가게는 eligible=false, NOT_STARTED. READY는 자료와 추천이 모두 커밋된 상태다.")
	@GetMapping
	public ApiResponse<DemoPreparationResponse> get() {
		return ApiResponse.ok(service.get(currentStore.currentStoreId()));
	}

	@Operation(summary = "시연 준비 시작/안전한 재시도", description = "본문 없음. 기본 메뉴/레시피 저장 후 시작. 진행 중/완료는 멱등. FAILED의 retryable=true일 때 실패 단계만 재시도한다. 일반 가게는 409 DEMO_NOT_ELIGIBLE, 기본 자료 없음은 409 DEMO_ONBOARDING_REQUIRED.")
	@PostMapping("/retry")
	public ApiResponse<DemoPreparationResponse> retry() {
		return ApiResponse.ok(service.retry(currentStore.currentStoreId()));
	}
}
