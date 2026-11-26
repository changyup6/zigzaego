package com.zigzaego.api.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zigzaego.api.domain.order.DeliverySchedule.Terms;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 배송 요일 · 주문 마감으로 실제 L · R 을 세는 규칙 (3주 계획 4.4절, DECISIONS #89). 기준일 2026-10-08(목). */
class DeliveryScheduleTest {

	private static final LocalDate THU = LocalDate.of(2026, 10, 8);
	private static final Set<DayOfWeek> MON_WED_FRI = EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY);

	@Test
	void 배송_요일이_없으면_L_R_그대로() {
		Terms t = DeliverySchedule.resolve(THU, null, 2, 3, Set.of(), null);

		assertThat(t.orderDate()).isEqualTo(THU);
		assertThat(t.arrival()).isEqualTo(THU.plusDays(2));
		assertThat(t.nextArrival()).isEqualTo(THU.plusDays(5));
		assertThat(t.leadTimeDays()).isEqualTo(2);
		assertThat(t.cycleDays()).isEqualTo(3);
		assertThat(t.afterCutoff()).isFalse();
	}

	/** 목요일 주문, L=1 → 금요일 도착. 다음은 월요일 → R=3. */
	@Test
	void 월수금_배송이면_목요일_주문은_금요일_도착_다음은_월요일() {
		Terms t = DeliverySchedule.resolve(THU, null, 1, 1, MON_WED_FRI, null);

		assertThat(t.arrival()).isEqualTo(LocalDate.of(2026, 10, 9));
		assertThat(t.nextArrival()).isEqualTo(LocalDate.of(2026, 10, 12));
		assertThat(t.leadTimeDays()).isEqualTo(1);
		assertThat(t.cycleDays()).isEqualTo(3);
	}

	/** 금요일 주문, L=1 → 토요일은 배송 안 함 → 월요일 도착(L=3). 다음은 수요일(R=2). */
	@Test
	void 배송_없는_날에_걸리면_다음_배송_요일로_밀린다() {
		LocalDate fri = LocalDate.of(2026, 10, 9);
		Terms t = DeliverySchedule.resolve(fri, null, 1, 1, MON_WED_FRI, null);

		assertThat(t.arrival()).isEqualTo(LocalDate.of(2026, 10, 12));
		assertThat(t.nextArrival()).isEqualTo(LocalDate.of(2026, 10, 14));
		assertThat(t.leadTimeDays()).isEqualTo(3);
		assertThat(t.cycleDays()).isEqualTo(2);
	}

	/** 거래처 발주 주기 7일 + 월수금 배송: 다음 도착은 도착일 + 7 이후 첫 배송 요일. */
	@Test
	void 발주_주기와_배송_요일을_같이_쓰면_주기_뒤_첫_배송_요일() {
		Terms t = DeliverySchedule.resolve(THU, null, 1, 7, MON_WED_FRI, null);

		assertThat(t.arrival()).isEqualTo(LocalDate.of(2026, 10, 9));
		assertThat(t.nextArrival()).isEqualTo(LocalDate.of(2026, 10, 16));
		assertThat(t.cycleDays()).isEqualTo(7);
	}

	/** 가격 연동 R'(목 주문 → 금 도착, R=3 비쌈 → 2일): 금 + 2 = 일요일은 배송 안 함 → 월요일. 도착일 · L 은 그대로. */
	@Test
	void 주기를_바꿔도_다음_도착은_배송_요일에_맞춘다() {
		Terms base = DeliverySchedule.resolve(THU, null, 1, 1, MON_WED_FRI, null);

		Terms t = DeliverySchedule.withCycle(base, 2, MON_WED_FRI);

		assertThat(t.arrival()).isEqualTo(base.arrival());
		assertThat(t.leadTimeDays()).isEqualTo(1);
		assertThat(t.nextArrival()).isEqualTo(LocalDate.of(2026, 10, 12));
		assertThat(t.cycleDays()).isEqualTo(3);
		assertThat(DeliverySchedule.withCycle(base, 6, MON_WED_FRI).nextArrival()).isEqualTo(LocalDate.of(2026, 10, 16));
		assertThat(DeliverySchedule.withCycle(base, 0, Set.of()).cycleDays()).isEqualTo(1);
	}

	@Test
	void 마감_시각이_지나면_주문이_내일로_밀려_L이_하루_는다() {
		LocalTime cutoff = LocalTime.of(11, 0);

		Terms before = DeliverySchedule.resolve(THU, LocalTime.of(10, 59), 1, 1, Set.of(), cutoff);
		assertThat(before.afterCutoff()).isFalse();
		assertThat(before.arrival()).isEqualTo(THU.plusDays(1));
		assertThat(before.leadTimeDays()).isEqualTo(1);

		Terms after = DeliverySchedule.resolve(THU, LocalTime.of(11, 0), 1, 1, Set.of(), cutoff);
		assertThat(after.afterCutoff()).isTrue();
		assertThat(after.orderDate()).isEqualTo(THU.plusDays(1));
		assertThat(after.arrival()).isEqualTo(THU.plusDays(2));
		assertThat(after.leadTimeDays()).isEqualTo(2);
		assertThat(after.cycleDays()).isEqualTo(1);
	}

	/** 시각을 모르면(날짜 고정 계산) 마감을 보지 않는다. */
	@Test
	void 시각이_null_이면_마감을_보지_않는다() {
		Terms t = DeliverySchedule.resolve(THU, null, 1, 1, Set.of(), LocalTime.of(0, 1));

		assertThat(t.afterCutoff()).isFalse();
		assertThat(t.arrival()).isEqualTo(THU.plusDays(1));
	}

	/** 마감 뒤 + 월수금: 목 마감 지남 → 금 주문 → L=1 이면 토 → 월 도착. */
	@Test
	void 마감과_배송_요일을_같이_적용() {
		Terms t = DeliverySchedule.resolve(THU, LocalTime.of(18, 0), 1, 1, MON_WED_FRI, LocalTime.of(11, 0));

		assertThat(t.arrival()).isEqualTo(LocalDate.of(2026, 10, 12));
		assertThat(t.leadTimeDays()).isEqualTo(4);
		assertThat(t.nextArrival()).isEqualTo(LocalDate.of(2026, 10, 14));
	}

	@Test
	void 리드타임_0이면_오늘_도착_주기_0은_1로() {
		Terms t = DeliverySchedule.resolve(THU, null, 0, 0, Set.of(), null);

		assertThat(t.arrival()).isEqualTo(THU);
		assertThat(t.leadTimeDays()).isZero();
		assertThat(t.cycleDays()).isEqualTo(1);
	}

	@Test
	void 음수_리드타임은_거부() {
		assertThatThrownBy(() -> DeliverySchedule.resolve(THU, null, -1, 1, Set.of(), null))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
