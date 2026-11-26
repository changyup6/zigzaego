package com.zigzaego.api.account.bootstrap;

import com.zigzaego.api.account.service.StoreAccountCreator;
import com.zigzaego.api.account.service.StoreAccountCreator.Created;
import com.zigzaego.api.account.service.StoreAccountCreator.NewStoreAccount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 운영자용 초기 계정 생성 (S15P31A308-37). {@code create-account} 프로필로 띄울 때만 돈다.
 * 웹 서버 없이 계정 · 가게 · 가게 연결을 만들고 끝난다. 실행법은 {@code backend/README.md}.
 */
@Slf4j
@Component
@Profile("create-account")
@EnableConfigurationProperties(CreateStoreAccountProperties.class)
@RequiredArgsConstructor
public class CreateStoreAccountRunner implements ApplicationRunner {

	private final CreateStoreAccountProperties properties;
	private final StoreAccountCreator creator;

	@Override
	public void run(ApplicationArguments args) {
		NewStoreAccount request = properties.toRequest();
		Created created = creator.create(request);
		log.info("계정 생성 완료 — email={}, accountId={}, storeId={}. 이 이메일 · 비밀번호로 로그인하세요.",
				request.email(), created.accountId(), created.storeId());
	}
}
