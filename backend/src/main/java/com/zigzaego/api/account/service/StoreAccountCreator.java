package com.zigzaego.api.account.service;

import com.zigzaego.api.account.entity.Account;
import com.zigzaego.api.account.entity.MemberRole;
import com.zigzaego.api.account.entity.Store;
import com.zigzaego.api.account.entity.StoreMember;
import com.zigzaego.api.account.repository.AccountRepository;
import com.zigzaego.api.account.repository.StoreMemberRepository;
import com.zigzaego.api.account.repository.StoreRepository;
import com.zigzaego.api.ingredient.service.IngredientCategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계정 · 가게 · 가게 연결(OWNER)을 한 트랜잭션으로 만든다. 공개 가입 API가 나오기 전까지 쓰는 임시 운영 도구다 (DECISIONS #54).
 * 요청 경로에서는 쓰지 않는다 — {@code create-account} 프로필의 {@code CreateStoreAccountRunner}가 진입점.
 */
@Service
@RequiredArgsConstructor
public class StoreAccountCreator {

	private final AccountRepository accountRepository;
	private final StoreRepository storeRepository;
	private final StoreMemberRepository storeMemberRepository;
	private final IngredientCategoryService ingredientCategoryService;
	private final PasswordEncoder passwordEncoder;

	/**
	 * @throws IllegalStateException 같은 이메일 계정이 이미 있으면. 아무것도 만들지 않는다
	 */
	@Transactional
	public Created create(NewStoreAccount request) {
		String email = request.email().trim();
		if (accountRepository.existsByEmail(email)) {
			throw new IllegalStateException("이미 있는 이메일입니다: " + email);
		}
		Account account = accountRepository.save(
				Account.create(email, passwordEncoder.encode(request.password()), request.ownerName()));
		Store store = storeRepository.save(
				Store.create(request.storeName(), request.storeDisplayName(), request.timezone()));
		storeMemberRepository.save(StoreMember.create(account.getId(), store.getId(), MemberRole.OWNER));
		// 가게마다 기본 재료 카테고리 11종 (S15P31A308-273). V11 이 기존 가게에 넣은 것과 같은 목록
		ingredientCategoryService.seedDefaults(store.getId());
		return new Created(account.getId(), store.getId());
	}

	/** 비밀번호는 원문으로 받아 BCrypt 해시만 저장한다. */
	public record NewStoreAccount(String email, String password, String ownerName, String storeName,
			String storeDisplayName, String timezone) {
	}

	public record Created(long accountId, long storeId) {
	}
}
