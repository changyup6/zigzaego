package com.zigzaego.api.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zigzaego.api.TestcontainersConfiguration;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Async;

/** jobExecutor 를 둬도 Boot 기본 실행기(applicationTaskExecutor)가 따로 있고, 이름 없는 @Async 는 그쪽으로 간다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class JobExecutorConfigTest {

	@Autowired
	private ApplicationContext context;

	@Autowired
	private AsyncProbe asyncProbe;

	@Autowired
	private ObservationRegistry observationRegistry;

	@Test
	void applicationTaskExecutor가_jobExecutor와_따로_있다() {
		assertThat(context.containsBean("applicationTaskExecutor")).isTrue();
		assertThat(context.getBean("applicationTaskExecutor"))
				.isNotSameAs(context.getBean(JobExecutorConfig.JOB_EXECUTOR));
	}

	@Test
	void 이름_없는_Async는_jobExecutor로_가지_않는다() throws Exception {
		String threadName = asyncProbe.threadName().get(5, TimeUnit.SECONDS);

		assertThat(threadName).doesNotStartWith("job-");
		assertThat(threadName).isNotEqualTo(Thread.currentThread().getName());
	}

	@Test
	void job_스레드의_로그가_요청의_traceId를_이어받는다() throws Exception {
		TaskExecutor jobExecutor = context.getBean(JobExecutorConfig.JOB_EXECUTOR, TaskExecutor.class);
		CompletableFuture<String> jobTraceId = new CompletableFuture<>();
		String[] requestTraceId = new String[1];

		Observation.createNotStarted("test.request", observationRegistry).observe(() -> {
			requestTraceId[0] = MDC.get("traceId");
			jobExecutor.execute(() -> jobTraceId.complete(MDC.get("traceId")));
		});

		assertThat(requestTraceId[0]).isNotBlank();
		assertThat(jobTraceId.get(5, TimeUnit.SECONDS)).isEqualTo(requestTraceId[0]);
	}

	static class AsyncProbe {

		@Async
		public CompletableFuture<String> threadName() {
			return CompletableFuture.completedFuture(Thread.currentThread().getName());
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class AsyncProbeConfig {

		@Bean
		AsyncProbe asyncProbe() {
			return new AsyncProbe();
		}
	}
}
