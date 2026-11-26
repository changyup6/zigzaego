package com.zigzaego.api.ingredient.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.auth.AuthTestSupport;
import com.zigzaego.api.ingredient.IngredientCategoryProperties;
import com.zigzaego.api.ingredient.entity.CategorySource;
import com.zigzaego.api.ingredient.entity.DefaultIngredientCategory;
import com.zigzaego.api.ingredient.entity.IngredientCategory;
import com.zigzaego.api.llm.GmsClient;
import com.zigzaego.api.llm.LlmErrorCode;
import com.zigzaego.api.llm.LlmException;
import com.zigzaego.api.llm.LlmRequest;
import com.zigzaego.api.llm.LlmResult;
import com.zigzaego.api.support.LlmFakes;
import com.zigzaego.api.support.StoreFixtures;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 재료 이름 → 카테고리 자동 분류 (S15P31A308-274). LLM 은 가짜 객체. 표 매핑 · LLM 선택 · 목록 밖 응답 → 기타 · LLM 예외 → 기타 ·
 * 시간 초과 → 기타 · 백업 파일 되살리기.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
		"app.ingredient-category.llm-timeout=500ms",
		"app.ingredient-category.backup-dir=build/test-data/ingredient-categories-classifier"})
class IngredientCategoryClassifierTest {

	@MockitoBean
	private GmsClient gmsClient;

	@Autowired
	private IngredientCategoryClassifier classifier;

	@Autowired
	private IngredientCategoryBackup backup;

	@Autowired
	private IngredientCategoryProperties properties;

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void removeBackupFile() throws IOException {
		Files.deleteIfExists(properties.backupDirPath().resolve(IngredientCategoryBackup.LATEST));
	}

	@Test
	void 재료표_품목군_표에_있는_이름은_LLM_없이_정한다() {
		long storeId = insertStore("표매핑");
		// 재료표에 없고 품목군 표(과일)에만 있는 이름 — 품목군 category '과일' → FRUIT
		String fruit = jdbc.queryForObject(
				"SELECT split_part(aliases, ';', 1) FROM market_item_group WHERE category = '과일' AND comparable ORDER BY code LIMIT 1",
				String.class);

		Map<String, IngredientCategory> picked = classifier.classify(storeId,
				List.of(" 스파게티 ", "다진마늘", fruit, "콩나물", "스파게티"));

		assertThat(picked).containsOnlyKeys("스파게티", "다진마늘", fruit, "콩나물");
		assertThat(picked.get("스파게티").getCode()).isEqualTo(DefaultIngredientCategory.GRAIN_NOODLE);  // 파스타면 별칭
		assertThat(picked.get("다진마늘").getCode()).isEqualTo(DefaultIngredientCategory.VEGETABLE);   // 마늘 별칭 '다진 마늘'
		assertThat(picked.get(fruit).getCode()).isEqualTo(DefaultIngredientCategory.FRUIT);
		assertThat(picked.get("콩나물").getCode()).isEqualTo(DefaultIngredientCategory.TOFU_BEAN);   // 기준 1: 채소가 아니라 두부·콩
		assertThat(picked.values()).allSatisfy(category -> assertThat(category.getStoreId()).isEqualTo(storeId));
		verifyNoInteractions(gmsClient);
	}

	@Test
	void 전분은_품목군_표를_거쳐_곡물면떡이다_V14() {
		long storeId = insertStore("전분");

		Map<String, IngredientCategory> picked = classifier.classify(storeId, List.of("옥수수 전분", "감자전분"));

		// 기준 2.1절 4번: 밀가루 · 전분 · 빵가루는 양념이 아니라 곡물·면·떡. V8 은 STARCH 를 '양념·소스' 로 넣었고 V14 가 '가루' 로 고친다
		assertThat(picked.get("옥수수 전분").getCode()).isEqualTo(DefaultIngredientCategory.GRAIN_NOODLE);
		assertThat(picked.get("감자전분").getCode()).isEqualTo(DefaultIngredientCategory.GRAIN_NOODLE);
		verifyNoInteractions(gmsClient);
	}

