package com.zigzaego.api.onboarding.job;

import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.exception.ErrorCode;
import com.zigzaego.api.ingredient.entity.IngredientUnit;
import com.zigzaego.api.job.service.JobContext;
import com.zigzaego.api.job.service.JobFailedException;
import com.zigzaego.api.llm.GmsClient;
import com.zigzaego.api.llm.LlmErrorCode;
import com.zigzaego.api.llm.LlmException;
import com.zigzaego.api.llm.LlmImage;
import com.zigzaego.api.llm.LlmRequest;
import com.zigzaego.api.menu.entity.MenuPhotoUrl;
import com.zigzaego.api.menu.entity.MenuSource;
import com.zigzaego.api.onboarding.ai.IngredientCatalog;
import com.zigzaego.api.onboarding.ai.OnboardingPrompt;
import com.zigzaego.api.onboarding.ai.QuestionRule;
import com.zigzaego.api.onboarding.dto.CollectedMenu;
import com.zigzaego.api.onboarding.dto.RecipeQuestionResponse;
import com.zigzaego.api.onboarding.dto.RecipeUnit;
import com.zigzaego.api.onboarding.exception.OnboardingErrorCode;
import com.zigzaego.api.onboarding.job.OnboardingResult.AnalysisMenu;
import com.zigzaego.api.onboarding.job.OnboardingResult.Draft;
import com.zigzaego.api.onboarding.job.OnboardingResult.DraftIngredient;
import com.zigzaego.api.onboarding.job.OnboardingResult.DraftLine;
import com.zigzaego.api.onboarding.job.OnboardingResult.DraftMenu;
import com.zigzaego.api.onboarding.replay.OnboardingPlaceSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 온보딩 분석 job 본문 (설계 2.4, S15P31A308-95). DB 에는 아무것도 쓰지 않는다 — 결과는 job 의 result_json 에만.
 *
 * <pre>
 * 수집(Apify)            0 → 0.30   끝나면 중간 결과 {"menus":[{id,name}]}
 * 정규화                  0.35       이름 trim, 같은 이름은 하나로(설명이 긴 쪽)
 * 구성 추출(LLM, 10개씩)  0.35 → 0.90  사진이 있는 메뉴는 모두 내려받아 넣는다(묶음당 원본 합 10MiB 까지, 실패면 글자만, 저장 안 함)
 * 분류(재료표 → LLM)      0.95       표에 없는 재료만 classify 프롬프트 1회
 * 질문                    1.0        QuestionRule
 * </pre>
 * 수집 재시도는 수집기 안에 있으므로 여기서는 다시 부르지 않는다. 실패 코드는 job.error 로 그대로 간다
 * (수집 {@code APIFY_UNAVAILABLE} · {@code APIFY_NOT_CONFIGURED}, LLM {@code LLM_*}).
 */
@Slf4j
@Component
public class OnboardingAnalyzer {

	static final int BATCH_SIZE = 10;
	/** 묶음 하나에 넣는 사진 원본 바이트 합 상한. base64 로 4/3 배라 본문 약 13.4MB, GMS 한계(약 16MB) 안쪽 (S15P31A308-237). */
	static final long PHOTO_BATCH_MAX_BYTES = 10L * 1024 * 1024;
	static final double COLLECT_DONE = 0.30;
	static final double NORMALIZED = 0.35;
	static final double EXTRACTED = 0.90;
	static final double CLASSIFIED = 0.95;

	private static final Set<String> MENU_KINDS = Set.of("MAIN", "SET", "OPTION");
	private static final Set<String> INGREDIENT_KINDS = Set.of("BOM", "ONE_TO_ONE");
	private static final Set<String> STORAGE_TYPES = Set.of("FROZEN", "AMBIENT", "CHILLED", "FRESH");
	private static final String PHOTO_MENUS = "(메뉴 목록은 첨부한 메뉴판 사진에 있다. 사진에 보이는 메뉴를 모두 읽어 메뉴마다 하나씩 낸다.)";

	private final OnboardingPlaceSource placeSource;
	private final GmsClient gmsClient;
	private final MenuImageFetcher imageFetcher;
	private final JsonMapper jsonMapper;
	private final IngredientCatalog catalog;
	private final OnboardingPrompt extractionPrompt;
	private final OnboardingPrompt classifyPrompt;

