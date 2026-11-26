package com.zigzaego.api.support;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** "오늘" 을 고정한다. 서울 2026-10-14(수) 12:00. 이 설정을 import 한 테스트끼리는 컨텍스트를 같이 쓴다. */
@TestConfiguration(proxyBeanMethods = false)
public class FixedClockConfig {

	public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	public static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

	@Bean
	@Primary
	Clock fixedClock() {
		return Clock.fixed(Instant.parse("2026-10-14T03:00:00Z"), SEOUL);
	}
}
