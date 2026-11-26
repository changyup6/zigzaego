package com.zigzaego.api.job;

import static com.zigzaego.api.job.JobTestSupport.awaitFinished;
import static com.zigzaego.api.job.JobTestSupport.insertStore;
import static com.zigzaego.api.job.JobTestSupport.readJob;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.job.entity.Job;
import com.zigzaego.api.job.entity.JobStatus;
import com.zigzaego.api.job.entity.JobType;
import com.zigzaego.api.job.service.JobFailedException;
import com.zigzaego.api.job.service.JobService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class JobServiceTest {

	@Autowired
	private JobService jobService;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@Autowired
	private JsonMapper jsonMapper;

	private long storeId;

	@BeforeEach
	void setUp() {
		storeId = insertStore(jdbc, "가게-" + System.nanoTime());
	}

	@Test
	void create는_QUEUED_progress_0으로_저장한다() {
		Job job = jobService.create(storeId, JobType.ONBOARDING);

		assertThat(job.getId()).isNotNull();
		assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
		Map<String, Object> row = readJob(jdbc, job.getId());
		assertThat(row.get("status")).isEqualTo("QUEUED");
		assertThat((BigDecimal) row.get("progress")).isEqualByComparingTo("0");
		assertThat(row.get("finished_at")).isNull();
	}

	@Test
	void 정상_끝나면_DONE_progress_1_결과_JSON_finished_at() {
		Job job = jobService.create(storeId, JobType.ONBOARDING);

		jobService.submit(job.getId(), ctx -> {
			ctx.progress(0.3);
			ctx.progress(0.9);
			return Map.of("menus", List.of());
		});

		Map<String, Object> row = awaitFinished(jdbc, job.getId());
		assertThat(row.get("status")).isEqualTo("DONE");
		assertThat(row.get("progress")).isEqualTo(new BigDecimal("1.000"));
		assertThat(jsonMapper.readTree((String) row.get("result_json")))
				.isEqualTo(jsonMapper.readTree("{\"menus\":[]}"));
		assertThat(row.get("finished_at")).isNotNull();
		assertThat(row.get("error")).isNull();
	}

	@Test
	void 결과가_null이면_result_json도_null() {
		Job job = jobService.create(storeId, JobType.ONBOARDING);

		jobService.submit(job.getId(), ctx -> null);

		Map<String, Object> row = awaitFinished(jdbc, job.getId());
		assertThat(row.get("status")).isEqualTo("DONE");
		assertThat(row.get("result_json")).isNull();
	}

	@Test
	void submit은_트랜잭션_커밋_뒤에_실행된다() throws Exception {
		long jobId = transactionTemplate.execute(status -> {
			Job job = jobService.create(storeId, JobType.ONBOARDING);
			jobService.submit(job.getId(), ctx -> "ok");
			try {
				// 커밋 전에 실행기가 돌았다면 아직 안 보이는 행을 갱신하지 못해 QUEUED 로 남는다
				Thread.sleep(300);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return job.getId();
		});

		Map<String, Object> row = awaitFinished(jdbc, jobId);
		assertThat(row.get("status")).isEqualTo("DONE");
		assertThat(jsonMapper.readTree((String) row.get("result_json")).asString()).isEqualTo("ok");
	}

	@Test
	void fail_작업이_던진_사유코드를_error에_적는다() {
		Job job = jobService.create(storeId, JobType.ONBOARDING);

		jobService.submit(job.getId(), ctx -> {
			ctx.progress(0.2);
			throw new JobFailedException("COLLECT_FAILED");
		});

		Map<String, Object> row = awaitFinished(jdbc, job.getId());
		assertThat(row.get("status")).isEqualTo("FAILED");
		assertThat(row.get("error")).isEqualTo("COLLECT_FAILED");
		assertThat(row.get("finished_at")).isNotNull();
	}

	@Test
	void fail_예상하지_못한_예외는_INTERNAL_ERROR() {
		Job job = jobService.create(storeId, JobType.ONBOARDING);

		jobService.submit(job.getId(), ctx -> {
			throw new RuntimeException("내부 정보가 담긴 메시지");
		});

		Map<String, Object> row = awaitFinished(jdbc, job.getId());
		assertThat(row.get("status")).isEqualTo("FAILED");
		assertThat(row.get("error")).isEqualTo("INTERNAL_ERROR");
		assertThat(row.get("finished_at")).isNotNull();
	}

	@Test
	void fail_progress가_1을_넘으면_FAILED() {
		Job job = jobService.create(storeId, JobType.ONBOARDING);

		jobService.submit(job.getId(), ctx -> {
			ctx.progress(1.5);
			return "닿으면 안 됨";
		});

		Map<String, Object> row = awaitFinished(jdbc, job.getId());
		assertThat(row.get("status")).isEqualTo("FAILED");
		assertThat(row.get("result_json")).isNull();
		assertThat(row.get("finished_at")).isNotNull();
	}

	@Test
	void fail_progress가_음수여도_FAILED() {
		Job job = jobService.create(storeId, JobType.ONBOARDING);

		jobService.submit(job.getId(), ctx -> {
			ctx.progress(-0.1);
			return "닿으면 안 됨";
		});

		Map<String, Object> row = awaitFinished(jdbc, job.getId());
		assertThat(row.get("status")).isEqualTo("FAILED");
	}

	@Test
	void fail_작업이_AssertionError를_던지면_INTERNAL_ERROR() {
		Job job = jobService.create(storeId, JobType.ONBOARDING);

		jobService.submit(job.getId(), ctx -> {
			throw new AssertionError("Error 계열도 RUNNING 으로 남으면 안 됨");
		});

		Map<String, Object> row = awaitFinished(jdbc, job.getId());
		assertThat(row.get("status")).isEqualTo("FAILED");
		assertThat(row.get("error")).isEqualTo("INTERNAL_ERROR");
		assertThat(row.get("finished_at")).isNotNull();
	}

	@Test
	void fail_작업이_StackOverflowError를_던지면_INTERNAL_ERROR() {
		Job job = jobService.create(storeId, JobType.ONBOARDING);

		jobService.submit(job.getId(), ctx -> {
			throw new StackOverflowError();
		});

		Map<String, Object> row = awaitFinished(jdbc, job.getId());
		assertThat(row.get("status")).isEqualTo("FAILED");
		assertThat(row.get("error")).isEqualTo("INTERNAL_ERROR");
		assertThat(row.get("finished_at")).isNotNull();
	}

	@Test
	void fail_submit을_부른_트랜잭션이_롤백되면_SUBMIT_ROLLED_BACK() {
		// job 은 이미 커밋돼 있고, submit 을 부른 바깥 트랜잭션만 롤백된다 → afterCommit 이 안 불린다
		Job job = jobService.create(storeId, JobType.ONBOARDING);

		assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
			jobService.submit(job.getId(), ctx -> "닿으면 안 됨");
			throw new IllegalStateException("호출한 쪽 롤백");
		})).isInstanceOf(IllegalStateException.class);

		Map<String, Object> row = awaitFinished(jdbc, job.getId());
		assertThat(row.get("status")).isEqualTo("FAILED");
		assertThat(row.get("error")).isEqualTo("SUBMIT_ROLLED_BACK");
		assertThat(row.get("result_json")).isNull();
		assertThat(row.get("finished_at")).isNotNull();
	}

	@Test
	void 같은_트랜잭션에서_만든_job이_롤백되면_행이_없다() {
		Long[] jobId = new Long[1];

		assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
			Job job = jobService.create(storeId, JobType.ONBOARDING);
			jobId[0] = job.getId();
			jobService.submit(job.getId(), ctx -> "닿으면 안 됨");
			throw new IllegalStateException("호출한 쪽 롤백");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(jdbc.queryForObject("SELECT count(*) FROM job WHERE id = ?", Long.class, jobId[0])).isZero();
	}
}
