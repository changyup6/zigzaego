package com.zigzaego.api.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.llm.GmsClient;
import com.zigzaego.api.llm.LlmRequest;
import com.zigzaego.api.llm.LlmResult;
import com.zigzaego.api.market.importer.MarketProductImportService;
import com.zigzaego.api.market.importer.MarketProductsImportedEvent;
import com.zigzaego.api.market.label.MarketProductLabelJobConfig;
import com.zigzaego.api.market.label.MarketProductLabelJobTrigger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 시세 적재 이벤트 → Spring Batch 라벨 Job → {@code market_product_label} · {@code BATCH_*} 실행 기록 (S15P31A308-229, ADR-0014).
 * GMS 는 목 — 상품명 줄을 읽어 "계란" 이 들면 EGG RAW, "찜" 이 들면 PROCESSED, 아니면 SCALLION RAW 로 답한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
		"app.llm.key=test-key", "app.llm.base-url=http://127.0.0.1:1/v1", "app.llm.model=gpt-test",
		"app.market-label.batch-size=2", "app.market-label.threads=2",
		"app.market-label.backup-dir=build/test-market-labels"})
class MarketProductLabelJobTest {

	private static final Pattern LINE = Pattern.compile("^\\d+\\. (.*)$", Pattern.MULTILINE);

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ApplicationEventPublisher events;

	@Autowired
	private MarketProductLabelJobTrigger trigger;

	@Autowired
	private JsonMapper json;

	@Autowired
	private MarketProductImportService importService;

	@MockitoBean
	private GmsClient gmsClient;

	@BeforeEach
	void clean() {
		jdbc.update("DELETE FROM market_product_label");
		jdbc.update("DELETE FROM market_product");
		when(gmsClient.complete(any())).thenAnswer(inv -> {
			LlmRequest request = inv.getArgument(0);
			ObjectNode root = json.createObjectNode();
			ArrayNode items = root.putArray("items");
			Matcher m = LINE.matcher(request.user());
			while (m.find()) {
				String name = m.group(1);
				ObjectNode it = items.addObject().put("name", name);
				if (name.contains("찜")) {
					it.putNull("item_group_code").put("form", "PROCESSED").putNull("qty").putNull("qty_unit")
							.put("confidence", 0.9).put("reason", "조리됨");
				} else {
					it.put("item_group_code", name.contains("계란") ? "EGG" : "SCALLION").put("form", "RAW").put("qty", 30)
							.put("qty_unit", "EA").put("confidence", 0.95).put("reason", "원물");
				}
			}
			return new LlmResult(root, 1000, 200);
		});
	}

	private void product(String name) {
		jdbc.update("INSERT INTO market_product (site, product_no, product_name, price, url, collected_on) VALUES ('식봄', ?, ?, 1000, 'u', '2026-10-31')",
				name.hashCode() + "", name);
	}

	private Map<String, Object> label(String name) {
		return jdbc.queryForMap("SELECT item_group_code, form, confidence, reason, label_version FROM market_product_label WHERE product_name = ?", name);
	}

