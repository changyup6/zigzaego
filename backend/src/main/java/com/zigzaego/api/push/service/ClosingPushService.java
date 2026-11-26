package com.zigzaego.api.push.service;

import com.zigzaego.api.closingcheck.service.ClosingItemService;
import com.zigzaego.api.global.config.StoreClock;
import java.sql.Date;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 마감 시각 알림 (S15P31A308-248, DECISIONS #88 ①). 스케줄러가 5분마다 부른다.
 *
 * <pre>
 * 대상 가게   구독이 하나 이상 있고 마감 시각 줄이 있는 가게
 * 영업일 D   오늘 · 어제 중, D 요일 줄의 마감 시각(05:00 전이면 D+1 새벽) ≤ 지금 &lt; 마감 + 3시간 인 가장 늦은 날
 *            D 가 휴무일(store_closed_day)이거나 last_closing_push_date = D 면 보내지 않는다
 * 보낼 것    확인 대상 재료(ClosingItemService)가 0개면 보내지 않는다(표시도 안 함 — 그 뒤 생기면 보낸다)
 * 보내기     last_closing_push_date = D 를 먼저 조건부 UPDATE 로 잡고(서버가 둘이어도 한 번만), 구독마다 "오늘 재고 확인" 1건.
 *            푸시 서비스가 404 · 410 을 주면 그 구독을 지운다
 * </pre>
 *
 * VAPID 키가 없으면 아무것도 하지 않는다(키가 없다는 로그는 앱이 뜰 때 1줄).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClosingPushService {

	/** 이 시각 전의 마감은 영업을 시작한 날의 다음 날 새벽으로 본다. */
	static final LocalTime AFTER_MIDNIGHT_BEFORE = LocalTime.of(5, 0);
	/** 마감 뒤 이 시간 안에만 보낸다 — 서버가 오래 멈췄다 켜져도 아침에 보내지 않게. */
	static final Duration SEND_WINDOW = Duration.ofHours(3);
	static final String CLICK_URL = "/closing-check";

	private static final String STORES_SQL = """
			SELECT s.id, s.timezone, s.last_closing_push_date
			FROM store s
			WHERE EXISTS (SELECT 1 FROM push_subscription p WHERE p.store_id = s.id)
			  AND EXISTS (SELECT 1 FROM store_closing_time c WHERE c.store_id = s.id)
			ORDER BY s.id
			""";
	private static final String CLOSING_TIMES_SQL = """
			SELECT day_of_week, close_time FROM store_closing_time WHERE store_id = :storeId
			""";
	private static final String CLOSED_SQL = """
			SELECT EXISTS (SELECT 1 FROM store_closed_day WHERE store_id = :storeId AND closed_date = :date)
			""";
	private static final String CLAIM_SQL = """
			UPDATE store SET last_closing_push_date = :date
			WHERE id = :storeId AND last_closing_push_date IS DISTINCT FROM :date
			""";

	private final NamedParameterJdbcTemplate jdbc;
	private final ClosingItemService closingItemService;
	private final PushSubscriptionService pushSubscriptionService;
	private final PushSender pushSender;
	private final StoreClock clock;

	/** 한 번 돈 결과 — 테스트와 로그용. */
	public record Result(int sentStores, int notifications, int removedSubscriptions) {
	}

	public Result runOnce() {
		if (!pushSender.ready()) {
			return new Result(0, 0, 0);
		}
		int stores = 0;
		int sent = 0;
		int removed = 0;
		for (StoreRow row : jdbc.query(STORES_SQL, (rs, i) -> new StoreRow(rs.getLong("id"), rs.getString("timezone"),
				rs.getDate("last_closing_push_date") == null ? null : rs.getDate("last_closing_push_date").toLocalDate()))) {
			try {
				int[] r = runStore(row);
				if (r != null) {
					stores++;
					sent += r[0];
					removed += r[1];
				}
			} catch (RuntimeException e) {
				log.error("마감 알림 실패 — storeId={}", row.id(), e);
			}
		}
		return new Result(stores, sent, removed);
	}

	/** @return 보낸 수 · 지운 구독 수. 보내지 않았으면 null */
	private int[] runStore(StoreRow row) {
		ZoneId zone = ZoneId.of(row.timezone());
		LocalDateTime now = LocalDateTime.now(clock.of(row.id()).withZone(zone));
		LocalDate businessDate = dueBusinessDate(row.id(), now);
		if (businessDate == null || businessDate.equals(row.lastPushDate()) || closed(row.id(), businessDate)) {
			return null;
		}
		int items = closingItemService.items(row.id()).size();
		if (items == 0) {
			return null;
		}
		MapSqlParameterSource claim = new MapSqlParameterSource().addValue("storeId", row.id())
				.addValue("date", Date.valueOf(businessDate));
		if (jdbc.update(CLAIM_SQL, claim) != 1) {
			return null;
		}
		String payload = "{\"title\":\"오늘 재고 확인\",\"body\":\"확인할 재료 " + items + "개\",\"url\":\"" + CLICK_URL + "\"}";
		int sent = 0;
		int removed = 0;
		for (PushSender.Target target : pushSubscriptionService.targets(row.id())) {
			int status = pushSender.send(target, payload);
			if (status == 404 || status == 410) {
				pushSubscriptionService.removeGone(row.id(), target.endpoint());
				removed++;
			} else if (status >= 200 && status < 300) {
				sent++;
			} else {
				log.warn("마감 알림 전송 실패 — storeId={}, status={}", row.id(), status);
			}
		}
		log.info("마감 알림 — storeId={}, 영업일={}, 재료={}개, 보냄={}, 지운 구독={}", row.id(), businessDate, items, sent, removed);
		return new int[] {sent, removed};
	}

	/** 지금 알림을 보낼 영업일. 없으면 null. */
	LocalDate dueBusinessDate(long storeId, LocalDateTime now) {
		Map<Integer, LocalTime> times = new HashMap<>();
		jdbc.query(CLOSING_TIMES_SQL, new MapSqlParameterSource("storeId", storeId),
				rs -> {
					times.put(rs.getInt("day_of_week"), rs.getTime("close_time").toLocalTime());
				});
		List<LocalDate> candidates = new ArrayList<>(List.of(now.toLocalDate(), now.toLocalDate().minusDays(1)));
		for (LocalDate d : candidates) {
			LocalTime close = times.get(d.getDayOfWeek().getValue());
			if (close == null) {
				continue;
			}
			LocalDateTime closeAt = d.atTime(close).plusDays(close.isBefore(AFTER_MIDNIGHT_BEFORE) ? 1 : 0);
			if (!now.isBefore(closeAt) && now.isBefore(closeAt.plus(SEND_WINDOW))) {
				return d;
			}
		}
		return null;
	}

	private boolean closed(long storeId, LocalDate date) {
		return Boolean.TRUE.equals(jdbc.queryForObject(CLOSED_SQL, new MapSqlParameterSource()
				.addValue("storeId", storeId).addValue("date", Date.valueOf(date)), Boolean.class));
	}

	private record StoreRow(long id, String timezone, LocalDate lastPushDate) {
	}
}
