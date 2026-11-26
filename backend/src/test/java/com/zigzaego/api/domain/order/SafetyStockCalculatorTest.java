package com.zigzaego.api.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.zigzaego.api.domain.order.SafetyStockCalculator.SafetyStockInput;
import com.zigzaego.api.domain.order.SafetyStockCalculator.SafetyStockResult;
import com.zigzaego.api.ingredient.entity.StorageType;
import com.zigzaego.api.recommendation.entity.SafetyStockMethod;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 설계 5.3절 식을 손으로 계산한 값과 맞춘다. 이력 끝 = 2026-10-09(어제). */
class SafetyStockCalculatorTest {

	private static final LocalDate YESTERDAY = LocalDate.of(2026, 10, 9);

	private final SafetyStockCalculator calculator = new SafetyStockCalculator();

	/**
	 * 이력 7일(10-03 ~ 10-09) &lt; 28일 → DAYS_N. 소비 1500 · 1500 · 0(휴무, 제외) · 1500 · 1500 · 1500 · 1500
	 * → 평균 1500 × N 2 = 3000
	 */
	@Test
	void 이력이_28일_미만이면_최근_7일_평균_곱하기_N() {
		Map<LocalDate, BigDecimal> daily = new HashMap<>();
		LocalDate first = LocalDate.of(2026, 10, 3);
		for (int i = 0; i < 7; i++) {
			daily.put(first.plusDays(i), new BigDecimal("1500"));
		}
		daily.remove(LocalDate.of(2026, 10, 5));

		SafetyStockResult r = calculator.calculate(new SafetyStockInput(StorageType.CHILLED, daily, YESTERDAY, first,
				Set.of(LocalDate.of(2026, 10, 5)), new BigDecimal("2"), 1, 1, false));

		assertThat(r.method()).isEqualTo(SafetyStockMethod.DAYS_N);
		assertThat(r.recentAverage()).isEqualByComparingTo("1500");
		assertThat(r.safetyStock()).isEqualByComparingTo("3000");
		assertThat(r.sigma()).isNull();
	}

	/**
	 * 이력 28일(09-12 ~ 10-09), 소비가 1000 · 2000 번갈아 → 표본 표준편차 σ = √(28 × 500² / 27) ≈ 509.175
	 * 냉동 z 1.88 · R 2 · L 1 → SS = 1.88 × σ × √3 ≈ 1658.00
	 */
	@Test
	void 이력이_28일_이상이면_STAT() {
		Map<LocalDate, BigDecimal> daily = new HashMap<>();
		LocalDate first = LocalDate.of(2026, 9, 12);
		for (int i = 0; i < 28; i++) {
			daily.put(first.plusDays(i), new BigDecimal(i % 2 == 0 ? "1000" : "2000"));
		}

		SafetyStockResult r = calculator.calculate(new SafetyStockInput(StorageType.FROZEN, daily, YESTERDAY, first,
				Set.of(), BigDecimal.ONE, 1, 2, false));

		assertThat(r.method()).isEqualTo(SafetyStockMethod.STAT);
		assertThat(r.sigma()).isCloseTo(new BigDecimal("509.175"), org.assertj.core.data.Offset.offset(new BigDecimal("0.001")));
		assertThat(r.safetyStock()).isCloseTo(new BigDecimal("1658.00"), org.assertj.core.data.Offset.offset(new BigDecimal("0.01")));
		assertThat(r.recentAverage()).isNull();
	}

