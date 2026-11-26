package com.zigzaego.api.job.dto;

import com.zigzaego.api.job.entity.Job;
import com.zigzaego.api.job.entity.JobStatus;
import com.zigzaego.api.job.entity.JobType;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@code GET /jobs/{id}} 응답. FE {@code frontend/src/lib/types.ts} 의 {@code Job} 과 같은 모양. */
@Schema(description = "오래 걸리는 작업 한 건의 상태")
public record JobResponse(
		@Schema(description = "job id (문자열)", example = "15")
		String id,

		@Schema(description = "작업 종류", example = "ONBOARDING")
		JobType type,

		@Schema(description = "상태. QUEUED → RUNNING → DONE | FAILED", example = "RUNNING")
		JobStatus status,

		@Schema(description = "진행률 0~1", example = "0.35")
		double progress,

		@Schema(description = "작업 결과 JSON 그대로. 결과가 없으면 null", type = "object", nullable = true)
		JsonNode result,

		@Schema(description = "실패 사유 코드. FAILED 일 때만 채운다", example = "COLLECT_FAILED", nullable = true)
		String error
) {

	public static JobResponse from(Job job, JsonMapper jsonMapper) {
		return new JobResponse(
				String.valueOf(job.getId()),
				job.getType(),
				job.getStatus(),
				job.getProgress().doubleValue(),
				job.getResultJson() == null ? null : jsonMapper.readTree(job.getResultJson()),
				job.getError());
	}
}
