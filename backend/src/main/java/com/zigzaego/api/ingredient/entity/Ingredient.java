package com.zigzaego.api.ingredient.entity;

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
import org.hibernate.annotations.DynamicUpdate;

/**
 * 가게의 재료 하나. 표는 V1 {@code ingredient}.
 * 대표 거래처 상품({@code primary_supplier_product_id})은 거래처 상품 CRUD 에서 매핑한다.
 *
 * <p>{@link DynamicUpdate}: 바뀐 칸만 UPDATE 한다. 이름만 고치는 요청이 그 사이 입고 확인이 걸어 둔
 * {@code unit_status=LOCKED}를 읽은 시점 값으로 덮어써 단위 고정을 풀지 않게 한다.
 */
@Entity
@DynamicUpdate
@Table(name = "ingredient")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Ingredient {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "store_id", nullable = false)
	private Long storeId;

	@Column(nullable = false, length = 100)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private IngredientUnit unit;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 12)
	private IngredientKind kind;

	@Enumerated(EnumType.STRING)
	@Column(name = "unit_status", nullable = false, length = 10)
	private UnitStatus unitStatus;

	/** 주기형(CYCLE)은 모르면 null. BOM · 1:1 은 필수 (ck_ingredient_storage_type_required). */
	@Enumerated(EnumType.STRING)
	@Column(name = "storage_type", length = 10)
	private StorageType storageType;

	/** 보관 위치(화면 배치). 만들 때 {@link StorageLocation#defaultFor}로 채운다 (S15P31A308-268). */
	@Enumerated(EnumType.STRING)
	@Column(name = "storage_location", nullable = false, length = 10)
	private StorageLocation storageLocation;

	/** 재료 카테고리(같은 가게의 {@code ingredient_category}). 만드는 쪽이 정하지 않으면 그 가게의 기타 (S15P31A308-273). */
	@Column(name = "category_id", nullable = false)
	private Long categoryId;

	/** 카테고리를 누가 정했나. 자동 분류는 AUTO 만 다시 분류하고 OWNER 는 건드리지 않는다 (S15P31A308-274). */
	@Enumerated(EnumType.STRING)
	@Column(name = "category_source", nullable = false, length = 5)
	private CategorySource categorySource;

	/** 안전재고 배수 N(일). DAYS_N 식에서만 쓴다 (DECISIONS #28). */
	@Column(name = "safety_days", nullable = false, precision = 6, scale = 2)
	private BigDecimal safetyDays;

	/** 한 번에 주문할 최대 양(기준 단위). 없으면 null. */
	@Column(name = "max_order_qty", precision = 14, scale = 3)
	private BigDecimal maxOrderQty;

	/** 가격 연동 발주를 켜는지 (3주 계획 4.8절). */
	@Column(name = "price_sensitive", nullable = false)
	private boolean priceSensitive;

	/** 유통기한(일). 없으면 null. */
	@Column(name = "shelf_life_days")
	private Integer shelfLifeDays;

	@Enumerated(EnumType.STRING)
	@Column(name = "sourcing_type", nullable = false, length = 10)
	private SourcingType sourcingType;

	/** 대표 거래처 상품. 추천이 상품을 따로 고르지 않으면 이 상품으로 산다. 없으면 null. */
	@Column(name = "primary_supplier_product_id")
	private Long primarySupplierProductId;

	public static Ingredient create(long storeId, String name, IngredientUnit unit, IngredientKind kind,
			UnitStatus unitStatus, StorageType storageType, BigDecimal safetyDays, BigDecimal maxOrderQty,
			boolean priceSensitive, Integer shelfLifeDays, SourcingType sourcingType, long categoryId) {
		Ingredient ingredient = new Ingredient();
		ingredient.storeId = storeId;
		ingredient.name = name;
		ingredient.unit = unit;
		ingredient.kind = kind;
		ingredient.unitStatus = unitStatus;
		ingredient.storageType = storageType;
		ingredient.storageLocation = StorageLocation.defaultFor(kind, storageType);
		ingredient.safetyDays = safetyDays;
		ingredient.maxOrderQty = maxOrderQty;
		ingredient.priceSensitive = priceSensitive;
		ingredient.shelfLifeDays = shelfLifeDays;
		ingredient.sourcingType = sourcingType;
		ingredient.categoryId = categoryId;
		ingredient.categorySource = CategorySource.AUTO;
		return ingredient;
	}

	/**
	 * 카테고리를 붙인다. 같은 가게의 카테고리 id 여야 한다 — 부르는 쪽이 확인한다.
	 * 자동 분류({@link CategorySource#AUTO})는 사장님이 고른({@link CategorySource#OWNER}) 카테고리를 덮어쓰지 않는다.
	 */
	public void assignCategory(long categoryId, CategorySource source) {
		if (source == CategorySource.AUTO && categoryChosenByOwner()) {
			return;
		}
		this.categoryId = categoryId;
		this.categorySource = source;
	}

	/** 사장님이 직접 고른 카테고리인지. */
	public boolean categoryChosenByOwner() {
		return categorySource == CategorySource.OWNER;
	}

	/** 그 단위로 입고 기록이 생겨 굳은 단위는 바꿀 수 없다 (설계 3.1절). */
	public boolean unitLocked() {
		return unitStatus == UnitStatus.LOCKED;
	}

	/** 사장님이 단위를 정했다. 첫 입고 전까지는 다시 바꿀 수 있다. 호출 전에 {@link #unitLocked()}를 확인한다. */
	public void confirmUnit(IngredientUnit unit) {
		this.unit = unit;
		this.unitStatus = UnitStatus.CONFIRMED;
	}

	/** 그 단위로 입고 기록(확정된 매입 문서 줄 · 입고 확인)이 생겨 단위를 굳힌다 (설계 3.1절). 이미 LOCKED 면 그대로. */
	public void lockUnit() {
		this.unitStatus = UnitStatus.LOCKED;
	}

	/**
	 * 온보딩 확정이 다시 들어올 때 추정 칸을 새 값으로 (S15P31A308-96). LOCKED(그 단위로 입고 기록이 생김)면
	 * 단위와 단위 상태는 그대로 둔다 (설계 3.1).
	 */
	public void updateFromOnboarding(IngredientUnit unit, IngredientKind kind, UnitStatus unitStatus,
			StorageType storageType, boolean priceSensitive, Integer shelfLifeDays, SourcingType sourcingType) {
		if (!unitLocked()) {
			this.unit = unit;
			this.unitStatus = unitStatus;
		}
		this.kind = kind;
		this.storageType = storageType;
		this.priceSensitive = priceSensitive;
		this.shelfLifeDays = shelfLifeDays;
		this.sourcingType = sourcingType;
	}

	public void rename(String name) {
		this.name = name;
	}

	public void changeKind(IngredientKind kind) {
		this.kind = kind;
	}

	public void changeStorageType(StorageType storageType) {
		this.storageType = storageType;
	}

	/** 화면 배치만 바꾼다. 보관 방식(storage_type)은 건드리지 않는다 (S15P31A308-269). */
	public void moveTo(StorageLocation storageLocation) {
		this.storageLocation = storageLocation;
	}

	public void changeSafetyDays(BigDecimal safetyDays) {
		this.safetyDays = safetyDays;
	}

	public void changeMaxOrderQty(BigDecimal maxOrderQty) {
		this.maxOrderQty = maxOrderQty;
	}

	public void changePriceSensitive(boolean priceSensitive) {
		this.priceSensitive = priceSensitive;
	}

	public void changeShelfLifeDays(Integer shelfLifeDays) {
		this.shelfLifeDays = shelfLifeDays;
	}

	public void changeSourcingType(SourcingType sourcingType) {
		this.sourcingType = sourcingType;
	}

	public boolean isPrimarySupplierProduct(long supplierProductId) {
		return primarySupplierProductId != null && primarySupplierProductId == supplierProductId;
	}

	/** 거래처 상품 CRUD 에서만 부른다. null 이면 대표 상품을 비운다. */
	public void changePrimarySupplierProduct(Long supplierProductId) {
		this.primarySupplierProductId = supplierProductId;
	}
}
