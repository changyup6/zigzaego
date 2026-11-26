package com.zigzaego.api.onboarding.service;

import com.zigzaego.api.demo.DemoPreparationService;
import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.exception.CommonErrorCode;
import com.zigzaego.api.global.store.ResourceIds;
import com.zigzaego.api.ingredient.entity.CategorySource;
import com.zigzaego.api.ingredient.entity.Ingredient;
import com.zigzaego.api.ingredient.entity.IngredientCategory;
import com.zigzaego.api.ingredient.entity.IngredientKind;
import com.zigzaego.api.ingredient.entity.IngredientUnit;
import com.zigzaego.api.ingredient.entity.SourcingType;
import com.zigzaego.api.ingredient.entity.StorageType;
import com.zigzaego.api.ingredient.entity.UnitStatus;
import com.zigzaego.api.ingredient.repository.IngredientRepository;
import com.zigzaego.api.ingredient.service.IngredientCategoryBackup;
import com.zigzaego.api.ingredient.service.IngredientCategoryClassifier;
import com.zigzaego.api.job.entity.Job;
import com.zigzaego.api.job.entity.JobStatus;
import com.zigzaego.api.job.entity.JobType;
import com.zigzaego.api.job.repository.JobRepository;
import com.zigzaego.api.menu.entity.Menu;
import com.zigzaego.api.menu.entity.MenuKind;
import com.zigzaego.api.menu.entity.MenuSource;
import com.zigzaego.api.menu.repository.MenuRepository;
import com.zigzaego.api.onboarding.dto.RecipeAnswerRequest;
import com.zigzaego.api.onboarding.dto.RecipeBookResponse;
import com.zigzaego.api.onboarding.dto.RecipeQuestionResponse;
import com.zigzaego.api.onboarding.dto.RecipeUnit;
import com.zigzaego.api.onboarding.exception.OnboardingJobErrorCode;
import com.zigzaego.api.onboarding.job.OnboardingResult.Draft;
import com.zigzaego.api.onboarding.job.OnboardingResult.DraftIngredient;
import com.zigzaego.api.onboarding.job.OnboardingResult.DraftLine;
import com.zigzaego.api.onboarding.job.OnboardingResult.DraftMenu;
import com.zigzaego.api.recipe.entity.Recipe;
import com.zigzaego.api.recipe.entity.RecipeSource;
import com.zigzaego.api.recipe.repository.RecipeRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 레시피 확인 → DB 저장 (S15P31A308-96, 설계 2.4 · 2.6). 분석 결과(가장 최근 DONE 온보딩 job 의 draft)에
 * 사장님 답을 얹어 메뉴 · 재료 · 레시피 행으로 펼친다. 가게는 언제나 로그인한 가게다 (ADR-0002).
 *
 * <ul>
 *   <li>메뉴: 이름 기준 upsert. 새 메뉴의 출처 = 수집 출처(NAVER · MENU_PHOTO)</li>
 *   <li>재료: 이름 기준 upsert. sourcing_type GENERIC, price_sensitive 는 보관 방식 기본값(S15P31A308-101),
 *       shelf_life_days 는 초안 값(재료표 · 분류 출력, 설계 5.3) — 없으면 보관 방식 기본값. 카테고리는 초안 재료 전부를 한 번에
 *       자동 분류해(LLM 호출 1번, S15P31A308-274) 새 재료와 자동 분류 상태인 기존 재료에 붙인다. 사장님이 고른 것은 그대로</li>
 *   <li>레시피: (메뉴, 재료) upsert, 양 = 1인분 × 배수. 답한 재료는 단위 CONFIRMED · 줄 OWNER_CONFIRMED, 나머지는 ESTIMATED · AI</li>
 * </ul>
 * 주기형 재료는 만들지 않는다 (설계 4.1).
 */
@Service
@RequiredArgsConstructor
public class RecipeSetupService {

	/** 응답 수량 자릿수 (결정 #42). */
	private static final int API_SCALE = 3;

	/** 새 재료의 안전재고 배수 = DB 기본값 1 (재료 CRUD 와 같은 값). */
	private static final BigDecimal DEFAULT_SAFETY_DAYS = BigDecimal.ONE;

	private final DemoPreparationService demoPreparation;
	private final JobRepository jobRepository;
	private final MenuRepository menuRepository;
	private final IngredientRepository ingredientRepository;
	private final IngredientCategoryClassifier categoryClassifier;
	private final IngredientCategoryBackup categoryBackup;
	private final RecipeRepository recipeRepository;
	private final JsonMapper jsonMapper;

