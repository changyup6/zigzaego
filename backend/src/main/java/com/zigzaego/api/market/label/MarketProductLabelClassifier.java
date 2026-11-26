package com.zigzaego.api.market.label;

import com.zigzaego.api.llm.GmsClient;
import com.zigzaego.api.llm.LlmErrorCode;
import com.zigzaego.api.llm.LlmException;
import com.zigzaego.api.llm.LlmRequest;
import com.zigzaego.api.llm.LlmResult;
import com.zigzaego.api.market.catalog.MarketItemGroupCatalog.Snapshot;
import com.zigzaego.api.market.label.MarketProductLabelJdbcRepository.LabelRow;
import com.zigzaego.api.onboarding.ai.OnboardingPrompt;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 상품명 묶음 → 품목군 라벨 LLM 호출 (S15P31A308-229). ai {@code weglee_ai.market.classify} 를 그대로 옮겼다 — 프롬프트 · 스키마는
 * {@code market/item_group.md} · {@code item_group.schema.json}(ai {@code prompts/market/} 복사본).
 * <ul>
 *   <li>상품명 N개와 품목군 표 전체를 한 요청에 보내고 {@code json_schema} 로 응답 모양을 강제한다</li>
 *   <li>응답은 검증한다: 입력 순서 · 이름 그대로, 코드는 표에 있는 것, 코드가 있으면 form 은 RAW · PREPARED</li>
 *   <li>한 묶음이 깨지면 반으로 나눠 다시 보내고, 하나짜리도 깨지면 그 상품은 UNKNOWN(이유 "응답 오류")</li>
 *   <li>전송 실패({@code LLM_FAILED} · {@code LLM_UNAVAILABLE})는 그대로 올린다 — 단계가 실패하고 그때까지 커밋된 라벨은 남는다</li>
 * </ul>
 */
@Slf4j
@Service
public class MarketProductLabelClassifier {

	static final String PROMPT_DIR = "market";
	static final String PROMPT_NAME = "item_group";
	static final Set<String> FORMS = Set.of("RAW", "PREPARED", "PROCESSED", "NONFOOD", "UNKNOWN");
	static final Set<String> UNITS = Set.of("G", "ML", "EA");
	static final int REASON_MAX = 80;

	private final GmsClient gmsClient;
	private final OnboardingPrompt prompt;

	public MarketProductLabelClassifier(GmsClient gmsClient, JsonMapper jsonMapper) {
		this.gmsClient = gmsClient;
		this.prompt = OnboardingPrompt.load(PROMPT_DIR, PROMPT_NAME, jsonMapper);
	}

	/** 한 실행의 비용 누적 (요청 · 다시 보낸 횟수 · 토큰). 여러 스레드가 같이 더하므로 동기화한다. 단계 끝에 BATCH_STEP_EXECUTION 에 남긴다. */
	public static final class Usage {

		private long requests;
		private long retries;
		private long promptTokens;
		private long completionTokens;

		synchronized void add(LlmResult result) {
			requests++;
			promptTokens += result.promptTokens();
			completionTokens += result.completionTokens();
		}

		synchronized void countRequest() {
			requests++;
		}

		synchronized void countRetry() {
			retries++;
		}

		public synchronized long getRequests() {
			return requests;
		}

		public synchronized long getRetries() {
			return retries;
		}

		public synchronized long getPromptTokens() {
			return promptTokens;
		}

		public synchronized long getCompletionTokens() {
			return completionTokens;
		}

		public synchronized String summary() {
			return "요청 %d · 다시 보냄 %d · 입력 토큰 %,d · 출력 토큰 %,d".formatted(requests, retries, promptTokens, completionTokens);
		}
	}

	/**
	 * @param names   상품명 묶음 (한 요청에 넣는다)
	 * @param groups  품목군 표 (프롬프트 · 코드 검증)
	 * @param version 붙일 {@code label_version}
	 * @param usage   비용 누적
	 * @return 입력 순서의 라벨. 수는 입력과 같다
	 * @throws LlmException 전송 실패 · 설정 없음
	 */
	public List<LabelRow> classify(List<String> names, Snapshot groups, String version, Usage usage) {
		if (names.isEmpty()) {
			return List.of();
		}
		JsonNode output;
		try {
			LlmResult result = gmsClient.complete(new LlmRequest(system(groups), user(names), List.of(), prompt.name(),
					prompt.schema()));
			usage.add(result);
			output = result.output();
		} catch (LlmException e) {
			if (e.getErrorCode() != LlmErrorCode.BAD_OUTPUT) {
				throw e;
			}
			usage.countRequest();
			return retrySplit(names, groups, version, usage, "응답이 JSON 이 아님");
		}
		try {
			return parse(output, names, groups, version);
		} catch (IllegalArgumentException e) {
			return retrySplit(names, groups, version, usage, e.getMessage());
		}
	}

