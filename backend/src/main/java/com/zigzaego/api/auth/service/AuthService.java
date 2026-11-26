package com.zigzaego.api.auth.service;

import com.zigzaego.api.account.entity.Account;
import com.zigzaego.api.account.entity.StoreMember;
import com.zigzaego.api.account.repository.AccountRepository;
import com.zigzaego.api.account.repository.StoreMemberRepository;
import com.zigzaego.api.account.service.StoreAccountCreator;
import com.zigzaego.api.account.service.StoreAccountCreator.Created;
import com.zigzaego.api.account.service.StoreAccountCreator.NewStoreAccount;
import com.zigzaego.api.auth.AuthUser;
import com.zigzaego.api.auth.dto.SignupAgreement;
import com.zigzaego.api.auth.dto.SignupRequest;
import com.zigzaego.api.auth.dto.TokenResponse;
import com.zigzaego.api.auth.exception.AuthErrorCode;
import com.zigzaego.api.auth.jwt.JwtTokenProvider;
import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.exception.CommonErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 가입 · 로그인 · 재발급 · 로그아웃 (ADR-0005). 가입은 공개 API {@code POST /auth/signup} 이다 (S15P31A308-204). */
@Service
@Transactional(readOnly = true)
public class AuthService {

	/** BCrypt 는 비밀번호 앞 72바이트만 본다. 넘는 비밀번호는 뒷부분이 무시되므로 받지 않는다. */
	static final int MAX_PASSWORD_BYTES = 72;

	static final int MIN_PASSWORD_LENGTH = 8;

	/** 가입으로 만든 가게의 시간대 기본값. 바꾸는 API 는 아직 없다. */
	static final String DEFAULT_TIMEZONE = "Asia/Seoul";

	/** account.name · store.name · store.display_name 칸 길이. */
	static final int MAX_NAME_LENGTH = 100;

	private final AccountRepository accountRepository;
	private final StoreMemberRepository storeMemberRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtTokenProvider tokenProvider;
	private final RefreshTokenStore refreshTokenStore;
	private final StoreAccountCreator storeAccountCreator;

	/** 없는 이메일도 BCrypt 비교를 한 번 해서 응답 시간으로 계정 유무가 드러나지 않게 한다. */
	private final String dummyHash;

	/** 다시 가입할 수 있는 시연 계정 이메일 (ADR-0018). 비면 쓰지 않는다. */
	private final String demoEmail;

	public AuthService(AccountRepository accountRepository, StoreMemberRepository storeMemberRepository,
			PasswordEncoder passwordEncoder, JwtTokenProvider tokenProvider, RefreshTokenStore refreshTokenStore,
			StoreAccountCreator storeAccountCreator, @Value("${app.demo-account.email:}") String demoEmail) {
		this.demoEmail = demoEmail.trim();
		this.accountRepository = accountRepository;
		this.storeMemberRepository = storeMemberRepository;
		this.passwordEncoder = passwordEncoder;
		this.tokenProvider = tokenProvider;
		this.refreshTokenStore = refreshTokenStore;
		this.storeAccountCreator = storeAccountCreator;
		this.dummyHash = passwordEncoder.encode("no-such-account");
	}

	/**
	 * 계정 · 가게 · OWNER 연결을 한 트랜잭션으로 만들고 로그인과 같은 토큰 한 쌍을 준다. 약관 동의는 검사만 하고 저장하지 않는다.
	 * 가게 이름 · 계정 이름은 storeName, 비었으면 이메일의 @ 앞부분(100자에서 자름).
	 *
	 * @throws BusinessException 비밀번호가 비었거나 공백뿐이거나 8자 미만이거나 72바이트를 넘거나 약관이 빠졌거나 모르는 값이면 {@link CommonErrorCode#INVALID_INPUT},
	 *                           같은 이메일 계정이 있으면 {@link AuthErrorCode#EMAIL_TAKEN} (시연 계정 이메일은 예외 — 다시 가입된다)
	 */
	@Transactional
	public TokenResponse signup(SignupRequest request) {
		// 비밀번호는 Bean Validation 에 맡기지 않는다 — 400 fieldErrors 의 rejectedValue 로 원문이 돌아가기 때문(메시지에도 값을 넣지 않는다)
		if (request.password() == null || request.password().isBlank()) {
			throw new BusinessException(CommonErrorCode.INVALID_INPUT, "비밀번호를 입력해야 합니다.");
		}
		// 글자 수는 코드 포인트로 센다 — 이모지 같은 4바이트 문자를 2자로 세지 않는다(가게 이름 자르기와 같은 기준)
		if (request.password().codePointCount(0, request.password().length()) < MIN_PASSWORD_LENGTH) {
			throw new BusinessException(CommonErrorCode.INVALID_INPUT,
					"비밀번호는 " + MIN_PASSWORD_LENGTH + "자 이상이어야 합니다.");
		}
		if (request.password().getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
			throw new BusinessException(CommonErrorCode.INVALID_INPUT,
					"비밀번호는 UTF-8 " + MAX_PASSWORD_BYTES + "바이트를 넘을 수 없습니다.");
		}
		requireAllAgreements(request.agreements());

		String email = request.email().trim();
		String name = request.storeName() == null || request.storeName().isBlank()
				? truncate(email.substring(0, email.lastIndexOf('@')))
				: request.storeName().trim();
		releaseDemoEmail(email);
		Created created;
		try {
			created = storeAccountCreator.create(
					new NewStoreAccount(email, request.password(), name, name, name, DEFAULT_TIMEZONE));
		} catch (IllegalStateException e) {
			// StoreAccountCreator 는 이메일 중복을 IllegalStateException 으로 알린다(create-account 러너용)
			throw new BusinessException(AuthErrorCode.EMAIL_TAKEN);
		}
		return issue(new AuthUser(created.accountId(), created.storeId()));
	}

