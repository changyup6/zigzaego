package com.zigzaego.api.auth.service;

import com.zigzaego.api.auth.AuthUser;
import com.zigzaego.api.auth.jwt.JwtProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * refresh token 저장소 (ADR-0005). 토큰은 256비트 랜덤 문자열이고, Redis에는 원문 대신 SHA-256 해시를 키로 둔다.
 * 키 {@code auth:refresh:<hash>} → 값 {@code <accountId>:<storeId>}, TTL = refresh 만료.
 * 토큰마다 키가 따로라 폰 · PC 로그인이 서로를 끊지 않는다.
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenStore {

	private static final String KEY_PREFIX = "auth:refresh:";
	private static final int TOKEN_BYTES = 32;

	private final StringRedisTemplate redis;
	private final JwtProperties properties;
	private final SecureRandom random = new SecureRandom();

	public String issue(AuthUser user) {
		byte[] bytes = new byte[TOKEN_BYTES];
		random.nextBytes(bytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		redis.opsForValue().set(key(token), user.accountId() + ":" + user.storeId(), properties.refreshTtl());
		return token;
	}

	/** 토큰을 꺼내면서 지운다(GETDEL). 같은 토큰은 한 번만 쓸 수 있다 — rotation. */
	public Optional<AuthUser> consume(String token) {
		String value = redis.opsForValue().getAndDelete(key(token));
		if (value == null) {
			return Optional.empty();
		}
		String[] parts = value.split(":");
		return Optional.of(new AuthUser(Long.parseLong(parts[0]), Long.parseLong(parts[1])));
	}

	public void revoke(String token) {
		redis.delete(key(token));
	}

	private static String key(String token) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
			return KEY_PREFIX + HexFormat.of().formatHex(hash);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
