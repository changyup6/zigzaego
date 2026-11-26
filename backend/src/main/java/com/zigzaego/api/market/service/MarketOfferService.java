package com.zigzaego.api.market.service;

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
import com.zigzaego.api.market.dto.IngredientOffersResponse;
import com.zigzaego.api.market.dto.IngredientOffersResponse.ItemGroupInfo;
import com.zigzaego.api.market.dto.IngredientOffersResponse.Offer;
import com.zigzaego.api.market.dto.IngredientOffersResponse.SiteOffers;
import com.zigzaego.api.market.repository.MarketProductJdbcRepository.Candidate;
import com.zigzaego.api.market.service.MarketCandidateFinder.Found;
import com.zigzaego.api.market.service.MarketCandidateFinder.Priced;
import com.zigzaego.api.price.dto.UnitPrices;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재료 하나의 식자재몰 최저가 (S15P31A308-231 · 229). 크롤러 {@code scripts/search_price.py} 의 순위 규칙을 옮긴 것.
 *
 * <ol>
 *   <li>재료는 로그인한 가게로 좁혀 읽는다(404). 시세 표는 전역이라 재료 이름으로만 찾는다 (ADR-0010)</li>
 *   <li>후보는 {@link MarketCandidateFinder} — 재료 이름이 품목군에 매핑되면 라벨(LABEL), 아니면 이름 검색 + 규칙(RULE) (ADR-0012)</li>
 *   <li>비교 단위 = 품목군 기준 단위, 없으면 정확한 상품이 가장 많은 단위 하나(g · ml · 개). 단위가 다르면 값을 나란히 놓을 수 없다</li>
 *   <li>이상치 = 같은 단위 후보가 3개 이상일 때 중앙값의 {@value #OUTLIER_RATIO}배 밖. 규격 · 가격이 어긋난 줄(깐대파 10kg 2,580원)만 걸리는 기준</li>
 *   <li>같은 사이트 · 상품번호는 가장 싼 점포 하나(식자재왕몰 점포 10곳). 사이트마다 상위 {@value #TOP_PER_SITE}개, 사이트는 1위 단가 순</li>
 *   <li>비교 단위로 값을 낼 수 없고 다른 단위로만 파는 사이트(두부 2모처럼 환산값이 없는 것)는 1위만 따로</li>
 * </ol>
 * 배달 가능 판매처(S15P31A308-230): 정확한 상품을 가게 시군구의 {@link DeliveryCoverage} 로 걸러 배달 안 하는 판매처(점포)는 뺀다
 * ({@code notDelivering}). 배송 지역을 모르는 판매처와 위치 없는 가게의 모든 판매처는 남기되 {@code deliveryUnknown} 에 적는다('확인 필요').
 */
@Service
@RequiredArgsConstructor
public class MarketOfferService {

	static final int TOP_PER_SITE = 3;

	static final BigDecimal OUTLIER_RATIO = BigDecimal.valueOf(5);

	static final int MIN_FOR_OUTLIER = 3;

	private static final int CALC_SCALE = 10;

	private final IngredientRepository ingredientRepository;
	private final StoreMarketQuotes storeMarketQuotes;
	private final MarketItemGroupCatalog catalog;
	private final MarketCandidateFinder finder;
	private final DeliveryCoverageService deliveryCoverageService;

	/**
	 * @throws com.zigzaego.api.global.exception.BusinessException 재료가 없거나 다른 가게이거나 id 가 숫자가 아니면 404 COMMON_NOT_FOUND
	 */
	@Transactional(readOnly = true)
	public IngredientOffersResponse offers(long storeId, String rawIngredientId) {
		return storeMarketQuotes.merge(storeId, globalOffers(storeId, rawIngredientId));
	}

	private IngredientOffersResponse globalOffers(long storeId, String rawIngredientId) {
		Ingredient ingredient = ingredientRepository.getInStore(ResourceIds.parse(rawIngredientId), storeId);
		String query = ingredient.getName().strip();
		String id = String.valueOf(ingredient.getId());

		Optional<MarketItemGroup> group = catalog.load().forIngredientName(query).filter(MarketItemGroup::comparable);
		Found found = finder.latest(query, group);
		ItemGroupInfo groupInfo = group.map(ItemGroupInfo::of).orElse(null);
		String source = found.source().name();

		DeliveryCoverage coverage = deliveryCoverageService.coverageFor(storeId);
		Map<String, Verdict> verdictBySite = new HashMap<>();
		TreeSet<String> notDelivering = new TreeSet<>();
		List<Priced> exact = new ArrayList<>();
		for (Priced p : found.rows()) {
			Verdict v = coverage.check(p.row().site(), p.row().storeRegion());
			if (v.allowed()) {
				exact.add(p);
				verdictBySite.putIfAbsent(p.row().site(), v);
			} else {
				notDelivering.add(p.row().site());
			}
		}
		notDelivering.removeAll(verdictBySite.keySet());  // 한 점포라도 배달하면 그 사이트는 나온다
		List<String> deliveryUnknown = verdictBySite.entrySet().stream()
				.filter(e -> e.getValue().status() == Status.UNKNOWN).map(Map.Entry::getKey).sorted().toList();
		IngredientUnit ingredientUnit = ingredient.getUnit();
		if (exact.isEmpty()) {
			return new IngredientOffersResponse(id, query, groupInfo, source, null, false, ingredientUnit, null, found.collectedOn(), 0,
					List.of(), List.of(), coverage.storeSigungu(), deliveryUnknown, List.copyOf(notDelivering));
		}

		// 비교 단위: 품목군이 있으면 기준 단위(환산된 줄만), 없으면 정확한 상품이 가장 많은 단위
		IngredientUnit compareUnit = group.map(MarketItemGroup::baseUnit).orElseGet(() -> mostCommonUnit(exact));
		List<Priced> comparable = exact.stream().filter(p -> p.unit() == compareUnit && (group.isEmpty() || p.inBaseUnit())).toList();
		List<Priced> kept = withoutOutliers(comparable);
		int outliers = comparable.size() - kept.size();

		List<SiteOffers> sites = rankSites(kept, verdictBySite);
		List<String> ranked = sites.stream().map(SiteOffers::site).toList();
		Map<String, Priced> bestOther = new LinkedHashMap<>();
		for (Priced p : exact) {
			if (comparable.contains(p) || ranked.contains(p.row().site())) {
				continue;
			}
			Priced current = bestOther.get(p.row().site());
			if (current == null || compare(p, current) < 0) {
				bestOther.put(p.row().site(), p);
			}
		}
		List<Offer> otherUnits = bestOther.values().stream().sorted(Comparator.comparing(p -> p.row().site()))
				.map(MarketOfferService::toOffer).toList();

		if (sites.isEmpty()) {
			return new IngredientOffersResponse(id, query, groupInfo, source, null, false, ingredientUnit, null, found.collectedOn(), outliers,
					List.of(), otherUnits, coverage.storeSigungu(), deliveryUnknown, List.copyOf(notDelivering));
		}
		return new IngredientOffersResponse(id, query, groupInfo, source, compareUnit, compareUnit == ingredientUnit, ingredientUnit,
				toIngredientUnitFactor(group, compareUnit, ingredientUnit), found.collectedOn(), outliers, sites, otherUnits,
				coverage.storeSigungu(), deliveryUnknown, List.copyOf(notDelivering));
	}

	/**
	 * 원/비교 단위 → 원/재료 단위 계수. 같은 단위면 1. 다르면 품목군 환산값으로(재료 단위 1 = 비교 단위 몇 개인가), 없으면 null.
	 * 계란(EA, 60g) 재료가 G 면 1g = 1/60 개 → 원/g = 원/개 × 1/60.
	 */
	static BigDecimal toIngredientUnitFactor(Optional<MarketItemGroup> group, IngredientUnit compareUnit, IngredientUnit ingredientUnit) {
		if (compareUnit == ingredientUnit) {
			return BigDecimal.ONE;
		}
		if (group.isEmpty() || group.get().baseUnit() != compareUnit) {
			return null;
		}
		BigDecimal perIngredientUnit = group.get().toBase(BigDecimal.ONE, ingredientUnit.name());
		return perIngredientUnit == null ? null : perIngredientUnit.setScale(7, RoundingMode.HALF_UP).stripTrailingZeros();
	}

	private static IngredientUnit mostCommonUnit(List<Priced> rows) {
		return rows.stream().collect(Collectors.groupingBy(Priced::unit, Collectors.counting())).entrySet().stream()
				.sorted(Comparator.<Map.Entry<IngredientUnit, Long>>comparingLong(e -> -e.getValue()).thenComparing(e -> e.getKey().name()))
				.findFirst().orElseThrow().getKey();
	}

	/** 같은 단위 후보 3개 이상이면 중앙값의 5배 밖을 뺀다. */
	static List<Priced> withoutOutliers(List<Priced> rows) {
		if (rows.size() < MIN_FOR_OUTLIER) {
			return rows;
		}
		List<BigDecimal> sorted = rows.stream().map(Priced::unitPrice).sorted().toList();
		int n = sorted.size();
		BigDecimal median = n % 2 == 1 ? sorted.get(n / 2)
				: sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(BigDecimal.valueOf(2), CALC_SCALE, RoundingMode.HALF_UP);
		BigDecimal low = median.divide(OUTLIER_RATIO, CALC_SCALE, RoundingMode.HALF_UP);
		BigDecimal high = median.multiply(OUTLIER_RATIO);
		return rows.stream().filter(r -> r.unitPrice().compareTo(low) >= 0 && r.unitPrice().compareTo(high) <= 0).toList();
	}

	/** 사이트 · 상품번호당 가장 싼 줄 하나 → 사이트마다 상위 3개 → 사이트는 1위 단가 순. */
	private static List<SiteOffers> rankSites(List<Priced> rows, Map<String, Verdict> verdictBySite) {
		Map<String, Priced> cheapestPerProduct = new HashMap<>();
		for (Priced p : rows) {
			String key = p.row().site() + "\u0000" + p.row().productNo();
			Priced current = cheapestPerProduct.get(key);
			if (current == null || compare(p, current) < 0) {
				cheapestPerProduct.put(key, p);
			}
		}
		Map<String, List<Priced>> bySite = cheapestPerProduct.values().stream().collect(Collectors.groupingBy(p -> p.row().site()));
		List<SiteOffers> sites = new ArrayList<>();
		for (Map.Entry<String, List<Priced>> e : bySite.entrySet()) {
			List<Offer> offers = e.getValue().stream().sorted(MarketOfferService::compare).limit(TOP_PER_SITE)
					.map(MarketOfferService::toOffer).toList();
			Verdict verdict = verdictBySite.getOrDefault(e.getKey(), Verdict.UNKNOWN);
			LocalDate collectedOn = e.getValue().stream().sorted(MarketOfferService::compare).findFirst().map(p -> p.row().collectedOn()).orElse(null);
			sites.add(new SiteOffers(e.getKey(), offers.get(0).unitPrice(), verdict.status(), verdict.dawnDelivery(), collectedOn, offers));
		}
		sites.sort(Comparator.comparing(SiteOffers::bestUnitPrice).thenComparing(SiteOffers::site));
		return sites;
	}

	static int compare(Priced a, Priced b) {
		int byUnitPrice = a.unitPrice().compareTo(b.unitPrice());
		if (byUnitPrice != 0) {
			return byUnitPrice;
		}
		int byPrice = Long.compare(a.row().price(), b.row().price());
		return byPrice != 0 ? byPrice : a.row().productNo().compareTo(b.row().productNo());
	}

	static Offer toOffer(Priced p) {
		Candidate c = p.row();
		return new Offer(c.site(), c.storeRegion(), c.productName(), c.spec(), c.price(), p.unit(), UnitPrices.round(p.unitPrice()),
				c.url(), c.form());
	}
}
