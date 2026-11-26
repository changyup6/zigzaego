package com.zigzaego.api.closingcheck.dto;

/**
 * 마감 확인 대상이 된 이유. 선언 순서가 우선순위다 (S15P31A308-249).
 *
 * <ol>
 *   <li>NEEDS_COUNT: 실사 필요 — 실사 기록 없음 · 추정 재고가 음수로 내려가 0 보정 (DECISIONS #9)</li>
 *   <li>REPEATED_LOSS: 실사 차이가 반복되는 재료 (DECISIONS #93)</li>
 *   <li>RUNNING_OUT: 소진 예상일이 오늘부터 3일 이내</li>
 *   <li>STALE_COUNT: 마지막 실사가 7일보다 오래됨</li>
 * </ol>
 */
public enum ClosingItemReason {
	NEEDS_COUNT, REPEATED_LOSS, RUNNING_OUT, STALE_COUNT
}
