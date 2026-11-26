package com.zigzaego.api.purchasedoc.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 묶음 확정 결과 (S15P31A308-113). {@code GET /receipts} 와 같은 모양에 gain 을 더한 것.
 *
 * @param confirm        줄 확정 요약
 * @param skippedLineIds 날짜를 못 읽은 문서라 건너뛴 줄(문자열, 오름차순) — 목록의 같은 묶음에 남는다
 * @param gain           이번 확정으로 추정 재고에 더해진 양(고른 재료 기준 단위, 소수 셋째 자리). 주기형 · 무시면 0
 * @param board          확정 뒤 새 확인 목록
 */
public record GroupConfirmResult(ConfirmResultResponse confirm, List<String> skippedLineIds, BigDecimal gain,
		ReviewBoard board) {
}
