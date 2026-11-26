package com.zigzaego.api.job.service;

import com.zigzaego.api.global.store.ResourceIds;
import com.zigzaego.api.job.dto.JobResponse;
import com.zigzaego.api.job.entity.Job;
import com.zigzaego.api.job.entity.JobStatus;
import com.zigzaego.api.job.entity.JobType;
import com.zigzaego.api.job.repository.JobRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 오래 걸리는 작업(job)을 만들고 {@code jobExecutor} 에서 돌린다 (지라 S15P31A308-92).
 * 쓰는 쪽(온보딩 · 영수증)은 {@link #create} 로 job 을 만들어 id 를 바로 응답하고, {@link #submit} 으로 본문을 넘긴다.
 * 화면은 {@code GET /jobs/{id}} 로 진행률 · 결과를 물어본다.
 * 결과는 Map · record 같은 객체로 돌려줘라. String 을 돌려주면 JSON 문자열로 저장되어 화면이 객체가 아닌 글자를 받는다.
 */
@Slf4j
@Service
public class JobService {

	/** 작업이 사유 없이 실패했을 때(예상하지 못한 예외)의 {@code job.error}. */
	public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

	/** 실행기 대기열이 꽉 차 받지 못했을 때의 {@code job.error}. */
	public static final String QUEUE_FULL = "QUEUE_FULL";

	/** {@link #submit} 을 부른 트랜잭션이 롤백돼 작업을 넘기지 못했을 때의 {@code job.error}. */
	public static final String SUBMIT_ROLLED_BACK = "SUBMIT_ROLLED_BACK";

	private final JobRepository jobRepository;
	private final TaskExecutor jobExecutor;
	private final JsonMapper jsonMapper;
	private final TransactionTemplate requiresNew;

	public JobService(JobRepository jobRepository, @Qualifier("jobExecutor") TaskExecutor jobExecutor,
			JsonMapper jsonMapper, PlatformTransactionManager transactionManager) {
		this.jobRepository = jobRepository;
		this.jobExecutor = jobExecutor;
		this.jsonMapper = jsonMapper;
		this.requiresNew = new TransactionTemplate(transactionManager);
		this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/** QUEUED · progress 0 인 job 을 저장하고 돌려준다. */
	@Transactional
	public Job create(long storeId, JobType type) {
		return jobRepository.save(Job.queued(storeId, type, OffsetDateTime.now()));
	}

	/**
	 * 실행 전(QUEUED) job 의 결과를 미리 채운다 — 화면이 실행 전부터 보여 줄 것(예: 영수증 장 이름)이 있을 때.
	 * job 을 만든 트랜잭션 안에서 부른다. 실행이 시작되면 본문의 중간 결과 · 최종 결과가 덮어쓴다.
	 */
	@Transactional
	public void initResult(long jobId, Object result) {
		String json = result == null ? null : jsonMapper.writeValueAsString(result);
		jobRepository.updateQueuedResult(jobId, json, OffsetDateTime.now());
	}

	/**
	 * 작업 본문을 실행기에 넘긴다. 트랜잭션 안에서 부르면 커밋된 뒤에 넘긴다 — 커밋 전 job 행은 실행기에서 안 보이고,
	 * 롤백되면 돌리지 않는다. 그 job 이 이미 커밋돼 QUEUED 로 남아 있으면 FAILED(SUBMIT_ROLLED_BACK) 로 닫는다
	 * (같은 트랜잭션에서 만든 job 이면 행이 함께 사라져 바꿀 것이 없다). 대기열이 꽉 차면 그 job 은 FAILED(QUEUE_FULL).
	 * 결과는 Map · record 같은 객체로 돌려줘라. String 을 돌려주면 JSON 문자열로 저장되어 화면이 객체가 아닌 글자를 받는다.
	 */
	public void submit(long jobId, JobTask task) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					dispatch(jobId, task);
				}

				@Override
				public void afterCompletion(int status) {
					if (status == STATUS_ROLLED_BACK) {
						log.warn("작업 {} 을 제출하지 않았다: 호출한 트랜잭션이 롤백됐다", jobId);
						// 원래 트랜잭션은 끝났으므로 새 트랜잭션으로 쓴다. QUEUED 일 때만 바꾼다
						requiresNew.executeWithoutResult(tx -> jobRepository.markFailed(
								jobId, List.of(JobStatus.QUEUED), SUBMIT_ROLLED_BACK, OffsetDateTime.now()));
					}
				}
			});
		} else {
			dispatch(jobId, task);
		}
	}

	/** 현재 가게의 job 하나. 다른 가게 job · 없는 id · 숫자가 아닌 id 는 모두 404 (ADR-0002). */
	@Transactional(readOnly = true)
	public JobResponse getForStore(long storeId, String rawId) {
		Job job = jobRepository.getInStore(ResourceIds.parse(rawId), storeId);
		return JobResponse.from(job, jsonMapper);
	}

	private void dispatch(long jobId, JobTask task) {
		try {
			jobExecutor.execute(() -> run(jobId, task));
		} catch (TaskRejectedException e) {
			log.warn("작업 {} 거절: 실행 대기열이 가득 찼다", jobId);
			// afterCommit 안에서도 원래 트랜잭션에 끼지 않도록 새 트랜잭션으로 쓴다
			requiresNew.executeWithoutResult(status ->
					jobRepository.markFailed(jobId, List.of(JobStatus.QUEUED), QUEUE_FULL, OffsetDateTime.now()));
		}
	}

	private void run(long jobId, JobTask task) {
		if (jobRepository.markRunning(jobId, OffsetDateTime.now()) == 0) {
			log.warn("작업 {} 이 QUEUED 가 아니라 건너뛴다", jobId);
			return;
		}
		try {
			Object result = task.run(new Context(jobId));
			String resultJson = result == null ? null : jsonMapper.writeValueAsString(result);
			jobRepository.markDone(jobId, resultJson, OffsetDateTime.now());
		} catch (JobFailedException e) {
			log.info("작업 {} 실패: {}", jobId, e.getCode());
			fail(jobId, e.getCode());
		} catch (Throwable e) {
			// Error(AssertionError · StackOverflowError 등)까지 잡는다 — 놓치면 job 이 RUNNING 으로 영원히 남는다.
			// 기록한 뒤 다시 던지지 않는다: 던지면 실행기 스레드가 죽고 새로 만들어질 뿐 얻는 것이 없고, 스택은 이 로그에 남는다
			log.error("작업 {} 이 예상치 못하게 실패했다", jobId, e);
			fail(jobId, INTERNAL_ERROR);
		}
	}

	private void fail(long jobId, String error) {
		jobRepository.markFailed(jobId, List.of(JobStatus.RUNNING), error, OffsetDateTime.now());
	}

	private final class Context implements JobContext {

		private final long jobId;

		private Context(long jobId) {
			this.jobId = jobId;
		}

		@Override
		public long jobId() {
			return jobId;
		}

		@Override
		public void progress(double progress) {
			if (!(progress >= 0 && progress <= 1)) {
				throw new IllegalArgumentException("progress must be within 0..1: " + progress);
			}
			BigDecimal value = BigDecimal.valueOf(progress).setScale(3, RoundingMode.HALF_UP);
			jobRepository.updateProgress(jobId, value, OffsetDateTime.now());
		}

		@Override
		public void partialResult(Object partial) {
			String json = partial == null ? null : jsonMapper.writeValueAsString(partial);
			jobRepository.updateResult(jobId, json, OffsetDateTime.now());
		}
	}
}
