package com.zigzaego.api.global.store;

import com.zigzaego.api.auth.AuthUser;
import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.exception.CommonErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * JWT 필터가 SecurityContext 에 넣은 {@link AuthUser}에서 가게 id를 읽는다.
 * 컨트롤러에서 {@code @LoginUser AuthUser}를 받는 것과 같은 값이다.
 */
@Component
public class JwtCurrentStoreProvider implements CurrentStoreProvider {

	@Override
	public long currentStoreId() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof AuthUser user) {
			return user.storeId();
		}
		throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
	}
}
