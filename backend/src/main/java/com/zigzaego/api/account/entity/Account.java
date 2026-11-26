package com.zigzaego.api.account.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 로그인 계정. 표는 V1 {@code account}. 공개 가입({@code POST /auth/signup})으로 만들고,
 * 운영자용(데모 · 시험 계정)으로는 {@code create-account} 프로필도 남아 있다.
 * 어느 가게를 볼 수 있는지는 {@link StoreMember}가 정한다.
 */
@Entity
@Table(name = "account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String email;

	/** BCrypt 해시. 원문 비밀번호는 저장하지 않는다. */
	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Column(nullable = false, length = 100)
	private String name;

	public static Account create(String email, String passwordHash, String name) {
		Account account = new Account();
		account.email = email;
		account.passwordHash = passwordHash;
		account.name = name;
		return account;
	}

	/** 이메일을 바꾼다. 시연 계정 재가입에서 옛 계정이 이메일을 비워 줄 때만 쓴다 (ADR-0018). */
	public void changeEmail(String email) {
		this.email = email;
	}
}
