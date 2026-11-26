package com.zigzaego.api.domain.order;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/**
 * 거래처 배송 일정으로 실제 리드타임 L 과 발주 주기 R 을 정한다 (3주 계획 4.4절 "R = 다음 발주 기회까지 날 수", DECISIONS #89).
 *
 * <pre>
 * 주문일      = 오늘. 주문 마감 시각(cutoff_time)이 있고 지금이 마감 이후면 내일
 * 도착일      = 주문일 + lead_time_days 이후 첫 배송 요일 (배송 요일이 없으면 주문일 + L 그대로)
 * 다음 도착일 = 도착일 + order_cycle_days(없으면 1) 이후 첫 배송 요일
 * L           = 오늘 → 도착일 날수,  R = 도착일 → 다음 도착일 날수
 * </pre>
 *
 * <p>배송 요일 · 마감 시각을 모르면 지금까지와 같다(L · R 상수). 상태 없는 순수 계산.
 */
public final class DeliverySchedule {

	/**
	 * @param orderDate    실제 주문일 (마감이 지났으면 내일)
	 * @param arrival      오늘 주문하면 도착하는 날
	 * @param nextArrival  그 다음 받을 수 있는 날 — 목표 재고는 여기까지 덮는다
	 * @param leadTimeDays 오늘 → 도착일 (일)
	 * @param cycleDays    도착일 → 다음 도착일 (일), 1 이상
	 * @param afterCutoff  주문 마감이 지나 주문이 내일로 밀렸는지
	 */
	public record Terms(LocalDate orderDate, LocalDate arrival, LocalDate nextArrival, int leadTimeDays, int cycleDays,
			boolean afterCutoff) {
	}

	private DeliverySchedule() {
	}

	/**
	 * @param today        가게 시간대의 오늘
	 * @param now          가게 시간대의 지금 시각. 날짜를 고정한 계산(테스트 · 재계산)이면 null — 마감을 보지 않는다
	 * @param leadTimeDays 거래처 리드타임(일), 0 이상
	 * @param cycleDays    거래처 발주 주기(일). 1 미만이면 1
	 * @param deliveryDays 배송 요일. 비어 있으면 매일
	 * @param cutoff       주문 마감 시각. 모르면 null
	 */
	public static Terms resolve(LocalDate today, LocalTime now, int leadTimeDays, int cycleDays,
			Set<DayOfWeek> deliveryDays, LocalTime cutoff) {
		if (leadTimeDays < 0) {
			throw new IllegalArgumentException("leadTimeDays must be >= 0");
		}
		boolean afterCutoff = now != null && cutoff != null && !now.isBefore(cutoff);
		LocalDate orderDate = afterCutoff ? today.plusDays(1) : today;
		int cycle = Math.max(1, cycleDays);

		LocalDate arrival = nextDeliveryOnOrAfter(orderDate.plusDays(leadTimeDays), deliveryDays);
		LocalDate nextArrival = nextDeliveryOnOrAfter(arrival.plusDays(cycle), deliveryDays);
		return new Terms(orderDate, arrival, nextArrival,
				(int) ChronoUnit.DAYS.between(today, arrival),
				(int) ChronoUnit.DAYS.between(arrival, nextArrival),
				afterCutoff);
	}

	/**
	 * 같은 도착일에서 발주 주기만 바꿔 다음 도착일을 다시 센다 — 가격 연동 R'(3주 계획 4.8절)도 실제 배송 요일에 맞춘다.
	 * 다음 도착일 = 도착일 + cycleDays 이후 첫 배송 요일. 주문일 · 도착일 · L · 마감 여부는 그대로.
	 *
	 * @param base         {@link #resolve} 결과
	 * @param cycleDays    바꾼 발주 주기(일). 1 미만이면 1
	 * @param deliveryDays 배송 요일. 비어 있으면 매일
	 */
	public static Terms withCycle(Terms base, int cycleDays, Set<DayOfWeek> deliveryDays) {
		LocalDate nextArrival = nextDeliveryOnOrAfter(base.arrival().plusDays(Math.max(1, cycleDays)), deliveryDays);
		return new Terms(base.orderDate(), base.arrival(), nextArrival, base.leadTimeDays(),
				(int) ChronoUnit.DAYS.between(base.arrival(), nextArrival), base.afterCutoff());
	}

	private static LocalDate nextDeliveryOnOrAfter(LocalDate date, Set<DayOfWeek> deliveryDays) {
		if (deliveryDays == null || deliveryDays.isEmpty()) {
			return date;
		}
		LocalDate d = date;
		for (int i = 0; i < 7; i++) {
			if (deliveryDays.contains(d.getDayOfWeek())) {
				return d;
			}
			d = d.plusDays(1);
		}
		throw new IllegalStateException("unreachable: deliveryDays has no valid DayOfWeek");
	}
}