	public OnboardingAnalyzer(OnboardingPlaceSource placeSource, GmsClient gmsClient, MenuImageFetcher imageFetcher,
			JsonMapper jsonMapper) {
		this.placeSource = placeSource;
		this.gmsClient = gmsClient;
		this.imageFetcher = imageFetcher;
		this.jsonMapper = jsonMapper;
		this.catalog = IngredientCatalog.loadDefault();
		this.extractionPrompt = OnboardingPrompt.load("menu_extraction", jsonMapper);
		this.classifyPrompt = OnboardingPrompt.load("ingredient_classify", jsonMapper);
	}

	/**
	 * 네이버 플레이스 한 곳의 메뉴를 수집해 분석한다. 분석 결과 저장본(ANALYSIS, ADR-0016)이 있으면 수집 · LLM 을 부르지 않고
	 * 메뉴 이름 중간 결과와 진행률 1.0 을 남긴 뒤 저장된 결과를 그대로 돌려준다.
	 */
	public OnboardingResult analyzePlace(JobContext ctx, String placeId) {
		Optional<OnboardingResult> saved = placeSource.findAnalysis(placeId);
		if (saved.isPresent()) {
			OnboardingResult result = saved.get();
			List<AnalysisMenu> cards = new ArrayList<>();
			for (AnalysisMenu menu : result.menus() == null ? List.<AnalysisMenu>of() : result.menus()) {
				cards.add(new AnalysisMenu(menu.id(), menu.name(), null, null));
			}
			// 실제 분석처럼 수집 구간이 지난 뒤에 메뉴 이름을 보여 준다. 지연이 0 이면 바로 남긴다
			boolean[] shown = {false};
			placeSource.paceAnalysis(progress -> {
				if (!shown[0] && progress >= COLLECT_DONE) {
					ctx.partialResult(Map.of("menus", cards));
					shown[0] = true;
				}
				ctx.progress(progress);
			});
			if (!shown[0]) {
				ctx.partialResult(Map.of("menus", cards));
			}
			ctx.progress(1.0);
			return result;
		}
		return analyzePlaceFresh(ctx, placeId);
	}

	/** 분석 결과 저장본을 보지 않고 실제로 분석한다 (저장 실행 전용 — 일반 요청 경로는 쓰지 않는다). 수집은 메뉴 저장본을 따른다. */
	public OnboardingResult analyzePlaceFresh(JobContext ctx, String placeId) {
		List<SourceMenu> menus = normalize(collect(placeId));
		ctx.partialResult(partial(menus));
		ctx.progress(COLLECT_DONE);
		if (menus.isEmpty()) {
			return OnboardingResult.noMenus();
		}
		ctx.progress(NORMALIZED);
		try {
			List<JsonNode> extracted = extractInBatches(ctx, menus);
			return finish(ctx, menus, extracted, MenuSource.NAVER);
		} catch (LlmException e) {
			throw new JobFailedException(e.getErrorCode().getCode(), e);
		}
	}

	/** 메뉴판 사진 한 장을 LLM 에 직접 넣어 메뉴 · 구성을 뽑는다. 사진은 저장하지 않는다. */
	public OnboardingResult analyzeMenuPhoto(JobContext ctx, LlmImage photo) {
		try {
			JsonNode output = gmsClient.completeJson(new LlmRequest(extractionPrompt.system(),
					extractionPrompt.user("menus_json", PHOTO_MENUS), List.of(photo), extractionPrompt.name(),
					extractionPrompt.schema()));
			List<JsonNode> extracted = new ArrayList<>();
			output.path("menus").forEach(extracted::add);
			Map<String, SourceMenu> byName = new LinkedHashMap<>();
			for (JsonNode menu : extracted) {
				String name = text(menu, "menu").strip();
				if (!name.isEmpty()) {
					byName.putIfAbsent(name, new SourceMenu(name, null, null, List.of()));
				}
			}
			List<SourceMenu> menus = List.copyOf(byName.values());
			ctx.partialResult(partial(menus));
			ctx.progress(COLLECT_DONE);
			if (menus.isEmpty()) {
				return OnboardingResult.noMenus();
			}
			ctx.progress(EXTRACTED);
			return finish(ctx, menus, extracted, MenuSource.MENU_PHOTO);
		} catch (LlmException e) {
			throw new JobFailedException(e.getErrorCode().getCode(), e);
		}
	}

	// ---- 수집 · 정규화

