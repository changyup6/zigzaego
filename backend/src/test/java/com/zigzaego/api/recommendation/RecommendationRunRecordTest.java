package com.zigzaego.api.recommendation;

import static com.zigzaego.api.recommendation.RecommendationTestData.countRecommendations;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertCount;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertIngredient;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertMenu;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertRecipe;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertSale;
import static com.zigzaego.api.recommendation.RecommendationTestData.insertStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.forecast.service.ForecastService;
import com.zigzaego.api.inventory.service.InventoryEstimateService;
import com.zigzaego.api.recommendation.dto.RecommendationResponse;
import com.zigzaego.api.recommendation.dto.RecommendationRunResponse;
import com.zigzaego.api.recommendation.entity.RecommendationRun;
import com.zigzaego.api.recommendation.repository.RecommendationRunRepository;
import com.zigzaego.api.recommendation.service.RecommendationService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 추천 생성 실행 기록 — 같은 날 재실행 시 계산 건너뛰기 (S15P31A308-188, ADR-0009).
 * 재고 추정 · 예측 서비스를 스파이로 감싸 "컨텍스트 조회를 보냈는지" 를 호출 여부로 검증한다 (소비량 SQL 은 재고 추정이 읽는다).
 * 기준일 T = 2026-10-10, 가게 시간대 Asia/Seoul.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RecommendationRunRecordTest {

	private static final LocalDate T = LocalDate.of(2026, 10, 10);
	private static final ZoneOffset KST = ZoneOffset.ofHours(9);

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private RecommendationService service;

	@Autowired
	private RecommendationRunRepository runRepository;

	@MockitoSpyBean
	private InventoryEstimateService inventoryEstimateService;

	@MockitoSpyBean
	private ForecastService forecastService;

	@Test
	void 같은_날_두_번째_실행은_재고_추정_예측_소비량을_다시_묻지_않는다() {
		long store = insertStore(jdbc, "기록");
		long 등심 = orderingIngredient(store, "등심");
		clearInvocations(inventoryEstimateService, forecastService);

		RecommendationRunResponse first = service.run(store, T);
		assertThat(first.createdCount()).isEqualTo(1);
		assertThat(first.noOrderCount()).isZero();
		verify(inventoryEstimateService, times(1)).estimateRun(eq(store), eq(T), anyList());
		verify(forecastService, times(1)).forecastRange(eq(store), any(), any(), eq(T));

		RecommendationRun run = runRepository.findByStoreIdAndRunDate(store, T).orElseThrow();
		assertThat(run.isFinished()).isTrue();
		assertThat(run.getStartedAt()).isBeforeOrEqualTo(run.getFinishedAt());
		assertThat(run.getCreatedCount()).isEqualTo(1);
		assertThat(run.getNoOrderCount()).isZero();

		clearInvocations(inventoryEstimateService, forecastService);
		RecommendationRunResponse second = service.run(store, T);

		verify(inventoryEstimateService, never()).estimateRun(eq(store), any(), anyList());
		verify(forecastService, never()).forecastRange(eq(store), any(), any(), any());
		verify(forecastService, never()).forecast(eq(store), any(), any());
		assertThat(second.createdCount()).isZero();
		assertThat(second.existingCount()).isEqualTo(1);
		assertThat(second.noOrderCount()).isZero();
		assertThat(second.recommendations()).extracting(RecommendationResponse::ingredientId)
				.containsExactly(String.valueOf(등심));
		assertThat(second.recommendations().get(0).id()).isEqualTo(first.recommendations().get(0).id());
		assertThat(countRecommendations(jdbc, store, T)).isEqualTo(1);
	}

	/** 실행 뒤 재료가 늘었다. force 없이는 건너뛰어 새 재료가 안 보이고, force=true 면 기존 건은 그대로 두고 새 재료만 계산한다. */
	@Test
	void force면_기존_건은_두고_없는_재료만_새로_계산한다() {
		long store = insertStore(jdbc, "강제");
		long 등심 = orderingIngredient(store, "등심");
		RecommendationRunResponse first = service.run(store, T);
		String keptId = first.recommendations().get(0).id();

		long 양배추 = orderingIngredient(store, "양배추");

		RecommendationRunResponse skipped = service.run(store, T, false);
		assertThat(skipped.createdCount()).isZero();
		assertThat(skipped.recommendations()).extracting(RecommendationResponse::ingredientId)
				.containsExactly(String.valueOf(등심));

		clearInvocations(inventoryEstimateService);
		RecommendationRunResponse forced = service.run(store, T, true);

		verify(inventoryEstimateService, times(1)).estimateRun(eq(store), eq(T), anyList());
		assertThat(forced.createdCount()).isEqualTo(1);
		assertThat(forced.existingCount()).isEqualTo(1);
		assertThat(forced.recommendations()).extracting(RecommendationResponse::ingredientId)
				.containsExactly(String.valueOf(등심), String.valueOf(양배추));
		assertThat(forced.recommendations().get(0).id()).isEqualTo(keptId);
		assertThat(countRecommendations(jdbc, store, T)).isEqualTo(2);

		// 실행 기록은 가게 · 날짜 한 줄 — 덮어쓴다
		Map<String, Object> row = jdbc.queryForMap(
				"SELECT created_count, no_order_count FROM recommendation_run WHERE store_id = ? AND run_date = ?",
				store, T);
		assertThat(row.get("created_count")).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_run WHERE store_id = ?", Integer.class,
				store)).isEqualTo(1);

		// force 로 다시 돌려도 둘 다 기존 건
		RecommendationRunResponse again = service.run(store, T, true);
		assertThat(again.createdCount()).isZero();
		assertThat(again.existingCount()).isEqualTo(2);
		assertThat(countRecommendations(jdbc, store, T)).isEqualTo(2);
	}

	/** 가게 1이 오늘 실행을 끝내도 가게 2에는 실행 기록이 없고, 가게 2의 실행은 건너뛰지 않는다. */
	@Test
	void 남의_가게_실행_기록은_보이지_않는다() {
		long mine = insertStore(jdbc, "내가게");
		long other = insertStore(jdbc, "남의가게");
		orderingIngredient(mine, "등심");
		orderingIngredient(other, "등심");

		service.run(mine, T);
		assertThat(runRepository.findByStoreIdAndRunDate(mine, T)).isPresent();
		assertThat(runRepository.findByStoreIdAndRunDate(other, T)).isEmpty();

		clearInvocations(inventoryEstimateService);
		RecommendationRunResponse otherRun = service.run(other, T);

		verify(inventoryEstimateService, times(1)).estimateRun(eq(other), eq(T), anyList());
		assertThat(otherRun.createdCount()).isEqualTo(1);
		assertThat(otherRun.existingCount()).isZero();
		assertThat(runRepository.findByStoreIdAndRunDate(other, T)).isPresent();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_run WHERE store_id = ?", Integer.class,
				mine)).isEqualTo(1);
	}

	/** 돈까스 1인분 150, 10-03 ~ 10-09 매일 10 판매, 10-09 실사 100 → 추정 재고 0 → 발주. */
	private long orderingIngredient(long store, String name) {
		long ingredient = insertIngredient(jdbc, store, name, "G", "BOM", "CHILLED", "2", false);
		long menu = insertMenu(jdbc, store, "돈까스-" + name);
		insertRecipe(jdbc, store, menu, ingredient, "150", "MANUAL");
		for (int i = 3; i <= 9; i++) {
			insertSale(jdbc, store, LocalDate.of(2026, 10, i), menu, "10");
		}
		insertCount(jdbc, store, ingredient, OffsetDateTime.of(2026, 10, 9, 0, 0, 0, 0, KST), "100");
		return ingredient;
	}
}
