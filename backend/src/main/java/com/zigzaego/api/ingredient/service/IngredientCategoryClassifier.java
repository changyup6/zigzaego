package com.zigzaego.api.ingredient.service;

import com.zigzaego.api.ingredient.IngredientCategoryProperties;
import com.zigzaego.api.ingredient.dto.AutoAssignResultResponse;
import com.zigzaego.api.ingredient.entity.CategorySource;
import com.zigzaego.api.ingredient.entity.DefaultIngredientCategory;
import com.zigzaego.api.ingredient.entity.Ingredient;
import com.zigzaego.api.ingredient.entity.IngredientCategory;
import com.zigzaego.api.ingredient.repository.IngredientRepository;
import com.zigzaego.api.ingredient.service.IngredientCategoryBackup.Row;
import com.zigzaego.api.llm.GmsClient;
import com.zigzaego.api.llm.LlmException;
import com.zigzaego.api.llm.LlmRequest;
import com.zigzaego.api.llm.LlmResult;
import com.zigzaego.api.market.catalog.MarketItemGroupCatalog;
import com.zigzaego.api.market.catalog.MarketItemGroupCatalog.Snapshot;
import com.zigzaego.api.onboarding.ai.IngredientCatalog;
import com.zigzaego.api.onboarding.ai.OnboardingPrompt;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 재료 이름 → 카테고리 자동 분류 (S15P31A308-274, DECISIONS #78). 기준은 {@code docs/plans/2026-11-06_재료_카테고리_분류_기준.md}.
 * 순서대로 보고 처음 맞는 것을 쓴다:
 * <ol>
 *   <li>백업 파일({@link IngredientCategoryBackup}) — 같은 (가게 이름, 재료 이름)이 있으면 그 값. DB 를 새로 만들었을 때 LLM 을 다시 부르지 않게</li>
 *   <li>범용 재료표({@code onboarding/ingredient_catalog.csv}, 이름 · 별칭) → {@code ingredient/catalog_category.csv}</li>
 *   <li>품목군 표({@code market_item_group}, 이름 · 별칭이 정확히 같을 때) → {@code category} 17종 → 11종 매핑 (기준 문서 3절)</li>
 *   <li>LLM — 남은 이름을 {@code batchSize} 개씩, 그 가게의 카테고리 이름 목록에서 하나 고르게. 프롬프트 {@code ingredient/category.md}</li>
 *   <li>기타 — 목록 밖 응답 · LLM 실패 · 시간 초과 · 설정 없음. 어떤 경우에도 예외를 올리지 않는다 — 재료 생성은 LLM 과 무관하게 성공한다</li>
 * </ol>
 * 가게 카테고리는 기본 11종과 사장님이 만든 것(S15P31A308-275)을 모두 후보로 준다. 요청 · 토큰은 로그에 남긴다.
 */
@Slf4j
@Service
public class IngredientCategoryClassifier {

	static final String PROMPT_DIR = "ingredient";
	static final String PROMPT_NAME = "category";
	static final String CATALOG_CATEGORY_RESOURCE = "ingredient/catalog_category.csv";

	/** 품목군 표 {@code market_item_group.category} 17종 → 기본 카테고리 (기준 문서 3절). */
	static final Map<String, DefaultIngredientCategory> ITEM_GROUP_CATEGORY = Map.ofEntries(
			Map.entry("육류", DefaultIngredientCategory.MEAT),
			Map.entry("가공육", DefaultIngredientCategory.PROCESSED),
			Map.entry("수산", DefaultIngredientCategory.SEAFOOD),
			Map.entry("채소", DefaultIngredientCategory.VEGETABLE),
			Map.entry("버섯", DefaultIngredientCategory.VEGETABLE),
			Map.entry("과일", DefaultIngredientCategory.FRUIT),
			Map.entry("계란", DefaultIngredientCategory.EGG_DAIRY),
			Map.entry("유제품", DefaultIngredientCategory.EGG_DAIRY),
			Map.entry("곡물·면·떡", DefaultIngredientCategory.GRAIN_NOODLE),
			Map.entry("가루", DefaultIngredientCategory.GRAIN_NOODLE),
			Map.entry("두부·콩", DefaultIngredientCategory.TOFU_BEAN),
			Map.entry("견과", DefaultIngredientCategory.TOFU_BEAN),
			Map.entry("양념·소스", DefaultIngredientCategory.SAUCE),
			Map.entry("기름", DefaultIngredientCategory.SAUCE),
			Map.entry("절임", DefaultIngredientCategory.PROCESSED),
			Map.entry("통조림", DefaultIngredientCategory.PROCESSED),
			Map.entry("음료", DefaultIngredientCategory.BEVERAGE));

	/** 어느 단계가 정했나 (로그용). */
	public enum Step { BACKUP, CATALOG, ITEM_GROUP, LLM, OTHER }

	/** 동시에 기다리는 LLM 호출 상한. 넘치면 줄을 서다 시간 제한에 걸려 기타가 된다 — LLM 이 멈춰도 스레드가 쌓이지 않게. */
	static final int LLM_THREADS = 8;

	/**
	 * 이름 하나의 분류 결과.
	 *
	 * @param source 붙일 출처. 백업 파일에서 사장님이 고른 줄을 되살리면 {@link CategorySource#OWNER}, 그 밖은 AUTO
	 */
	public record Pick(IngredientCategory category, CategorySource source) {
	}

	private final GmsClient gmsClient;
	private final IngredientCategoryService categoryService;
	private final IngredientCategoryBackup backup;
	private final IngredientRepository ingredientRepository;
	private final MarketItemGroupCatalog itemGroups;
	private final IngredientCategoryProperties properties;
	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;
	private final OnboardingPrompt prompt;
	private final IngredientCatalog catalog;
	private final Map<String, DefaultIngredientCategory> catalogCategory;
	/** LLM 호출을 시간 제한으로 끊기 위한 스레드. 끊긴 호출은 뒤에서 끝나고 결과는 버린다. */
	private final ExecutorService executor = Executors.newFixedThreadPool(LLM_THREADS, runnable -> {
		Thread thread = new Thread(runnable, "ingredient-category-llm");
		thread.setDaemon(true);
		return thread;
	});

	public IngredientCategoryClassifier(GmsClient gmsClient, IngredientCategoryService categoryService,
			IngredientCategoryBackup backup, IngredientRepository ingredientRepository, MarketItemGroupCatalog itemGroups,
			IngredientCategoryProperties properties, JdbcTemplate jdbc, TransactionTemplate transactions,
			JsonMapper jsonMapper) {
		this.gmsClient = gmsClient;
		this.categoryService = categoryService;
		this.backup = backup;
		this.ingredientRepository = ingredientRepository;
		this.itemGroups = itemGroups;
		this.properties = properties;
		this.jdbc = jdbc;
		this.transactions = transactions;
		this.prompt = OnboardingPrompt.load(PROMPT_DIR, PROMPT_NAME, jsonMapper);
		this.catalog = IngredientCatalog.loadDefault();
		this.catalogCategory = loadCatalogCategory();
	}

	@PreDestroy
	void shutdown() {
		executor.shutdownNow();
	}

	/**
	 * 이름마다 카테고리 하나. 결과는 입력 이름(앞뒤 공백 뺀 것) 전부를 담고 null 이 없다 — 못 정하면 그 가게의 기타.
	 * LLM 쪽 실패(전송 · 응답 모양 · 시간 초과)는 예외 대신 기타. DB 오류와 기타가 없는 가게({@link IllegalStateException})는 그대로 올린다.
	 * 되살린 출처는 버린다(새 재료는 AUTO) — 출처까지 쓰려면 {@link #classifyDetailed}.
	 */
	public Map<String, IngredientCategory> classify(long storeId, Collection<String> rawNames) {
		Map<String, IngredientCategory> result = new LinkedHashMap<>();
		classifyDetailed(storeId, rawNames).forEach((name, pick) -> result.put(name, pick.category()));
		return result;
	}

	/** {@link #classify} 와 같되 출처도 준다 — 백업 파일에서 사장님이 고른 줄(OWNER)은 기타여도 그대로 되살린다. */
	public Map<String, Pick> classifyDetailed(long storeId, Collection<String> rawNames) {
		Set<String> names = new LinkedHashSet<>();
		for (String raw : rawNames) {
			if (raw != null && !raw.isBlank()) {
				names.add(raw.strip());
			}
		}
		Map<String, Pick> result = new LinkedHashMap<>();
		Map<Step, Integer> counts = new EnumMap<>(Step.class);
		if (names.isEmpty()) {
			return result;
		}
		Candidates candidates = Candidates.from(categoryService.byId(storeId).values());
		IngredientCategory other = candidates.byCode().get(DefaultIngredientCategory.OTHER);
		if (other == null) {
			other = categoryService.other(storeId);
		}
		List<String> pending = new ArrayList<>(names);

		// 1. 백업 파일
		if (backup.enabled()) {
			Map<String, Row> rows = backup.load();
			if (!rows.isEmpty()) {
				String storeName = storeName(storeId);
				for (String name : List.copyOf(pending)) {
					// 사장님이 고른 줄(OWNER)은 기타여도 그대로. 자동 분류의 '기타' 는 되살리지 않는다 — 그때 못 정한 것이니 표 · LLM 에 다시 묻는다
					IngredientCategoryBackup.find(rows, storeName, name).ifPresent(row -> {
						IngredientCategory category = candidates.of(row);
						if (category == null || (category.isOther() && row.source() != CategorySource.OWNER)) {
							return;
						}
						result.put(name, new Pick(category, row.source()));
						pending.remove(name);
						counts.merge(Step.BACKUP, 1, Integer::sum);
					});
				}
			}
		}
		// 2. 범용 재료표 → 3. 품목군 표
		Snapshot groups = null;
		for (String name : List.copyOf(pending)) {
			IngredientCategory category = null;
			Step step = null;
			IngredientCatalog.Item item = catalog.lookup(name);
			if (item != null && catalogCategory.containsKey(item.name())) {
				category = candidates.byCode().get(catalogCategory.get(item.name()));
				step = Step.CATALOG;
			}
			if (category == null) {
				if (groups == null) {
					groups = itemGroups.load();
				}
				DefaultIngredientCategory code = groups.forIngredientName(name)
						.map(group -> ITEM_GROUP_CATEGORY.get(group.category())).orElse(null);
				if (code != null) {
					category = candidates.byCode().get(code);
					step = Step.ITEM_GROUP;
				}
			}
			if (category != null) {
				result.put(name, new Pick(category, CategorySource.AUTO));
				pending.remove(name);
				counts.merge(step, 1, Integer::sum);
			}
		}
		// 4. LLM → 5. 기타
		for (int from = 0; from < pending.size(); from += properties.batchSize()) {
			List<String> chunk = pending.subList(from, Math.min(pending.size(), from + properties.batchSize()));
			Map<String, String> picked = ask(chunk, candidates.names());
			for (String name : chunk) {
				IngredientCategory category = candidates.byName(picked.get(name));
				if (category != null) {
					result.put(name, new Pick(category, CategorySource.AUTO));
					counts.merge(Step.LLM, 1, Integer::sum);
				} else {
					result.put(name, new Pick(other, CategorySource.AUTO));
					counts.merge(Step.OTHER, 1, Integer::sum);
				}
			}
		}
		log.info("재료 카테고리 자동 분류 — 가게 {} · {}개: 백업 {} · 재료표 {} · 품목군 {} · LLM {} · 기타 {}", storeId, names.size(),
				counts.getOrDefault(Step.BACKUP, 0), counts.getOrDefault(Step.CATALOG, 0),
				counts.getOrDefault(Step.ITEM_GROUP, 0), counts.getOrDefault(Step.LLM, 0),
				counts.getOrDefault(Step.OTHER, 0));
		return result;
	}

	/**
	 * 로그인한 가게에서 자동 분류로 기타에 놓인 재료를 다시 분류한다 ({@code POST /ingredient-categories/auto-assign}).
	 * 사장님이 직접 고른 재료({@link CategorySource#OWNER})는 건드리지 않는다. 백업 파일에 사장님이 고른 줄이 있으면(DB 를 새로 만든 뒤)
	 * 출처까지 OWNER 로 되살린다. 끝나면 백업 파일을 다시 쓴다.
	 * <p>LLM 대기(묶음마다 최대 {@code llmTimeout})는 트랜잭션 밖이다 — 그동안 DB 커넥션을 쥐지 않는다. 저장만 짧은 트랜잭션으로 묶고,
	 * 그 사이 사장님이 고친 재료(OWNER · 기타 아님)는 다시 읽어 건너뛴다.
	 */
	public AutoAssignResultResponse autoAssign(long storeId) {
		IngredientCategory other = categoryService.other(storeId);
		List<String> names = ingredientRepository.findAllByStoreIdAndCategoryIdAndCategorySourceOrderByIdAsc(storeId,
				other.getId(), CategorySource.AUTO).stream().map(Ingredient::getName).toList();
		Map<String, Pick> picked = classifyDetailed(storeId, names);
		return transactions.execute(status -> {
			long otherBefore = ingredientRepository.countByStoreIdAndCategoryId(storeId, other.getId());
			List<Ingredient> targets = ingredientRepository.findAllByStoreIdAndCategoryIdAndCategorySourceOrderByIdAsc(
					storeId, other.getId(), CategorySource.AUTO);
			int assigned = 0;
			for (Ingredient ingredient : targets) {
				Pick pick = picked.get(ingredient.getName().strip());
				if (pick == null) {
					continue;
				}
				if (!pick.category().isOther()) {
					ingredient.assignCategory(pick.category().getId(), pick.source());
					assigned++;
				} else if (pick.source() == CategorySource.OWNER) {
					ingredient.assignCategory(pick.category().getId(), CategorySource.OWNER);  // 사장님이 고른 기타를 되살림
				}
			}
			backup.exportAfterCommit();
			return new AutoAssignResultResponse(assigned, (int) (otherBefore - assigned));
		});
	}

	// ---- LLM

	/** 재료 이름 → 응답 카테고리 이름. 실패 · 시간 초과 · 모양이 이상하면 빈 맵(부르는 쪽이 기타로). */
	Map<String, String> ask(List<String> names, List<String> categoryNames) {
		LlmRequest request = new LlmRequest(system(categoryNames), user(names), List.of(), prompt.name(), prompt.schema());
		Future<LlmResult> future = executor.submit(() -> gmsClient.complete(request));
		LlmResult result;
		try {
			result = future.get(properties.llmTimeout().toMillis(), TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			future.cancel(true);
			log.warn("재료 카테고리 LLM 시간 초과({}), {}개를 기타로 둔다", properties.llmTimeout(), names.size());
			return Map.of();
		} catch (InterruptedException e) {
			future.cancel(true);
			Thread.currentThread().interrupt();
			return Map.of();
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof LlmException llm) {
				log.warn("재료 카테고리 LLM 실패({}), {}개를 기타로 둔다", llm.getErrorCode().getCode(), names.size());
			} else {
				log.warn("재료 카테고리 LLM 오류, {}개를 기타로 둔다: {}", names.size(), String.valueOf(cause));
			}
			return Map.of();
		}
		if (result == null || result.output() == null) {
			log.warn("재료 카테고리 LLM 응답이 비었다, {}개를 기타로 둔다", names.size());
			return Map.of();
		}
		log.info("재료 카테고리 LLM — 재료 {}개 · 입력 토큰 {} · 출력 토큰 {}", names.size(), result.promptTokens(),
				result.completionTokens());
		return parse(result.output());
	}

	String system(List<String> categoryNames) {
		return prompt.system().replace("{{categories}}", String.join("\n", categoryNames));
	}

	String user(List<String> names) {
		return prompt.user("ingredients", IntStream.range(0, names.size())
				.mapToObj(i -> (i + 1) + ". " + names.get(i)).collect(Collectors.joining("\n")));
	}

	/** {@code {items: [{name, category}]}} → 이름(공백 뺀 것) → 카테고리 이름. 모양이 어긋난 항목은 뺀다. */
	static Map<String, String> parse(JsonNode output) {
		Map<String, String> picked = new HashMap<>();
		JsonNode items = output.path("items");
		if (!items.isArray()) {
			return picked;
		}
		for (JsonNode item : items) {
			JsonNode name = item.path("name");
			JsonNode category = item.path("category");
			if (name.isString() && category.isString()) {
				picked.putIfAbsent(name.stringValue().strip(), category.stringValue().strip());
			}
		}
		return picked;
	}

	private String storeName(long storeId) {
		return jdbc.queryForObject("SELECT name FROM store WHERE id = ?", String.class, storeId);
	}

	/** {@code ingredient/catalog_category.csv}: 재료표 대표 이름 → 기본 카테고리. 재료표에 있는 이름마다 한 줄 (테스트가 지킨다). */
	static Map<String, DefaultIngredientCategory> loadCatalogCategory() {
		Map<String, DefaultIngredientCategory> out = new HashMap<>();
		try (BufferedReader in = new BufferedReader(new InputStreamReader(
				new ClassPathResource(CATALOG_CATEGORY_RESOURCE).getInputStream(), StandardCharsets.UTF_8))) {
			String line = in.readLine();  // header
			while ((line = in.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				int comma = line.lastIndexOf(',');
				out.put(line.substring(0, comma).strip(), DefaultIngredientCategory.valueOf(line.substring(comma + 1).strip()));
			}
		} catch (IOException e) {
			throw new UncheckedIOException("재료표 카테고리 매핑을 읽지 못했다: " + CATALOG_CATEGORY_RESOURCE, e);
		}
		return out;
	}

	/** 한 가게의 카테고리 후보: 코드로, 이름으로 찾는다. */
	private record Candidates(Map<DefaultIngredientCategory, IngredientCategory> byCode,
			Map<String, IngredientCategory> byExactName, Map<String, IngredientCategory> byNormalizedName,
			List<String> names) {

		static Candidates from(Collection<IngredientCategory> categories) {
			Map<DefaultIngredientCategory, IngredientCategory> byCode = new EnumMap<>(DefaultIngredientCategory.class);
			Map<String, IngredientCategory> byExactName = new HashMap<>();
			Map<String, IngredientCategory> byNormalizedName = new HashMap<>();
			List<String> names = new ArrayList<>();
			for (IngredientCategory category : categories) {
				if (category.getCode() != null) {
					byCode.put(category.getCode(), category);
				}
				byExactName.putIfAbsent(category.getName(), category);
				byNormalizedName.putIfAbsent(IngredientCatalog.normalize(category.getName()), category);
				names.add(category.getName());
			}
			return new Candidates(byCode, byExactName, byNormalizedName, List.copyOf(names));
		}

		/** 백업 줄 → 이 가게의 카테고리. 코드가 있으면 코드로, 없으면(사장님이 만든 것) 이름으로. 둘 다 없으면 null. */
		IngredientCategory of(Row row) {
			if (row.categoryCode() != null) {
				try {
					IngredientCategory byCodeHit = byCode.get(DefaultIngredientCategory.valueOf(row.categoryCode()));
					if (byCodeHit != null) {
						return byCodeHit;
					}
				} catch (IllegalArgumentException e) {
					// 모르는 코드 — 이름으로
				}
			}
			return byName(row.categoryName());
		}

		/** LLM 응답 이름 → 카테고리. 글자 그대로, 없으면 공백 · 대소문자를 뺀 비교. 그래도 없으면 null. */
		IngredientCategory byName(String name) {
			if (name == null || name.isBlank()) {
				return null;
			}
			IngredientCategory exact = byExactName.get(name.strip());
			return exact != null ? exact : byNormalizedName.get(IngredientCatalog.normalize(name));
		}
	}
}
