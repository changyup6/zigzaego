package com.zigzaego.api.support;

import com.zigzaego.api.global.store.CurrentStoreProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** JwtCurrentStoreProvider(토큰의 가게) 대신 테스트가 가게 번호를 바꿔 끼우는 공용 설정. 컨텍스트를 함께 쓴다. */
@TestConfiguration(proxyBeanMethods = false)
public class FakeStoreConfig {

	@Bean
	@Primary
	FakeCurrentStoreProvider fakeCurrentStoreProvider() {
		return new FakeCurrentStoreProvider();
	}

	public static class FakeCurrentStoreProvider implements CurrentStoreProvider {

		public volatile long storeId;

		@Override
		public long currentStoreId() {
			return storeId;
		}
	}
}
