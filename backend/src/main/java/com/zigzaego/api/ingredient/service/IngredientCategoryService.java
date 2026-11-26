package com.zigzaego.api.ingredient.service;

import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.store.ResourceIds;
import com.zigzaego.api.ingredient.dto.IngredientCategoryCreateRequest;
import com.zigzaego.api.ingredient.dto.IngredientCategoryResponse;
import com.zigzaego.api.ingredient.dto.IngredientCategoryUpdateRequest;
import com.zigzaego.api.ingredient.entity.CategorySource;
import com.zigzaego.api.ingredient.entity.DefaultIngredientCategory;
import com.zigzaego.api.ingredient.entity.IngredientCategory;
import com.zigzaego.api.ingredient.exception.IngredientCategoryErrorCode;
import com.zigzaego.api.ingredient.repository.IngredientCategoryRepository;
import com.zigzaego.api.ingredient.repository.IngredientRepository;
import com.zigzaego.api.onboarding.ai.IngredientCatalog;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재료 카테고리 (S15P31A308-273 · 275). 가게가 생길 때 기본 11종을 넣고, 재료를 만드는 쪽에 그 가게의 '기타'를 주며,
 * 사장님이 카테고리를 만들고 · 고치고 · 지운다. 모든 조회 · 쓰기는 가게로 좁힌다 (ADR-0002).
 * <ul>
 *   <li>이름은 가게 안에서 하나 — 공백을 빼고 견준다("수제 소스" = "수제소스"). 같으면 409</li>
 *   <li>'기타'(OTHER)는 이름을 바꾸거나 지우지 못한다(400). 카테고리를 지우면 그 재료는 기타로(출처 AUTO — 자동 분류가 다시 볼 수 있게)</li>
 *   <li>가게당 최대 {@link #MAX_PER_STORE}개(기본 11종 포함). 사장님이 만든 카테고리의 순서는 기본 10종 뒤 · 기타(99) 앞</li>
 *   <li>만들기 · 이름 바꾸기는 가게 행을 잠그고 한다 — 같은 가게의 동시 요청이 30개 상한이나 공백만 다른 이름을 비켜 가지 않게</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class IngredientCategoryService {

	public static final int MAX_PER_STORE = 30;

	private final IngredientCategoryRepository categoryRepository;
	private final IngredientRepository ingredientRepository;
	private final IngredientCategoryBackup backup;
	private final JdbcTemplate jdbc;

	/** 가게가 생길 때 기본 11종({@link DefaultIngredientCategory})을 넣는다. 이미 하나라도 있으면 그대로 둔다. */
	@Transactional
	public void seedDefaults(long storeId) {
		if (categoryRepository.existsByStoreId(storeId)) {
			return;
		}
		for (DefaultIngredientCategory code : DefaultIngredientCategory.values()) {
			categoryRepository.save(IngredientCategory.ofDefault(storeId, code));
		}
	}

	/**
	 * 이 가게의 '기타'. 재료를 만드는 쪽이 카테고리를 정하지 않았을 때 쓴다.
	 *
	 * @throws IllegalStateException 가게에 기타가 없으면 — V11 과 {@link #seedDefaults}가 가게마다 넣으므로 데이터가 깨진 것이다
	 */
	@Transactional(readOnly = true)
	public IngredientCategory other(long storeId) {
		return categoryRepository.findByStoreIdAndCode(storeId, DefaultIngredientCategory.OTHER)
				.orElseThrow(() -> new IllegalStateException("가게 " + storeId + " 에 '기타' 카테고리가 없다"));
	}

	/** 현재 가게의 카테고리 하나. 다른 가게 · 없는 id 는 404 COMMON_NOT_FOUND (ADR-0002). */
	@Transactional(readOnly = true)
	public IngredientCategory get(long storeId, long categoryId) {
		return categoryRepository.getInStore(categoryId, storeId);
	}

	/** {@link #get} 과 같되 문자열 id. 숫자가 아니면 404 (재료 API 와 같은 규칙). */
	@Transactional(readOnly = true)
	public IngredientCategory get(long storeId, String rawId) {
		return get(storeId, ResourceIds.parse(rawId));
	}

	/** 현재 가게의 카테고리 전부를 id 로 찾게. 재료 목록에 이름을 붙일 때 한 번만 읽는다. */
	@Transactional(readOnly = true)
	public Map<Long, IngredientCategory> byId(long storeId) {
		Map<Long, IngredientCategory> byId = new LinkedHashMap<>();
		for (IngredientCategory category : categoryRepository.findAllByStoreIdOrderBySortOrderAscIdAsc(storeId)) {
			byId.put(category.getId(), category);
		}
		return byId;
	}

	// ---- CRUD (S15P31A308-275)

	/** 현재 가게의 카테고리 전부(sort_order → id 순)와 각각의 재료 수. */
	@Transactional(readOnly = true)
	public List<IngredientCategoryResponse> list(long storeId) {
		Map<Long, Long> counts = new HashMap<>();
		for (Object[] row : ingredientRepository.countByStoreIdGroupByCategory(storeId)) {
			counts.put((Long) row[0], (Long) row[1]);
		}
		return categoryRepository.findAllByStoreIdOrderBySortOrderAscIdAsc(storeId).stream()
				.map(category -> IngredientCategoryResponse.of(category, counts.getOrDefault(category.getId(), 0L)))
				.toList();
	}

	/**
	 * 사장님이 카테고리를 만든다. 순서는 기타를 뺀 마지막 다음.
	 *
	 * @throws BusinessException 같은 이름 409 INGREDIENT_CATEGORY_DUPLICATE_NAME, 30개 넘침 400 INGREDIENT_CATEGORY_LIMIT_EXCEEDED
	 */
	@Transactional
	public IngredientCategoryResponse create(long storeId, IngredientCategoryCreateRequest request) {
		lockStore(storeId);
		List<IngredientCategory> existing = categoryRepository.findAllByStoreIdOrderBySortOrderAscIdAsc(storeId);
		if (existing.size() >= MAX_PER_STORE) {
			throw new BusinessException(IngredientCategoryErrorCode.LIMIT_EXCEEDED);
		}
		String name = request.name().strip();
		requireUniqueName(existing, name, null);
		int sortOrder = Math.min(IngredientCategoryUpdateRequest.MAX_SORT_ORDER,
				existing.stream().filter(c -> !c.isOther()).mapToInt(IngredientCategory::getSortOrder).max().orElse(0) + 1);
		IngredientCategory category = categoryRepository.save(IngredientCategory.ofOwner(storeId, name, sortOrder));
		return IngredientCategoryResponse.of(category, 0);
	}

	/**
	 * 보낸 칸만 바꾼다.
	 *
	 * @throws BusinessException 기타의 이름 변경 400 INGREDIENT_CATEGORY_OTHER_IMMUTABLE, 같은 이름 409, 남의 가게 · 없는 id 404
	 */
	@Transactional
	public IngredientCategoryResponse update(long storeId, String rawId, IngredientCategoryUpdateRequest request) {
		IngredientCategory category = get(storeId, rawId);
		if (request.name() != null) {
			String name = request.name().strip();
			if (!name.equals(category.getName())) {
				if (category.isOther()) {
					throw new BusinessException(IngredientCategoryErrorCode.OTHER_IMMUTABLE);
				}
				lockStore(storeId);
				requireUniqueName(categoryRepository.findAllByStoreIdOrderBySortOrderAscIdAsc(storeId), name,
						category.getId());
				category.rename(name);
				backup.exportAfterCommit();  // 백업 파일의 category_name 도 바뀐다
			}
		}
		if (request.sortOrder() != null && !category.isOther()) {
			category.reorder(request.sortOrder());
		}
		return IngredientCategoryResponse.of(category,
				ingredientRepository.countByStoreIdAndCategoryId(storeId, category.getId()));
	}

	/**
	 * 카테고리를 지운다. 그 카테고리의 재료는 전부 이 가게의 기타로 옮긴다(출처 AUTO) — 한 트랜잭션.
	 *
	 * @throws BusinessException 기타 삭제 400 INGREDIENT_CATEGORY_OTHER_IMMUTABLE, 남의 가게 · 없는 id 404
	 */
	@Transactional
	public void delete(long storeId, String rawId) {
		IngredientCategory category = get(storeId, rawId);
		if (category.isOther()) {
			throw new BusinessException(IngredientCategoryErrorCode.OTHER_IMMUTABLE);
		}
		ingredientRepository.moveCategory(storeId, category.getId(), other(storeId).getId(), CategorySource.AUTO);
		categoryRepository.delete(category);
		backup.exportAfterCommit();
	}

	/** 같은 가게의 만들기 · 이름 바꾸기를 한 줄로 세운다. 트랜잭션이 끝나면 풀린다. */
	private void lockStore(long storeId) {
		jdbc.queryForList("SELECT id FROM store WHERE id = ? FOR UPDATE", Long.class, storeId);
	}

	/** 이름이 공백 · 대소문자를 빼고 같은 카테고리가 있으면 409. {@code selfId} 는 자기 자신(고칠 때) 제외. */
	private static void requireUniqueName(List<IngredientCategory> existing, String name, Long selfId) {
		String key = IngredientCatalog.normalize(name);
		for (IngredientCategory category : existing) {
			if (!category.getId().equals(selfId) && IngredientCatalog.normalize(category.getName()).equals(key)) {
				throw new BusinessException(IngredientCategoryErrorCode.DUPLICATE_NAME);
			}
		}
	}
}
