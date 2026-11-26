package com.zigzaego.api.account.bootstrap;

import com.zigzaego.api.account.service.StoreAccountCreator.NewStoreAccount;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 초기 계정 · 가게 값 ({@code application-create-account.yaml}). 전부 환경 변수로 받는다 — {@code backend/.env.example}.
 *
 * @param email            로그인 이메일
 * @param password         로그인 비밀번호 원문. 로그에 남기지 않는다
 * @param ownerName        계정 이름
 * @param storeName        가게 이름
 * @param storeDisplayName 화면에 보일 가게 이름. 비면 {@code storeName}
 * @param timezone         가게 시간대
 */
@ConfigurationProperties(prefix = "app.create-account")
public record CreateStoreAccountProperties(String email, String password, String ownerName, String storeName,
		String storeDisplayName, String timezone) {

	static final int MIN_PASSWORD_LENGTH = 8;

	/** 빠진 값을 한 번에 모아 알려준다. 비밀번호 값은 메시지에 넣지 않는다. */
	NewStoreAccount toRequest() {
		List<String> missing = new ArrayList<>();
		if (isBlank(email)) {
			missing.add("CREATE_ACCOUNT_EMAIL");
		}
		if (isBlank(password)) {
			missing.add("CREATE_ACCOUNT_PASSWORD");
		}
		if (isBlank(ownerName)) {
			missing.add("CREATE_ACCOUNT_OWNER_NAME");
		}
		if (isBlank(storeName)) {
			missing.add("CREATE_ACCOUNT_STORE_NAME");
		}
		if (isBlank(timezone)) {
			missing.add("CREATE_ACCOUNT_TIMEZONE");
		}
		if (!missing.isEmpty()) {
			throw new IllegalStateException("환경 변수를 채우세요: " + String.join(", ", missing));
		}
		if (password.length() < MIN_PASSWORD_LENGTH) {
			throw new IllegalStateException("CREATE_ACCOUNT_PASSWORD 는 " + MIN_PASSWORD_LENGTH + "자 이상이어야 합니다.");
		}
		String displayName = isBlank(storeDisplayName) ? storeName.trim() : storeDisplayName.trim();
		return new NewStoreAccount(email.trim(), password, ownerName.trim(), storeName.trim(), displayName,
				timezone.trim());
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
