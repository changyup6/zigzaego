package com.zigzaego.api.recommendation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 가게 · 날짜별 추천 생성 실행 기록. 표는 V4 {@code recommendation_run}, {@code (store_id, run_date)} unique (ADR-0009).
 * 쓰기는 {@code RecommendationService} 가 {@code INSERT ... ON CONFLICT DO UPDATE} 로만 한다 — 같은 날 다시 계산(force)하면 덮어쓴다.
 * 끝난 실행({@code finished_at} 이 있는 줄)이 그날 있으면 같은 날 재실행은 계산을 건너뛴다.
 */
@Entity
@Table(name = "recommendation_run")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecommendationRun {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "store_id", nullable = false)
	private Long storeId;

	@Column(name = "run_date", nullable = false)
	private LocalDate runDate;

	@Column(name = "started_at", nullable = false)
	private OffsetDateTime startedAt;

	@Column(name = "finished_at")
	private OffsetDateTime finishedAt;

	@Column(name = "created_count", nullable = false)
	private int createdCount;

	@Column(name = "no_order_count", nullable = false)
	private int noOrderCount;

	public boolean isFinished() {
		return finishedAt != null;
	}
}
