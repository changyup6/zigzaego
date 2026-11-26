package com.zigzaego.api.domain.price;

import static org.assertj.core.api.Assertions.assertThat;

import com.zigzaego.api.domain.price.PriceIndexCalculator.PriceIndex;
import com.zigzaego.api.domain.price.PriceIndexCalculator.PriceIndexReason;
import com.zigzaego.api.domain.price.PriceIndexCalculator.PriceLevel;
import com.zigzaego.api.domain.price.PriceIndexCalculator.PriceObservation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PriceIndexCalculatorTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);

	private final PriceIndexCalculator calculator = new PriceIndexCalculator();

	private static PriceObservation obs(int daysAgo, String unitPrice, String source) {
		return new PriceObservation(TODAY.minusDays(daysAgo), new BigDecimal(unitPrice), source);
	}

	private static PriceObservation obs(int daysAgo, String unitPrice) {
		return obs(daysAgo, unitPrice, "RECEIVE");
	}

	/** 명세 4절: 최근 8주 단위 단가 4.0 · 3.9 · 4.1 · 4.0 · 4.2 → 기준 4.0 */
	private static List<PriceObservation> baseHistory() {
		List<PriceObservation> list = new ArrayList<>();
		list.add(obs(40, "4.0", "RECEIVE"));
		list.add(obs(30, "3.9", "DOC"));
		list.add(obs(20, "4.1", "MANUAL"));
		list.add(obs(10, "4.0", "RECEIVE"));
		list.add(obs(5, "4.2", "RECEIVE"));
		return list;
	}

	private PriceIndex calcWithToday(String todayPrice) {
		List<PriceObservation> list = baseHistory();
		list.add(obs(0, todayPrice));
		return calculator.calculate(TODAY, list);
	}

	@Test
	void 오늘_4_6이면_지수_1_15_비쌈() {
		PriceIndex result = calcWithToday("4.6");

		assertThat(result.index()).isEqualByComparingTo("1.15");
		assertThat(result.level()).isEqualTo(PriceLevel.HIGH);
		assertThat(result.basePrice()).isEqualByComparingTo("4.0");
		assertThat(result.currentPrice()).isEqualByComparingTo("4.6");
		assertThat(result.sampleCount()).isEqualTo(5);
		assertThat(result.reason()).isEqualTo(PriceIndexReason.OK);
	}

	@Test
	void 오늘_4_1이면_지수_1_025_보통() {
		PriceIndex result = calcWithToday("4.1");

		assertThat(result.index()).isEqualByComparingTo("1.025");
		assertThat(result.level()).isEqualTo(PriceLevel.NORMAL);
		assertThat(result.reason()).isEqualTo(PriceIndexReason.OK);
	}

	@Test
	void 오늘_3_4이면_지수_0_85_쌈() {
		PriceIndex result = calcWithToday("3.4");

		assertThat(result.index()).isEqualByComparingTo("0.85");
		assertThat(result.level()).isEqualTo(PriceLevel.LOW);
	}

	@Test
	void 기준_기간_이력이_2건뿐이면_지수_1_보통_TOO_FEW() {
		List<PriceObservation> list = List.of(obs(20, "4.0"), obs(10, "4.0"), obs(0, "4.6"));

		PriceIndex result = calculator.calculate(TODAY, list);

		assertThat(result.index()).isEqualByComparingTo("1");
		assertThat(result.level()).isEqualTo(PriceLevel.NORMAL);
		assertThat(result.reason()).isEqualTo(PriceIndexReason.TOO_FEW);
		assertThat(result.sampleCount()).isEqualTo(2);
	}

	@Test
	void MARKET_관측은_무시한다() {
		List<PriceObservation> list = baseHistory();
		list.add(obs(3, "100.0", "MARKET"));
		list.add(obs(2, "100.0", "MARKET"));
		list.add(obs(0, "4.6"));
		list.add(obs(0, "999.0", "MARKET"));

		PriceIndex result = calculator.calculate(TODAY, list);

		assertThat(result.basePrice()).isEqualByComparingTo("4.0");
		assertThat(result.currentPrice()).isEqualByComparingTo("4.6");
		assertThat(result.sampleCount()).isEqualTo(5);
		assertThat(result.level()).isEqualTo(PriceLevel.HIGH);
	}

	@Test
	void MARKET만_남으면_기준_이력이_모자란다() {
		List<PriceObservation> list = List.of(
				obs(30, "4.0", "MARKET"), obs(20, "4.0", "MARKET"), obs(10, "4.0", "MARKET"),
				obs(0, "4.6"));

		PriceIndex result = calculator.calculate(TODAY, list);

		assertThat(result.reason()).isEqualTo(PriceIndexReason.TOO_FEW);
		assertThat(result.sampleCount()).isZero();
	}

	@Test
	void 같은_날_3건은_그날_중앙값_1건으로_센다() {
		List<PriceObservation> list = List.of(
				obs(10, "3.9"), obs(10, "4.0"), obs(10, "10.0"),
				obs(20, "4.0"),
				obs(0, "4.6"));

		PriceIndex result = calculator.calculate(TODAY, list);

		// 3건 → 1건으로 접혀 기준 표본은 2일 → 이력 부족
		assertThat(result.sampleCount()).isEqualTo(2);
		assertThat(result.reason()).isEqualTo(PriceIndexReason.TOO_FEW);
	}

	@Test
	void 오늘_같은_날_여러_건이면_현재_단가는_그날_중앙값() {
		List<PriceObservation> list = baseHistory();
		list.add(obs(0, "4.4"));
		list.add(obs(0, "4.6"));
		list.add(obs(0, "9.0"));

		PriceIndex result = calculator.calculate(TODAY, list);

		assertThat(result.currentPrice()).isEqualByComparingTo("4.6");
		assertThat(result.index()).isEqualByComparingTo("1.15");
	}

	@Test
	void 최근_8주_구매가_없으면_지수_1_NO_RECENT() {
		List<PriceObservation> list = List.of(obs(57, "4.0"), obs(70, "4.0"), obs(80, "4.0"), obs(90, "4.0"));

		PriceIndex result = calculator.calculate(TODAY, list);

		assertThat(result.index()).isEqualByComparingTo("1");
		assertThat(result.level()).isEqualTo(PriceLevel.NORMAL);
		assertThat(result.reason()).isEqualTo(PriceIndexReason.NO_RECENT);
		assertThat(result.currentPrice()).isNull();
	}

	@Test
	void 현재_단가가_어제면_어제_값은_기준에_섞이지_않는다() {
		List<PriceObservation> list = List.of(
				obs(40, "4.0"), obs(30, "3.9"), obs(20, "4.1"), obs(10, "4.0"),
				obs(1, "4.6"));

		PriceIndex result = calculator.calculate(TODAY, list);

		assertThat(result.currentPrice()).isEqualByComparingTo("4.6");
		assertThat(result.basePrice()).isEqualByComparingTo("4.0");
		assertThat(result.sampleCount()).isEqualTo(4);
		assertThat(result.index()).isEqualByComparingTo("1.15");
		assertThat(result.level()).isEqualTo(PriceLevel.HIGH);
		assertThat(result.reason()).isEqualTo(PriceIndexReason.OK);
	}

	@Test
	void 현재_단가가_10일_전이고_그_전_표본이_2건이면_TOO_FEW() {
		List<PriceObservation> list = List.of(obs(30, "4.0"), obs(20, "4.0"), obs(10, "4.6"));

		PriceIndex result = calculator.calculate(TODAY, list);

		assertThat(result.currentPrice()).isEqualByComparingTo("4.6");
		assertThat(result.sampleCount()).isEqualTo(2);
		assertThat(result.index()).isEqualByComparingTo("1");
		assertThat(result.level()).isEqualTo(PriceLevel.NORMAL);
		assertThat(result.reason()).isEqualTo(PriceIndexReason.TOO_FEW);
	}

	@Test
	void 기준_창은_현재_단가_날짜를_따라_과거로_옮겨_간다() {
		// 현재 단가 50일 전 → 기준 창 = 106일 전 ~ 51일 전. 오늘 기준 8주 밖 관측도 기준에 든다
		List<PriceObservation> list = List.of(
				obs(100, "4.0"), obs(80, "4.0"), obs(60, "4.0"), obs(107, "1.0"),
				obs(50, "3.4"));

		PriceIndex result = calculator.calculate(TODAY, list);

		assertThat(result.sampleCount()).isEqualTo(3);
		assertThat(result.basePrice()).isEqualByComparingTo("4.0");
		assertThat(result.level()).isEqualTo(PriceLevel.LOW);
	}

	@Test
	void 지수가_정확히_1_10이면_비쌈() {
		PriceIndex result = calcWithToday("4.4");

		assertThat(result.index()).isEqualByComparingTo("1.10");
		assertThat(result.level()).isEqualTo(PriceLevel.HIGH);
	}

	@Test
	void 지수가_정확히_0_90이면_쌈() {
		PriceIndex result = calcWithToday("3.6");

		assertThat(result.index()).isEqualByComparingTo("0.90");
		assertThat(result.level()).isEqualTo(PriceLevel.LOW);
	}

	@Test
	void 단가가_null이거나_0이하면_버린다() {
		List<PriceObservation> list = baseHistory();
		list.add(new PriceObservation(TODAY.minusDays(3), null, "RECEIVE"));
		list.add(obs(2, "0", "RECEIVE"));
		list.add(obs(1, "-1.0", "RECEIVE"));
		list.add(obs(0, "4.6"));

		PriceIndex result = calculator.calculate(TODAY, list);

		assertThat(result.sampleCount()).isEqualTo(5);
		assertThat(result.index()).isEqualByComparingTo("1.15");
	}

	@Test
	void 기준_표본이_짝수면_가운데_둘의_평균() {
		List<PriceObservation> list = List.of(
				obs(40, "3.0"), obs(30, "4.0"), obs(20, "5.0"), obs(10, "6.0"),
				obs(0, "4.5"));

		PriceIndex result = calculator.calculate(TODAY, list);

		assertThat(result.basePrice()).isEqualByComparingTo("4.5");
		assertThat(result.index()).isEqualByComparingTo("1");
	}

	@Test
	void R_배수_비쌈은_절반_최소_1일() {
		assertThat(PriceIndexCalculator.adjustedCycleDays(new BigDecimal("2"), PriceLevel.HIGH))
				.isEqualByComparingTo("1");
		assertThat(PriceIndexCalculator.adjustedCycleDays(new BigDecimal("1"), PriceLevel.HIGH))
				.isEqualByComparingTo("1");
	}

	@Test
	void R_배수_쌈은_두_배() {
		assertThat(PriceIndexCalculator.adjustedCycleDays(new BigDecimal("2"), PriceLevel.LOW))
				.isEqualByComparingTo("4");
	}

	@Test
	void R_배수_보통은_그대로() {
		assertThat(PriceIndexCalculator.adjustedCycleDays(new BigDecimal("2"), PriceLevel.NORMAL))
				.isEqualByComparingTo("2");
	}
}
