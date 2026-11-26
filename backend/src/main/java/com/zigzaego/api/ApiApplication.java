package com.zigzaego.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

// JWT 인증(ADR-0005)이라 Spring Security 기본 인메모리 사용자 · 임시 비밀번호를 만들지 않는다
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class ApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(ApiApplication.class, args);
	}

}
