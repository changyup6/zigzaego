package com.zigzaego.api.job;

import static com.zigzaego.api.job.JobTestSupport.insertStore;
import static com.zigzaego.api.job.JobTestSupport.readJob;
import static org.assertj.core.api.Assertions.assertThat;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.job.service.JobRecovery;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class JobRecoveryTest {

	@Autowired
	private JobRecovery jobRecovery;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void 재시작하면_RUNNING과_QUEUED는_FAILED_SERVER_RESTARTED_DONE은_그대로() {
		long storeId = insertStore(jdbc, "가게-" + System.nanoTime());
		long running = insertJob(storeId, "RUNNING", "0.400", null);
		long queued = insertJob(storeId, "QUEUED", "0", null);
		long done = insertJob(storeId, "DONE", "1", "2026-10-01T00:00:00+09:00");
		Map<String, Object> doneBefore = readJob(jdbc, done);

		jobRecovery.recoverUnfinished();

		for (long id : new long[] {running, queued}) {
			Map<String, Object> row = readJob(jdbc, id);
			assertThat(row.get("status")).as("job %d", id).isEqualTo("FAILED");
			assertThat(row.get("error")).as("job %d", id).isEqualTo("SERVER_RESTARTED");
			assertThat(row.get("finished_at")).as("job %d", id).isNotNull();
		}
		assertThat(readJob(jdbc, done)).isEqualTo(doneBefore);
	}

	private long insertJob(long storeId, String status, String progress, String finishedAt) {
		return jdbc.queryForObject(
				"INSERT INTO job (store_id, type, status, progress, finished_at)"
						+ " VALUES (?, 'ONBOARDING', ?, CAST(? AS NUMERIC), CAST(? AS TIMESTAMPTZ)) RETURNING id",
				Long.class, storeId, status, progress, finishedAt);
	}
}
