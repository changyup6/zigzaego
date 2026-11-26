package com.zigzaego.api.job.controller;

import com.zigzaego.api.global.response.ApiResponse;
import com.zigzaego.api.global.store.CurrentStoreProvider;
import com.zigzaego.api.job.dto.JobResponse;
import com.zigzaego.api.job.service.JobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Job", description = "오래 걸리는 작업(온보딩 분석 · 영수증 인식)의 진행 상태")
@RestController
@RequestMapping("/jobs")
@RequiredArgsConstructor
public class JobController {

	private final JobService jobService;
	private final CurrentStoreProvider currentStoreProvider;

	@Operation(
			summary = "job 상태 조회",
			description = "화면이 2초마다 진행률 · 결과를 물어본다. 로그인한 가게의 job 만 보인다 — "
					+ "다른 가게 job · 없는 id · 숫자가 아닌 id 는 모두 404 COMMON_NOT_FOUND.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "job 상태")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 없음 (COMMON_UNAUTHORIZED)")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "없거나 다른 가게의 job (COMMON_NOT_FOUND)")
	@GetMapping("/{id}")
	public ApiResponse<JobResponse> get(
			@Parameter(description = "job id", example = "15") @PathVariable String id) {
		return ApiResponse.ok(jobService.getForStore(currentStoreProvider.currentStoreId(), id));
	}
}