	private List<LabelRow> retrySplit(List<String> names, Snapshot groups, String version, Usage usage, String why) {
		usage.countRetry();
		if (names.size() == 1) {
			log.warn("상품명 라벨 응답 오류, UNKNOWN 으로 둔다 — {} ({})", names.get(0), why);
			return List.of(unknown(names.get(0), "응답 오류: " + cut(why, 40), version));
		}
		log.warn("상품명 라벨 응답 오류, {}개를 반으로 나눠 다시 보낸다 ({})", names.size(), why);
		int mid = names.size() / 2;
		List<LabelRow> out = new ArrayList<>(names.size());
		out.addAll(classify(names.subList(0, mid), groups, version, usage));
		out.addAll(classify(names.subList(mid, names.size()), groups, version, usage));
		return out;
	}

	String system(Snapshot groups) {
		return prompt.system().replace("{{groups}}", groups.promptTable());
	}

	String user(List<String> names) {
		String products = IntStream.range(0, names.size()).mapToObj(i -> (i + 1) + ". " + names.get(i))
				.collect(Collectors.joining("\n"));
		return prompt.user("products", products);
	}

	/** 응답 → 입력 순서의 라벨. 모양이 어긋나면 {@link IllegalArgumentException} (ai {@code parse_labels} 와 같은 규칙). */
	static List<LabelRow> parse(JsonNode output, List<String> names, Snapshot groups, String version) {
		JsonNode items = output.path("items");
		if (!items.isArray()) {
			throw new IllegalArgumentException("응답이 {items: [...]} 이 아니다");
		}
		if (items.size() != names.size()) {
			throw new IllegalArgumentException("응답 항목 수가 입력과 다르다: " + items.size() + " != " + names.size());
		}
		List<LabelRow> out = new ArrayList<>(names.size());
		for (int i = 0; i < names.size(); i++) {
			JsonNode it = items.get(i);
			if (!it.isObject()) {
				throw new IllegalArgumentException("응답 항목이 객체가 아니다");
			}
			String name = names.get(i);
			String got = text(it, "name");
			if (got == null || !got.strip().equals(name.strip())) {
				throw new IllegalArgumentException("응답 상품명이 입력과 다르다: " + got + " != " + name);
			}
			String code = text(it, "item_group_code");
			code = code == null || code.isBlank() ? null : code.strip().toUpperCase();
			String form = text(it, "form");
			form = form == null || form.isBlank() ? "UNKNOWN" : form.strip().toUpperCase();
			if (!FORMS.contains(form)) {
				throw new IllegalArgumentException("form 값이 이상하다: " + form);
			}
			if (code != null && groups.get(code).isEmpty()) {
				code = null;  // 표에 없는 코드를 지어내면 모르는 것으로
				form = "UNKNOWN";
			}
			if (code != null && !"RAW".equals(form) && !"PREPARED".equals(form)) {
				code = null;  // 가공품 · 비식품에는 코드가 없다
			}
			if (code == null && ("RAW".equals(form) || "PREPARED".equals(form))) {
				form = "UNKNOWN";  // 원물이라면서 품목군이 없으면 표 밖 식품 — 집계에 안 쓴다
			}
			String unit = text(it, "qty_unit");
			unit = unit == null ? null : unit.strip().toUpperCase();
			BigDecimal qty = positive(it.path("qty"));
			if (unit == null || !UNITS.contains(unit) || qty == null) {
				unit = null;
				qty = null;
			}
			BigDecimal confidence = positive(it.path("confidence"));
			confidence = confidence == null ? BigDecimal.ZERO : confidence.min(BigDecimal.ONE);
			String reason = text(it, "reason");
			out.add(new LabelRow(name, code, form, qty, unit, confidence, cut(reason == null ? "" : reason.strip(), REASON_MAX),
					version));
		}
		return out;
	}

	static LabelRow unknown(String name, String reason, String version) {
		return new LabelRow(name, null, "UNKNOWN", null, null, BigDecimal.ZERO, cut(reason, REASON_MAX), version);
	}

	private static String text(JsonNode node, String field) {
		JsonNode v = node.path(field);
		return v.isString() ? v.stringValue() : null;
	}

	/** 양수만. 숫자가 아니거나 0 이하면 null (ai {@code _num}). */
	private static BigDecimal positive(JsonNode v) {
		if (!v.isNumber()) {
			return null;
		}
		BigDecimal d = v.decimalValue();
		return d.signum() > 0 ? d : null;
	}

	private static String cut(String s, int max) {
		return s == null ? null : s.length() <= max ? s : s.substring(0, max);
	}
}
