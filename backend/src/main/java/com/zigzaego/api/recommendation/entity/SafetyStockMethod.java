package com.zigzaego.api.recommendation.entity;

/**
 * 안전재고 식 ({@code recommendation.ss_method}, 설계 5.3절).
 * STAT = z × σ × √(R+L) / DAYS_N = 최근 7일 평균 × N일 (소비 이력 28일 미만 · 주기형).
 */
public enum SafetyStockMethod {
	STAT, DAYS_N
}
