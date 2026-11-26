package com.zigzaego.api.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zigzaego.api.ingredient.entity.IngredientUnit;
import com.zigzaego.api.llm.GmsClient;
import com.zigzaego.api.llm.LlmErrorCode;
import com.zigzaego.api.llm.LlmException;
import com.zigzaego.api.llm.LlmRequest;
import com.zigzaego.api.llm.LlmResult;
import com.zigzaego.api.market.catalog.MarketItemGroup;
import com.zigzaego.api.market.catalog.MarketItemGroupCatalog.Snapshot;
import com.zigzaego.api.market.label.MarketProductLabelClassifier;
import com.zigzaego.api.market.label.MarketProductLabelClassifier.Usage;
import com.zigzaego.api.market.label.MarketProductLabelJdbcRepository.LabelRow;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 상품명 라벨 LLM 호출 — 프롬프트 모양 · 응답 검증 · 깨진 묶음 반으로 나누기 (S15P31A308-229).
 * 기대값은 ai {@code tests/market/test_classify.py} 와 같다.
 */
class MarketProductLabelClassifierTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Pattern LINE = Pattern.compile("^\\d+\\. (.*)$", Pattern.MULTILINE);

	static final MarketItemGroup EGG = new MarketItemGroup("EGG", "계란", null, "계란", true, IngredientUnit.EA,
			new BigDecimal("60"), null, List.of("계란", "달걀", "특란"), "액란은 EGG_LIQUID");
	static final MarketItemGroup PORK = new MarketItemGroup("PORK", "돼지고기", null, "육류", false, IngredientUnit.G,
			null, null, List.of("돼지고기"), "");
	static final Snapshot GROUPS = new Snapshot(List.of(EGG, PORK));

	private final GmsClient gms = mock(GmsClient.class);
	private final MarketProductLabelClassifier classifier = new MarketProductLabelClassifier(gms, JSON);
	private final List<LlmRequest> sent = new ArrayList<>();

	/** 사용자 글의 "N. 이름" 줄마다 items 한 항목. {@code answer} 가 항목을 채운다. */
	private void reply(java.util.function.BiConsumer<String, ObjectNode> answer) {
		when(gms.complete(any())).thenAnswer(inv -> {
			LlmRequest request = inv.getArgument(0);
			sent.add(request);
			ObjectNode root = JSON.createObjectNode();
			ArrayNode items = root.putArray("items");
			Matcher m = LINE.matcher(request.user());
			while (m.find()) {
				ObjectNode it = items.addObject();
				it.put("name", m.group(1));
				answer.accept(m.group(1), it);
			}
			return new LlmResult(root, 100, 20);
		});
	}

	private static void raw(ObjectNode it, String code, double conf) {
		it.put("item_group_code", code).put("form", "RAW").put("qty", 30).put("qty_unit", "EA").put("confidence", conf)
				.put("reason", "원물");
	}

	@Test
	void 프롬프트에_품목군_표와_번호_매긴_상품명이_들어간다() {
		reply((name, it) -> raw(it, "EGG", 0.9));

		classifier.classify(List.of("특란 30구", "계란찜"), GROUPS, "v2", new Usage());

		LlmRequest r = sent.get(0);
		assertThat(r.schemaName()).isEqualTo("item_group");
		assertThat(r.schema().path("properties").path("items").path("type").asString()).isEqualTo("array");
		assertThat(r.system()).contains("EGG | 계란 | 달걀 · 특란 | 액란은 EGG_LIQUID")
				.contains("PORK | 돼지고기 |  | 부위 품목군의 부모 — 부위를 모를 때만")
				.doesNotContain("{{groups}}");
		assertThat(r.user()).isEqualTo("상품명 목록:\n1. 특란 30구\n2. 계란찜");
	}

	@Test
	void 응답을_검증하고_정리한다() {
		reply((name, it) -> {
			switch (name) {
				case "특란 30구" -> raw(it, "egg", 1.5);  // 소문자 코드 · 확신 1 초과
				case "계란찜" -> it.put("item_group_code", "EGG").put("form", "PROCESSED").putNull("qty").putNull("qty_unit")
						.put("confidence", 0.9).put("reason", "조리됨");  // 가공품엔 코드가 없다
				case "양파" -> it.put("item_group_code", "ONION").put("form", "RAW").put("qty", 1).put("qty_unit", "KG")
						.put("confidence", 0.8).put("reason", "표에 없는 코드");  // 지어낸 코드 → UNKNOWN, 모르는 단위 → 수량 없음
				case "무엇" -> it.putNull("item_group_code").put("form", "RAW").put("qty", -1).putNull("qty_unit")
						.put("confidence", 0.2).put("reason", "x".repeat(100));  // 원물인데 코드 없음 → UNKNOWN, 이유 80자
				default -> throw new IllegalStateException(name);
			}
		});
		Usage usage = new Usage();

		List<LabelRow> rows = classifier.classify(List.of("특란 30구", "계란찜", "양파", "무엇"), GROUPS, "v2", usage);

		assertThat(rows).extracting(LabelRow::productName).containsExactly("특란 30구", "계란찜", "양파", "무엇");
		LabelRow egg = rows.get(0);
		assertThat(egg.itemGroupCode()).isEqualTo("EGG");
		assertThat(egg.form()).isEqualTo("RAW");
		assertThat(egg.qty()).isEqualByComparingTo("30");
		assertThat(egg.qtyUnit()).isEqualTo("EA");
		assertThat(egg.confidence()).isEqualByComparingTo("1");
		assertThat(egg.labelVersion()).isEqualTo("v2");
		assertThat(rows.get(1).itemGroupCode()).isNull();
		assertThat(rows.get(1).form()).isEqualTo("PROCESSED");
		assertThat(rows.get(2).itemGroupCode()).isNull();
		assertThat(rows.get(2).form()).isEqualTo("UNKNOWN");
		assertThat(rows.get(2).qty()).isNull();
		assertThat(rows.get(2).qtyUnit()).isNull();
		assertThat(rows.get(3).form()).isEqualTo("UNKNOWN");
		assertThat(rows.get(3).qty()).isNull();
		assertThat(rows.get(3).reason()).hasSize(80);
		assertThat(usage.getRequests()).isEqualTo(1);
		assertThat(usage.getRetries()).isZero();
		assertThat(usage.getPromptTokens()).isEqualTo(100);
		assertThat(usage.getCompletionTokens()).isEqualTo(20);
	}

	@Test
	void 응답_모양이_어긋나면_반으로_나눠_다시_보내고_하나짜리는_UNKNOWN() {
		// "나쁜" 이 든 요청은 상품명을 바꿔 답한다 → 검증 실패 → 반으로 나눈다
		when(gms.complete(any())).thenAnswer(inv -> {
			LlmRequest request = inv.getArgument(0);
			sent.add(request);
			ObjectNode root = JSON.createObjectNode();
			ArrayNode items = root.putArray("items");
			Matcher m = LINE.matcher(request.user());
			while (m.find()) {
				String name = m.group(1);
				ObjectNode it = items.addObject();
				it.put("name", name.contains("나쁜") ? "다른 이름" : name);
				raw(it, "EGG", 0.9);
			}
			return new LlmResult(root, 10, 5);
		});
		Usage usage = new Usage();

		List<LabelRow> rows = classifier.classify(List.of("계란 1", "계란 2", "나쁜 계란", "계란 4"), GROUPS, "v2", usage);

		assertThat(rows).extracting(LabelRow::productName).containsExactly("계란 1", "계란 2", "나쁜 계란", "계란 4");
		assertThat(rows.get(0).itemGroupCode()).isEqualTo("EGG");
		assertThat(rows.get(2).form()).isEqualTo("UNKNOWN");
		assertThat(rows.get(2).reason()).startsWith("응답 오류: ");
		assertThat(rows.get(3).itemGroupCode()).isEqualTo("EGG");
		// [4] 실패 → [2] 성공 · [2] 실패 → [1] 실패(UNKNOWN) · [1] 성공 = 요청 5번, 다시 보냄 3번
		assertThat(sent).hasSize(5);
		assertThat(usage.getRequests()).isEqualTo(5);
		assertThat(usage.getRetries()).isEqualTo(3);
		assertThat(usage.getPromptTokens()).isEqualTo(50);
	}

	@Test
	void 전송_실패는_그대로_올린다() {
		when(gms.complete(any())).thenThrow(new LlmException(LlmErrorCode.FAILED));

		assertThatThrownBy(() -> classifier.classify(List.of("계란"), GROUPS, "v2", new Usage()))
				.isInstanceOf(LlmException.class);
		assertThat(classifier.classify(List.of(), GROUPS, "v2", new Usage())).isEmpty();
	}
}
