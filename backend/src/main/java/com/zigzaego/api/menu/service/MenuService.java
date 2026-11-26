package com.zigzaego.api.menu.service;

import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.review.ReviewReason;
import com.zigzaego.api.global.store.ResourceIds;
import com.zigzaego.api.menu.dto.MenuCreateRequest;
import com.zigzaego.api.menu.dto.MenuResponse;
import com.zigzaego.api.menu.dto.MenuUpdateRequest;
import com.zigzaego.api.menu.entity.Menu;
import com.zigzaego.api.menu.entity.MenuKind;
import com.zigzaego.api.menu.exception.MenuErrorCode;
import com.zigzaego.api.menu.repository.MenuRepository;
import com.zigzaego.api.recipe.repository.RecipeRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 메뉴 CRUD. 모든 조회 · 쓰기를 로그인한 가게로 좁힌다 — 다른 가게 메뉴 · 없는 id · 숫자가 아닌 id 는 모두 404 (ADR-0002).
 * 확인 필요(설계 6절): AI 추정 레시피 줄이 남은 메뉴는 UNANSWERED. 목록은 확인 필요 메뉴가 맨 위.
 */
@Service
@RequiredArgsConstructor
public class MenuService {

	/** 판매 · 예측 기록과 세트 구성은 메뉴를 지우면 사라지는 이력이라 삭제를 막는다. */
	private static final String IN_USE_SQL = """
			SELECT EXISTS (SELECT 1 FROM pos_sale WHERE store_id = :storeId AND menu_id = :menuId)
			    OR EXISTS (SELECT 1 FROM pos_sale_line WHERE store_id = :storeId AND menu_id = :menuId)
			    OR EXISTS (SELECT 1 FROM forecast WHERE store_id = :storeId AND menu_id = :menuId)
			    OR EXISTS (SELECT 1 FROM menu_component WHERE store_id = :storeId
			               AND (set_menu_id = :menuId OR component_menu_id = :menuId))
			""";

	private static final String DELETE_ALIASES_SQL =
			"DELETE FROM menu_alias WHERE store_id = :storeId AND menu_id = :menuId";

	private final MenuRepository menuRepository;
	private final RecipeRepository recipeRepository;
	private final NamedParameterJdbcTemplate jdbc;

	@Transactional(readOnly = true)
	public List<MenuResponse> listForStore(long storeId) {
		List<Menu> menus = menuRepository.findAllByStoreIdOrderByIdAsc(storeId);
		if (menus.isEmpty()) {
			return List.of();
		}
		Set<Long> estimated = recipeRepository.findEstimatedMenuIds(storeId, menus.stream().map(Menu::getId).toList());
		return menus.stream()
				.map(menu -> MenuResponse.of(menu, reasons(estimated.contains(menu.getId()))))
				.sorted(Comparator.comparing(MenuResponse::needsReview).reversed())
				.toList();
	}

	@Transactional(readOnly = true)
	public MenuResponse getForStore(long storeId, String rawId) {
		return toResponse(storeId, menuRepository.getInStore(ResourceIds.parse(rawId), storeId));
	}

	@Transactional
	public MenuResponse create(long storeId, MenuCreateRequest request) {
		String name = request.name().strip();
		requireUniqueName(storeId, name);
		MenuKind kind = request.kind() == null ? MenuKind.MAIN : request.kind();
		Menu menu = menuRepository.save(
				Menu.create(storeId, name, kind, request.description(), request.source(), request.price()));
		return MenuResponse.of(menu, List.of());
	}

	@Transactional
	public MenuResponse update(long storeId, String rawId, MenuUpdateRequest request) {
		Menu menu = menuRepository.getInStore(ResourceIds.parse(rawId), storeId);
		if (request.name() != null) {
			String name = request.name().strip();
			if (!name.equals(menu.getName())) {
				requireUniqueName(storeId, name);
				menu.rename(name);
			}
		}
		if (request.kind() != null) {
			menu.changeKind(request.kind());
		}
		if (request.active() != null) {
			menu.changeActive(request.active());
		}
		if (request.description() != null) {
			menu.changeDescription(request.description());
		}
		if (request.price() != null) {
			menu.changePrice(request.price());
		}
		return toResponse(storeId, menu);
	}

	/**
	 * 메뉴와 그 레시피 줄 · 포스 이름 연결을 지운다.
	 *
	 * @throws BusinessException 판매 · 예측 기록이나 세트 구성에 쓰였으면 409 MENU_IN_USE
	 */
	@Transactional
	public void delete(long storeId, String rawId) {
		long menuId = menuRepository.getInStore(ResourceIds.parse(rawId), storeId).getId();
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("storeId", storeId)
				.addValue("menuId", menuId);
		if (Boolean.TRUE.equals(jdbc.queryForObject(IN_USE_SQL, params, Boolean.class))) {
			throw new BusinessException(MenuErrorCode.IN_USE);
		}
		recipeRepository.deleteAllInMenu(storeId, menuId);
		jdbc.update(DELETE_ALIASES_SQL, params);
		// 레시피 일괄 삭제가 영속성 컨텍스트를 비웠으니 다시 읽어 지운다
		menuRepository.delete(menuRepository.getInStore(menuId, storeId));
	}

	private MenuResponse toResponse(long storeId, Menu menu) {
		boolean estimated = !recipeRepository.findEstimatedMenuIds(storeId, List.of(menu.getId())).isEmpty();
		return MenuResponse.of(menu, reasons(estimated));
	}

	private void requireUniqueName(long storeId, String name) {
		if (menuRepository.existsByStoreIdAndName(storeId, name)) {
			throw new BusinessException(MenuErrorCode.DUPLICATE_NAME);
		}
	}

	private static List<ReviewReason> reasons(boolean estimated) {
		return estimated ? List.of(ReviewReason.UNANSWERED) : List.of();
	}
}
