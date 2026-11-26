package com.zigzaego.api.purchasedoc.entity;

/** PAST = 재고 실사일 이전(재고에 안 더함) / RECEIVE = 이후 (설계 4.2 · 4.3, DECISIONS #27 · #41). */
public enum DocKind {
	PAST, RECEIVE
}
