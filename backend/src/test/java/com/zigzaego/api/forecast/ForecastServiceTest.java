package com.zigzaego.api.forecast;

import static com.zigzaego.api.forecast.ForecastTestData.insertClosedDay;
import static com.zigzaego.api.forecast.ForecastTestData.insertForecast;
import static com.zigzaego.api.forecast.ForecastTestData.insertMenu;
import static com.zigzaego.api.forecast.ForecastTestData.insertSale;
import static com.zigzaego.api.forecast.ForecastTestData.insertStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.forecast.dto.ForecastModel;
import com.zigzaego.api.forecast.dto.MenuForecastResponse;
import com.zigzaego.api.forecast.service.ForecastService;
import com.zigzaego.api.global.exception.BusinessException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 예측 손계산 검증. 오늘 = 대상일 T = 2026-10-10(토). 이력 끝 = 10-09(금), 4주 구간 = 09-12(토) ~ 10-09.
 * 기대값은 아래 표를 손으로 계산한 숫자 리터럴이다.
 *
 * <pre>
 * [가게 A] 메뉴 (id 오름차순으로 넣음): 돈까스 · 콜라 · 김밥 · 옛메뉴(active=false)
 *   휴무일 09-19(토) · 10-05(월)
 *
 *   돈까스 — timesfm 행 없음 → 요일 평균
 *     배달: 가게 배달 첫 판매 09-01 (4주 이상) → 구간 안 토요일 09-12 10 · 09-19 20(휴무, 제외) · 09-26 30 · 10-03 40
 *           → (10 + 30 + 40) / 3 = 26.666…   (목요일 10-08 99 는 요일이 달라 무관)
 *     홀:   가게 홀 첫 판매 09-26 (2주) → 14일 평균 09-26 ~ 10-09 중 휴무 10-05 뺀 13일, 판매 09-26 7 · 10-03 14 · 10-09 21
 *           → 42 / 13 = 3.230769…
 *     합계 29.897435… → 29.897 DOW_AVG
 *
 *   콜라 — timesfm 10-10 배달 12.5 + 홀 3.25 = 15.750 TIMESFM
 *     무시: 같은 날 dow_avg 행 999 · 10-11 timesfm 행 777 · 판매 기록
 *
 *   김밥 — 판매 · 예측 없음 → 0.000 DOW_AVG
 *   옛메뉴 — 비활성이라 결과에 없음
 *
 * [가게 B] 돈까스B timesfm 10-10 55 + 판매. 가게 A 의 김밥을 가리키는 B 의 timesfm 행 123 → A 결과에 섞이면 안 된다
 * </pre>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ForecastServiceTest {

	private static final LocalDate T = LocalDate.of(2026, 10, 10);

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ForecastService service;

	private long storeA;
	private long storeB;
	private long 돈까스;
	private long 콜라;
	private long 김밥;

	@BeforeEach
	void setUp() {
		storeA = insertStore(jdbc, "예측A");
		storeB = insertStore(jdbc, "예측B");

		돈까스 = insertMenu(jdbc, storeA, "돈까스");
		콜라 = insertMenu(jdbc, storeA, "콜라");
		김밥 = insertMenu(jdbc, storeA, "김밥");
		insertMenu(jdbc, storeA, "옛메뉴", false);

		insertClosedDay(jdbc, storeA, LocalDate.of(2026, 9, 19));
		insertClosedDay(jdbc, storeA, LocalDate.of(2026, 10, 5));

		insertSale(jdbc, storeA, LocalDate.of(2026, 9, 1), 돈까스, "DELIVERY", "5");
		insertSale(jdbc, storeA, LocalDate.of(2026, 9, 12), 돈까스, "DELIVERY", "10");
		insertSale(jdbc, storeA, LocalDate.of(2026, 9, 19), 돈까스, "DELIVERY", "20");
		insertSale(jdbc, storeA, LocalDate.of(2026, 9, 26), 돈까스, "DELIVERY", "30");
		insertSale(jdbc, storeA, LocalDate.of(2026, 10, 3), 돈까스, "DELIVERY", "40");
		insertSale(jdbc, storeA, LocalDate.of(2026, 10, 8), 돈까스, "DELIVERY", "99");
		insertSale(jdbc, storeA, LocalDate.of(2026, 9, 26), 돈까스, "HALL", "7");
		insertSale(jdbc, storeA, LocalDate.of(2026, 10, 3), 돈까스, "HALL", "14");
		insertSale(jdbc, storeA, LocalDate.of(2026, 10, 9), 돈까스, "HALL", "21");

		insertForecast(jdbc, storeA, 콜라, T, "timesfm", "DELIVERY", "12.5");
		insertForecast(jdbc, storeA, 콜라, T, "timesfm", "HALL", "3.25");
		insertForecast(jdbc, storeA, 콜라, T, "dow_avg", "DELIVERY", "999");
		insertForecast(jdbc, storeA, 콜라, T.plusDays(1), "timesfm", "DELIVERY", "777");
		insertSale(jdbc, storeA, LocalDate.of(2026, 10, 3), 콜라, "DELIVERY", "100");

		long 돈까스B = insertMenu(jdbc, storeB, "돈까스");
		insertForecast(jdbc, storeB, 돈까스B, T, "timesfm", "NONE", "55");
		insertSale(jdbc, storeB, LocalDate.of(2026, 10, 3), 돈까스B, "NONE", "8");
		insertForecast(jdbc, storeB, 김밥, T, "timesfm", "NONE", "123");
	}

	@Test
	void 오늘은_TimesFM_먼저_없으면_요일_평균으로_손계산과_같다() {
		List<MenuForecastResponse> result = service.forecast(storeA, T, T);

		assertThat(lines(result)).containsExactly(
				"돈까스|2026-10-10|29.897|DOW_AVG",
				"콜라|2026-10-10|15.750|TIMESFM",
				"김밥|2026-10-10|0.000|DOW_AVG");
		assertThat(result.get(0).menuId()).isEqualTo(String.valueOf(돈까스));
		assertThat(result.get(1).menuId()).isEqualTo(String.valueOf(콜라));
		assertThat(result.get(2).menuId()).isEqualTo(String.valueOf(김밥));
	}

	@Test
	void TimesFM_행이_없으면_요일_평균으로_대신하고_생기면_TimesFM이_이긴다() {
		// 우동 NONE 채널, 첫 판매 09-12 (딱 4주) → 토요일 09-12 10 · 09-19 20 · 09-26 30 · 10-03 40 = 25.000
		long store = insertStore(jdbc, "대체");
		long 우동 = insertMenu(jdbc, store, "우동");
		insertSale(jdbc, store, LocalDate.of(2026, 9, 12), 우동, "NONE", "10");
		insertSale(jdbc, store, LocalDate.of(2026, 9, 19), 우동, "NONE", "20");
		insertSale(jdbc, store, LocalDate.of(2026, 9, 26), 우동, "NONE", "30");
		insertSale(jdbc, store, LocalDate.of(2026, 10, 3), 우동, "NONE", "40");

		assertThat(lines(service.forecast(store, T, T))).containsExactly("우동|2026-10-10|25.000|DOW_AVG");

		insertForecast(jdbc, store, 우동, T, "timesfm", "NONE", "31.5");

		assertThat(lines(service.forecast(store, T, T))).containsExactly("우동|2026-10-10|31.500|TIMESFM");
	}

	@Test
	void 홀_이력이_두_주면_14일_평균을_쓴다() {
		// 홀만 있는 가게, 첫 판매 09-26 (2주). 토요일 평균이면 (7 + 14) / 2 = 10.5 지만 14일 평균 42 / 14 = 3.000
		long store = insertStore(jdbc, "홀2주");
		long 라면 = insertMenu(jdbc, store, "라면");
		insertSale(jdbc, store, LocalDate.of(2026, 9, 26), 라면, "HALL", "7");
		insertSale(jdbc, store, LocalDate.of(2026, 10, 3), 라면, "HALL", "14");
		insertSale(jdbc, store, LocalDate.of(2026, 10, 9), 라면, "HALL", "21");

		assertThat(lines(service.forecast(store, T, T))).containsExactly("라면|2026-10-10|3.000|DOW_AVG");
	}

	@Test
	void 과거_대상일은_이력이_전날까지다() {
		// 대상일 10-03(토), 이력 끝 10-02, 4주 구간 09-05 ~ 10-02
		//   배달 토요일 09-05 0 · 09-12 10 · 09-19 휴무 · 09-26 30 → 40 / 3 = 13.333…
		//   홀 첫 판매 09-26 → 14일 평균 09-26 ~ 10-02 (7일), 판매 7 → 1
		//   합계 14.333
		List<MenuForecastResponse> result = service.forecast(storeA, LocalDate.of(2026, 10, 3), T);

		assertThat(lines(result)).contains("돈까스|2026-10-03|14.333|DOW_AVG");
	}

	@Test
	void 미래_대상일은_이력이_어제까지다() {
		// 대상일 10-17(토) 이지만 오늘이 10-10 이라 이력 끝은 어제 10-09 → 돈까스는 T 와 같은 값
		// 콜라는 10-17 timesfm 행이 없어 요일 평균: 배달 토요일 09-12 0 · 09-26 0 · 10-03 100 (09-19 휴무) → 100 / 3 = 33.333…, 홀 0
		List<MenuForecastResponse> result = service.forecast(storeA, LocalDate.of(2026, 10, 17), T);

		assertThat(lines(result)).contains(
				"돈까스|2026-10-17|29.897|DOW_AVG",
				"콜라|2026-10-17|33.333|DOW_AVG");
	}

	@Test
	void 다른_가게_데이터는_섞이지_않는다() {
		assertThat(lines(service.forecast(storeB, T, T))).containsExactly("돈까스|2026-10-10|55.000|TIMESFM");
	}

	@Test
	void 메뉴가_없으면_빈_목록을_돌려준다() {
		long store = insertStore(jdbc, "빈가게");

		assertThat(service.forecast(store, T, T)).isEmpty();
	}

	@Test
	void 없는_가게는_BusinessException을_던진다() {
		assertThatThrownBy(() -> service.forecast(-1L, T, T)).isInstanceOf(BusinessException.class);
	}

	@Test
	void 모델_enum은_컬럼_값과_같다() {
		assertThat(ForecastModel.TIMESFM.columnValue()).isEqualTo("timesfm");
		assertThat(ForecastModel.DOW_AVG.columnValue()).isEqualTo("dow_avg");
	}

	/** 반올림하지 않는다 — 서비스가 낸 predictedQty 를 문자열 그대로 비교한다. */
	/**
	 * 구간 예측(지라 158)은 날마다 forecast 를 부른 것과 같아야 한다. 콜라는 T 는 timesfm(15.75), T+1 은 timesfm 777,
	 * 그 뒤는 요일 평균. 돈까스는 요일마다 평균이 다르다.
	 */
	@Test
	void 구간_예측은_날마다_예측한_것과_같다() {
		LocalDate to = T.plusDays(6);
		Map<LocalDate, List<MenuForecastResponse>> range = service.forecastRange(storeA, T, to, T);

		assertThat(range.keySet()).containsExactly(T, T.plusDays(1), T.plusDays(2), T.plusDays(3), T.plusDays(4),
				T.plusDays(5), T.plusDays(6));
		for (LocalDate day = T; !day.isAfter(to); day = day.plusDays(1)) {
			assertThat(lines(range.get(day))).as(day.toString()).isEqualTo(lines(service.forecast(storeA, day, T)));
		}
		assertThat(lines(range.get(T))).containsExactly(
				"돈까스|2026-10-10|29.897|DOW_AVG",
				"콜라|2026-10-10|15.750|TIMESFM",
				"김밥|2026-10-10|0.000|DOW_AVG");
		assertThat(lines(range.get(T.plusDays(1)))).contains("콜라|2026-10-11|777.000|TIMESFM");
		assertThat(lines(range.get(T.plusDays(2)))).contains("콜라|2026-10-12|0.000|DOW_AVG");
		// 월요일 10-12: 돈까스 배달 구간 안 월요일은 판매 없음 → 0, 홀 14일 평균 3.230769… → 3.231
		assertThat(lines(range.get(T.plusDays(2)))).contains("돈까스|2026-10-12|3.231|DOW_AVG");

		assertThatThrownBy(() -> service.forecastRange(storeA, to, T, T)).isInstanceOf(BusinessException.class);
	}

	private static List<String> lines(List<MenuForecastResponse> rows) {
		return rows.stream()
				.map(r -> r.menuName() + "|" + r.targetDate() + "|" + r.predictedQty().toPlainString() + "|" + r.model())
				.toList();
	}
}
