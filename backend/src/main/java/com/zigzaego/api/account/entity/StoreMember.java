package com.zigzaego.api.account.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 계정-가게 연결과 역할. 표는 V1 {@code store_member}, {@code (account_id, store_id)} unique. */
@Entity
@Table(name = "store_member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoreMember {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "account_id", nullable = false)
	private Long accountId;

	@Column(name = "store_id", nullable = false)
	private Long storeId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private MemberRole role;

	public static StoreMember create(long accountId, long storeId, MemberRole role) {
		StoreMember member = new StoreMember();
		member.accountId = accountId;
		member.storeId = storeId;
		member.role = role;
		return member;
	}
}