	/** 가장 최근 DONE 분석의 질문. 그런 job 이 없으면 404 ONBOARDING_JOB_NOT_FOUND. */
	@Transactional(readOnly = true)
	public List<RecipeQuestionResponse> questions(long storeId) {
		return questionsOf(latestResult(storeId));
	}

	/** 답을 적용해 저장한다. 모르는 질문 id 는 400, 0 이하 값(소수 셋째 자리에서 0 포함)은 400. 한 트랜잭션. */
	@Transactional
	public RecipeBookResponse confirm(long storeId, List<RecipeAnswerRequest> answers) {
		JsonNode result = latestResult(storeId);
		Map<String, RecipeQuestionResponse> questions = new HashMap<>();
		for (RecipeQuestionResponse question : questionsOf(result)) {
			questions.put(question.id(), question);
		}
		Map<String, RecipeAnswerRequest> answerByIngredient = new HashMap<>();
		for (RecipeAnswerRequest answer : answers == null ? List.<RecipeAnswerRequest>of() : answers) {
			if (answer == null) {
				throw new BusinessException(OnboardingJobErrorCode.ANSWER_INVALID);
			}
			RecipeQuestionResponse question = questions.get(answer.questionId());
			if (question == null) {
				throw new BusinessException(OnboardingJobErrorCode.QUESTION_NOT_FOUND);
			}
			if (answer.value() == null || roundsToZero(answer.value()) || answer.unit() == null) {
				throw new BusinessException(OnboardingJobErrorCode.ANSWER_INVALID);
			}
			answerByIngredient.put(question.ingredientId(), answer);
		}
		save(storeId, draftOf(result), answerByIngredient);
		demoPreparation.register(storeId);
		return book(storeId);
	}

	/** 답 없이 추정값 그대로 저장한다. */
	@Transactional
	public RecipeBookResponse skip(long storeId) {
		return confirm(storeId, List.of());
	}

	/** DB 의 이 가게 레시피. */
	@Transactional(readOnly = true)
	public RecipeBookResponse book(long storeId) {
		List<Recipe> recipes = recipeRepository.findAllByStoreIdOrderByIdAsc(storeId);
		Map<Long, BigDecimal> perServing = new HashMap<>();
		for (Recipe recipe : recipes) {
			perServing.merge(recipe.getIngredientId(), recipe.getQtyPerServing(), BigDecimal::min);
		}
		Map<Long, List<RecipeBookResponse.Line>> linesByMenu = new HashMap<>();
		for (Recipe recipe : recipes) {
			BigDecimal base = perServing.get(recipe.getIngredientId());
			if (base.signum() <= 0) {
				continue;
			}
			BigDecimal mult = recipe.getQtyPerServing().divide(base, API_SCALE, RoundingMode.HALF_UP);
			if (mult.signum() > 0) {
				linesByMenu.computeIfAbsent(recipe.getMenuId(), k -> new ArrayList<>())
						.add(new RecipeBookResponse.Line(String.valueOf(recipe.getIngredientId()), mult));
			}
		}
		List<RecipeBookResponse.Menu> menus = menuRepository.findAllByStoreIdOrderByIdAsc(storeId).stream()
				.filter(Menu::isActive)
				.map(menu -> new RecipeBookResponse.Menu(String.valueOf(menu.getId()), menu.getName(), won(menu.getPrice()),
						menu.getPhotoUrl(), linesByMenu.getOrDefault(menu.getId(), List.of())))
				.toList();
		List<RecipeBookResponse.Ingredient> ingredients = perServing.isEmpty() ? List.of()
				: ingredientRepository.findAllByStoreIdAndIdIn(storeId, perServing.keySet()).stream()
						.sorted(Comparator.comparing(Ingredient::getId))
						.map(ingredient -> new RecipeBookResponse.Ingredient(String.valueOf(ingredient.getId()),
								ingredient.getName(), RecipeUnit.of(ingredient.getUnit()),
								perServing.get(ingredient.getId()).setScale(API_SCALE, RoundingMode.HALF_UP),
								ingredient.getUnitStatus() == UnitStatus.ESTIMATED))
						.toList();
		return new RecipeBookResponse(menus, ingredients);
	}

