package com.zigzaego.api.push.service;

/** 푸시 서비스로 알림 하나를 보낸다. 테스트는 가짜로 바꾼다. */
public interface PushSender {

	/** 보낼 구독 하나. 키는 base64url 그대로. */
	record Target(String endpoint, String p256dh, String auth) {
	}

	/**
	 * @param payload 알림 본문(JSON, UTF-8)
	 * @return 푸시 서비스의 HTTP 상태. 연결 실패는 -1
	 */
	int send(Target target, String payload);

	/** VAPID 키가 있고 맞는 쌍이라 보낼 수 있는지. */
	boolean ready();
}
