package com.zigzaego.api.closingcheck.dto;

/**
 * 빼기 기록 사유 5종 (DECISIONS #88 ③). V17 {@code stock_adjustment.reason} CHECK 값과 같다.
 * DISCARD 폐기 · SPOILED 상함 · DAMAGED 파손 · SPILLED 쏟음 · OTHER 기타.
 */
public enum AdjustmentReason {
	DISCARD, SPOILED, DAMAGED, SPILLED, OTHER
}
