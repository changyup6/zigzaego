package com.zigzaego.api.job.service;

import com.zigzaego.api.global.config.WebServerProfile;
import com.zigzaego.api.job.entity.JobStatus;
import com.zigzaego.api.job.repository.JobRepository;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 서버가 다시 뜨면 실행기의 대기열과 돌던 작업은 메모리와 함께 사라진 뒤다. 그래서 RUNNING · QUEUED job 을 전부
 * FAILED(SERVER_RESTARTED)로 바꾼다. DONE · FAILED 는 그대로. 자동 재시도는 없다 — 화면이 새 job 을 만든다.
 */
@Slf4j
@Component
@Profile(WebServerProfile.EXPRESSION)
@RequiredArgsConstructor
public class JobRecovery {

	public static final String SERVER_RESTARTED = "SERVER_RESTARTED";

	private final JobRepository jobRepository;

	@EventListener(ApplicationReadyEvent.class)
	public void recoverUnfinished() {
		int count = jobRepository.failAll(List.of(JobStatus.RUNNING, JobStatus.QUEUED), SERVER_RESTARTED,
				OffsetDateTime.now());
		if (count > 0) {
			log.warn("끝나지 않은 job {}건을 FAILED 로 바꿨다 ({})", count, SERVER_RESTARTED);
		}
	}
}
