package com.zigzaego.api.price.service;

import com.zigzaego.api.domain.price.PriceIndexCalculator;
import com.zigzaego.api.domain.price.PriceIndexCalculator.PriceObservation;
import com.zigzaego.api.global.config.ClockConfig;
import com.zigzaego.api.global.config.StoreClock;
import com.zigzaego.api.global.store.ResourceIds;
import com.zigzaego.api.ingredient.entity.Ingredient;
import com.zigzaego.api.ingredient.repository.IngredientRepository;
import com.zigzaego.api.price.dto.PriceHistoryResponse;
import com.zigzaego.api.price.dto.PriceIndexResponse;
import com.zigzaego.api.price.dto.PriceObservationResponse;
import com.zigzaego.api.price.dto.UnitPrices;
import com.zigzaego.api.price.repository.PriceHistoryQueryRepository;
import com.zigzaego.api.price.repository.PriceHistoryQueryRepository.UnitPriceRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재료 하나의 단가 이력 · 주별 중앙값 · 가격 지수 (S15P31A308-64). 명세: docs/plans/2026-10-15_가격지수_정의.md.
 *
 * <ul>
 *   <li>대상: 그 재료에 연결된 이 가게의 모든 거래처 상품 이력을 단위 단가로 합친다</li>
 *   <li>observations: 최근 8주(오늘 포함 56일) 관측, 날짜 내림차순, 최대 {@value #MAX_OBSERVATIONS}건</li>
 *   <li>weekly: 7일 칸 8개, 마지막 칸 = 오늘 − 6일 ~ 오늘. 칸 값 = 그 7일 관측의 중앙값, 관측 없으면 null</li>
 *   <li>index: {@link PriceIndexCalculator} 에 관측을 넘긴 결과 그대로</li>
 * </ul>
 * "오늘" 은 {@link Clock} 빈의 시각을 서울 날짜로 본 것.
 */
@Service
public class PriceHistoryService {

	static final int WEEKS = 8;

	static final int DAYS_PER_WEEK = 7;

	/** 화면 · 주별 칸이 보는 기간(오늘 포함). */
	static final int WINDOW_DAYS = WEEKS * DAYS_PER_WEEK;

	static final int MAX_OBSERVATIONS = 200;

	/**
	 * 지수 계산에 필요한 가장 먼 날: 현재 단가는 오늘 − 56일 안, 기준 기간은 현재 단가 날짜 − 56일부터라
	 * 오늘 − 112일보다 오래된 관측은 결과를 바꾸지 않는다. 그 앞은 읽지 않는다.
	 */
	private static final int INDEX_LOOKBACK_DAYS = PriceIndexCalculator.LOOKBACK_DAYS;

	private static final BigDecimal TWO = BigDecimal.valueOf(2);

	private final IngredientRepository ingredientRepository;
	private final PriceHistoryQueryRepository priceHistoryRepository;
	private final StoreClock clock;
	private final PriceIndexCalculator calculator = new PriceIndexCalculator();

	public PriceHistoryService(IngredientRepository ingredientRepository,
			PriceHistoryQueryRepository priceHistoryRepository, StoreClock clock) {
		this.ingredientRepository = ingredientRepository;
		this.priceHistoryRepository = priceHistoryRepository;
		this.clock = clock;
	}

	/**
	 * @throws com.zigzaego.api.global.exception.BusinessException 재료가 없거나 다른 가게이거나 id 가 숫자가 아니면
	 *         404 COMMON_NOT_FOUND
	 */
	@Transactional(readOnly = true)
	public PriceHistoryResponse get(long storeId, String rawIngredientId) {
		Ingredient ingredient = ingredientRepository.getInStore(ResourceIds.parse(rawIngredientId), storeId);
		LocalDate today = clock.today(storeId, ClockConfig.SERVICE_ZONE);

		List<UnitPriceRow> rows = priceHistoryRepository.findUnitPrices(storeId, ingredient.getId(),
				startOf(today.minusDays(INDEX_LOOKBACK_DAYS)), startOf(today.plusDays(1)));

		LocalDate windowStart = today.minusDays(WINDOW_DAYS - 1);
		List<PriceObservation> all = new ArrayList<>(rows.size());
		List<PriceObservationResponse> observations = new ArrayList<>();
		List<List<BigDecimal>> weeks = new ArrayList<>(WEEKS);
		for (int i = 0; i < WEEKS; i++) {
			weeks.add(new ArrayList<>());
		}
		for (UnitPriceRow row : rows) {
			LocalDate day = LocalDate.ofInstant(row.observedAt(), ClockConfig.SERVICE_ZONE);
			all.add(new PriceObservation(day, row.unitPrice(), row.source()));
			if (day.isBefore(windowStart) || day.isAfter(today)) {
				continue;
			}
			weeks.get((int) (ChronoUnit.DAYS.between(windowStart, day) / DAYS_PER_WEEK)).add(row.unitPrice());
			if (observations.size() < MAX_OBSERVATIONS) {
				observations.add(new PriceObservationResponse(day.toString(), UnitPrices.round(row.unitPrice()),
						row.source(), String.valueOf(row.supplierProductId()), row.productName()));
			}
		}

		BigDecimal[] weekly = new BigDecimal[WEEKS];
		for (int i = 0; i < WEEKS; i++) {
			weekly[i] = weeks.get(i).isEmpty() ? null : UnitPrices.round(median(weeks.get(i)));
		}

		return new PriceHistoryResponse(String.valueOf(ingredient.getId()), ingredient.getUnit(),
				Arrays.asList(weekly), observations, PriceIndexResponse.of(calculator.calculate(today, all)));
	}

	private static OffsetDateTime startOf(LocalDate day) {
		return day.atStartOfDay(ClockConfig.SERVICE_ZONE).toOffsetDateTime();
	}

	/** 중앙값. 짝수 개면 가운데 둘의 평균. */
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
