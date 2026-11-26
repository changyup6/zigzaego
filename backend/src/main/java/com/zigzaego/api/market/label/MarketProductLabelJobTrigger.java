package com.zigzaego.api.market.label;

import com.zigzaego.api.llm.GmsProperties;
import com.zigzaego.api.market.importer.MarketProductsImportedEvent;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobExecutionException;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 시세 적재가 끝났다는 이벤트({@link MarketProductsImportedEvent})를 받아 라벨 Job 을 돌린다 (S15P31A308-229, ADR-0014).
 * 스케줄은 없다 — 새 수집분이 DB 에 들어왔을 때만 LLM 을 부른다. 같은 스레드에서 끝까지 돌고 나서 돌아온다
 * ({@code import-market-products} 프로필은 웹 서버 없이 러너가 끝나면 종료하므로 비동기면 중간에 죽는다).
 * GMS 설정(키 · 주소 · 모델)이 없으면 WARN 만 남기고 건너뛴다 — 시세 적재 자체는 라벨 없이도 쓸 수 있다(규칙 폴백).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketProductLabelJobTrigger {

	public static final String PARAM_IMPORTED_AT = "importedAt";
	public static final String PARAM_LABEL_VERSION = "labelVersion";
	public static final String PARAM_LIMIT = "limit";

	private final JobOperator jobOperator;
	private final Job marketProductLabelJob;
	private final GmsProperties gmsProperties;
	private final MarketLabelProperties properties;
	private final Clock clock;

	@EventListener
	public void onImported(MarketProductsImportedEvent event) {
		log.info("시세 적재 이벤트 — {}줄 ({}). 상품명 라벨 배치를 시작한다", event.rows(), event.file().getFileName());
		if (!gmsProperties.configured()) {
			log.warn("GMS 설정(GMS_KEY · GMS_BASE_URL · GMS_MODEL)이 없어 상품명 라벨 배치를 건너뛴다. 라벨 없는 상품명은 규칙으로 판정한다");
			return;
		}
		run(properties.limit());
	}

	/**
	 * Job 을 지금 돌린다. {@code importedAt} 이 식별 파라미터라 실행마다 새 JobInstance 다.
	 *
	 * @param limit 분류할 상품명 상한. 0 이면 전부
	 * @return 끝난 실행 (COMPLETED · FAILED)
	 */
	public JobExecution run(long limit) {
		JobParameters parameters = new JobParametersBuilder()
				.addLocalDateTime(PARAM_IMPORTED_AT, LocalDateTime.now(clock))
				.addString(PARAM_LABEL_VERSION, properties.version(), false)
				.addLong(PARAM_LIMIT, limit, false)
				.toJobParameters();
		try {
			JobExecution execution = jobOperator.start(marketProductLabelJob, parameters);
			log.info("상품명 라벨 배치 끝 — {} (job execution {}, {})", execution.getStatus(), execution.getId(),
					execution.getExitStatus().getExitDescription());
			return execution;
		} catch (JobExecutionException e) {
			throw new IllegalStateException("상품명 라벨 배치를 시작하지 못했다: " + e.getMessage(), e);
		}
	}
}
