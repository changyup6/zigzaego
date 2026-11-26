package com.zigzaego.api.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/** {@code @Scheduled} 배치(추천 생성 04:00 등)를 켠다. 배치는 Spring {@code @Scheduled} 로 (DECISIONS #20). */
@Configuration
@EnableScheduling
@Profile(WebServerProfile.EXPRESSION)
public class SchedulingConfig {
}
