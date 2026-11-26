package com.zigzaego.api.price;

import static com.zigzaego.api.price.PriceHistoryTestData.insertPriceAt;
import static com.zigzaego.api.price.PriceHistoryTestData.insertProduct;
import static com.zigzaego.api.price.PriceHistoryTestData.insertSupplier;
import static com.zigzaego.api.support.StoreFixtures.insertIngredient;
import static org.assertj.core.api.Assertions.assertThat;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.auth.AuthTestSupport;
import com.zigzaego.api.domain.price.PriceIndexCalculator;
import com.zigzaego.api.domain.price.PriceIndexCalculator.PriceIndex;
import com.zigzaego.api.domain.price.PriceIndexCalculator.PriceObservation;
import com.zigzaego.api.global.config.StoreClock;
import com.zigzaego.api.ingredient.repository.IngredientRepository;
import com.zigzaego.api.price.dto.PriceHistoryResponse;
import com.zigzaego.api.price.dto.PriceObservationResponse;
import com.zigzaego.api.price.repository.PriceHistoryQueryRepository;
import com.zigzaego.api.price.service.PriceHistoryService;
import com.zigzaego.api.support.FixedClockConfig;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 서울 날짜 경계. 지금 = 2026-10-13T15:30Z = 서울 10-14 00:30 (UTC 로는 아직 10-13).
 * 컨텍스트는 다른 단가 테스트와 같이 쓰고, 서비스만 이 시계로 직접 만든다.
 */
@Import({TestcontainersConfiguration.class, FixedClockConfig.class})
@SpringBootTest
class PriceHistorySeoulBoundaryTest {

	private static final Clock JUST_AFTER_SEOUL_MIDNIGHT =
			Clock.fixed(Instant.parse("2026-10-13T15:30:00Z"), FixedClockConfig.SEOUL);

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private IngredientRepository ingredientRepository;

	@Autowired
	private PriceHistoryQueryRepository priceHistoryRepository;

	private PriceHistoryService service;

	private long myStore;

	private long greenOnion;

	/** 대파 1kg = 1000 g. */
	private long kg1;

	@BeforeEach
	void setUp() {
		service = new PriceHistoryService(ingredientRepository, priceHistoryRepository,
				new StoreClock(JUST_AFTER_SEOUL_MIDNIGHT));
		myStore = AuthTestSupport.insertStore(jdbc, "단가경계가게-" + System.nanoTime());
		greenOnion = insertIngredient(jdbc, myStore, "대파");
		long supplier = insertSupplier(jdbc, myStore, "한빛농산");
		kg1 = insertProduct(jdbc, myStore, supplier, greenOnion, "대파 1kg", 1000);
	}

	@Test
	void seoulBoundary_자정_앞뒤와_56일_창_양끝() {
		insertPriceAt(jdbc, myStore, kg1, 4000, seoul(2026, 8, 19, 23, 59), "MANUAL"); // 창 밖: 관측에서 빠짐
		insertPriceAt(jdbc, myStore, kg1, 4200, seoul(2026, 8, 20, 0, 1), "MANUAL"); // 가장 오래된 관측 · 칸0
		insertPriceAt(jdbc, myStore, kg1, 4400, seoul(2026, 10, 13, 23, 59), "MANUAL"); // 어제 · 칸7
		insertPriceAt(jdbc, myStore, kg1, 4800, seoul(2026, 10, 14, 0, 1), "MANUAL"); // 오늘 · 칸7

		PriceHistoryResponse res = service.get(myStore, String.valueOf(greenOnion));

		assertThat(res.observations()).extracting(PriceObservationResponse::observedOn)
				.containsExactly("2026-10-14", "2026-10-13", "2026-08-20");
		assertThat(res.observations().get(0).unitPrice()).isEqualByComparingTo("4.8");
		assertThat(res.observations().get(2).unitPrice()).isEqualByComparingTo("4.2");

		// 칸0 = 08-20~26 (08-19 의 4.0 이 섞이면 중앙값 4.1), 칸7 = 10-08~14 에 어제 4.4 · 오늘 4.8
		List<String> weekly = new ArrayList<>();
		for (BigDecimal v : res.weekly()) {
			weekly.add(v == null ? null : v.stripTrailingZeros().toPlainString());
		}
		assertThat(weekly).containsExactly("4.2", null, null, null, null, null, null, "4.6");

		// 오늘 = 서울 10-14: 현재 단가는 00:01 의 4.8
		assertThat(res.index().currentPrice()).isEqualByComparingTo("4.8");
		PriceIndex expected = new PriceIndexCalculator().calculate(TODAY, List.of(
				obs(LocalDate.of(2026, 8, 19), "4.0"), obs(LocalDate.of(2026, 8, 20), "4.2"),
				obs(LocalDate.of(2026, 10, 13), "4.4"), obs(TODAY, "4.8")));
		assertThat(res.index().reason()).isEqualTo(expected.reason());
		assertThat(res.index().level()).isEqualTo(expected.level());
		assertThat(res.index().sampleCount()).isEqualTo(expected.sampleCount());
		assertThat(res.index().index()).isEqualByComparingTo(expected.index().setScale(4, RoundingMode.HALF_UP));
	}

	private static Instant seoul(int y, int m, int d, int hh, int mm) {
		return LocalDateTime.of(y, m, d, hh, mm).atZone(FixedClockConfig.SEOUL).toInstant();
	}

	private static PriceObservation obs(LocalDate day, String unitPrice) {
		return new PriceObservation(day, new BigDecimal(unitPrice), "MANUAL");
	}
}
