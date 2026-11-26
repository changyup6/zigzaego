package com.zigzaego.api.global.store;

/**
 * 지금 요청한 계정이 어느 가게인지 알려 준다 (ADR-0002). 모든 조회 · 쓰기는 이 값으로 가게를 좁힌다.
 * URL · 바디로 store_id 를 받지 않는다.
 */
public interface CurrentStoreProvider {

	/**
	 * @return 로그인한 계정의 가게 id
	 * @throws com.zigzaego.api.global.exception.BusinessException 인증 정보가 없으면 401 COMMON_UNAUTHORIZED
	 */
	long currentStoreId();
}
