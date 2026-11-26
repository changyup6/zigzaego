package com.zigzaego.api.auth;

import io.swagger.v3.oas.annotations.Parameter;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

/**
 * 컨트롤러 파라미터에 붙이면 JWT 필터가 넣은 {@link AuthUser}를 받는다. Swagger 파라미터에는 나오지 않는다.
 *
 * <pre>{@code
 * @GetMapping("/jobs/{id}")
 * public ApiResponse<JobResponse> get(@LoginUser AuthUser user, @PathVariable String id) {
 *     return ApiResponse.ok(jobService.getForStore(user.storeId(), id));
 * }
 * }</pre>
 *
 * 인증이 없는 공개 경로에서는 {@code null} 이다. 서비스에는 {@link AuthUser} 대신 필요한 id만 넘긴다.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@AuthenticationPrincipal
@Parameter(hidden = true)
public @interface LoginUser {
}
