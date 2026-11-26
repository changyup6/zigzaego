package com.zigzaego.api.onboarding.service;

import com.zigzaego.api.account.service.StoreService;
import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.job.dto.JobResponse;
import com.zigzaego.api.job.entity.Job;
import com.zigzaego.api.job.entity.JobType;
import com.zigzaego.api.job.service.JobService;
import com.zigzaego.api.llm.LlmImage;
import com.zigzaego.api.onboarding.dto.OnboardingJobRequest;
import com.zigzaego.api.onboarding.dto.PlaceCandidateResponse;
import com.zigzaego.api.onboarding.exception.OnboardingJobErrorCode;
import com.zigzaego.api.onboarding.job.OnboardingAnalyzer;
import com.zigzaego.api.onboarding.job.PlaceIdResolver;
import com.zigzaego.api.onboarding.replay.OnboardingPlaceSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;

/**
 * 온보딩 분석 job 을 만든다 (S15P31A308-95). 다시 시도 · 메뉴판 사진은 언제나 새 job 이다 — 화면은 새 id 로 다시 물어본다.
 * 화면은 {@code GET /jobs/{id}} 로 진행률 · 결과를 본다.
 */
@Service
@RequiredArgsConstructor
public class OnboardingJobService {

	/** 메뉴판 사진 상한 10MB. */
	public static final long MAX_PHOTO_BYTES = 10L * 1024 * 1024;

	private final JobService jobService;
	private final PlaceIdResolver placeIdResolver;
	private final OnboardingAnalyzer analyzer;
	private final JsonMapper jsonMapper;
	private final StoreService storeService;
	private final OnboardingPlaceSource placeSource;

	/**
	 * 가게 찾기 후보 id 또는 네이버 지도 링크로 분석을 시작한다. 링크에서 id 를 못 뽑으면 job 을 만들지 않는다.
	 * 후보의 가게 이름이 오면 가게 이름 · 화면 이름으로 쓴다 — 홈 인사말이 이메일 앞부분 대신 가게 이름을 보인다.
	 */
	public JobResponse startFromPlace(long storeId, OnboardingJobRequest request) {
		String placeId = placeIdResolver.resolve(request);
		String address = placeSource.findCandidate(placeId).map(PlaceCandidateResponse::address).orElse(null);
		storeService.adoptPlace(storeId, placeId, request.placeName(), address);
		Job job = jobService.create(storeId, JobType.ONBOARDING);
		jobService.submit(job.getId(), ctx -> analyzer.analyzePlace(ctx, placeId));
		return JobResponse.from(job, jsonMapper);
	}

	/** 메뉴판 사진 한 장으로 분석을 시작한다. 사진은 메모리에서 LLM 에 넘기고 저장하지 않는다. */
	public JobResponse startFromMenuPhoto(long storeId, MultipartFile file) {
		LlmImage photo = readPhoto(file);
		Job job = jobService.create(storeId, JobType.ONBOARDING);
		jobService.submit(job.getId(), ctx -> analyzer.analyzeMenuPhoto(ctx, photo));
		return JobResponse.from(job, jsonMapper);
	}

	private static LlmImage readPhoto(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new BusinessException(OnboardingJobErrorCode.PHOTO_REQUIRED);
		}
		String contentType = file.getContentType();
		if (contentType == null || !contentType.toLowerCase().startsWith("image/")) {
			throw new BusinessException(OnboardingJobErrorCode.PHOTO_NOT_IMAGE);
		}
		if (file.getSize() > MAX_PHOTO_BYTES) {
			throw new BusinessException(OnboardingJobErrorCode.PHOTO_TOO_LARGE);
		}
		try {
			return new LlmImage(contentType.toLowerCase(), file.getBytes());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