	/**
	 * 시연 계정 이메일로 가입하는데 그 이메일의 계정이 이미 있으면, 옛 계정의 이메일을 보관용 값으로 바꿔 자리를 비운다 (ADR-0018).
	 * 옛 계정 · 가게 · 데이터는 지우지 않으며, 새 가입은 새 계정 · 새 가게를 만든다.
	 */
	private void releaseDemoEmail(String email) {
		if (demoEmail.isEmpty() || !demoEmail.equals(email)) {
			return;
		}
		accountRepository.findByEmail(email).ifPresent(old -> {
			// ponytail: 보관된 옛 계정 · 가게는 계속 쌓인다. 많아지면 운영자가 지우는 실행을 따로 둔다
			old.changeEmail("archived-" + old.getId() + "-" + email);
			accountRepository.saveAndFlush(old);
		});
	}

	private static void requireAllAgreements(List<String> agreements) {
		Set<SignupAgreement> agreed = EnumSet.noneOf(SignupAgreement.class);
		for (String value : agreements) {
			SignupAgreement agreement = Arrays.stream(SignupAgreement.values())
					.filter(a -> a.name().equals(value))
					.findFirst()
					.orElseThrow(() -> new BusinessException(CommonErrorCode.INVALID_INPUT, "모르는 약관 값이 있습니다."));
			agreed.add(agreement);
		}
		if (!agreed.containsAll(EnumSet.allOf(SignupAgreement.class))) {
			throw new BusinessException(CommonErrorCode.INVALID_INPUT, "TERMS · PRIVACY · COLLECTION 약관에 모두 동의해야 합니다.");
		}
	}

	/** 서로게이트 쌍을 가르지 않게 코드 포인트 기준으로 자른다. */
	private static String truncate(String value) {
		if (value.codePointCount(0, value.length()) <= MAX_NAME_LENGTH) {
			return value;
		}
		return value.substring(0, value.offsetByCodePoints(0, MAX_NAME_LENGTH));
	}

	/**
	 * @throws BusinessException 이메일 · 비밀번호가 틀리면 {@link AuthErrorCode#INVALID_CREDENTIALS},
	 *                           연결된 가게가 없으면 {@link AuthErrorCode#NO_STORE}
	 */
	public TokenResponse login(String email, String password) {
		Account account = accountRepository.findByEmail(email.trim()).orElse(null);
		String hash = account == null ? dummyHash : account.getPasswordHash();
		if (!passwordEncoder.matches(password, hash) || account == null) {
			throw new BusinessException(AuthErrorCode.INVALID_CREDENTIALS);
		}
		StoreMember member = storeMemberRepository.findFirstByAccountIdOrderByIdAsc(account.getId())
				.orElseThrow(() -> new BusinessException(AuthErrorCode.NO_STORE));
		return issue(new AuthUser(account.getId(), member.getStoreId()));
	}

	/**
	 * refresh token을 소모하고 새 토큰 한 쌍을 준다(rotation). 그 사이 가게 연결이 끊겼으면 재발급하지 않는다.
	 *
	 * @throws BusinessException 없거나 이미 쓴 · 만료 · 로그아웃된 토큰이면 {@link AuthErrorCode#INVALID_REFRESH_TOKEN}
	 */
	public TokenResponse refresh(String refreshToken) {
		AuthUser user = refreshTokenStore.consume(refreshToken)
				.filter(u -> storeMemberRepository.existsByAccountIdAndStoreId(u.accountId(), u.storeId()))
				.orElseThrow(() -> new BusinessException(AuthErrorCode.INVALID_REFRESH_TOKEN));
		return issue(user);
	}

	/** refresh token을 지운다. 없는 토큰이어도 성공으로 본다. access token은 만료까지 유효하다(ADR-0005). */
	public void logout(String refreshToken) {
		refreshTokenStore.revoke(refreshToken);
	}

	private TokenResponse issue(AuthUser user) {
		return new TokenResponse(
				tokenProvider.createAccessToken(user),
				tokenProvider.accessTtlSeconds(),
				refreshTokenStore.issue(user));
	}
}