	/**
	 * 재료 1인분을 고친다. 그 재료의 레시피 줄을 모두 (새 1인분 / 옛 1인분) 비율로 바꾸고 — 더블 메뉴도 같은 비율 —
	 * 단위 CONFIRMED, 줄 출처 OWNER_CONFIRMED. 남의 가게 · 없는 id 는 404.
	 */
	@Transactional
	public RecipeBookResponse updatePerServing(long storeId, String rawId, BigDecimal perServing) {
		if (perServing == null || roundsToZero(perServing)) {
			throw new BusinessException(CommonErrorCode.INVALID_INPUT, "1인분은 0보다 커야 합니다.");
		}
		Ingredient ingredient = ingredientRepository.getInStore(ResourceIds.parse(rawId), storeId);
		List<Recipe> recipes = recipeRepository.findAllByStoreIdAndIngredientId(storeId, ingredient.getId());
		BigDecimal old = recipes.stream().map(Recipe::getQtyPerServing).min(Comparator.naturalOrder())
				.orElse(BigDecimal.ZERO);
		for (Recipe recipe : recipes) {
			BigDecimal qty = old.signum() > 0
					? recipe.getQtyPerServing().multiply(perServing).divide(old, Recipe.QTY_SCALE, RoundingMode.HALF_UP)
					: perServing;
			recipe.confirmByOwner(qty.setScale(Recipe.QTY_SCALE, RoundingMode.HALF_UP));
		}
		if (!ingredient.unitLocked()) {
			ingredient.confirmUnit(ingredient.getUnit());
		}
		return book(storeId);
	}

	/** 금액은 원 단위 정수 (DECISIONS #55). {@code MenuResponse} 와 같은 반올림 — 그쪽 도우미는 private 이라 같은 식을 둔다. */
	private static Long won(BigDecimal amount) {
		return amount == null ? null : amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
	}

	/** 수량은 소수 셋째 자리까지 보인다(결정 #42) — 거기서 0 이 되는 값은 0 이하와 같이 거부한다. */
	private static boolean roundsToZero(BigDecimal value) {
		return value.setScale(API_SCALE, RoundingMode.HALF_UP).signum() <= 0;
	}

	// ---- 저장

	private void save(long storeId, Draft draft, Map<String, RecipeAnswerRequest> answers) {
		Map<String, Ingredient> ingredients = new LinkedHashMap<>();
		Map<String, BigDecimal> perServing = new HashMap<>();
		Map<String, BigDecimal> confidence = new HashMap<>();
		Map<String, IngredientCategory> categories = categoryClassifier.classify(storeId,
				draft.ingredients().stream().map(DraftIngredient::name).toList());
		for (DraftIngredient draftIngredient : draft.ingredients()) {
			RecipeAnswerRequest answer = answers.get(draftIngredient.id());
			IngredientUnit unit = answer != null ? answer.unit().toIngredientUnit()
					: IngredientUnit.valueOf(draftIngredient.unit());
			UnitStatus status = answer != null ? UnitStatus.CONFIRMED : UnitStatus.ESTIMATED;
			ingredients.put(draftIngredient.id(),
					upsertIngredient(storeId, draftIngredient, unit, status, categories.get(draftIngredient.name().strip())));
			perServing.put(draftIngredient.id(),
					answer != null ? answer.value() : BigDecimal.valueOf(draftIngredient.perServing()));
			confidence.put(draftIngredient.id(), BigDecimal.valueOf(draftIngredient.confidence()));
		}
		categoryBackup.exportAfterCommit();
		for (DraftMenu draftMenu : draft.menus()) {
			Menu menu = upsertMenu(storeId, draftMenu);
			for (DraftLine line : draftMenu.lines() == null ? List.<DraftLine>of() : draftMenu.lines()) {
				Ingredient ingredient = ingredients.get(line.ingredientId());
				if (ingredient == null || !(line.mult() > 0)) {
					continue;
				}
				BigDecimal qty = perServing.get(line.ingredientId()).multiply(BigDecimal.valueOf(line.mult()));
				RecipeSource source = answers.containsKey(line.ingredientId()) ? RecipeSource.OWNER_CONFIRMED
						: RecipeSource.AI;
				BigDecimal lineConfidence = confidence.get(line.ingredientId());
				recipeRepository.findByStoreIdAndMenuIdAndIngredientId(storeId, menu.getId(), ingredient.getId())
						.ifPresentOrElse(
								recipe -> recipe.updateFromOnboarding(qty, source, lineConfidence),
								() -> recipeRepository.save(Recipe.fromOnboarding(storeId, menu.getId(), ingredient.getId(),
										qty, source, lineConfidence)));
			}
		}
	}

