package com.zigzaego.api.forecast.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 요일 평균 예측 (3주 계획 4.3절, DECISIONS #24). 채널 한 계열의 판매 기록으로 대상일 하루 판매량을 낸다.
 *
 * <pre>
 * 이력 구간 = 끝(end)에서 거슬러 4주(28일). end 는 호출자가 정한다 — 보통 대상일 전날과 어제 중 이른 날
 *   채널 첫 판매일 ≤ 구간 시작  → 구간 안 대상일과 같은 요일(최대 4일) 중 휴무일이 아닌 날의 평균
 *                                 (같은 요일이 전부 휴무면 아래 14일 평균)
 *   채널 첫 판매일 &gt; 구간 시작 → 이력 4주 미만. max(end−13, 첫 판매일) ~ end 중 휴무일이 아닌 날의 평균 (14일 평균)
 * 판매가 없던 날은 0 으로 센다 (문을 연 날이다). 표본이 하나도 없으면 0.
 * </pre>
 */
final class DowAverage {

	private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

	/** 요일 평균 이력 길이(일). 4주. */
	static final int HISTORY_DAYS = 28;

	/** 이력이 4주 미만일 때 쓰는 평균 길이(일). */
	static final int SHORT_HISTORY_DAYS = 14;

	private DowAverage() {
	}

	/**
	 * @param dailyQty   채널 한 계열의 날짜별 판매량. 없는 날은 0
	 * @param targetDate 대상일. 요일만 쓴다
	 * @param firstSale  이 채널의 첫 판매일. null 이면 이 채널 기록이 없다 → 0
	 * @param end        이력 끝(포함)
	 * @param closedDays 휴무일. 표본에서 뺀다
	 */
	static BigDecimal predict(Map<LocalDate, BigDecimal> dailyQty, LocalDate targetDate, LocalDate firstSale,
			LocalDate end, Set<LocalDate> closedDays) {
		if (firstSale == null || firstSale.isAfter(end)) {
			return BigDecimal.ZERO;
		}
		LocalDate start = end.minusDays(HISTORY_DAYS - 1);
		if (!firstSale.isAfter(start)) {
			List<LocalDate> sameWeekday = openDays(start, end, closedDays)
					.filter(d -> d.getDayOfWeek() == targetDate.getDayOfWeek())
					.toList();
			if (!sameWeekday.isEmpty()) {
				return average(sameWeekday, dailyQty);
			}
		}
		LocalDate shortStart = end.minusDays(SHORT_HISTORY_DAYS - 1);
		LocalDate from = firstSale.isAfter(shortStart) ? firstSale : shortStart;
		return average(openDays(from, end, closedDays).toList(), dailyQty);
	}

	private static Stream<LocalDate> openDays(LocalDate from, LocalDate to, Set<LocalDate> closedDays) {
		if (from.isAfter(to)) {
			return Stream.empty();
		}
		return from.datesUntil(to.plusDays(1)).filter(d -> !closedDays.contains(d));
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