	private List<CollectedMenu> collect(String placeId) {
		try {
			return placeSource.collectMenus(placeId);
		} catch (BusinessException e) {
			throw new JobFailedException(jobErrorCode(e.getErrorCode()), e);
		}
	}

	/** 수집기 오류는 코드 이름(enum 이름 {@code APIFY_UNAVAILABLE})으로, 그 밖은 응답 코드로. */
	static String jobErrorCode(ErrorCode code) {
		return code instanceof OnboardingErrorCode onboarding ? onboarding.name() : code.getCode();
	}

	/** 메뉴 하나 (정규화 뒤). */
	record SourceMenu(String name, Integer price, String description, List<String> imageUrls) {
	}

	/** 이름 trim, 빈 이름은 버리고, 같은 이름은 하나로 — 설명이 긴 쪽을 남긴다(처음 나온 자리). */
	static List<SourceMenu> normalize(List<CollectedMenu> collected) {
		Map<String, SourceMenu> byName = new LinkedHashMap<>();
		for (CollectedMenu menu : collected == null ? List.<CollectedMenu>of() : collected) {
			String name = menu.name() == null ? "" : menu.name().strip();
			if (name.isEmpty()) {
				continue;
			}
			SourceMenu incoming = new SourceMenu(name, menu.price(), blankToNull(menu.description()), menu.imageUrls());
			SourceMenu existing = byName.get(name);
			if (existing == null) {
				byName.put(name, incoming);
			} else {
				boolean longer = length(incoming.description()) > length(existing.description());
				SourceMenu preferred = longer ? incoming : existing;
				SourceMenu fallback = longer ? existing : incoming;
				List<String> images = java.util.stream.Stream.concat(preferred.imageUrls().stream(),
						fallback.imageUrls().stream()).distinct().toList();
				byName.put(name, new SourceMenu(name,
						preferred.price() != null ? preferred.price() : fallback.price(), preferred.description(), images));
			}
		}
		return List.copyOf(byName.values());
	}

	private static Map<String, Object> partial(List<SourceMenu> menus) {
		List<AnalysisMenu> cards = new ArrayList<>();
		for (int i = 0; i < menus.size(); i++) {
			cards.add(new AnalysisMenu(menuId(i), menus.get(i).name(), null, null));
		}
		return Map.of("menus", cards);
	}

	// ---- 구성 추출

	private List<JsonNode> extractInBatches(JobContext ctx, List<SourceMenu> menus) {
		List<JsonNode> extracted = new ArrayList<>();
		int batches = (menus.size() + BATCH_SIZE - 1) / BATCH_SIZE;
		for (int b = 0; b < batches; b++) {
			List<SourceMenu> batch = menus.subList(b * BATCH_SIZE, Math.min(menus.size(), (b + 1) * BATCH_SIZE));
			ArrayNode input = jsonMapper.createArrayNode();
			List<LlmImage> images = new ArrayList<>();
			long photoBytes = 0;
			boolean capped = false;
			int skipped = 0;
			for (SourceMenu menu : batch) {
				ObjectNode row = input.addObject();
				row.put("name", menu.name());
				row.put("description", menu.description());
				if (menu.price() == null) {
					row.putNull("price");
				} else {
					row.put("price", menu.price());
				}
				if (!menu.imageUrls().isEmpty()) {
					if (capped) {
						skipped++;
						continue;
					}
					Optional<LlmImage> image = imageFetcher.fetch(menu.imageUrls().get(0));
					if (image.isPresent()) {
						long size = image.get().bytes().length;
						if (size > PHOTO_BATCH_MAX_BYTES) {
							skipped++; // 혼자 상한을 넘는 사진만 뺀다. 상한 상태는 그대로
						} else if (photoBytes + size > PHOTO_BATCH_MAX_BYTES) {
							capped = true; // 이 사진과 그 뒤 사진은 글자만 보낸다
							skipped++;
						} else {
							photoBytes += size;
							images.add(image.get());
							row.put("photo", images.size());
						}
					}
				}
			}
			if (skipped > 0) {
				log.info("묶음 {} 사진 합이 {} 바이트 상한을 넘어 사진 {}장을 빼고 글자만 보낸다", b + 1, PHOTO_BATCH_MAX_BYTES, skipped);
			}
			JsonNode output = gmsClient.completeJson(new LlmRequest(extractionPrompt.system(),
					extractionPrompt.user("menus_json", jsonMapper.writeValueAsString(input)), images,
					extractionPrompt.name(), extractionPrompt.schema()));
			output.path("menus").forEach(extracted::add);
			ctx.progress(NORMALIZED + (EXTRACTED - NORMALIZED) * (b + 1) / batches);
		}
		return extracted;
	}

