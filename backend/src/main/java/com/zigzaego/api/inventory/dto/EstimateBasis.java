package com.zigzaego.api.inventory.dto;

/**
 * 추정 재고 한 줄을 어떤 식으로 계산했는지. FE 가 null 칸의 뜻을 고르는 기준이다.
 *
 * <ul>
 *   <li>COUNT: BOM · 1:1. 마지막 실사 + 그 뒤 입고 − 그 뒤 소비 (3주 계획 4.2절)</li>
 *   <li>NO_COUNT: BOM · 1:1. 실사 기록이 없어 0 을 기준점으로 전체 입고 − 전체 소비. needsCount 가 항상 true</li>
 *   <li>CYCLE_SALES: 주기형. 입고 2건 이상 + 그 사이 판매 있음 → 그릇당 소비량으로 계산 (설계 5.1절)</li>
 *   <li>CYCLE_INTERVAL: 주기형. 입고 2건 이상 + 그 사이 판매 없음 → 평균 입고 간격으로 소진일만. estimatedQty 는 null</li>
 *   <li>CYCLE_COLLECTING: 주기형. 입고 1건 이하라 기록을 쌓는 중. estimatedQty · runOutDate 모두 null</li>
 * </ul>
 */
public enum EstimateBasis {
	COUNT, NO_COUNT, CYCLE_SALES, CYCLE_INTERVAL, CYCLE_COLLECTING
}
