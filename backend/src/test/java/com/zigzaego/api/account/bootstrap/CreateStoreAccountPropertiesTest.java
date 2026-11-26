package com.zigzaego.api.account.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zigzaego.api.account.service.StoreAccountCreator.NewStoreAccount;
import org.junit.jupiter.api.Test;

class CreateStoreAccountPropertiesTest {

	@Test
	void 빠진_환경_변수를_한_번에_알려준다() {
		CreateStoreAccountProperties properties =
				new CreateStoreAccountProperties(" ", null, "운영자", "", null, "Asia/Seoul");

		assertThatThrownBy(properties::toRequest)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("CREATE_ACCOUNT_EMAIL")
				.hasMessageContaining("CREATE_ACCOUNT_PASSWORD")
				.hasMessageContaining("CREATE_ACCOUNT_STORE_NAME")
				.hasMessageNotContaining("CREATE_ACCOUNT_OWNER_NAME");
	}

	@Test
	void 비밀번호가_8자보다_짧으면_거절하고_값은_메시지에_넣지_않는다() {
		CreateStoreAccountProperties properties =
				new CreateStoreAccountProperties("a@b.c", "short1", "운영자", "가게", null, "Asia/Seoul");

		assertThatThrownBy(properties::toRequest)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageNotContaining("short1");
	}

	@Test
	void 표시_이름이_비면_가게_이름을_쓰고_앞뒤_공백을_지운다() {
		NewStoreAccount request = new CreateStoreAccountProperties(
				" a@b.c ", "password1234", " 운영자 ", " 가게 ", "", " Asia/Seoul ").toRequest();

		assertThat(request).isEqualTo(
				new NewStoreAccount("a@b.c", "password1234", "운영자", "가게", "가게", "Asia/Seoul"));
	}
}
