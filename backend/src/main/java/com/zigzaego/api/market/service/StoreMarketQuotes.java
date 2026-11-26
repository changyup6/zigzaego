package com.zigzaego.api.market.service;

import com.zigzaego.api.global.config.StoreClock;
import com.zigzaego.api.market.delivery.DeliveryCoverage.Status;
import com.zigzaego.api.market.dto.IngredientOffersResponse;
import com.zigzaego.api.market.dto.IngredientOffersResponse.Offer;
import com.zigzaego.api.market.dto.IngredientOffersResponse.SiteOffers;
import com.zigzaego.api.market.dto.MarketPriceHistoryResponse;
import com.zigzaego.api.market.dto.MarketPriceHistoryResponse.Point;
import com.zigzaego.api.market.dto.MarketPriceHistoryResponse.SiteSeries;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/** 명시적으로 가져온 가게 가격만 합친다. 모든 조회는 store와 ingredient 양쪽으로 제한한다. */
@Service
@RequiredArgsConstructor
public class StoreMarketQuotes {
	private final NamedParameterJdbcTemplate jdbc;
	private final StoreClock clock;
	private record Quote(String site, LocalDate date, BigDecimal unitPrice, long packPrice, String name, String spec,
			String url, boolean synthetic) { }
	private List<Quote> rows(long storeId, String ingredientId, LocalDate from, LocalDate to) {
		return jdbc.query("""
				SELECT site,collected_on,unit_price,pack_price,product_name,spec,url,synthetic
				FROM store_market_quote WHERE store_id=:store AND ingredient_id=:ingredient
				AND collected_on BETWEEN :from AND :to ORDER BY site,collected_on
				""", new MapSqlParameterSource("store", storeId).addValue("ingredient", Long.parseLong(ingredientId))
				.addValue("from", from).addValue("to", to), (rs, n) -> new Quote(rs.getString("site"),
					rs.getObject("collected_on", LocalDate.class), rs.getBigDecimal("unit_price"), rs.getLong("pack_price"),
					rs.getString("product_name"), rs.getString("spec"), rs.getString("url"), rs.getBoolean("synthetic")));
	}
	public IngredientOffersResponse merge(long storeId, IngredientOffersResponse base) {
		LocalDate today = LocalDate.now(clock.of(storeId));
		List<Quote> quotes = rows(storeId, base.ingredientId(), LocalDate.of(1970, 1, 1), today);
		if (quotes.isEmpty()) return base;
		Map<String, SiteOffers> sites = new LinkedHashMap<>();
		Set<String> overridden = new HashSet<>();
		BigDecimal factor = base.toIngredientUnitFactor();
		if (factor != null) for (SiteOffers s : base.sites()) {
			if (s.collectedOn().isAfter(today)) continue;
			List<Offer> offers = s.offers().stream().map(o -> new Offer(o.site(), o.region(), o.productName(), o.spec(),
					o.price(), base.ingredientUnit(), o.unitPrice().multiply(factor), o.url(), o.form())).toList();
			sites.put(s.site(), new SiteOffers(s.site(), s.bestUnitPrice().multiply(factor), s.delivery(), s.dawnDelivery(),
					s.collectedOn(), offers, s.synthetic()));
		}
		for (Quote q : quotes) {
			SiteOffers old = sites.get(q.site());
			if (old != null && old.collectedOn().isAfter(q.date())) continue;
			Offer o = new Offer(q.site(), null, q.name(), q.spec(), q.packPrice(), base.ingredientUnit(), q.unitPrice(), q.url(), null);
			overridden.add(q.site());
			sites.put(q.site(), new SiteOffers(q.site(), q.unitPrice(), Status.AVAILABLE, null, q.date(), List.of(o), q.synthetic()));
		}
		List<SiteOffers> sorted = sites.values().stream().sorted(Comparator.comparing(SiteOffers::bestUnitPrice).thenComparing(SiteOffers::site)).toList();
		LocalDate collected = sorted.stream().map(SiteOffers::collectedOn).max(Comparator.naturalOrder()).orElse(today);
		return new IngredientOffersResponse(base.ingredientId(), base.query(), base.itemGroup(), base.source(),
				base.ingredientUnit(), true, base.ingredientUnit(), BigDecimal.ONE, collected, base.excludedOutliers(), sorted,
				base.otherUnits().stream().filter(o -> !overridden.contains(o.site())).toList(), base.storeSigungu(),
				base.deliveryUnknown().stream().filter(s -> !overridden.contains(s)).toList(),
				base.notDelivering().stream().filter(s -> !overridden.contains(s)).toList());
	}
	public MarketPriceHistoryResponse merge(long storeId, MarketPriceHistoryResponse base) {
		List<Quote> quotes = rows(storeId, base.ingredientId(), base.from(), base.to());
		if (quotes.isEmpty()) return base;
		Map<String, SiteSeries> sites = new LinkedHashMap<>();
		BigDecimal factor = base.toIngredientUnitFactor();
		if (factor != null) for (SiteSeries s : base.sites()) sites.put(s.site(), new SiteSeries(s.site(), s.delivery(), s.dawnDelivery(),
				s.points().stream().map(p -> new Point(p.date(), p.unitPrice().multiply(factor), p.price(), p.productName())).toList(), s.synthetic()));
		for (Quote q : quotes) {
			SiteSeries old = sites.get(q.site());
			List<Point> points = new ArrayList<>(old == null ? List.of() : old.points());
			points.removeIf(p -> p.date().equals(q.date()));
			points.add(new Point(q.date(), q.unitPrice(), q.packPrice(), q.name()));
			points.sort(Comparator.comparing(Point::date));
			sites.put(q.site(), new SiteSeries(q.site(), Status.AVAILABLE, null, points, q.synthetic()));
		}
		List<String> overridden = quotes.stream().map(Quote::site).distinct().toList();
		return new MarketPriceHistoryResponse(base.ingredientId(), base.query(), base.itemGroup(), base.source(), base.ingredientUnit(),
				base.ingredientUnit(), BigDecimal.ONE, base.from(), base.to(), base.storeSigungu(),
				base.deliveryUnknown().stream().filter(s -> !overridden.contains(s)).toList(),
				base.notDelivering().stream().filter(s -> !overridden.contains(s)).toList(), sites.values().stream().sorted(Comparator.comparing((SiteSeries s) -> s.points().getLast().unitPrice()).thenComparing(SiteSeries::site)).toList());
	}
}
