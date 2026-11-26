package com.zigzaego.api.market.service;

import com.zigzaego.api.global.config.StoreClock;
import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.exception.CommonErrorCode;
import com.zigzaego.api.global.store.ResourceIds;
import com.zigzaego.api.ingredient.entity.Ingredient;
import com.zigzaego.api.ingredient.entity.IngredientUnit;
import com.zigzaego.api.ingredient.repository.IngredientRepository;
import com.zigzaego.api.market.catalog.MarketItemGroup;
import com.zigzaego.api.market.catalog.MarketItemGroupCatalog;
import com.zigzaego.api.market.delivery.DeliveryCoverage;
import com.zigzaego.api.market.delivery.DeliveryCoverage.Status;
import com.zigzaego.api.market.delivery.DeliveryCoverage.Verdict;
import com.zigzaego.api.market.delivery.DeliveryCoverageService;
import com.zigzaego.api.market.dto.IngredientOffersResponse.ItemGroupInfo;
import com.zigzaego.api.market.dto.MarketPriceHistoryResponse;
import com.zigzaego.api.market.dto.MarketPriceHistoryResponse.Point;
import com.zigzaego.api.market.dto.MarketPriceHistoryResponse.SiteSeries;
import com.zigzaego.api.market.service.MarketCandidateFinder.Found;
import com.zigzaego.api.market.service.MarketCandidateFinder.Priced;
import com.zigzaego.api.price.dto.UnitPrices;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재료 하나의 식자재몰 **일별** 최저가 추이 (S15P31A308-229, ADR-0012, 기준 문서 7.2절).
 * 점 하나 = (품목군, 사이트, 수집일) 의 최저 단가. 대상 · 이상치 · 배달 규칙은 {@link MarketOfferService} 와 같고 날짜마다 적용한다.
 * 수집이 없는 날은 점이 없다. 사이트마다 1위 상품이 날마다 바뀌어도 된다 — 선은 "그 사이트에서 그 품목을 가장 싸게 살 수 있는 값"이다.
 */
@Service
@RequiredArgsConstructor
public class MarketPriceHistoryService {

	public static final int DEFAULT_DAYS = 30;

	public static final int MAX_DAYS = 365;

	private final IngredientRepository ingredientRepository;
	private final StoreMarketQuotes storeMarketQuotes;
	private final MarketItemGroupCatalog catalog;
	private final MarketCandidateFinder finder;
	private final DeliveryCoverageService deliveryCoverageService;
	private final StoreClock clock;

	/**
	 * @param days 오늘 포함 며칠(1~365). 밖이면 400 COMMON_INVALID_INPUT
	 * @throws BusinessException 재료가 없거나 다른 가게이거나 id 가 숫자가 아니면 404 COMMON_NOT_FOUND
	 */
	@Transactional(readOnly = true)
	public MarketPriceHistoryResponse history(long storeId, String rawIngredientId, Integer days) {
		return storeMarketQuotes.merge(storeId, globalHistory(storeId, rawIngredientId, days));
	}

	private MarketPriceHistoryResponse globalHistory(long storeId, String rawIngredientId, Integer days) {
		int n = days == null ? DEFAULT_DAYS : days;
		if (n < 1 || n > MAX_DAYS) {
			throw new BusinessException(CommonErrorCode.INVALID_INPUT);
		}
		Ingredient ingredient = ingredientRepository.getInStore(ResourceIds.parse(rawIngredientId), storeId);
		String query = ingredient.getName().strip();
		LocalDate to = LocalDate.now(clock.of(storeId));
		LocalDate from = to.minusDays(n - 1L);

		Optional<MarketItemGroup> group = catalog.load().forIngredientName(query).filter(MarketItemGroup::comparable);
		Found found = finder.since(query, group, from);
		DeliveryCoverage coverage = deliveryCoverageService.coverageFor(storeId);
		Map<String, Verdict> verdictBySite = new HashMap<>();
		TreeSet<String> notDelivering = new TreeSet<>();
		List<Priced> rows = new ArrayList<>();
		for (Priced p : found.rows()) {
			if (p.row().collectedOn() == null || p.row().collectedOn().isAfter(to)) {
				continue;
			}
			Verdict v = coverage.check(p.row().site(), p.row().storeRegion());
			if (v.allowed()) {
				rows.add(p);
				verdictBySite.putIfAbsent(p.row().site(), v);
			} else {
				notDelivering.add(p.row().site());
			}
		}
		notDelivering.removeAll(verdictBySite.keySet());
		List<String> deliveryUnknown = verdictBySite.entrySet().stream()
				.filter(e -> e.getValue().status() == Status.UNKNOWN).map(Map.Entry::getKey).sorted().toList();
		ItemGroupInfo groupInfo = group.map(ItemGroupInfo::of).orElse(null);

		// 단위: 품목군 기준 단위(환산된 줄만), 없으면 가장 많은 단위
		IngredientUnit unit = null;
		List<Priced> usable = List.of();
		if (!rows.isEmpty()) {
			if (group.isPresent()) {
				unit = group.get().baseUnit();
				usable = rows.stream().filter(Priced::inBaseUnit).toList();
			} else {
				unit = rows.stream().collect(Collectors.groupingBy(Priced::unit, Collectors.counting())).entrySet().stream()
						.sorted(Comparator.<Map.Entry<IngredientUnit, Long>>comparingLong(e -> -e.getValue())
								.thenComparing(e -> e.getKey().name()))
						.findFirst().orElseThrow().getKey();
				IngredientUnit u = unit;
				usable = rows.stream().filter(p -> p.unit() == u).toList();
			}
		}

		// 날짜마다 이상치 제거 → (사이트, 날짜) 최저
		Map<String, TreeMap<LocalDate, Priced>> bySite = new HashMap<>();
		for (List<Priced> day : usable.stream().collect(Collectors.groupingBy(p -> p.row().collectedOn())).values()) {
			for (Priced p : MarketOfferService.withoutOutliers(day)) {
				TreeMap<LocalDate, Priced> series = bySite.computeIfAbsent(p.row().site(), k -> new TreeMap<>());
				Priced current = series.get(p.row().collectedOn());
				if (current == null || MarketOfferService.compare(p, current) < 0) {
					series.put(p.row().collectedOn(), p);
				}
			}
		}
		List<SiteSeries> sites = new ArrayList<>();
		for (Map.Entry<String, TreeMap<LocalDate, Priced>> e : bySite.entrySet()) {
			List<Point> points = e.getValue().values().stream().map(p -> new Point(p.row().collectedOn(),
					UnitPrices.round(p.unitPrice()), p.row().price(), p.row().productName())).toList();
			Verdict v = verdictBySite.getOrDefault(e.getKey(), Verdict.UNKNOWN);
			sites.add(new SiteSeries(e.getKey(), v.status(), v.dawnDelivery(), points));
		}
		sites.sort(Comparator.<SiteSeries, java.math.BigDecimal>comparing(s -> s.points().get(s.points().size() - 1).unitPrice())
				.thenComparing(SiteSeries::site));
		IngredientUnit shownUnit = sites.isEmpty() ? null : unit;
		return new MarketPriceHistoryResponse(String.valueOf(ingredient.getId()), query, groupInfo, found.source().name(), shownUnit,
				ingredient.getUnit(), shownUnit == null ? null : MarketOfferService.toIngredientUnitFactor(group, shownUnit, ingredient.getUnit()),
				from, to, coverage.storeSigungu(), deliveryUnknown, List.copyOf(notDelivering), sites);
	}
}
