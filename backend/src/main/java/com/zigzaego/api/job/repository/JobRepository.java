package com.zigzaego.api.job.repository;

import com.zigzaego.api.global.store.StoreScopeExempt;
import com.zigzaego.api.global.store.StoreScopedRepository;
import com.zigzaego.api.job.entity.Job;
import com.zigzaego.api.job.entity.JobStatus;
import com.zigzaego.api.job.entity.JobType;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상태 변경은 전부 한 줄 UPDATE 로 한다. 실행기 스레드가 엔티티를 들고 있지 않아도 되고,
 * 다른 상태로 이미 바뀐 job(예: 재시작 복구로 FAILED)을 되살리지 않도록 현재 상태를 조건에 건다.
 * 상태 갱신은 실행기가 자기가 만든 job id 로만 부르므로 가게 조건을 걸지 않는다 ({@link StoreScopeExempt}).
 * 화면 조회는 {@link StoreScopedRepository#getInStore}로만 한다 (ADR-0002).
 * 빈 이름은 따로 준다 — 기본 이름 {@code jobRepository} 가 Spring Batch 의 {@code JobRepository} 빈과 겹친다 (ADR-0014).
 */
@Repository("jobEntityRepository")
public interface JobRepository extends StoreScopedRepository<Job> {

	String EXECUTOR_ONLY = "job 실행기 · 재시작 복구 전용. 가게가 이미 정해진 job id 로만 부른다";

	@StoreScopeExempt(EXECUTOR_ONLY)
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("UPDATE Job j SET j.status = com.zigzaego.api.job.entity.JobStatus.RUNNING, j.updatedAt = :now"
			+ " WHERE j.id = :id AND j.status = com.zigzaego.api.job.entity.JobStatus.QUEUED")
	int markRunning(@Param("id") long id, @Param("now") OffsetDateTime now);

	@StoreScopeExempt(EXECUTOR_ONLY)
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("UPDATE Job j SET j.progress = :progress, j.updatedAt = :now"
			+ " WHERE j.id = :id AND j.status = com.zigzaego.api.job.entity.JobStatus.RUNNING")
	int updateProgress(@Param("id") long id, @Param("progress") BigDecimal progress, @Param("now") OffsetDateTime now);

	/** 중간 결과. RUNNING 일 때만 덮어쓴다 — 끝난 job 의 결과를 되돌리지 않는다. */
	@StoreScopeExempt(EXECUTOR_ONLY)
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("UPDATE Job j SET j.resultJson = :resultJson, j.updatedAt = :now"
			+ " WHERE j.id = :id AND j.status = com.zigzaego.api.job.entity.JobStatus.RUNNING")
	int updateResult(@Param("id") long id, @Param("resultJson") String resultJson, @Param("now") OffsetDateTime now);

	/** 이 가게의 그 종류 · 상태 job 중 가장 최근 것 (온보딩 확정이 최근 DONE 분석을 찾는다). */
	Optional<Job> findFirstByStoreIdAndTypeAndStatusOrderByIdDesc(Long storeId, JobType type, JobStatus status);

	/** 이 가게의 그 종류 job 중 가장 최근 것 (영수증 목록의 {@code jobId}). */
	Optional<Job> findFirstByStoreIdAndTypeOrderByIdDesc(Long storeId, JobType type);

	/** 이 가게의 job 여러 개 (영수증 목록이 장마다 그 job 의 상태 · 결과를 본다). */
	List<Job> findAllByStoreIdAndIdIn(Long storeId, Collection<Long> ids);

	/**
	 * 실행 전 결과. QUEUED 일 때만 쓴다 — 영수증 job 이 올린 장 이름을 실행 전부터 결과에 남겨 목록이 보이게 한다.
	 * 만든 트랜잭션 안에서 자기가 만든 job id 로만 부른다.
	 */
	@StoreScopeExempt("job 을 만든 서비스가 같은 트랜잭션에서 자기가 만든 job id 로만 부른다")
	@Transactional
	@Modifying(flushAutomatically = true)
	@Query("UPDATE Job j SET j.resultJson = :resultJson, j.updatedAt = :now"
			+ " WHERE j.id = :id AND j.status = com.zigzaego.api.job.entity.JobStatus.QUEUED")
	int updateQueuedResult(@Param("id") long id, @Param("resultJson") String resultJson,
			@Param("now") OffsetDateTime now);

	@StoreScopeExempt(EXECUTOR_ONLY)
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("UPDATE Job j SET j.status = com.zigzaego.api.job.entity.JobStatus.DONE, j.progress = 1BD,"
			+ " j.resultJson = :resultJson, j.updatedAt = :now, j.finishedAt = :now"
			+ " WHERE j.id = :id AND j.status = com.zigzaego.api.job.entity.JobStatus.RUNNING")
	int markDone(@Param("id") long id, @Param("resultJson") String resultJson, @Param("now") OffsetDateTime now);

	@StoreScopeExempt(EXECUTOR_ONLY)
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("UPDATE Job j SET j.status = com.zigzaego.api.job.entity.JobStatus.FAILED, j.error = :error,"
			+ " j.updatedAt = :now, j.finishedAt = :now"
			+ " WHERE j.id = :id AND j.status IN :from")
	int markFailed(@Param("id") long id, @Param("from") Collection<JobStatus> from, @Param("error") String error,
			@Param("now") OffsetDateTime now);

	/** 재시작 복구: 끝나지 않은 job 을 전부 실패로. */
	@StoreScopeExempt(EXECUTOR_ONLY)
	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("UPDATE Job j SET j.status = com.zigzaego.api.job.entity.JobStatus.FAILED, j.error = :error,"
			+ " j.updatedAt = :now, j.finishedAt = :now WHERE j.status IN :from")
	int failAll(@Param("from") Collection<JobStatus> from, @Param("error") String error,
			@Param("now") OffsetDateTime now);
}
