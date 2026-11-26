package com.zigzaego.api.global.config;

/** 실제 운영자 러너 프로필은 공유 DB의 웹 작업과 예약 배치를 실행하지 않는다. */
public final class WebServerProfile {

	public static final String EXPRESSION = "!import-market-products & !create-account"
			+ " & !onboarding-replay-save & !onboarding-replay-delete";

	private WebServerProfile() {
	}
}
