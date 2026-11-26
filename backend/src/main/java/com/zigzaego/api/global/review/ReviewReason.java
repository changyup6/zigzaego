package com.zigzaego.api.global.review;

/**
 * 확인 필요(설계 6절) 이유 코드. 응답에 {@code needsReview: true}와 함께 싣고, FE는 이 코드로 ⓘ 문구를 고른다.
 *
 * <ul>
 *   <li>UNANSWERED: AI 가 추정한 레시피 양(recipe.source=AI)을 사장님이 아직 확인하지 않았다</li>
 *   <li>NEEDS_COUNT: 재고 실사가 필요하다. 지금은 실사 기록(stock_count)이 한 번도 없는 재료 —
 *       추정 재고의 기준점이 없다. 추정 재고가 음수가 되는 경우는 재고 추정 API 에서 더한다</li>
 *   <li>SUPPLIER_UNSET: 발주 계산이 거래처의 리드타임 · 발주 주기를 썼는데 그 값이 없다(대표 상품이 없거나
 *       거래처 설정이 비어 기본값 1일로 계산). 거래처 · 재료 화면에서 채우면 사라진다 (V1 supplier 주석)</li>
 * </ul>
 */
public enum ReviewReason {
	UNANSWERED, NEEDS_COUNT, SUPPLIER_UNSET
}
