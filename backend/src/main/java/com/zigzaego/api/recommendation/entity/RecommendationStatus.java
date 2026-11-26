package com.zigzaego.api.recommendation.entity;

/** 추천 상태. PENDING → APPROVED | REJECTED 한 번만 (3주 계획 8절). */
public enum RecommendationStatus {
	PENDING, APPROVED, REJECTED
}
