package com.zigzaego.api.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 오래 걸리는 작업(job)을 요청 스레드와 따로 돌리는 실행기 (지라 S15P31A308-92).
 * 동시에 {@code JOB_EXECUTOR_POOL_SIZE}(기본 4)개, 대기열 100개. 꽉 차면 {@code JobService} 가 그 job 을 FAILED 로 둔다.
 * 이 빈이 있어도 {@code spring.task.execution.mode=force}(application.yaml) 로 Boot 기본 실행기 {@code applicationTaskExecutor} 가
 * 따로 만들어지고, 이름 없는 {@code @Async} 는 그쪽으로 간다. job 실행기를 쓰려면 이름({@value #JOB_EXECUTOR})으로 고른다.
 */
@Configuration
@EnableAsync
public class JobExecutorConfig {

	public static final String JOB_EXECUTOR = "jobExecutor";

	private static final int QUEUE_CAPACITY = 100;

	@Bean(name = JOB_EXECUTOR)
	public ThreadPoolTaskExecutor jobExecutor(@Value("${app.job.executor.pool-size}") int poolSize) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(poolSize);
		executor.setMaxPoolSize(poolSize);
		executor.setQueueCapacity(QUEUE_CAPACITY);
		executor.setThreadNamePrefix("job-");
		// 요청 스레드의 traceId 등 관측 컨텍스트를 job 스레드로 넘겨, job 로그가 요청 로그와 같은 traceId를 갖게 한다
		executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
		return executor;
	}
}
