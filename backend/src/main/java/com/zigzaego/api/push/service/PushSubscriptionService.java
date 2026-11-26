package com.zigzaego.api.push.service;

import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.exception.CommonErrorCode;
import com.zigzaego.api.push.dto.PushSubscriptionRequest;
import com.zigzaego.api.push.sender.WebPushCrypto;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 웹 푸시 구독 (S15P31A308-248, DECISIONS #88). 브라우저 · 기기 하나가 한 줄(endpoint unique).
 * 같은 endpoint 로 다시 구독하면 가게 · 계정 · 키를 그 요청 것으로 바꾼다 — 같은 기기에서 다른 계정으로 로그인한 경우.
 *
 * <p>서버가 이 주소로 POST 하므로 https 이고 알려진 푸시 서비스 호스트만 받는다(내부망 주소로 요청을 보내게 만들지 못하게).
 */
@Service
@RequiredArgsConstructor
public class PushSubscriptionService {

	/** 브라우저 푸시 서비스 호스트(끝부분). Chrome · Edge(FCM), Firefox, Safari, 옛 Edge(WNS). */
	static final List<String> ALLOWED_HOST_SUFFIXES = List.of(
			"fcm.googleapis.com", "android.googleapis.com", "push.services.mozilla.com", "push.apple.com",
			"notify.windows.com");

	private static final String UPSERT_SQL = """
			INSERT INTO push_subscription (store_id, user_id, endpoint, p256dh, auth)
			VALUES (:storeId, :userId, :endpoint, :p256dh, :auth)
			ON CONFLICT (endpoint) DO UPDATE
			SET store_id = EXCLUDED.store_id, user_id = EXCLUDED.user_id, p256dh = EXCLUDED.p256dh, auth = EXCLUDED.auth
			""";

	private static final String DELETE_SQL = """
			DELETE FROM push_subscription WHERE store_id = :storeId AND endpoint = :endpoint
			""";

	private static final String TARGETS_SQL = """
			SELECT endpoint, p256dh, auth FROM push_subscription WHERE store_id = :storeId ORDER BY id
			""";

	private final NamedParameterJdbcTemplate jdbc;

	/** @throws BusinessException 주소 · 키 형식이 틀리면 400 COMMON_INVALID_INPUT */
	@Transactional
	public void subscribe(long storeId, long accountId, PushSubscriptionRequest request) {
		String endpoint = request.endpoint().strip();
		checkEndpoint(endpoint);
		checkKey(request.keys().p256dh(), 65, "keys.p256dh");
		checkKey(request.keys().auth(), 16, "keys.auth");
		jdbc.update(UPSERT_SQL, new MapSqlParameterSource()
				.addValue("storeId", storeId)
				.addValue("userId", accountId)
				.addValue("endpoint", endpoint)
				.addValue("p256dh", request.keys().p256dh().strip())
				.addValue("auth", request.keys().auth().strip()));
	}

	/** 내 가게의 그 구독을 지운다. 없으면 아무 일도 없다 — 같은 요청을 두 번 보내도 되고, 남의 가게 구독은 지우지 않는다. */
	@Transactional
	public void unsubscribe(long storeId, String endpoint) {
		delete(storeId, endpoint.strip());
	}

	/** 푸시 서비스가 404 · 410(구독 없음)을 준 구독을 지운다. */
	@Transactional
	public void removeGone(long storeId, String endpoint) {
		delete(storeId, endpoint);
	}

	@Transactional(readOnly = true)
	public List<PushSender.Target> targets(long storeId) {
		return jdbc.query(TARGETS_SQL, new MapSqlParameterSource("storeId", storeId),
				(rs, i) -> new PushSender.Target(rs.getString("endpoint"), rs.getString("p256dh"), rs.getString("auth")));
	}

	private void delete(long storeId, String endpoint) {
		jdbc.update(DELETE_SQL, new MapSqlParameterSource().addValue("storeId", storeId).addValue("endpoint", endpoint));
	}

	static void checkEndpoint(String endpoint) {
		URI uri;
		try {
			uri = URI.create(endpoint);
		} catch (IllegalArgumentException e) {
			throw invalid("endpoint (URL 형식이 아닙니다)");
		}
		String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
		boolean known = ALLOWED_HOST_SUFFIXES.stream().anyMatch(s -> host.equals(s) || host.endsWith("." + s));
		if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1 || !known) {
			throw invalid("endpoint (https 의 알려진 푸시 서비스 주소만 받습니다)");
		}
	}

	private static void checkKey(String value, int bytes, String field) {
		byte[] decoded;
		try {
			decoded = WebPushCrypto.base64Url(value);
		} catch (IllegalArgumentException e) {
			throw invalid(field + " (base64url 이 아닙니다)");
		}
		if (decoded.length != bytes) {
			throw invalid(field + " (" + bytes + "바이트여야 합니다)");
		}
	}

	private static BusinessException invalid(String detail) {
		return new BusinessException(CommonErrorCode.INVALID_INPUT, CommonErrorCode.INVALID_INPUT.getMessage() + " " + detail);
	}
}
