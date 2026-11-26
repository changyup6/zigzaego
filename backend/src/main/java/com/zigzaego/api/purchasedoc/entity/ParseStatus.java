package com.zigzaego.api.purchasedoc.entity;

/** PENDING=올림 / PROCESSING=인식 중 / PARSED=자동으로 읽음 / CONFIRMED=사장님 확인 / FAILED=못 읽음. */
public enum ParseStatus {
	PENDING, PROCESSING, PARSED, CONFIRMED, FAILED
}