	@Test
	void DB_품목군_표의_category_는_ai_원본_CSV_와_전부_같다() throws IOException {
		Path original = Path.of("../ai/seed/market_item_group.csv");
		assertThat(original).as("ai 원본이 있어야 한다: " + original.toAbsolutePath()).isRegularFile();
		List<String> lines = Files.readAllLines(original, StandardCharsets.UTF_8);
		List<String> header = IngredientCategoryBackup.split(lines.get(0).replace("﻿", ""));
		int code = header.indexOf("code");
		int category = header.indexOf("category");
		Map<String, String> fromCsv = new java.util.TreeMap<>();
		for (String line : lines.subList(1, lines.size())) {
			if (!line.isBlank()) {
				List<String> cells = IngredientCategoryBackup.split(line);
				fromCsv.put(cells.get(code).strip(), cells.get(category).strip());
			}
		}
		Map<String, String> fromDb = new java.util.TreeMap<>();
		jdbc.query("SELECT code, category FROM market_item_group",
				rs -> { fromDb.put(rs.getString("code"), rs.getString("category")); });

		assertThat(fromCsv).containsEntry("STARCH", "가루");
		assertThat(fromDb).containsExactlyEntriesOf(fromCsv);  // V8 INSERT 뒤 CSV 가 바뀌면 그 차이를 새 마이그레이션으로 메운다 (V14 처럼)
	}

	@Test
	void 표에_없으면_LLM이_그_가게의_카테고리_목록에서_고른다() {
		long storeId = insertStore("LLM");
		when(gmsClient.complete(any(LlmRequest.class)))
				.thenReturn(answer(Map.of("수제 함박 패티", "가공식품", "트러플 오일", "양념·소스")));

		Map<String, IngredientCategory> picked = classifier.classify(storeId, List.of("수제 함박 패티", "트러플 오일"));

		assertThat(picked.get("수제 함박 패티").getCode()).isEqualTo(DefaultIngredientCategory.PROCESSED);
		assertThat(picked.get("트러플 오일").getCode()).isEqualTo(DefaultIngredientCategory.SAUCE);
		ArgumentCaptor<LlmRequest> request = ArgumentCaptor.forClass(LlmRequest.class);
		verify(gmsClient).complete(request.capture());
		assertThat(request.getValue().schemaName()).isEqualTo("category");
		assertThat(request.getValue().system()).contains("육류").contains("기타").doesNotContain("{{categories}}");
		assertThat(request.getValue().user()).contains("1. 수제 함박 패티").contains("2. 트러플 오일");
	}

	@Test
	void 사장님이_만든_카테고리도_후보에_들어가고_고를_수_있다() {
		long storeId = insertStore("디저트");
		jdbc.update("INSERT INTO ingredient_category (store_id, code, name, sort_order) VALUES (?, NULL, '디저트', 11)", storeId);
		when(gmsClient.complete(any(LlmRequest.class))).thenReturn(answer(Map.of("티라미수", " 디저트 ")));

		IngredientCategory picked = classifier.classify(storeId, List.of("티라미수")).get("티라미수");

		assertThat(picked.getName()).isEqualTo("디저트");
		assertThat(picked.getCode()).isNull();
		ArgumentCaptor<LlmRequest> request = ArgumentCaptor.forClass(LlmRequest.class);
		verify(gmsClient).complete(request.capture());
		assertThat(request.getValue().system()).contains("디저트");
	}

	@Test
	void 목록_밖_응답과_응답에_빠진_이름은_기타() {
		long storeId = insertStore("목록밖");
		when(gmsClient.complete(any(LlmRequest.class)))
				.thenReturn(answer(Map.of("정체불명", "없는 카테고리", "엉뚱한이름", "육류")));

		Map<String, IngredientCategory> picked = classifier.classify(storeId, List.of("정체불명", "빠진이름"));

		assertThat(picked.get("정체불명").isOther()).isTrue();
		assertThat(picked.get("빠진이름").isOther()).isTrue();
		assertThat(picked).doesNotContainKey("엉뚱한이름");
	}

	@Test
	void LLM_실패_예외_빈_응답_시간_초과는_전부_기타이고_예외를_올리지_않는다() {
		long storeId = insertStore("실패");

		when(gmsClient.complete(any(LlmRequest.class))).thenThrow(new LlmException(LlmErrorCode.FAILED));
		assertThat(classifier.classify(storeId, List.of("정체불명")).get("정체불명").isOther()).isTrue();

		when(gmsClient.complete(any(LlmRequest.class))).thenThrow(new IllegalStateException("뜻밖의 오류"));
		assertThat(classifier.classify(storeId, List.of("정체불명")).get("정체불명").isOther()).isTrue();

		when(gmsClient.complete(any(LlmRequest.class))).thenReturn(null);
		assertThat(classifier.classify(storeId, List.of("정체불명")).get("정체불명").isOther()).isTrue();

		when(gmsClient.complete(any(LlmRequest.class))).thenAnswer(invocation -> {
			Thread.sleep(3_000);
			return answer(Map.of("정체불명", "육류"));
		});
		long started = System.nanoTime();
		assertThat(classifier.classify(storeId, List.of("정체불명")).get("정체불명").isOther()).isTrue();
		assertThat((System.nanoTime() - started) / 1_000_000).as("시간 제한(500ms)에 끊긴다").isLessThan(2_500);
	}

