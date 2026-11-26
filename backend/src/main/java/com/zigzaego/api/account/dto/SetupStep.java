package com.zigzaego.api.account.dto;

/** 가게 설정 체크리스트 단계. 응답 순서도 이 순서다 (설계 2.1절). */
public enum SetupStep {
	/** 가게 찾기 · 메뉴 수집 (온보딩 ①②). 메뉴가 저장돼 있으면 끝. */
	ONBOARDING,
	/** 레시피 확인 (온보딩 ③). 남은 개수 = 답하지 않은 질문 수. */
	RECIPE,
	/** POS 연결. 포스기 업체가 설정됐거나 판매 기록이 있으면 끝. */
	POS,
	/** 거래처 등록. */
	SUPPLIER,
	/** 첫 입고. 발주 입고 확인 또는 사장님이 확인한 입고 문서가 있으면 끝. */
	FIRST_RECEIVE,
	/** 재고 실사. 실사 기록이 하나라도 있으면 끝. */
	STOCK_COUNT
}
