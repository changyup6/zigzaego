package com.zigzaego.api.global.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** "오늘" 을 정하는 시계. 서비스 날짜는 서울 기준이다. 테스트는 고정 시계 빈(@Primary)으로 바꾼다. */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

	/** 서비스 날짜의 시간대. */
	public static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

	@Bean
	public Clock clock() {
		return Clock.system(SERVICE_ZONE);
	}
}
