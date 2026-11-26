package com.zigzaego.api.global.config;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 가게가 보는 시계. 시연 계정(ADR-0018)의 가게는 날짜가 {@code app.demo-account.today} 로 고정되고(시각은 실제 그대로),
 * 그 밖의 가게는 {@link Clock} 빈 그대로다 (ADR-0019). 가게의 "오늘" · 기록 시각은 이 시계로 읽는다.
 */
@Component
public class StoreClock {

	private static final String IS_DEMO_SQL = """
			SELECT EXISTS (
			    SELECT 1 FROM store_member m JOIN account a ON a.id = m.account_id
			    WHERE m.store_id = ? AND a.email = ?)
			""";

	private final Clock clock;
	private final JdbcTemplate jdbc;
	private final String demoEmail;
	/** null 이면 날짜를 고정하지 않는다. */
	private final LocalDate demoToday;

	@Autowired
	public StoreClock(Clock clock, JdbcTemplate jdbc, @Value("${app.demo-account.email:}") String demoEmail,
			@Value("${app.demo-account.today:}") String demoToday) {
		this.clock = clock;
		this.jdbc = jdbc;
		this.demoEmail = demoEmail.trim();
		this.demoToday = this.demoEmail.isEmpty() || demoToday.isBlank() ? null : LocalDate.parse(demoToday.trim());
	}

	/** 날짜를 고정하지 않는 시계. 테스트가 서비스를 직접 만들 때 쓴다. */
	public StoreClock(Clock clock) {
		this(clock, null, "", "");
	}

	/** 그 가게의 시계. 시연 가게면 날짜만 고정일로 옮긴 시계, 아니면 기본 시계. */
	public Clock of(long storeId) {
		// ponytail: 시연 설정이 있는 환경에서는 부를 때마다 조회 1번. 느려지면 가게 id 를 기억해 둔다(재가입 때 비운다)
		if (demoToday == null || !isDemoStore(storeId)) {
			return clock;
		}
		long back = ChronoUnit.DAYS.between(demoToday, LocalDate.now(clock));
		return Clock.offset(clock, Duration.ofDays(-back));
	}

	/** 현재 설정된 시연 이메일과 정확히 일치하는 가게만. 보관된 옛 계정은 포함하지 않는다. */
	public boolean isDemoStore(long storeId) {
		return !demoEmail.isEmpty() && Boolean.TRUE.equals(
				jdbc.queryForObject(IS_DEMO_SQL, Boolean.class, storeId, demoEmail));
	}

	/** 그 가게 시간대의 오늘. */
	public LocalDate today(long storeId, ZoneId zone) {
		return LocalDate.now(of(storeId).withZone(zone));
	}
}