	// ---- 분류 · 초안 · 질문

	/** 메뉴 하나의 구성요소 (같은 재료가 두 줄이면 배수를 더해 한 줄로). */
	private record MenuLines(SourceMenu menu, String kind, List<QuestionRule.Component> components) {
	}

	private OnboardingResult finish(JobContext ctx, List<SourceMenu> menus, List<JsonNode> extracted,
			MenuSource source) {
		Map<String, JsonNode> extractedByName = new HashMap<>();
		for (JsonNode node : extracted) {
			extractedByName.putIfAbsent(text(node, "menu").strip(), node);
		}

		List<MenuLines> menuLines = new ArrayList<>();
		Map<String, String> roleOf = new LinkedHashMap<>();
		Map<String, Set<String>> menusOf = new LinkedHashMap<>();
		for (SourceMenu menu : menus) {
			JsonNode node = extractedByName.get(menu.name());
			String kind = node == null ? "MAIN" : text(node, "kind");
			Map<String, QuestionRule.Component> merged = new LinkedHashMap<>();
			if (node != null) {
				for (JsonNode c : node.path("components")) {
					String name = text(c, "name").strip();
					double mult = c.path("mult").isNumber() ? c.path("mult").doubleValue() : 0;
					if (name.isEmpty() || !(mult > 0)) {
						continue;
					}
					String role = text(c, "role");
					merged.merge(name, new QuestionRule.Component(name, role, mult),
							(a, b) -> new QuestionRule.Component(name, a.role(), a.mult() + b.mult()));
					roleOf.putIfAbsent(name, role);
					menusOf.computeIfAbsent(name, k -> new LinkedHashSet<>()).add(menu.name());
				}
			}
			menuLines.add(new MenuLines(menu, MENU_KINDS.contains(kind) ? kind : "MAIN",
					List.copyOf(merged.values())));
		}

		List<String> unknown = roleOf.keySet().stream().filter(name -> catalog.lookup(name) == null).toList();
		JsonNode classify = classify(unknown, roleOf, menusOf);
		ctx.progress(CLASSIFIED);

		Map<String, QuestionRule.Info> infos = new LinkedHashMap<>();
		Map<String, String> storageOf = new HashMap<>();
		Map<String, Integer> shelfLifeOf = new HashMap<>();
		Map<String, JsonNode> classified = new HashMap<>();
		for (JsonNode item : classify.path("items")) {
			classified.putIfAbsent(IngredientCatalog.normalize(text(item, "name")), item);
		}
		for (String name : roleOf.keySet()) {
			IngredientCatalog.Item hit = catalog.lookup(name);
			if (hit != null) {
				infos.put(name, new QuestionRule.Info(hit.kind(), hit.unit(), hit.perServing(), hit.confidence()));
				storageOf.put(name, hit.storageType());
				shelfLifeOf.put(name, hit.shelfLifeDays());
				continue;
			}
			JsonNode item = classified.get(IngredientCatalog.normalize(name));
			infos.put(name, classifiedInfo(name, item));
			storageOf.put(name, text(item, "storageType"));
			shelfLifeOf.put(name, shelfLife(item.get("shelfLifeDays")));
		}

		// 초안 재료: 처음 나온 순서대로 c1, c2 …
		Map<String, String> ingredientIds = new LinkedHashMap<>();
		List<DraftIngredient> draftIngredients = new ArrayList<>();
		for (String name : roleOf.keySet()) {
			String id = "c" + (ingredientIds.size() + 1);
			ingredientIds.put(name, id);
			QuestionRule.Info info = infos.get(name);
			draftIngredients.add(new DraftIngredient(id, name, info.kind(), info.unit(), storageOf.get(name),
					info.perServing(), info.confidence(), roleOf.get(name), shelfLifeOf.get(name)));
		}

		List<DraftMenu> draftMenus = new ArrayList<>();
		List<AnalysisMenu> cards = new ArrayList<>();
		List<QuestionRule.MenuComponents> forQuestions = new ArrayList<>();
		for (int i = 0; i < menuLines.size(); i++) {
			MenuLines lines = menuLines.get(i);
			List<DraftLine> draftLines = lines.components().stream()
					.map(c -> new DraftLine(ingredientIds.get(c.name()), c.mult())).toList();
			draftMenus.add(new DraftMenu(menuId(i), lines.menu().name(), lines.menu().price(), lines.kind(),
					source.name(), draftLines, MenuPhotoUrl.first(lines.menu().imageUrls())));
			cards.add(new AnalysisMenu(menuId(i), lines.menu().name(), lines.menu().price(), draftLines.size()));
			forQuestions.add(new QuestionRule.MenuComponents(lines.menu().name(), lines.components()));
		}

		List<RecipeQuestionResponse> questions = new ArrayList<>();
		for (QuestionRule.Question q : QuestionRule.build(forQuestions, infos)) {
			questions.add(new RecipeQuestionResponse("q" + (questions.size() + 1), ingredientIds.get(q.ingredient()), q.text(),
					RecipeUnit.of(IngredientUnit.valueOf(q.unit())), q.options()));
		}

		ObjectNode extraction = jsonMapper.createObjectNode();
		ArrayNode extractionMenus = extraction.putArray("menus");
		extracted.forEach(extractionMenus::add);
		return new OnboardingResult(cards, false, new Draft(draftMenus, draftIngredients), questions, extraction,
				classify);
	}

