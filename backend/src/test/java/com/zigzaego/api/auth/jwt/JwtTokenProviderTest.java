package com.zigzaego.api.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zigzaego.api.auth.AuthUser;
import com.zigzaego.api.auth.exception.AuthErrorCode;
import com.zigzaego.api.global.exception.BusinessException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import org.junit.jupiter.api.Test;

class JwtTokenProviderTest {

	private static final String SECRET = "unit-test-jwt-secret-0123456789abcdef";
	private static final Instant NOW = Instant.parse("2026-10-15T00:00:00Z");
	private static final JwtProperties PROPERTIES =
			new JwtProperties(SECRET, Duration.ofMinutes(30), Duration.ofDays(14));

	private final JwtTokenProvider provider = providerAt(NOW);

	@Test
	void 발급한_토큰을_읽으면_계정과_가게가_그대로_나온다() {
		String token = provider.createAccessToken(new AuthUser(7, 42));

		assertThat(provider.parseAccessToken(token)).isEqualTo(new AuthUser(7, 42));
		assertThat(provider.accessTtlSeconds()).isEqualTo(1800);
	}

	@Test
	void 만료_직전까지는_유효하고_지나면_TOKEN_EXPIRED() {
		String token = provider.createAccessToken(new AuthUser(7, 42));

		assertThat(providerAt(NOW.plus(Duration.ofMinutes(29))).parseAccessToken(token).storeId()).isEqualTo(42);
		assertErrorCode(() -> providerAt(NOW.plus(Duration.ofMinutes(31))).parseAccessToken(token),
				AuthErrorCode.TOKEN_EXPIRED);
	}

	@Test
	void 다른_키로_서명한_토큰은_INVALID_TOKEN() {
		JwtTokenProvider other = new JwtTokenProvider(
				new JwtProperties("another-secret-key-0123456789abcdefgh", Duration.ofMinutes(30), Duration.ofDays(14)),
				Clock.fixed(NOW, ZoneOffset.UTC));
		String forged = other.createAccessToken(new AuthUser(7, 999));

		assertErrorCode(() -> provider.parseAccessToken(forged), AuthErrorCode.INVALID_TOKEN);
	}

	@Test
	void 형식이_깨진_토큰은_INVALID_TOKEN() {
		assertErrorCode(() -> provider.parseAccessToken("not-a-jwt"), AuthErrorCode.INVALID_TOKEN);
		assertErrorCode(() -> provider.parseAccessToken(""), AuthErrorCode.INVALID_TOKEN);
	}

	@Test
	void 가게_클레임이_없거나_subject가_숫자가_아니면_INVALID_TOKEN() {
		var key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
		Date exp = Date.from(NOW.plus(Duration.ofMinutes(10)));
		String noStore = Jwts.builder().subject("7").expiration(exp).signWith(key).compact();
		String badSubject = Jwts.builder().subject("abc").claim("storeId", 42L).expiration(exp).signWith(key).compact();

		assertErrorCode(() -> provider.parseAccessToken(noStore), AuthErrorCode.INVALID_TOKEN);
		assertErrorCode(() -> provider.parseAccessToken(badSubject), AuthErrorCode.INVALID_TOKEN);
	}

	@Test
	void 서명_키가_32바이트보다_짧으면_설정에서_막는다() {
		assertThatThrownBy(() -> new JwtProperties("short", Duration.ofMinutes(30), Duration.ofDays(14)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("JWT_SECRET");
		assertThatThrownBy(() -> new JwtProperties("", Duration.ofMinutes(30), Duration.ofDays(14)))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void 만료가_0이하면_설정에서_막는다() {
		assertThatThrownBy(() -> new JwtProperties(SECRET, Duration.ZERO, Duration.ofDays(14)))
				.isInstanceOf(IllegalStateException.class);
	}

	private static JwtTokenProvider providerAt(Instant instant) {
		return new JwtTokenProvider(PROPERTIES, Clock.fixed(instant, ZoneOffset.UTC));
	}

	private static void assertErrorCode(Runnable call, AuthErrorCode expected) {
		assertThatThrownBy(call::run)
				.isInstanceOf(BusinessException.class)
				.extracting(e -> ((BusinessException) e).getErrorCode())
				.isEqualTo(expected);
	}
}
