package com.zigzaego.api.global.store;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 가게 조건 없이 도는 리포지토리 메서드에 붙인다. 요청 경로(컨트롤러 → 서비스)에서는 쓰지 않는다.
 * 실행기 · 배치처럼 이미 가게가 정해진 id 로만 움직이는 내부 경로만 해당한다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface StoreScopeExempt {

	/** 가게 조건이 필요 없는 이유. */
	String value();
}
