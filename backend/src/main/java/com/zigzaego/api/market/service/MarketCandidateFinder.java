package com.zigzaego.api.market.service;

import com.zigzaego.api.domain.market.ItemMatcher;
import com.zigzaego.api.ingredient.entity.IngredientUnit;
import com.zigzaego.api.market.catalog.MarketItemGroup;
import com.zigzaego.api.market.repository.MarketProductJdbcRepository;
import com.zigzaego.api.market.repository.MarketProductJdbcRepository.Candidate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 검색어(재료 이름)에 "정확히 같은 품목" 인 시세 줄을 찾아 기준 단위 단가까지 붙인다 (ADR-0012, 기준 문서 5 · 10절).
 *
 * <ul>
 *   <li><b>LABEL</b> — 재료 이름이 품목군에 매핑되면: 라벨이 그 품목군(원물 · 손질품, 확신 0.6 이상)이라고 한 줄
 *       + 라벨이 아직 없는 상품명 중 규칙({@link ItemMatcher})이 맞다고 한 줄. 단가는 품목군 기준 단위로 환산(개 ↔ g · ml ↔ g)</li>
 *   <li><b>RULE</b> — 품목군이 없으면(표 밖 재료 · 부모 품목군 · 겹치는 별칭): 지금까지처럼 이름 검색 + 규칙. 단가는 데이터셋 단위 그대로</li>
 * </ul>
 * 수량은 크롤러 규격 해석(total_qty · unit)이 먼저, 없으면 라벨이 상품명에서 읽은 수량. 기준 단위가 개인 품목군에서 둘이 2배 넘게 어긋나면
 * 규격 해석이 틀린 것으로 보고 라벨 개수를 쓴다("지킴이계란 대란 1560g(30개입)" 이 46,800g 으로 읽힌 사례).
 */
@Service
@RequiredArgsConstructor
public class MarketCandidateFinder {

	/** 라벨 확신이 이 아래면 UNKNOWN 취급 — 크롤러 {@code classify.MIN_CONFIDENCE} 와 같은 값. */
	static final BigDecimal MIN_CONFIDENCE = new BigDecimal("0.6");

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	private static final int CALC_SCALE = 10;

	private final MarketProductJdbcRepository repository;

	public enum Source { LABEL, RULE }

	/**
	 * 정확한 상품 한 줄 + 단가.
	 *
	 * @param unit 단가의 단위. 품목군이 있고 환산이 됐으면 기준 단위, 아니면 데이터셋 단위
	 * @param unitPrice 원 / unit 1 (반올림 전)
	 * @param inBaseUnit 품목군 기준 단위로 낸 값인가. false 면 값을 나란히 비교할 수 없어 '다른 단위' 로 따로 보여 준다
	 */
	public record Priced(Candidate row, IngredientUnit unit, BigDecimal unitPrice, boolean inBaseUnit) {
	}

	public record Found(Source source, List<Priced> rows, LocalDate collectedOn) {
	}

	/** 사이트별 최신 수집일에서 정확한 상품. */
	public Found latest(String query, Optional<MarketItemGroup> group) {
		return find(query, group, null);
	}

	/** {@code since} 이후 모든 수집일에서 정확한 상품 — 추이용. */
	public Found since(String query, Optional<MarketItemGroup> group, LocalDate since) {
		return find(query, group, since);
	}

