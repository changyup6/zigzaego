package com.zigzaego.api.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 가입 때 반드시 모두 동의해야 하는 약관. 동의 여부는 검사만 하고 저장하지 않는다 (S15P31A308-204). */
@Schema(description = "약관 종류. TERMS=이용약관 / PRIVACY=개인정보 처리방침 / COLLECTION=메뉴 · 사진 수집 고지(결정 #45)")
public enum SignupAgreement {
	TERMS,
	PRIVACY,
	COLLECTION
}
