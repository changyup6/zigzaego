package com.zigzaego.api.inventory.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 하루 예상 소비량(3주 계획 4.3절). 최근 4주 중 같은 요일 값의 평균, 휴무일 제외.
 * 같은 요일 표본이 2개 미만이면 최근 14일 평균으로 대체한다.
 *
 * <p>표본 = 이력 구간 안의 휴무일이 아닌 날. 그날 값이 없으면 0 으로 센다 (판매가 없던 날도 하루다).
 * 이력 구간은 호출자가 정한다 — 가게의 첫 판매일 이전은 구간에 넣지 않아야 "앱을 쓰기 전"이 0 으로 섞이지 않는다.
 */
final class DailyRate {

	private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

	/** 소진일 탐색 상한(일). 이 안에 안 떨어지면 소진일 없음. */
	static final int MAX_LOOKAHEAD_DAYS = 365;

	private final Map<DayOfWeek, BigDecimal> byWeekday;

	private DailyRate(Map<DayOfWeek, BigDecimal> byWeekday) {
		this.byWeekday = byWeekday;
	}

	/**
	 * @param dailyQty    날짜별 값. 없는 날은 0
	 * @param from        이력 시작(포함). {@code to} 보다 늦으면 표본 없음
	 * @param to          이력 끝(포함). 보통 어제 — 오늘은 아직 진행 중이라 뺀다
	 * @param closedDays  휴무일. 표본에서 뺀다
	 * @return 표본이 하나도 없으면 null
	 */
	static DailyRate of(Map<LocalDate, BigDecimal> dailyQty, LocalDate from, LocalDate to, Set<LocalDate> closedDays) {
		if (from.isAfter(to)) {
			return null;
		}
		List<LocalDate> openDays = from.datesUntil(to.plusDays(1)).filter(d -> !closedDays.contains(d)).toList();
		if (openDays.isEmpty()) {
			return null;
		}
		LocalDate recentFrom = to.minusDays(13).isAfter(from) ? to.minusDays(13) : from;
		BigDecimal recentAverage = average(openDays.stream().filter(d -> !d.isBefore(recentFrom)).toList(), dailyQty);

		Map<DayOfWeek, BigDecimal> byWeekday = new EnumMap<>(DayOfWeek.class);
		for (DayOfWeek dow : DayOfWeek.values()) {
			List<LocalDate> sameWeekday = openDays.stream().filter(d -> d.getDayOfWeek() == dow).toList();
			byWeekday.put(dow, sameWeekday.size() >= 2 ? average(sameWeekday, dailyQty) : recentAverage);
		}
		return new DailyRate(byWeekday);
	}

	/** 요일별 값에 {@code factor} 를 곱한 새 비율. 주기형: 하루 그릇 수 × 그릇당 소비량. */
	DailyRate scaled(BigDecimal factor) {
		Map<DayOfWeek, BigDecimal> scaled = new EnumMap<>(DayOfWeek.class);
		byWeekday.forEach((dow, v) -> scaled.put(dow, v.multiply(factor)));
		return new DailyRate(scaled);
	}

	BigDecimal on(LocalDate date) {
		return byWeekday.get(date.getDayOfWeek());
	}

	/** 모든 요일이 0 이면 아무리 기다려도 줄지 않는다. */
	boolean isZero() {
		return byWeekday.values().stream().allMatch(v -> v.signum() == 0);
	}

	/**
	 * {@code remaining} 이 0 이하로 떨어지는 첫 날. 내일부터 하루씩 빼고 휴무일은 건너뛴다.
	 * 이미 0 이하면 오늘. {@link #MAX_LOOKAHEAD_DAYS} 안에 안 떨어지면 null.
	 */
	LocalDate runOutDate(BigDecimal remaining, LocalDate today, Set<LocalDate> closedDays) {
		if (remaining.signum() <= 0) {
			return today;
		}
		if (isZero()) {
			return null;
		}
		BigDecimal left = remaining;
		for (int i = 1; i <= MAX_LOOKAHEAD_DAYS; i++) {
			LocalDate day = today.plusDays(i);
			if (closedDays.contains(day)) {
				continue;
			}
			left = left.subtract(on(day));
			if (left.signum() <= 0) {
				return day;
			}
		}
		return null;
	}

	private static BigDecimal average(List<LocalDate> days, Map<LocalDate, BigDecimal> dailyQty) {
		if (days.isEmpty()) {
			return BigDecimal.ZERO;
		}
		BigDecimal sum = days.stream()
				.map(d -> dailyQty.getOrDefault(d, BigDecimal.ZERO))
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		return sum.divide(BigDecimal.valueOf(days.size()), MC);
	}
}
