package com.zigzaego.api.purchasedoc.entity;

/** 매입 문서 줄 확인 상태: PENDING 확인 전 / CONFIRMED 사장님 확인 / IGNORED 무시 (설계 4.4 · 7절). */
public enum DocLineStatus {
	PENDING, CONFIRMED, IGNORED
}
