package com.zigzaego.api.push.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 웹 푸시 설정 ({@code app.push}, backend/.env.example). VAPID 키가 비어 있으면 앱은 뜨고 마감 알림만 건너뛴다 (S15P31A308-247 · 248).
 *
 * @param vapidPublicKey  VAPID 공개키(비압축 P-256 점, base64url). FE 의 NEXT_PUBLIC_VAPID_PUBLIC_KEY 와 같은 값
 * @param vapidPrivateKey VAPID 개인키(32바이트 스칼라, base64url). 로그에 남기지 않는다
 * @param vapidSubject    연락처 — mailto: 또는 https: 주소. 푸시 서비스가 문제 있을 때 연락한다
 * @param closingCron     마감 알림 확인 주기 (Spring cron 6자리)
 * @param closingZone     cron 의 시간대. 마감 시각 판단은 가게마다 store.timezone 으로 한다
 */
@ConfigurationProperties(prefix = "app.push")
public record PushProperties(String vapidPublicKey, String vapidPrivateKey, String vapidSubject, String closingCron,
		String closingZone) {

	public boolean configured() {
		return notBlank(vapidPublicKey) && notBlank(vapidPrivateKey) && notBlank(vapidSubject);
	}

	private static boolean notBlank(String s) {
		return s != null && !s.isBlank();
	}
}
