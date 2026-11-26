package com.zigzaego.api.market;

import static org.assertj.core.api.Assertions.assertThat;

import com.zigzaego.api.ingredient.entity.IngredientUnit;
import com.zigzaego.api.market.catalog.MarketItemGroup;
import com.zigzaego.api.market.catalog.MarketItemGroupCatalog;
import com.zigzaego.api.market.catalog.MarketItemGroupCatalog.Snapshot;
import com.zigzaego.api.market.repository.MarketProductJdbcRepository.Candidate;
import com.zigzaego.api.market.service.MarketCandidateFinder;
import com.zigzaego.api.market.service.MarketCandidateFinder.Priced;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 품목군 단위 환산 · 재료 이름 매핑 · 수량 교차 검증 (ADR-0012). 기대값은 크롤러 {@code ai/tests/market/test_taxonomy.py} 와 같다.
 */
class MarketItemGroupTest {

	static final MarketItemGroup EGG = new MarketItemGroup("EGG", "계란", null, "계란", true, IngredientUnit.EA,
			new BigDecimal("60"), null, List.of("계란", "달걀", "특란"), "");

	static final MarketItemGroup SOY = new MarketItemGroup("SOY_SAUCE", "간장", null, "양념", true, IngredientUnit.ML,
			null, new BigDecimal("1.2"), List.of("간장", "진간장"), "");

	static final MarketItemGroup TOFU = new MarketItemGroup("TOFU", "두부", null, "두부", true, IngredientUnit.G,
			null, null, List.of("두부"), "");

	static final MarketItemGroup PORK = new MarketItemGroup("PORK", "돼지고기", null, "육류", false, IngredientUnit.G,
			null, null, List.of("돼지고기", "등심"), "");

	static final MarketItemGroup BEEF = new MarketItemGroup("BEEF", "소고기", null, "육류", false, IngredientUnit.G,
			null, null, List.of("소고기", "등심"), "");

	@Test
	void 기준_단위로_환산한다() {
		assertThat(EGG.toBase(new BigDecimal("30"), "개")).isEqualByComparingTo("30");
		assertThat(EGG.toBase(new BigDecimal("1800"), "g")).isEqualByComparingTo("30");  // 1.8kg (60g x 30)
		assertThat(SOY.toBase(new BigDecimal("1900"), "g")).isEqualByComparingTo("1583.3333333333");
		assertThat(SOY.toBase(new BigDecimal("1800"), "ML")).isEqualByComparingTo("1800");
		assertThat(TOFU.toBase(new BigDecimal("2"), "개")).isNull();  // 1모 무게를 정하지 않았다
		assertThat(EGG.toBase(null, "개")).isNull();
		assertThat(EGG.toBase(BigDecimal.ZERO, "개")).isNull();
		assertThat(EGG.toBase(BigDecimal.ONE, "box")).isNull();
	}

	@Test
	void 재료_이름은_정확히_같은_별칭_하나에만_매핑한다() {
		Snapshot s = new Snapshot(List.of(EGG, SOY, TOFU, PORK, BEEF));
		assertThat(s.forIngredientName("계란")).contains(EGG);
		assertThat(s.forIngredientName(" 달 걀 ")).contains(EGG);
		assertThat(s.forIngredientName("진간장")).contains(SOY);
		assertThat(s.forIngredientName("계란찜")).isEmpty();
		assertThat(s.forIngredientName("등심")).isEmpty();  // 두 품목군이 쓰는 별칭
		assertThat(MarketItemGroupCatalog.normalize("다진 마늘")).isEqualTo("다진마늘");
	}

	@Test
	void 상품명에_별칭이_낱말로_들면_LLM_분류_후보다() {
		Snapshot s = new Snapshot(List.of(EGG, SOY, TOFU, PORK, BEEF));
		assertThat(s.isCandidate("흙대파 1단")).isFalse();  // 표에 대파가 없다
		assertThat(s.isCandidate("국산 계란 30구")).isTrue();
		assertThat(s.isCandidate("계란 찜용 450g")).isTrue();  // 후보일 뿐, 품목군은 LLM 이 정한다
		assertThat(s.isCandidate("계란찜 450g")).isFalse();  // 별칭 바로 뒤에 한글이 붙으면 다른 낱말 (크롤러 word_re 와 같다)
		assertThat(s.isCandidate("계란(특란) 10구")).isTrue();  // 괄호는 정규식 글자 그대로
		assertThat(s.isCandidate("두부조림 반찬")).isFalse();  // 별칭 뒤에 한글이 붙으면 다른 낱말
		assertThat(s.isCandidate("순두부 1kg")).isTrue();  // 앞은 괜찮다
		assertThat(s.isCandidate("주방세제 1L")).isFalse();
		assertThat(s.isCandidate(null)).isFalse();
	}

	@Test
	void 프롬프트_표는_코드_이름_별칭_메모_순이고_부모는_표시한다() {
		Snapshot s = new Snapshot(List.of(EGG, PORK));
		assertThat(s.promptTable()).isEqualTo("EGG | 계란 | 달걀 · 특란\nPORK | 돼지고기 | 등심 | 부위 품목군의 부모 — 부위를 모를 때만");
	}

	private static Candidate candidate(BigDecimal totalQty, String unit, BigDecimal labelQty, String labelUnit, long price) {
		return new Candidate("식봄", null, "1", "계란", null, price, totalQty, unit, null, "u", LocalDate.of(2026, 10, 31),
				"RAW", labelQty, labelUnit, new BigDecimal("0.9"));
	}

	@Test
	void 수량은_규격_해석이_먼저고_개수가_두_배_넘게_어긋나면_라벨_개수를_쓴다() {
		// 1560g (30개입) 이 46,800g 으로 읽힌 사례 → 라벨 30개
		Priced p = MarketCandidateFinder.price(EGG, candidate(new BigDecimal("46800"), "g", new BigDecimal("30"), "EA", 6890));
		assertThat(p.unit()).isEqualTo(IngredientUnit.EA);
		assertThat(p.inBaseUnit()).isTrue();
		assertThat(p.unitPrice()).isEqualByComparingTo("229.6666666667");
		// 1.8kg (60gx30) 은 규격 해석(30개)과 라벨(30개)이 맞는다 → 규격 해석
		assertThat(MarketCandidateFinder.price(EGG, candidate(new BigDecimal("1800"), "g", new BigDecimal("30"), "EA", 6000)).unitPrice())
				.isEqualByComparingTo("200");
		// 규격 해석이 없으면 라벨 수량
		assertThat(MarketCandidateFinder.price(EGG, candidate(null, null, new BigDecimal("10"), "EA", 5000)).unitPrice())
				.isEqualByComparingTo("500");
		// 둘 다 없으면 단가 없음
		assertThat(MarketCandidateFinder.price(EGG, candidate(null, null, null, null, 5000)).unitPrice()).isNull();
		// 환산값 없는 단위(두부 2모) → 데이터셋 단가로 '다른 단위'
		Candidate tofu = new Candidate("식봄", null, "2", "두부 2모", null, 3000, new BigDecimal("2"), "개", new BigDecimal("1500"), "u",
				LocalDate.of(2026, 10, 31), "RAW", null, null, new BigDecimal("0.9"));
		Priced t = MarketCandidateFinder.price(TOFU, tofu);
		assertThat(t.inBaseUnit()).isFalse();
		assertThat(t.unit()).isEqualTo(IngredientUnit.EA);
		assertThat(t.unitPrice()).isEqualByComparingTo("1500");
	}
}
