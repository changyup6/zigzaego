package com.zigzaego.api.domain.order;

import com.zigzaego.api.ingredient.entity.StorageType;
import com.zigzaego.api.recommendation.entity.SafetyStockMethod;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 재료 하나의 안전재고 (설계 5.3절, DECISIONS #28).
 *
 * <pre>
 * STAT    SS = z × σ × √(R + L)
 *           σ  최근 28일 하루 소비량의 표본 표준편차 (휴무일 제외, 판매가 없던 날은 0)
 *           z  냉동 · 상온 97% (1.88) / 냉장 95% (1.65) / 신선 90% (1.28)
 * DAYS_N  SS = 최근 7일 평균 소비량 × N     — 소비 이력 28일 미만 · 주기형 재료
 * </pre>
 *
 * <p>상태 없는 순수 계산이다. 이력 구간의 끝은 보통 어제(오늘은 아직 진행 중). 가게 첫 판매일 이전은 표본에 넣지 않는다 —
 * "앱을 쓰기 전"이 0 으로 섞이지 않게. R 은 기본 발주 주기다. 가격 연동 R 조정(3주 계획 4.8절)은 SS 를 바꾸지 않는다.
 */
public class SafetyStockCalculator {

	/** STAT 식에 필요한 최소 소비 이력 길이(일). */
	public static final int STAT_MIN_HISTORY_DAYS = 28;

	/** DAYS_N 식의 평균 길이(일). */
	public static final int DAYS_N_WINDOW = 7;

	private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
	private static final BigDecimal Z_97 = new BigDecimal("1.88");
	private static final BigDecimal Z_95 = new BigDecimal("1.65");
	private static final BigDecimal Z_90 = new BigDecimal("1.28");

	/**
	 * @param storageType      보관 방식. 모르면 null → 냉장(1.65)
	 * @param dailyConsumption 날짜별 하루 소비량(기준 단위). 없는 날은 0
	 * @param historyTo        이력 끝(포함). 보통 어제
	 * @param firstSaleDate    가게 첫 판매일. null 이면 판매 기록이 없다 → 표본 없음
	 * @param closedDays       휴무일. 표본에서 뺀다
	 * @param safetyDays       N(일). DAYS_N 에서만 쓴다
	 * @param leadTimeDays     L(일)
	 * @param cycleDays        R(일). 기본 발주 주기
	 * @param forceDaysN       true 면 이력 길이와 무관하게 DAYS_N (주기형)
	 */
	public record SafetyStockInput(StorageType storageType, Map<LocalDate, BigDecimal> dailyConsumption,
			LocalDate historyTo, LocalDate firstSaleDate, Set<LocalDate> closedDays, BigDecimal safetyDays,
			int leadTimeDays, int cycleDays, boolean forceDaysN) {

		public SafetyStockInput {
			Objects.requireNonNull(dailyConsumption, "dailyConsumption");
			Objects.requireNonNull(historyTo, "historyTo");
			Objects.requireNonNull(closedDays, "closedDays");
			Objects.requireNonNull(safetyDays, "safetyDays");
		}
	}

	/**
	 * @param safetyStock   SS. 0 이상
	 * @param method        쓴 식
	 * @param sigma         STAT 일 때 σ. DAYS_N 이면 null
	 * @param recentAverage DAYS_N 일 때 최근 7일 평균. STAT 이면 null
	 */
	public record SafetyStockResult(BigDecimal safetyStock, SafetyStockMethod method, BigDecimal sigma,
			BigDecimal recentAverage) {
	}

	public SafetyStockResult calculate(SafetyStockInput in) {
		if (!in.forceDaysN() && hasEnoughHistory(in)) {
			return stat(in);
		}
		return daysN(in);
	}

	/** 첫 판매일부터 이력 끝까지 28일 이상이면 σ 를 믿을 만하다고 본다. */
	private static boolean hasEnoughHistory(SafetyStockInput in) {
		return in.firstSaleDate() != null
				&& ChronoUnit.DAYS.between(in.firstSaleDate(), in.historyTo()) + 1 >= STAT_MIN_HISTORY_DAYS;
	}

	private static SafetyStockResult stat(SafetyStockInput in) {
		LocalDate from = in.historyTo().minusDays(STAT_MIN_HISTORY_DAYS - 1);
		List<BigDecimal> samples = openDays(from, in.historyTo(), in.firstSaleDate(), in.closedDays()).stream()
				.map(d -> in.dailyConsumption().getOrDefault(d, BigDecimal.ZERO))
				.toList();
		BigDecimal sigma = standardDeviation(samples);
		BigDecimal horizon = BigDecimal.valueOf(in.cycleDays() + in.leadTimeDays());
		BigDecimal ss = zOf(in.storageType()).multiply(sigma).multiply(horizon.sqrt(MC));
		return new SafetyStockResult(ss.max(BigDecimal.ZERO), SafetyStockMethod.STAT, sigma, null);
	}

	private static SafetyStockResult daysN(SafetyStockInput in) {
		LocalDate from = in.historyTo().minusDays(DAYS_N_WINDOW - 1);
		List<LocalDate> days = openDays(from, in.historyTo(), in.firstSaleDate(), in.closedDays());
		BigDecimal average = BigDecimal.ZERO;
		if (!days.isEmpty()) {
			BigDecimal sum = days.stream()
					.map(d -> in.dailyConsumption().getOrDefault(d, BigDecimal.ZERO))
					.reduce(BigDecimal.ZERO, BigDecimal::add);
			average = sum.divide(BigDecimal.valueOf(days.size()), MC);
		}
		BigDecimal ss = average.multiply(in.safetyDays());
		return new SafetyStockResult(ss.max(BigDecimal.ZERO), SafetyStockMethod.DAYS_N, null, average);
	}

	/** 구간 안에서 첫 판매일 이후 · 휴무일이 아닌 날. */
	private static List<LocalDate> openDays(LocalDate from, LocalDate to, LocalDate firstSale, Set<LocalDate> closed) {
		LocalDate start = firstSale != null && firstSale.isAfter(from) ? firstSale : from;
		if (start.isAfter(to)) {
			return List.of();
		}
		return start.datesUntil(to.plusDays(1)).filter(d -> !closed.contains(d)).toList();
	}

	/** 표본 표준편차(n − 1). 표본이 2개 미만이면 0. */
	static BigDecimal standardDeviation(List<BigDecimal> samples) {
		int n = samples.size();
		if (n < 2) {
			return BigDecimal.ZERO;
		}
		BigDecimal mean = samples.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(n), MC);
		BigDecimal sumSq = samples.stream()
				.map(v -> v.subtract(mean).pow(2))
				.reduce(BigDecimal.ZERO, BigDecimal::add);
		return sumSq.divide(BigDecimal.valueOf(n - 1), MC).sqrt(MC);
	}

	static BigDecimal zOf(StorageType storageType) {
		if (storageType == null) {
			return Z_95;
		}
		return switch (storageType) {
			case FROZEN, AMBIENT -> Z_97;
			case CHILLED -> Z_95;
			case FRESH -> Z_90;
		};
	}
}
