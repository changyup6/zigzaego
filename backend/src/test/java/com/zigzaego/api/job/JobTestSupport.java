package com.zigzaego.api.job;

import static org.assertj.core.api.Assertions.fail;

import com.zigzaego.api.auth.AuthTestSupport;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** job 테스트 공용: 가게 넣기 · job 상태 읽기 · 비동기 끝 기다리기. */
final class JobTestSupport {

	private JobTestSupport() {
	}

	/** 가게 하나(기본 재료 카테고리 포함)를 넣고 id 를 돌려준다. */
	static long insertStore(JdbcTemplate jdbc, String name) {
		return AuthTestSupport.insertStore(jdbc, name);
	}

	static Map<String, Object> readJob(JdbcTemplate jdbc, long jobId) {
		return jdbc.queryForMap(
				"SELECT status, progress, result_json::text AS result_json, error, finished_at, updated_at, created_at"
						+ " FROM job WHERE id = ?", jobId);
	}

	/** job 이 DONE 또는 FAILED 가 될 때까지 50ms 간격으로 최대 10초 기다린다. */
	static Map<String, Object> awaitFinished(JdbcTemplate jdbc, long jobId) {
		long deadline = System.currentTimeMillis() + 10_000;
		while (System.currentTimeMillis() < deadline) {
			Map<String, Object> row = readJob(jdbc, jobId);
			if (Set.of("DONE", "FAILED").contains((String) row.get("status"))) {
				return row;
			}
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(e);
			}
		}
		return fail("job %d 이 10초 안에 끝나지 않았다: %s", jobId, readJob(jdbc, jobId));
	}
}
