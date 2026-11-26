package com.zigzaego.api.onboarding.job;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.zigzaego.api.onboarding.dto.RecipeQuestionResponse;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * 온보딩 분석 job 의 {@code result_json} (설계 2.4). 사장님이 확정 · 건너뛰기 전까지 메뉴 · 재료 · 레시피는 여기에만 있다.
 *
 * <ul>
 *   <li>menus · needsMenuPhoto: 분석 화면용 (FE {@code AnalysisResult})</li>
 *   <li>draft: 확정 때 DB 에 펼칠 추정 레시피</li>
 *   <li>questions: 레시피 확인 질문 (FE {@code RecipeQuestion})</li>
 *   <li>extraction · classify: 채점용 LLM 원본 출력 (ai {@code scripts/onboarding/score.py} 가 읽는다)</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OnboardingResult(
		List<AnalysisMenu> menus,
		boolean needsMenuPhoto,
		Draft draft,
		List<RecipeQuestionResponse> questions,
		JsonNode extraction,
		JsonNode classify
) {

	/** 메뉴 0개 — 화면이 메뉴판 사진 올리기를 띄운다. */
	public static OnboardingResult noMenus() {
		return new OnboardingResult(List.of(), true, null, null, null, null);
	}

	/** 분석 화면의 메뉴 카드. 수집 직후(중간 결과)에는 id · name 만, 끝나면 price · ingredientCount 도. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AnalysisMenu(String id, String name, Integer price, Integer ingredientCount) {
	}

	/** 확정 전 추정 레시피. id 는 결과 안에서만 쓰는 값(m1 · c1)이고 DB id 가 아니다. */
	public record Draft(List<DraftMenu> menus, List<DraftIngredient> ingredients) {
	}

	/**
	 * @param kind   MAIN · SET · OPTION
	 * @param source 메뉴 출처 NAVER · MENU_PHOTO (menu.source)
	 */
	public record DraftMenu(String id, String name, Integer price, String kind, String source, List<DraftLine> lines,
			String photoUrl) {
	}

	public record DraftLine(String ingredientId, double mult) {
	}

	/**
	 * @param kind        BOM · ONE_TO_ONE
	 * @param unit        DB 기준 단위 G · ML · EA
	 * @param storageType FROZEN · AMBIENT · CHILLED · FRESH
	 * @param role        구성 추출에서 처음 나온 역할 (BASE · SAUCE · PROTEIN …)
	 * @param shelfLifeDays 유통기한(일). 재료표 값 또는 분류 출력 값(정수 · 2 이상만), 없으면 null — 저장 때 보관 방식 표로 채운다
	 */
	public record DraftIngredient(String id, String name, String kind, String unit, String storageType,
			double perServing, double confidence, String role,
			@JsonInclude(JsonInclude.Include.ALWAYS) Integer shelfLifeDays) {
	}
}
