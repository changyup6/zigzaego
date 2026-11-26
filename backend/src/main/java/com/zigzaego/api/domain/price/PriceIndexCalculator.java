package com.zigzaego.api.domain.price;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 재료 하나의 가격 지수(현재 단가 ÷ 최근 8주 기준 단가)와 가격 수준을 계산한다.
 * 명세: docs/plans/2026-10-15_가격지수_정의.md 1~4절, 3주 개발 계획 4.8절.
 *
 * <p>상태 없는 순수 계산이다. 단위 단가 환산(price ÷ base_qty_per_pack)은 호출하는 쪽이 해서 넘긴다.
 *
 * <p>금액 타입은 API · DB 에서 아직 미정이라(docs/API.md 4절) 이 클래스 안에서만 BigDecimal 을 쓴다.
 */
public class PriceIndexCalculator {

	/** 기본 θ (보통으로 보는 폭). */
	public static final BigDecimal DEFAULT_THETA = new BigDecimal("0.10");

	/** 기준 기간 · 최근 기간 길이(일). */
	static final int WINDOW_DAYS = 56;

	/**
	 * 지수 계산에 필요한 단가 이력 길이(일) = 최근 기간 + 기준 기간. 이보다 오래된 관측은 결과에 영향이 없으니
	 * 이력을 읽는 쪽(PriceHistoryService · RecommendationService)은 이 날짜로 잘라 읽는다.
	 */
	public static final int LOOKBACK_DAYS = 2 * WINDOW_DAYS;

	/** 기준 기간 안 최소 표본 수(같은 날은 1건으로 접은 뒤). */
	static final int MIN_SAMPLES = 3;

	private static final Set<String> ALLOWED_SOURCES = Set.of("RECEIVE", "DOC", "MANUAL");
	private static final BigDecimal TWO = BigDecimal.valueOf(2);
	private static final BigDecimal HALF = new BigDecimal("0.5");
	private static final int INDEX_SCALE = 4;

	/** recommendation.price_level CHECK 값과 같은 이름. */
	public enum PriceLevel {
		HIGH, NORMAL, LOW
	}

	public enum PriceIndexReason {
		/** 지수를 계산했다. */
		OK,
		/** 기준 기간 표본이 3건 미만 → 지수 1. */
		TOO_FEW,
		/** 최근 8주(오늘 포함) 안에 단가가 없음 → 지수 1. */
		NO_RECENT
	}

	/** 단위 단가 관측 한 건. source 는 price_history.source 값. */
	public record PriceObservation(LocalDate observedOn, BigDecimal unitPrice, String source) {
	}

	/**
	 * @param basePrice    기준 단가. 계산할 수 없으면 null
	 * @param currentPrice 현재 단가. 최근 8주에 없으면 null
	 * @param sampleCount  기준 기간 표본 수(같은 날은 1건)
	 */
	public record PriceIndex(PriceLevel level, BigDecimal index, BigDecimal basePrice, BigDecimal currentPrice,
			int sampleCount, PriceIndexReason reason) {
	}

	private final BigDecimal theta;

	public PriceIndexCalculator() {
		this(DEFAULT_THETA);
	}

	public PriceIndexCalculator(BigDecimal theta) {
		this.theta = Objects.requireNonNull(theta, "theta");
	}

	public PriceIndex calculate(LocalDate today, List<PriceObservation> observations) {
		// 날짜별로 모은 뒤 그날 중앙값 1건으로 접는다 (오늘 이후 관측은 버림)
		TreeMap<LocalDate, List<BigDecimal>> byDay = new TreeMap<>();
		for (PriceObservation o : observations) {
			if (!isUsable(o) || o.observedOn().isAfter(today)) {
				continue;
			}
			byDay.computeIfAbsent(o.observedOn(), d -> new ArrayList<>()).add(o.unitPrice());
		}

		// 현재 단가 = 최근 8주(오늘 − 56일 ~ 오늘) 중 가장 최근 날짜의 값
		if (byDay.isEmpty() || byDay.lastKey().isBefore(today.minusDays(WINDOW_DAYS))) {
			return new PriceIndex(PriceLevel.NORMAL, BigDecimal.ONE, null, null, 0, PriceIndexReason.NO_RECENT);
		}
		LocalDate currentDate = byDay.lastKey();
		BigDecimal currentPrice = median(byDay.lastEntry().getValue());

		// 기준 기간 = 현재 단가 날짜 − 56일 ~ 현재 단가 날짜 − 1일 (현재 단가 날짜는 넣지 않는다)
		List<BigDecimal> baseSamples = new ArrayList<>();
		for (Map.Entry<LocalDate, List<BigDecimal>> e : byDay
				.subMap(currentDate.minusDays(WINDOW_DAYS), true, currentDate, false).entrySet()) {
			baseSamples.add(median(e.getValue()));
		}
		int sampleCount = baseSamples.size();

		if (sampleCount < MIN_SAMPLES) {
			BigDecimal basePrice = sampleCount == 0 ? null : median(baseSamples);
			return new PriceIndex(PriceLevel.NORMAL, BigDecimal.ONE, basePrice, currentPrice, sampleCount,
					PriceIndexReason.TOO_FEW);
		}

		BigDecimal basePrice = median(baseSamples);
		BigDecimal index = currentPrice.divide(basePrice, INDEX_SCALE, RoundingMode.HALF_UP);
		return new PriceIndex(levelOf(index), index, basePrice, currentPrice, sampleCount, PriceIndexReason.OK);
	}

	/**
	 * 가격 수준에 따른 발주 주기 R' (3주 계획 4.8절).
	 * 쌀 때 (유통기한 − L)일치 · max_order_qty 상한은 발주 엔진이 건다. 여기서는 배수만.
	 */
	public static BigDecimal adjustedCycleDays(BigDecimal r, PriceLevel level) {
		return switch (level) {
			case HIGH -> r.multiply(HALF).max(BigDecimal.ONE);
			case LOW -> r.multiply(TWO);
			case NORMAL -> r;
		};
	}

	private PriceLevel levelOf(BigDecimal index) {
		if (index.compareTo(BigDecimal.ONE.add(theta)) >= 0) {
			return PriceLevel.HIGH;
		}
		if (index.compareTo(BigDecimal.ONE.subtract(theta)) <= 0) {
			return PriceLevel.LOW;
		}
		return PriceLevel.NORMAL;
	}

	private static boolean isUsable(PriceObservation o) {
		return o != null
				&& o.observedOn() != null
				&& o.source() != null
				&& ALLOWED_SOURCES.contains(o.source())
				&& o.unitPrice() != null
				&& o.unitPrice().signum() > 0;
	}

	/** 중앙값. 짝수 개면 가운데 둘의 평균. 빈 목록은 받지 않는다. */
	private static BigDecimal median(List<BigDecimal> values) {
		List<BigDecimal> sorted = new ArrayList<>(values);
		sorted.sort(null);
		int n = sorted.size();
		if (n % 2 == 1) {
			return sorted.get(n / 2);
		}
		return sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(TWO);
	}
}
