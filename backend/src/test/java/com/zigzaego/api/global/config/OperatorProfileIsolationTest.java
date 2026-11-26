package com.zigzaego.api.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.zigzaego.api.job.repository.JobRepository;
import com.zigzaego.api.job.service.JobRecovery;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

class OperatorProfileIsolationTest {

	@ParameterizedTest
	@ValueSource(strings = {"import-market-products", "create-account", "onboarding-replay-save", "onboarding-replay-delete"})
	void 운영자_프로필은_복구와_모든_예약작업_인프라를_등록하지_않는다(String profile) {
		JobRepository jobs = mock(JobRepository.class);
		new ApplicationContextRunner()
				.withPropertyValues("spring.profiles.active=" + profile)
				.withBean(JobRepository.class, () -> jobs)
				.withUserConfiguration(SchedulingConfig.class, JobRecovery.class)
				.run(context -> {
					assertThat(context).doesNotHaveBean(JobRecovery.class);
					assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
					context.publishEvent(new ContextRefreshedEvent(context.getSourceApplicationContext()));
					verifyNoInteractions(jobs);
				});
	}
}
