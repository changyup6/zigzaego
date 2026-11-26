package com.zigzaego.api.auth;

/**
 * 인증된 요청의 주인. JWT 필터가 access token 클레임으로 만들어 SecurityContext 에 넣는다.
 * 요청마다 DB를 다시 읽지 않는다 — 토큰에 든 값이 전부다.
 *
 * @param accountId 로그인한 계정
 * @param storeId   이 토큰이 스코프된 가게 (ADR-0002)
 */
public record AuthUser(long accountId, long storeId) {
}
