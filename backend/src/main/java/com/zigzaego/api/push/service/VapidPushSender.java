package com.zigzaego.api.push.service;

import com.zigzaego.api.push.config.PushProperties;
import com.zigzaego.api.push.sender.WebPushCrypto;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * VAPID 웹 푸시 (RFC 8030 · 8291 · 8292). 본문은 aes128gcm 으로 암호화하고 JWT 는 12시간짜리.
 * 키가 비었거나 짝이 안 맞으면 {@link #ready()} 가 false — 앱은 뜨고 마감 알림만 건너뛴다 (로그 1줄).
 */
@Slf4j
@Component
public class VapidPushSender implements PushSender {

	/** 알림을 푸시 서비스가 들고 있을 시간. 마감 확인은 그날 밤에만 뜻이 있다. */
	static final Duration TTL = Duration.ofHours(4);
	private static final Duration JWT_LIFETIME = Duration.ofHours(12);
	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final PushProperties properties;
	private final Clock clock;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
	private final byte[] publicKey;
	private final byte[] privateKey;

	public VapidPushSender(PushProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
		byte[] pub = null;
		byte[] priv = null;
		if (!properties.configured()) {
			log.info("VAPID 키가 없어 마감 확인 웹 푸시를 보내지 않는다 (VAPID_PUBLIC_KEY · VAPID_PRIVATE_KEY · VAPID_SUBJECT)");
		} else {
			try {
				pub = WebPushCrypto.base64Url(properties.vapidPublicKey());
				priv = WebPushCrypto.base64Url(properties.vapidPrivateKey());
			} catch (IllegalArgumentException e) {
				pub = null;
			}
			if (pub == null || !WebPushCrypto.isKeyPair(pub, priv)) {
				log.error("VAPID 공개키 · 개인키가 P-256 키 쌍이 아니라 마감 확인 웹 푸시를 보내지 않는다");
				pub = null;
				priv = null;
			}
		}
		this.publicKey = pub;
		this.privateKey = priv;
	}

	@Override
	public boolean ready() {
		return publicKey != null;
	}

	@Override
	public int send(Target target, String payload) {
		if (!ready()) {
			throw new IllegalStateException("VAPID keys are not configured");
		}
		URI endpoint = URI.create(target.endpoint());
		String audience = endpoint.getScheme() + "://" + endpoint.getRawAuthority();
		long exp = clock.instant().plus(JWT_LIFETIME).getEpochSecond();
		byte[] body = WebPushCrypto.encrypt(WebPushCrypto.base64Url(target.p256dh()), WebPushCrypto.base64Url(target.auth()),
				payload.getBytes(StandardCharsets.UTF_8));
		HttpRequest request = HttpRequest.newBuilder(endpoint)
				.timeout(TIMEOUT)
				.header("TTL", String.valueOf(TTL.toSeconds()))
				.header("Urgency", "normal")
				.header("Content-Type", "application/octet-stream")
				.header("Content-Encoding", "aes128gcm")
				.header("Authorization", WebPushCrypto.vapidAuthorization(audience, properties.vapidSubject(), exp,
						publicKey, privateKey))
				.POST(HttpRequest.BodyPublishers.ofByteArray(body))
				.build();
		try {
			return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
		} catch (IOException e) {
			log.warn("웹 푸시 전송 실패 — host={}, {}", endpoint.getHost(), e.toString());
			return -1;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return -1;
		}
	}
}
