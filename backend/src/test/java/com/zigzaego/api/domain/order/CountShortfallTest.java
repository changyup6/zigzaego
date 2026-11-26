package com.zigzaego.api.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.zigzaego.api.domain.order.CountShortfall.Sample;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 실사 차이 반복 손실 (S15P31A308-253). 최근 3회 중 2회 이상 손실률 ≥ 10% 면 평균 손실량. */
class CountShortfallTest {

	private static Sample s(String estimated, String counted) {
		return new Sample(new BigDecimal(estimated), new BigDecimal(counted));
	}

	@Test
	void 세_번_중_두_번_부족하면_세_번의_평균_손실량() {
		// 600(12%) · 500(10%, 경계 포함) · 100(2%) → 평균 400
		assertThat(CountShortfall.averageLoss(List.of(s("5000", "4400"), s("5000", "4500"), s("5000", "4900"))))
				.hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("400"));
	}

	@Test
	void 한_번만_부족하면_상향_없음() {
		assertThat(CountShortfall.averageLoss(List.of(s("5000", "4400"), s("5000", "4600"), s("5000", "5200"))))
				.isEmpty();
	}

	@Test
	void 넷째부터는_보지_않고_실사가_두_번뿐이어도_둘_다_부족하면_상향() {
		assertThat(CountShortfall.averageLoss(List.of(s("100", "100"), s("100", "100"), s("100", "50"), s("100", "50"))))
				.isEmpty();
		assertThat(CountShortfall.averageLoss(List.of(s("100", "80"), s("100", "70"))))
				.hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("25"));
	}

	@Test
	void 추정이_0_이면_판단하지_않고_실사가_더_많으면_손실_0() {
		assertThat(CountShortfall.averageLoss(List.of(s("0", "0"), s("0", "10"), s("100", "50")))).isEmpty();
		assertThat(CountShortfall.averageLoss(List.of())).isEmpty();
	}
}