	/**
	 * 경계: 첫 판매일 09-13 → 이력 27일 → STAT 조건 미달 → DAYS_N 으로 대체. 앞 20일은 2000 · 최근 7일은 1500 이라 σ 는 0 이
	 * 아니지만(STAT 이었다면 SS &gt; 0 · 식이 다름) 최근 7일 평균 1500 × N 2 = 3000. 같은 데이터로 첫 판매일만 하루 앞(09-12,
	 * 28일)이면 STAT.
	 */
	@Test
	void 이력이_27일이면_DAYS_N으로_대체하고_28일부터_STAT() {
		Map<LocalDate, BigDecimal> daily = new HashMap<>();
		LocalDate first = LocalDate.of(2026, 9, 13);
		for (int i = 0; i < 27; i++) {
			daily.put(first.plusDays(i), new BigDecimal(i < 20 ? "2000" : "1500"));
		}

		SafetyStockResult r = calculator.calculate(new SafetyStockInput(StorageType.CHILLED, daily, YESTERDAY, first,
				Set.of(), new BigDecimal("2"), 1, 1, false));

		assertThat(r.method()).isEqualTo(SafetyStockMethod.DAYS_N);
		assertThat(r.recentAverage()).isEqualByComparingTo("1500");
		assertThat(r.safetyStock()).isEqualByComparingTo("3000");
		assertThat(r.sigma()).isNull();

		SafetyStockResult atBoundary = calculator.calculate(new SafetyStockInput(StorageType.CHILLED, daily, YESTERDAY,
				first.minusDays(1), Set.of(), new BigDecimal("2"), 1, 1, false));

		assertThat(atBoundary.method()).isEqualTo(SafetyStockMethod.STAT);
		assertThat(atBoundary.sigma()).isPositive();
		assertThat(atBoundary.safetyStock()).isNotEqualByComparingTo("3000");
	}

	@Test
	void 소비가_일정하면_STAT_안전재고는_0() {
		Map<LocalDate, BigDecimal> daily = new HashMap<>();
		LocalDate first = LocalDate.of(2026, 9, 1);
		for (int i = 0; i < 39; i++) {
			daily.put(first.plusDays(i), new BigDecimal("1500"));
		}

		SafetyStockResult r = calculator.calculate(new SafetyStockInput(StorageType.AMBIENT, daily, YESTERDAY, first,
				Set.of(), BigDecimal.ONE, 1, 1, false));

		assertThat(r.method()).isEqualTo(SafetyStockMethod.STAT);
		assertThat(r.safetyStock()).isEqualByComparingTo("0");
	}

	@Test
	void 주기형은_이력이_길어도_DAYS_N() {
		Map<LocalDate, BigDecimal> daily = new HashMap<>();
		LocalDate first = LocalDate.of(2026, 9, 1);
		for (int i = 0; i < 39; i++) {
			daily.put(first.plusDays(i), new BigDecimal("10"));
		}

		SafetyStockResult r = calculator.calculate(new SafetyStockInput(null, daily, YESTERDAY, first, Set.of(),
				new BigDecimal("3"), 1, 1, true));

		assertThat(r.method()).isEqualTo(SafetyStockMethod.DAYS_N);
		assertThat(r.safetyStock()).isEqualByComparingTo("30");
	}

	@Test
	void 판매_기록이_없으면_0() {
		SafetyStockResult r = calculator.calculate(new SafetyStockInput(StorageType.FRESH, Map.of(), YESTERDAY, null,
				Set.of(), new BigDecimal("2"), 1, 1, false));

		assertThat(r.method()).isEqualTo(SafetyStockMethod.DAYS_N);
		assertThat(r.safetyStock()).isEqualByComparingTo("0");
	}

	@Test
	void z값은_보관_방식별() {
		assertThat(SafetyStockCalculator.zOf(StorageType.FROZEN)).isEqualByComparingTo("1.88");
		assertThat(SafetyStockCalculator.zOf(StorageType.AMBIENT)).isEqualByComparingTo("1.88");
		assertThat(SafetyStockCalculator.zOf(StorageType.CHILLED)).isEqualByComparingTo("1.65");
		assertThat(SafetyStockCalculator.zOf(StorageType.FRESH)).isEqualByComparingTo("1.28");
		assertThat(SafetyStockCalculator.zOf(null)).isEqualByComparingTo("1.65");
	}

	@Test
	void 표본_표준편차는_n_minus_1() {
		BigDecimal sd = SafetyStockCalculator.standardDeviation(List.of(new BigDecimal("2"), new BigDecimal("4"),
				new BigDecimal("4"), new BigDecimal("4"), new BigDecimal("5"), new BigDecimal("5"), new BigDecimal("7"),
				new BigDecimal("9")));
		// 평균 5, 편차 제곱 합 32, 32/7 = 4.571… → √ ≈ 2.138
		assertThat(sd).isCloseTo(new BigDecimal("2.138"), org.assertj.core.data.Offset.offset(new BigDecimal("0.001")));
		assertThat(SafetyStockCalculator.standardDeviation(List.of(BigDecimal.ONE))).isEqualByComparingTo("0");
	}
}