	private Ingredient upsertIngredient(long storeId, DraftIngredient draft, IngredientUnit unit, UnitStatus status,
			IngredientCategory category) {
		IngredientKind kind = IngredientKind.valueOf(draft.kind());
		StorageType storage = StorageType.valueOf(draft.storageType());
		boolean priceSensitive = priceSensitive(storage);
		Integer shelfLife = draft.shelfLifeDays() != null ? draft.shelfLifeDays() : shelfLifeDays(storage);
		return ingredientRepository.findByStoreIdAndName(storeId, draft.name())
				.map(existing -> {
					existing.updateFromOnboarding(unit, kind, status, storage, priceSensitive, shelfLife,
							SourcingType.GENERIC);
					existing.assignCategory(category.getId(), CategorySource.AUTO);  // OWNER 가 고른 것은 그대로
					return existing;
				})
				.orElseGet(() -> ingredientRepository.save(Ingredient.create(storeId, draft.name(), unit, kind, status,
						storage, DEFAULT_SAFETY_DAYS, null, priceSensitive, shelfLife, SourcingType.GENERIC,
						category.getId())));
	}

	private Menu upsertMenu(long storeId, DraftMenu draft) {
		MenuKind kind = parse(MenuKind.class, draft.kind(), MenuKind.MAIN);
		BigDecimal price = draft.price() == null ? null : BigDecimal.valueOf(draft.price());
		Menu menu = menuRepository.findByStoreIdAndName(storeId, draft.name())
				.map(existing -> {
					// 종류 · 판매가만 새 값으로. 출처는 처음 넣은 쪽을 그대로 둔다.
					existing.changeKind(kind);
					if (price != null) {
						existing.changePrice(price);
					}
					return existing;
				})
				.orElseGet(() -> menuRepository.save(Menu.create(storeId, draft.name(), kind, null,
						parse(MenuSource.class, draft.source(), MenuSource.NAVER), price)));
		menu.changePhotoUrl(draft.photoUrl());
		return menu;
	}

	/** 보관 방식별 가격 연동 기본값 (S15P31A308-101): 냉동 · 상온 켬, 냉장 · 신선 끔. */
	static boolean priceSensitive(StorageType storage) {
		return storage == StorageType.FROZEN || storage == StorageType.AMBIENT;
	}

	/** 보관 방식별 유통기한 기본값 (S15P31A308-101): 냉동 90 · 상온 180 · 냉장 7 · 신선 3일. */
	static Integer shelfLifeDays(StorageType storage) {
		return switch (storage) {
			case FROZEN -> 90;
			case AMBIENT -> 180;
			case CHILLED -> 7;
			case FRESH -> 3;
		};
	}

	// ---- 분석 결과 읽기

	private JsonNode latestResult(long storeId) {
		Job job = jobRepository.findFirstByStoreIdAndTypeAndStatusOrderByIdDesc(storeId, JobType.ONBOARDING,
						JobStatus.DONE)
				.orElseThrow(() -> new BusinessException(OnboardingJobErrorCode.JOB_NOT_FOUND));
		return job.getResultJson() == null ? jsonMapper.createObjectNode() : jsonMapper.readTree(job.getResultJson());
	}

	private List<RecipeQuestionResponse> questionsOf(JsonNode result) {
		List<RecipeQuestionResponse> questions = new ArrayList<>();
		for (JsonNode node : result.path("questions")) {
			questions.add(jsonMapper.treeToValue(node, RecipeQuestionResponse.class));
		}
		return questions;
	}

	private Draft draftOf(JsonNode result) {
		JsonNode node = result.path("draft");
		if (!node.isObject()) {
			return new Draft(List.of(), List.of());
		}
		Draft draft = jsonMapper.treeToValue(node, Draft.class);
		return new Draft(draft.menus() == null ? List.of() : draft.menus(),
				draft.ingredients() == null ? List.of() : draft.ingredients());
	}

	private static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
		if (value == null) {
			return fallback;
		}
		try {
			return Enum.valueOf(type, value);
		} catch (IllegalArgumentException e) {
			return fallback;
		}
	}
}
