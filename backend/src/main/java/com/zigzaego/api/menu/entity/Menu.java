package com.zigzaego.api.menu.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 가게의 메뉴 하나. 표는 V1 {@code menu}. */
@Entity
@Table(name = "menu")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Menu {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "store_id", nullable = false)
	private Long storeId;

	@Column(nullable = false, length = 100)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private MenuKind kind;

	@Column(nullable = false)
	private boolean active;

	/** 수집한 메뉴 설명. 없으면 null. */
	@Column(columnDefinition = "TEXT")
	private String description;

	/** 대표 메뉴 사진 원본 URL. 없으면 null (ADR-0022). */
	@Column(name = "photo_url", columnDefinition = "TEXT")
	private String photoUrl;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 12)
	private MenuSource source;

	/** 판매가(원). 칸은 NUMERIC(12,2)지만 금액은 원 단위 정수만 넣는다 (DECISIONS #55). 모르면 null. */
	@Column(precision = 12, scale = 2)
	private BigDecimal price;

	public static Menu create(long storeId, String name, MenuKind kind, String description, MenuSource source,
			BigDecimal price) {
		Menu menu = new Menu();
		menu.storeId = storeId;
		menu.name = name;
		menu.kind = kind;
		menu.active = true;
		menu.description = description;
		menu.source = source;
		menu.price = price;
		return menu;
	}

	public void changePrice(BigDecimal price) {
		this.price = price;
	}

	public void rename(String name) {
		this.name = name;
	}

	public void changeKind(MenuKind kind) {
		this.kind = kind;
	}

	public void changeActive(boolean active) {
		this.active = active;
	}

	public void changeDescription(String description) {
		this.description = description;
	}

	/** 사진 없는 이전 분석 결과로 다시 확정할 때 기존 사진을 유지한다. */
	public void changePhotoUrl(String photoUrl) {
		String normalized = MenuPhotoUrl.normalize(photoUrl);
		if (normalized != null) {
			this.photoUrl = normalized;
		}
	}
}