	/** 재료표에 없는 재료만 classify 프롬프트로 한 번에. 없으면 부르지 않고 빈 items. */
	private JsonNode classify(List<String> unknown, Map<String, String> roleOf, Map<String, Set<String>> menusOf) {
		if (unknown.isEmpty()) {
			ObjectNode empty = jsonMapper.createObjectNode();
			empty.putArray("items");
			return empty;
		}
		ArrayNode input = jsonMapper.createArrayNode();
		for (String name : unknown) {
			ObjectNode row = input.addObject();
			row.put("name", name);
			row.put("role", roleOf.get(name));
			ArrayNode menus = row.putArray("menus");
			menusOf.getOrDefault(name, Set.of()).forEach(menus::add);
		}
		return gmsClient.completeJson(new LlmRequest(classifyPrompt.system(),
				classifyPrompt.user("ingredients_json", jsonMapper.writeValueAsString(input)), List.of(),
				classifyPrompt.name(), classifyPrompt.schema()));
	}

	/** LLM 분류 한 줄을 검사한다. 빠졌거나 값이 스키마 밖이면 job 을 LLM_BAD_OUTPUT 으로 끝낸다(ai {@code resolve} 의 KeyError). */
	private static QuestionRule.Info classifiedInfo(String name, JsonNode item) {
		if (item == null) {
			log.warn("분류 결과에 재료가 빠졌다: {}", name);
			throw new LlmException(LlmErrorCode.BAD_OUTPUT);
		}
		String kind = text(item, "kind");
		String unit = text(item, "unit");
		double perServing = item.path("perServing").isNumber() ? item.path("perServing").doubleValue() : 0;
		double confidence = item.path("confidence").isNumber() ? item.path("confidence").doubleValue() : -1;
		if (!INGREDIENT_KINDS.contains(kind) || !Set.of("G", "ML", "EA").contains(unit)
				|| !STORAGE_TYPES.contains(text(item, "storageType")) || !(perServing > 0)
				|| !(confidence >= 0 && confidence <= 1)) {
			log.warn("분류 결과 값이 규칙 밖이다: {} {}", name, item);
			throw new LlmException(LlmErrorCode.BAD_OUTPUT);
		}
		return new QuestionRule.Info(kind, unit, perServing, confidence);
	}

	/**
	 * 분류 출력의 유통기한 (ai {@code catalog._shelf_life} 와 같은 규칙). 없거나 · 정수가 아니거나(불리언 · 소수 포함) ·
	 * 2 미만이면 null — 저장 때 보관 방식 표 값을 쓴다. job 을 실패시키지 않는다.
	 */
	static Integer shelfLife(JsonNode value) {
		if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
			return null;
		}
		int days = value.intValue();
		return days < 2 ? null : days;
	}

	private static String menuId(int index) {
		return "m" + (index + 1);
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node == null ? null : node.get(field);
		return value != null && value.isString() ? value.asString() : "";
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private static int length(String value) {
		return value == null ? 0 : value.length();
	}
}