	private Found find(String query, Optional<MarketItemGroup> group, LocalDate since) {
		String regex = ItemMatcher.candidateRegex(query);
		List<Candidate> byRegex = since == null ? repository.findLatestByRegex(regex) : repository.findByRegexSince(regex, since);
		List<Priced> out = new ArrayList<>();
		Source source;
		if (group.isPresent()) {
			source = Source.LABEL;
			MarketItemGroup g = group.get();
			List<Candidate> labeled = since == null ? repository.findLatestByGroup(g.code(), MIN_CONFIDENCE)
					: repository.findByGroupSince(g.code(), MIN_CONFIDENCE, since);
			for (Candidate c : labeled) {
				out.add(price(g, c));
			}
			for (Candidate c : byRegex) {
				if (!c.labeled() && ItemMatcher.isExact(c.productName(), query)) {  // 라벨 없는 새 상품명은 규칙으로 임시 판정
					out.add(price(g, c));
				}
			}
		} else {
			source = Source.RULE;
			for (Candidate c : byRegex) {
				if (c.unitPrice100() != null && c.unitPrice100().signum() > 0 && c.unit() != null
						&& ItemMatcher.isExact(c.productName(), query)) {
					out.add(new Priced(c, MarketItemGroup.unitOf(c.unit()), datasetUnitPrice(c), false));
				}
			}
		}
		LocalDate collectedOn = byRegex.stream().map(Candidate::collectedOn).filter(d -> d != null)
				.max(LocalDate::compareTo).orElse(null);
		out.removeIf(p -> p.unitPrice() == null);
		return new Found(source, out, collectedOn);
	}

	/**
	 * 품목군 기준 단위 단가. 총량을 알면 판매가 ÷ 기준 단위 양, 모르면 크롤러 단위가격을 환산값으로 기준 단위에 맞춘다(원/g × 60 = 원/개).
	 * 환산이 안 되면 데이터셋 단위 값으로(inBaseUnit false), 그것도 없으면 unitPrice null.
	 */
	public static Priced price(MarketItemGroup g, Candidate c) {
		BigDecimal base = baseQty(g, c);
		if (base != null && base.signum() > 0) {
			return new Priced(c, g.baseUnit(), BigDecimal.valueOf(c.price()).divide(base, CALC_SCALE, RoundingMode.HALF_UP), true);
		}
		if (c.unitPrice100() != null && c.unitPrice100().signum() > 0 && MarketItemGroup.unitOf(c.unit()) != null) {
			BigDecimal perDatasetUnit = datasetUnitPrice(c);
			BigDecimal basePerDatasetUnit = g.toBase(BigDecimal.ONE, c.unit());  // 데이터셋 단위 1 = 기준 단위 몇 개
			if (basePerDatasetUnit != null && basePerDatasetUnit.signum() > 0) {
				return new Priced(c, g.baseUnit(), perDatasetUnit.divide(basePerDatasetUnit, CALC_SCALE, RoundingMode.HALF_UP), true);
			}
			return new Priced(c, MarketItemGroup.unitOf(c.unit()), perDatasetUnit, false);
		}
		return new Priced(c, g.baseUnit(), null, false);
	}

	/** 기준 단위 양: 규격 해석 → 라벨 수량 → 교차 검증 (기준 문서 5.3절). */
	static BigDecimal baseQty(MarketItemGroup g, Candidate c) {
		BigDecimal parsed = g.toBase(c.totalQty(), c.unit());
		BigDecimal labeled = g.toBase(c.labelQty(), c.labelUnit());
		if (parsed != null && labeled != null && g.baseUnit() == IngredientUnit.EA && "EA".equals(c.labelUnit())
				&& !"개".equals(c.unit())) {
			BigDecimal ratio = parsed.divide(labeled, CALC_SCALE, RoundingMode.HALF_UP);
			if (ratio.compareTo(new BigDecimal("0.5")) < 0 || ratio.compareTo(BigDecimal.valueOf(2)) > 0) {
				return labeled;
			}
		}
		return parsed != null ? parsed : labeled;
	}

	/** 데이터셋 단위가격(원/100g · 원/100ml · 원/개) → 원 / 단위 1 (결정 #57). */
	static BigDecimal datasetUnitPrice(Candidate c) {
		BigDecimal factor = "개".equals(c.unit()) ? BigDecimal.ONE : HUNDRED;
		return c.unitPrice100().divide(factor, CALC_SCALE, RoundingMode.HALF_UP);
	}
}
