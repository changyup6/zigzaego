package com.zigzaego.api.ingredient.entity;

/** ESTIMATED(LLM 추정) → CONFIRMED(사장님 답) → LOCKED(그 단위로 입고 기록이 생겨 고정). */
public enum UnitStatus {
	ESTIMATED, CONFIRMED, LOCKED
}