	@Test
	void 시세_적재_이벤트로_라벨_없는_후보_상품명만_분류하고_실행_기록을_남긴다() throws IOException {
		List.of("흙대파 1단", "국산 계란 30구", "계란 찜용 450g", "주방세제 1L", "양파링 과자", "계란찜 450g", "특란 10구").forEach(this::product);
		// 같은 사이트 · 다른 줄에 같은 이름이 있어도 상품명 하나에 한 번
		jdbc.update("INSERT INTO market_product (site, product_no, product_name, price, url, collected_on) VALUES ('마켓컬리', 'k1', '흙대파 1단', 900, 'u', '2026-10-30')");
		// 이미 현재 버전 라벨 → 보내지 않는다. 옛 버전 라벨 → 다시 분류
		jdbc.update("INSERT INTO market_product_label (product_name, item_group_code, form, confidence, reason, label_version) VALUES ('특란 10구', 'EGG', 'RAW', 0.7, '예전', 'v2')");
		jdbc.update("INSERT INTO market_product_label (product_name, item_group_code, form, confidence, reason, label_version) VALUES ('국산 계란 30구', NULL, 'UNKNOWN', 0, '옛 버전', 'v1')");
		long before = jdbc.queryForObject("SELECT count(*) FROM batch_job_execution", Long.class);

		events.publishEvent(new MarketProductsImportedEvent(Path.of("products.csv"), 7));

		// 후보 3개(흙대파 · 국산 계란 · 계란 찜용) — 세제(별칭 없음) · 양파링 · 계란찜(별칭 뒤 한글)은 보내지 않고 라벨도 없다
		assertThat(label("흙대파 1단")).containsEntry("item_group_code", "SCALLION").containsEntry("form", "RAW")
				.containsEntry("label_version", "v2");
		assertThat(label("국산 계란 30구")).containsEntry("item_group_code", "EGG").containsEntry("reason", "원물")
				.containsEntry("label_version", "v2");
		assertThat(label("계란 찜용 450g")).containsEntry("item_group_code", null).containsEntry("form", "PROCESSED");
		assertThat(label("특란 10구")).containsEntry("reason", "예전");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM market_product_label", Long.class)).isEqualTo(4L);
		// 묶음 2개씩 → 요청 2번 (chunk = 2 × 2 = 4 → 한 커밋)
		verify(gmsClient, times(2)).complete(any());
		assertThat(jdbc.queryForObject("SELECT commit_count FROM batch_step_execution ORDER BY step_execution_id DESC LIMIT 1", Long.class)).isEqualTo(1L);

		// Spring Batch 실행 기록 (Flyway V10 표)
		assertThat(jdbc.queryForObject("SELECT count(*) FROM batch_job_execution", Long.class)).isEqualTo(before + 1);
		Map<String, Object> job = jdbc.queryForMap("""
				SELECT e.status, e.exit_code, i.job_name FROM batch_job_execution e JOIN batch_job_instance i ON i.job_instance_id = e.job_instance_id
				ORDER BY e.job_execution_id DESC LIMIT 1""");
		assertThat(job).containsEntry("status", "COMPLETED").containsEntry("job_name", MarketProductLabelJobConfig.JOB_NAME);
		Map<String, Object> step = jdbc.queryForMap("""
				SELECT step_name, status, read_count, write_count, commit_count, exit_message FROM batch_step_execution
				ORDER BY step_execution_id DESC LIMIT 1""");
		assertThat(step).containsEntry("step_name", MarketProductLabelJobConfig.STEP_NAME).containsEntry("status", "COMPLETED")
				.containsEntry("read_count", 3L).containsEntry("write_count", 3L);
		assertThat((String) step.get("exit_message")).contains("라벨 3").contains("요청 2").contains("입력 토큰 2,000")
				.contains("PROCESSED 1").contains("RAW 2");
		Map<String, Object> params = jdbc.queryForMap("""
				SELECT count(*) FILTER (WHERE parameter_name = 'importedAt' AND identifying = 'Y') AS identifying,
				       max(parameter_value) FILTER (WHERE parameter_name = 'labelVersion') AS version
				FROM batch_job_execution_params WHERE job_execution_id = (SELECT max(job_execution_id) FROM batch_job_execution)""");
		assertThat(params).containsEntry("identifying", 1L).containsEntry("version", "v2");

		// 커밋마다 라벨 표 전체가 CSV 로 백업되고, 단계 끝에 시각 붙은 사본이 남는다. 적재 형식과 같아 그대로 다시 넣을 수 있다
		Path latest = Path.of("build/test-market-labels/labels_latest.csv");
		assertThat(latest).exists();
		assertThat(Files.readAllLines(latest, StandardCharsets.UTF_8)).hasSize(5)
				.first().isEqualTo("product_name,item_group_code,form,qty,qty_unit,confidence,reason,label_version");
		try (var files = Files.list(latest.getParent())) {
			assertThat(files.map(f -> f.getFileName().toString()).filter(n -> n.matches("labels_\\d{8}_\\d{4}\\.csv"))).isNotEmpty();
		}
		jdbc.update("DELETE FROM market_product_label");
		assertThat(importService.importLabels(latest).rows()).isEqualTo(4);
		assertThat(label("흙대파 1단")).containsEntry("item_group_code", "SCALLION").containsEntry("label_version", "v2");
		assertThat(label("특란 10구")).containsEntry("reason", "예전");
	}

	@Test
	void 다시_돌리면_남은_상품명만_보내고_없으면_요청하지_않는다() {
		List.of("깐대파 500g", "계란 15구", "대파 2단").forEach(this::product);

		JobExecution first = trigger.run(2);  // 상한 2 → 둘만 (이름순: 計 '계란 15구' · '깐대파 500g')
		assertThat(first.getStatus().isUnsuccessful()).isFalse();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM market_product_label", Long.class)).isEqualTo(2L);
		verify(gmsClient, times(1)).complete(any());

		JobExecution second = trigger.run(0);  // 남은 하나
		assertThat(second.getId()).isNotEqualTo(first.getId());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM market_product_label", Long.class)).isEqualTo(3L);
		verify(gmsClient, times(2)).complete(any());

		JobExecution third = trigger.run(0);  // 보낼 것 없음 — 그래도 실행 기록은 남는다
		assertThat(third.getStatus().isUnsuccessful()).isFalse();
		verify(gmsClient, times(2)).complete(any());
		assertThat(jdbc.queryForObject("SELECT read_count FROM batch_step_execution WHERE job_execution_id = ?", Long.class,
				third.getId())).isZero();
	}
}
