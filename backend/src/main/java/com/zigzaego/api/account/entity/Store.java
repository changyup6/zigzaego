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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 가게 1곳 = 테넌트 1개 (ADR-0002). 표는 V1 {@code store}. 가게별 값은 코드 대신 여기 둔다. */
@Entity
@Table(name = "store")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Store {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(name = "display_name", nullable = false, length = 100)
	private String displayName;

	@Column(nullable = false, length = 50)
	private String timezone;

	/** 온보딩 단계별 완료 여부 JSON 원문. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private String onboarding;

	@Column(name = "naver_place_id", length = 50)
	private String naverPlaceId;

	@Column(name = "yogiyo_shop_id", length = 50)
	private String yogiyoShopId;

	@Column(name = "yogiyo_consent", nullable = false)
	private boolean yogiyoConsent;

	@Column(name = "pos_vendor", length = 30)
	private String posVendor;

	@Column(name = "pos_store_code", length = 100)
	private String posStoreCode;

	/** 가게가 있는 시군구("<시도> <시군구>", 예 "서울 중구"). NULL 이면 아직 안 받은 것 (S15P31A308-230). */
	@Column(length = 50)
	private String sigungu;

	/** 선택한 네이버 플레이스의 전체 주소. 배송 판정용 시군구와 분리한다 (ADR-0023). */
	@Column(columnDefinition = "text")
	private String address;

	/** 식자재몰 가격 비교(발주 화면 품목 상세의 최저가)를 보일지. 기본 켬 (DECISIONS #48, S15P31A308-205). */
	@Column(name = "price_compare", nullable = false)
	private boolean priceCompare = true;

	/** 온보딩은 아무 단계도 끝나지 않은 상태({@code {}})로 시작한다. */
	public static Store create(String name, String displayName, String timezone) {
		Store store = new Store();
		store.name = name;
		store.displayName = displayName;
		store.timezone = timezone;
		store.onboarding = "{}";
		return store;
	}

	/** 온보딩에서 고른 네이버 플레이스로 가게 이름 · 화면 이름 · 플레이스 id 를 바꾼다. 다듬기 · 길이 확인은 부르는 쪽이 한다. */
	public void adoptPlace(String placeId, String placeName, String address) {
		if (placeName != null) {
			this.name = placeName;
			this.displayName = placeName;
		}
		this.naverPlaceId = placeId;
		this.address = address;
	}

	/** 시군구를 바꾼다. null 이면 지운다. 공백 정리는 부르는 쪽이 한다. */
	public void updateSigungu(String sigungu) {
		this.sigungu = sigungu;
	}

	/** 가격 비교를 켜거나 끈다. */
	public void updatePriceCompare(boolean priceCompare) {
		this.priceCompare = priceCompare;
	}
}
