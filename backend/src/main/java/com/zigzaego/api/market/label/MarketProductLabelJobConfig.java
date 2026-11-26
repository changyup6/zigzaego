package com.zigzaego.api.market.label;

import com.zigzaego.api.market.catalog.MarketItemGroupCatalog;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.listener.StepListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 식자재몰 상품명 라벨 Spring Batch Job (S15P31A308-229, ADR-0014).
 * <pre>
 * marketProductLabelJob
 *   └ classifyProductNames  chunk = app.market-label.batch-size × threads
 *        reader  MarketLabelCandidateReader   라벨 없는 고유 상품명 중 후보 (증분)
 *        writer  MarketProductLabelWriter     batch-size 개씩 threads 개 LLM 요청을 나란히 → market_product_label upsert · 커밋
 *        listener MarketLabelBackupListener   커밋마다 labels_latest.csv, 단계 끝에 시각 붙은 사본 (app.market-label.backup-dir)
 * </pre>
 * 기동은 {@link MarketProductLabelJobTrigger}(시세 적재 이벤트)만 한다. 스케줄 · 기동 시 자동 실행은 없다({@code spring.batch.job.enabled=false}).
 * 실행 기록은 Flyway V10 의 {@code BATCH_*} 표.
 */
@Configuration
@EnableConfigurationProperties(MarketLabelProperties.class)
public class MarketProductLabelJobConfig {

	public static final String JOB_NAME = "marketProductLabelJob";
	public static final String STEP_NAME = "classifyProductNames";

	@Bean
	public Job marketProductLabelJob(JobRepository jobRepository, Step classifyProductNamesStep) {
		return new JobBuilder(JOB_NAME, jobRepository).start(classifyProductNamesStep).build();
	}

	@Bean
	public Step classifyProductNamesStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
			MarketLabelProperties properties, MarketLabelCandidateReader marketLabelCandidateReader,
			MarketProductLabelWriter marketProductLabelWriter, MarketLabelBackupListener marketLabelBackupListener) {
		return new StepBuilder(STEP_NAME, jobRepository)
				.<String, String>chunk(properties.chunkSize())
				.reader(marketLabelCandidateReader)
				.writer(marketProductLabelWriter)
				.listener((StepListener) marketLabelBackupListener)
				.transactionManager(transactionManager)
				.build();
	}

	/** 단계마다 새로 만든다 — {@code limit} 은 job 파라미터(없으면 설정값 0 = 전부). */
	@Bean
	@StepScope
	public MarketLabelCandidateReader marketLabelCandidateReader(@Value("#{jobParameters['limit']}") Long limit,
			MarketProductLabelJdbcRepository labelRepository, MarketItemGroupCatalog catalog, MarketLabelProperties properties) {
		return new MarketLabelCandidateReader(labelRepository, catalog, properties.version(), limit == null ? 0 : limit);
	}

	@Bean
	@StepScope
	public MarketProductLabelWriter marketProductLabelWriter(MarketProductLabelClassifier classifier,
			MarketProductLabelJdbcRepository labelRepository, MarketItemGroupCatalog catalog, MarketLabelProperties properties) {
		return new MarketProductLabelWriter(classifier, labelRepository, catalog, properties.version(), properties.batchSize(),
				properties.threads());
	}
}
