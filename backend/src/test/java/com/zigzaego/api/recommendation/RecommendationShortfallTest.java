package com.zigzaego.api.recommendation;

import static com.zigzaego.api.recommendation.RecommendationTestData.insertCount;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertIngredient;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertMenu;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertRecipe;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertSale;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertStore;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertSupplier;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertSupplierProduct;
import static com.zigzaego.api.recommendation.RecommendationTestData.setPrimaryProduct;
import static org.assertj.core.api.Assertions.assertThat;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.recommendation.dto.RecommendationResponse;
import com.zigzaego.api.recommendation.service.RecommendationService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 실사 차이가 반복되면 안전재고를 올리고 이유 문장에 한 줄 (S15P31A308-253, DECISIONS #88 ⑤).
 * 기준일 T = 2026-10-10(토), 손계산 가게는 {@link RecommendationSafetyTest} 와 같다: SS 3000 g · LTD 1500 g · 추정 2500 g.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RecommendationShortfallTest {

	private static final LocalDate T = LocalDate.of(2026, 10, 10);
	private static final ZoneOffset KST = ZoneOffset.ofHours(9);

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private RecommendationService service;

	@Test
	void 최근_실사_3회_중_2회_10퍼센트_이상_부족하면_평균_손실량만큼_안전재고를_올린다() {
		long store = 손계산_가게("반복손실");
		long 등심 = 등심(store);
		// 손실 600(12%) · 500(10%) · 100(2%) → 2회 → 평균 400 g
		estimatedCount(store, 등심, 3, "5000", "4400");
		estimatedCount(store, 등심, 5, "5000", "4500");
		estimatedCount(store, 등심, 7, "5000", "4900");

		RecommendationResponse r = service.run(store, T).recommendations().get(0);

		assertThat(r.safetyStock()).isEqualByComparingTo("3400.000");
		assertThat(r.reorderPoint()).isEqualByComparingTo("4900.000");
		assertThat(r.reason()).contains("안전재고 3400g.").contains("실사 때마다 평균 400g 부족.");
	}

	@Test
	void 한_번만_부족하면_안전재고는_그대로고_문장도_없다() {
		long store = 손계산_가게("한번손실");
		long 등심 = 등심(store);
		estimatedCount(store, 등심, 3, "5000", "4400");
		estimatedCount(store, 등심, 5, "5000", "4600");
		estimatedCount(store, 등심, 7, "5000", "4900");

		RecommendationResponse r = service.run(store, T).recommendations().get(0);

		assertThat(r.safetyStock()).isEqualByComparingTo("3000.000");
		assertThat(r.reason()).doesNotContain("실사 때마다");
	}

	@Test
	void 다른_가게의_손실은_내_안전재고에_섞이지_않는다() {
		long store = 손계산_가게("내가게");
		long other = 손계산_가게("남의가게");
		long 남의등심 = 등심(other);
		estimatedCount(other, 남의등심, 3, "5000", "4000");
		estimatedCount(other, 남의등심, 5, "5000", "4000");

		RecommendationResponse r = service.run(store, T).recommendations().get(0);

		assertThat(r.safetyStock()).isEqualByComparingTo("3000.000");
	}

	private long 등심(long store) {
		return jdbc.queryForObject("SELECT id FROM ingredient WHERE store_id = ? AND name = '등심'", Long.class, store);
	}

	/** 기준점(10-09 실사)보다 앞선 실사 — 추정 재고는 바꾸지 않고 손실 이력만 남긴다. */
	private void estimatedCount(long store, long ingredient, int day, String estimated, String qty) {
		jdbc.update("INSERT INTO stock_count (store_id, ingredient_id, counted_at, qty, estimated_qty, method)"
				+ " VALUES (?, ?, ?, CAST(? AS NUMERIC), CAST(? AS NUMERIC), 'CORRECTED')",
				store, ingredient, OffsetDateTime.of(2026, 10, day, 0, 0, 0, 0, KST), qty, estimated);
	}

	private long 손계산_가게(String name) {
		long store = insertStore(jdbc, name);
		long 등심 = insertIngredient(jdbc, store, "등심", "G", "BOM", "CHILLED", "2", false);
		long 돈까스 = insertMenu(jdbc, store, "돈까스");
		insertRecipe(jdbc, store, 돈까스, 등심, "150", "MANUAL");
		long supplier = insertSupplier(jdbc, store, "정육", 1, 1);
		long product = insertSupplierProduct(jdbc, store, supplier, 등심, "1000", null);
		setPrimaryProduct(jdbc, store, 등심, product);
		for (int i = 3; i <= 9; i++) {
			insertSale(jdbc, store, LocalDate.of(2026, 10, i), 돈까스, "10");
		}
		insertCount(jdbc, store, 등심, OffsetDateTime.of(2026, 10, 9, 0, 0, 0, 0, KST), "4000");
		return store;
	}
}
