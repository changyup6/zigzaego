package com.zigzaego.api.auth.jwt;

import com.zigzaego.api.auth.AuthUser;
import com.zigzaego.api.auth.exception.AuthErrorCode;
import com.zigzaego.api.global.exception.BusinessException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** access token(JWT, HS256) 발급 · 검증. refresh token은 JWT가 아니다 — {@code RefreshTokenStore} 참조. */
@Component
public class JwtTokenProvider {

	private static final String STORE_ID_CLAIM = "storeId";

	private final JwtProperties properties;
	private final Clock clock;
	private final SecretKey key;
	private final JwtParser parser;

	@Autowired
	public JwtTokenProvider(JwtProperties properties) {
		this(properties, Clock.systemUTC());
	}

	JwtTokenProvider(JwtProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
		this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
		this.parser = Jwts.parser()
				.verifyWith(key)
				.clock(() -> Date.from(clock.instant()))
				.build();
	}

	public String createAccessToken(AuthUser user) {
		Instant now = clock.instant();
		return Jwts.builder()
				.subject(String.valueOf(user.accountId()))
				.claim(STORE_ID_CLAIM, user.storeId())
				.issuedAt(Date.from(now))
				.expiration(Date.from(now.plus(properties.accessTtl())))
				.signWith(key)
				.compact();
	}

	/**
	 * @throws BusinessException 만료면 {@link AuthErrorCode#TOKEN_EXPIRED}, 서명 · 형식 · 클레임이 틀리면
	 *                           {@link AuthErrorCode#INVALID_TOKEN}
	 */
	public AuthUser parseAccessToken(String token) {
		try {
			Claims claims = parser.parseSignedClaims(token).getPayload();
			Long storeId = claims.get(STORE_ID_CLAIM, Long.class);
			if (claims.getSubject() == null || storeId == null) {
				throw new BusinessException(AuthErrorCode.INVALID_TOKEN);
			}
			return new AuthUser(Long.parseLong(claims.getSubject()), storeId);
		} catch (ExpiredJwtException e) {
			throw new BusinessException(AuthErrorCode.TOKEN_EXPIRED);
		} catch (JwtException | IllegalArgumentException e) {
			// NumberFormatException(subject가 숫자가 아님)도 여기로 온다
			throw new BusinessException(AuthErrorCode.INVALID_TOKEN);
		}
	}

	public long accessTtlSeconds() {
		return properties.accessTtl().toSeconds();
	}
}
