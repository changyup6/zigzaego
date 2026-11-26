package com.zigzaego.api.domain.order;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * 실사 차이로 본 기록 안 된 손실 (S15P31A308-253, DECISIONS #88 ⑤).
 *
 * <pre>
 * 실사 한 번의 손실 = 추정 − 실사 (양수일 때만, 아니면 0)
 * 손실률            = 손실 ÷ 추정            (추정 ≤ 0 이면 판단하지 않는다)
 * 반복 손실         = 최근 실사 {@value #WINDOW}회 중 {@value #MIN_HITS}회 이상 손실률 ≥ {@link #LOSS_RATE}
 * 안전재고 상향분   = 그 {@value #WINDOW}회(모자라면 있는 만큼)의 평균 손실량
 * </pre>
 *
 * <p>손실을 추정하지 않는다(#30 유지) — 사장님이 센 값과 그때의 추정치 차이만 쓴다. 조건에서 벗어나면 상향분이 없다(자동으로 빠진다).
 * 10% · 3회 중 2회는 기본값이다. AI 지표 일감(S15P31A308-257) 결과로 바꿀 수 있게 상수로 둔다.
 */
public final class CountShortfall {

	/** 반복 손실로 보는 손실률 하한. */
	public static final BigDecimal LOSS_RATE = new BigDecimal("0.10");

	/** 보는 최근 실사 수. */
	public static final int WINDOW = 3;

	/** 그중 손실률 하한을 넘어야 하는 횟수. */
	public static final int MIN_HITS = 2;

	private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

	private CountShortfall() {
	}

	/** 실사 한 번 — 그때 보여 준(또는 서버가 채운) 추정치와 센 값. */
	public record Sample(BigDecimal estimated, BigDecimal counted) {

		BigDecimal loss() {
			return estimated.subtract(counted).max(BigDecimal.ZERO);
		}

		boolean hit() {
			return estimated.signum() > 0 && loss().divide(estimated, MC).compareTo(LOSS_RATE) >= 0;
		}
	}

	/**
	 * @param recent 최근 실사, 최신 먼저. {@value #WINDOW}개를 넘으면 앞 {@value #WINDOW}개만 본다
	 * @return 반복 손실이면 평균 손실량(기준 단위), 아니면 빈 값
	 */
	public static Optional<BigDecimal> averageLoss(List<Sample> recent) {
		List<Sample> window = recent.subList(0, Math.min(WINDOW, recent.size()));
		long hits = window.stream().filter(Sample::hit).count();
		if (hits < MIN_HITS) {
			return Optional.empty();
		}
		BigDecimal sum = window.stream().map(Sample::loss).reduce(BigDecimal.ZERO, BigDecimal::add);
		return Optional.of(sum.divide(BigDecimal.valueOf(window.size()), MC));
	}
}