	@Test
	void 백업_파일에_같은_가게_같은_이름이_있으면_LLM_없이_그_값이고_내보내면_같은_모양으로_쓴다() throws IOException {
		long storeId = insertStore("백업");
		String storeName = jdbc.queryForObject("SELECT name FROM store WHERE id = ?", String.class, storeId);
		Path file = properties.backupDirPath().resolve(IngredientCategoryBackup.LATEST);
		Files.createDirectories(file.getParent());
		Files.writeString(file, String.join("\n",
				"store_name,ingredient_name,category_code,category_name,category_source",
				storeName + ",비밀 소스,SAUCE,양념·소스,AUTO",
				storeName + ",\"쉼표, 재료\",,디저트,OWNER",          // 사장님이 만든 카테고리가 이 가게에 없다 → 표 · LLM 으로
				"다른 가게,정체불명,MEAT,육류,AUTO",
				""), StandardCharsets.UTF_8);

		Map<String, IngredientCategory> picked = classifier.classify(storeId, List.of("비밀 소스", "쉼표, 재료", "정체불명"));

		assertThat(picked.get("비밀 소스").getCode()).isEqualTo(DefaultIngredientCategory.SAUCE);
		assertThat(picked.get("쉼표, 재료").isOther()).isTrue();
		assertThat(picked.get("정체불명").isOther()).isTrue();   // 다른 가게 줄은 쓰지 않는다
		verify(gmsClient).complete(any(LlmRequest.class));       // 백업에서 못 찾은 둘만 LLM

		// 내보내기: 재료 ↔ 카테고리 전체가 같은 열로
		long ingredientId = StoreFixtures.insertIngredient(jdbc, storeId, "쉼표, \"따옴표\" 재료");
		jdbc.update("UPDATE ingredient SET category_id = (SELECT id FROM ingredient_category WHERE store_id = ? AND code = 'MEAT'),"
				+ " category_source = 'OWNER' WHERE id = ?", storeId, ingredientId);
		backup.exportLatest();
		Map<String, IngredientCategoryBackup.Row> rows = backup.load();
		IngredientCategoryBackup.Row row = IngredientCategoryBackup.find(rows, storeName, "쉼표, \"따옴표\" 재료").orElseThrow();
		assertThat(row.categoryCode()).isEqualTo("MEAT");
		assertThat(row.categoryName()).isEqualTo("육류");
		assertThat(row.source()).isEqualTo(CategorySource.OWNER);
		assertThat(Files.readString(file, StandardCharsets.UTF_8)).startsWith(IngredientCategoryBackup.HEADER + "\n")
				.contains("\"쉼표, \"\"따옴표\"\" 재료\",MEAT,육류,OWNER");

		// 합쳐 쓰기: DB 를 새로 만든 셈(기타 · AUTO)으로 다시 내보내도 파일이 아는 값은 남고, 파일에만 있던 줄도 남는다
		jdbc.update("UPDATE ingredient SET category_id = (SELECT id FROM ingredient_category WHERE store_id = ? AND code = 'OTHER'),"
				+ " category_source = 'AUTO' WHERE id = ?", storeId, ingredientId);
		backup.exportLatest();
		rows = backup.load();
		assertThat(IngredientCategoryBackup.find(rows, storeName, "쉼표, \"따옴표\" 재료").orElseThrow().categoryCode()).isEqualTo("MEAT");
		assertThat(IngredientCategoryBackup.find(rows, storeName, "비밀 소스").orElseThrow().categoryCode()).isEqualTo("SAUCE");
		assertThat(IngredientCategoryBackup.find(rows, "다른 가게", "정체불명")).isPresent();
		assertThat(Files.list(file.getParent()).filter(p -> p.getFileName().toString().endsWith(".tmp"))).isEmpty();
	}

	@Test
	void CSV_칸은_쉼표_따옴표를_감싸고_되읽으면_같다() {
		List<String> cells = List.of("가게", "쉼표, 있음", "따옴표 \"있음\"", "", "AUTO");
		String line = String.join(",", cells.stream().map(IngredientCategoryBackup::cell).toList());

		assertThat(line).isEqualTo("가게,\"쉼표, 있음\",\"따옴표 \"\"있음\"\"\",,AUTO");
		assertThat(IngredientCategoryBackup.split(line)).isEqualTo(cells);
	}

	private long insertStore(String label) {
		return AuthTestSupport.insertStore(jdbc, label + "-" + System.nanoTime());
	}

	private static LlmResult answer(Map<String, String> picks) {
		return LlmFakes.categoryAnswer(picks);
	}
}
