package com.zigzaego.api.job.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 몇 십 초~몇 분 걸리는 작업 한 건(온보딩 분석 · 영수증 인식). 표는 V1 {@code job}.
 * 상태 · 진행률 · 결과는 {@code JobService} 가 갱신 쿼리로만 바꾼다.
 */
@Entity
@Table(name = "job")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Job {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "store_id", nullable = false)
	private Long storeId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 12)
	private JobType type;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private JobStatus status;

	/** 0~1, 소수 셋째 자리까지. */
	@Column(nullable = false, precision = 4, scale = 3)
	private BigDecimal progress;

	/** 작업 결과 JSON 원문. 없으면 null. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "result_json")
	private String resultJson;

	/** 실패 사유 코드 (예: {@code COLLECT_FAILED}, {@code INTERNAL_ERROR}). */
	@Column(columnDefinition = "text")
	private String error;

	@Column(name = "created_at", nullable = false)
	private OffsetDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	@Column(name = "finished_at")
	private OffsetDateTime finishedAt;

	public static Job queued(long storeId, JobType type, OffsetDateTime now) {
		Job job = new Job();
		job.storeId = storeId;
		job.type = type;
		job.status = JobStatus.QUEUED;
		job.progress = BigDecimal.ZERO.setScale(3);
		job.createdAt = now;
		job.updatedAt = now;
		return job;
	}
}
