package com.zigzaego.api.push;

import static com.zigzaego.api.support.StoreFixtures.insertIngredient;
import static org.assertj.core.api.Assertions.assertThat;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.auth.AuthTestSupport;
import com.zigzaego.api.push.service.ClosingPushService;
import com.zigzaego.api.push.service.PushSender;
import com.zigzaego.api.support.FixedClockConfig;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 마감 시각 알림 (S15P31A308-248). 지금은 FixedClockConfig 의 서울 2026-10-14(수, 요일 3) 12:00 고정.
 * 마감 시각 전 · 후 · 이미 보냄 · 휴무일 · 확인할 재료 없음 · 410 구독 삭제. 푸시 서비스는 가짜로 바꾼다.
 * 다른 테스트의 가게도 같은 DB 에 있으므로 결과는 이 테스트 가게의 구독 주소로만 본다.
 */
@Import({TestcontainersConfiguration.class, FixedClockConfig.class, ClosingPushServiceTest.FakeSenderConfig.class})
@SpringBootTest
class ClosingPushServiceTest {

	private static final LocalDate TODAY = FixedClockConfig.TODAY;
	private static final int WEDNESDAY = 3;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ClosingPushService service;

	@Autowired
	private FakeSender sender;

	@TestConfiguration(proxyBeanMethods = false)
	static class FakeSenderConfig {

		@Bean
		@Primary
		FakeSender fakeSender() {
			return new FakeSender();
		}
	}

	/** 보낸 구독 주소를 남기고, 주소마다 정한 상태(기본 201)를 돌려준다. */
	static class FakeSender implements PushSender {

		final List<String> sent = new CopyOnWriteArrayList<>();
		final List<String> payloads = new CopyOnWriteArrayList<>();
		final Map<String, Integer> status = new ConcurrentHashMap<>();

		@Override
		public int send(Target target, String payload) {
			sent.add(target.endpoint());
			payloads.add(payload);
			return status.getOrDefault(target.endpoint(), 201);
		}

		@Override
		public boolean ready() {
			return true;
		}
	}

	@BeforeEach
	void clear() {
		sender.sent.clear();
		sender.payloads.clear();
	}

	/** 실사 없는 재료 하나(확인 대상) · 구독 하나 · 오늘(수) 마감 시각. */
	private long store(String closeTime, String endpoint) {
		long store = AuthTestSupport.insertStore(jdbc, "마감알림-" + System.nanoTime());
		insertIngredient(jdbc, store, "양파");
		long account = jdbc.queryForObject("INSERT INTO account (email, password_hash, name) VALUES (?, 'x', '사장')"
				+ " RETURNING id", Long.class, "push-" + System.nanoTime() + "@test.local");
		jdbc.update("INSERT INTO push_subscription (store_id, user_id, endpoint, p256dh, auth) VALUES (?, ?, ?, 'p', 'a')",
				store, account, endpoint);
		jdbc.update("INSERT INTO store_closing_time (store_id, day_of_week, close_time) VALUES (?, ?, CAST(? AS time))",
				store, WEDNESDAY, closeTime);
		return store;
	}

	private static String endpoint() {
		return "https://fcm.googleapis.com/fcm/send/" + System.nanoTime();
	}

	private LocalDate lastPush(long store) {
		return jdbc.queryForObject("SELECT last_closing_push_date FROM store WHERE id = ?", LocalDate.class, store);
	}

	@Test
	void 마감_시각이_지나면_한_번_보내고_같은_날_다시_보내지_않는다() {
		String endpoint = endpoint();
		long store = store("11:00", endpoint);

		service.runOnce();
		service.runOnce();

		assertThat(sender.sent).containsOnlyOnce(endpoint);
		assertThat(lastPush(store)).isEqualTo(TODAY);
		assertThat(sender.payloads.get(sender.sent.indexOf(endpoint)))
				.contains("\"title\":\"오늘 재고 확인\"").contains("\"url\":\"/closing-check\"").contains("확인할 재료 1개");
	}

	@Test
	void 마감_시각_전이면_보내지_않는다() {
		String endpoint = endpoint();
		long store = store("13:00", endpoint);

		service.runOnce();

		assertThat(sender.sent).doesNotContain(endpoint);
		assertThat(lastPush(store)).isNull();
	}

	@Test
	void 이미_보낸_날이면_보내지_않는다() {
		String endpoint = endpoint();
		long store = store("11:00", endpoint);
		jdbc.update("UPDATE store SET last_closing_push_date = ? WHERE id = ?", TODAY, store);

		service.runOnce();

		assertThat(sender.sent).doesNotContain(endpoint);
	}

	@Test
	void 휴무일이면_보내지_않는다() {
		String endpoint = endpoint();
		long store = store("11:00", endpoint);
		jdbc.update("INSERT INTO store_closed_day (store_id, closed_date) VALUES (?, ?)", store, TODAY);

		service.runOnce();

		assertThat(sender.sent).doesNotContain(endpoint);
		assertThat(lastPush(store)).isNull();
	}

	@Test
	void 마감_뒤_3시간이_지났거나_그날_줄이_없으면_보내지_않는다() {
		String late = endpoint();
		store("08:00", late);
		String noRow = endpoint();
		long store = store("11:00", noRow);
		jdbc.update("UPDATE store_closing_time SET day_of_week = 4 WHERE store_id = ?", store);

		service.runOnce();

		assertThat(sender.sent).doesNotContain(late, noRow);
	}

	@Test
	void 확인할_재료가_없으면_보내지_않고_표시도_하지_않는다() {
		String endpoint = endpoint();
		long store = store("11:00", endpoint);
		jdbc.update("DELETE FROM ingredient WHERE store_id = ?", store);

		service.runOnce();

		assertThat(sender.sent).doesNotContain(endpoint);
		assertThat(lastPush(store)).isNull();
	}

	@Test
	void 푸시_서비스가_410_을_주면_그_구독을_지운다() {
		String endpoint = endpoint();
		store("11:00", endpoint);
		sender.status.put(endpoint, 410);

		service.runOnce();

		assertThat(sender.sent).contains(endpoint);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM push_subscription WHERE endpoint = ?", Integer.class, endpoint))
				.isZero();
	}
}
