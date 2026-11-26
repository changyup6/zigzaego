package com.zigzaego.api.supplier.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 거래처가 파는 상품 하나. 표는 V1 {@code supplier_product}. 영수증 상품명 → 재료 사전 역할도 한다 (설계 4.2절).
 * 한 거래처 안에서 상품명은 하나 (uq_supplier_product_supplier_name).
 * 포장 · MOQ · 단가 · 포장 환산은 영수증으로 처음 생긴 상품이면 모를 수 있어 null 이다 (설계 6절).
 */
@Entity
@Table(name = "supplier_product")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SupplierProduct {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "store_id", nullable = false)
	private Long storeId;

	@Column(name = "supplier_id", nullable = false)
	private Long supplierId;

	@Column(name = "ingredient_id", nullable = false)
	private Long ingredientId;

	@Column(name = "product_name", nullable = false, length = 150)
	private String productName;

	@Column(name = "pack_size", precision = 14, scale = 3)
	private BigDecimal packSize;

	/** 포장 단위 이름 (단 · 박스 · 망). */
	@Column(name = "pack_unit", length = 20)
	private String packUnit;

	@Column(precision = 14, scale = 3)
	private BigDecimal moq;

	/** 현재 단가(원, 포장 1개). 바뀌면 price_history 에 한 줄 (3주 계획 5절). 원 단위 정수만 넣는다 (DECISIONS #55). */
	@Column(precision = 12, scale = 2)
	private BigDecimal price;

	/** 포장 1개 = 재료 기준 단위 몇 (대파 1단 → 1000 g). 모르면 null (설계 3절). */
	@Column(name = "base_qty_per_pack", precision = 14, scale = 3)
	private BigDecimal baseQtyPerPack;

	public static SupplierProduct create(long storeId, long supplierId, long ingredientId, String productName) {
		SupplierProduct product = new SupplierProduct();
		product.storeId = storeId;
		product.supplierId = supplierId;
		product.ingredientId = ingredientId;
		product.productName = productName;
		return product;
	}

	/**
	 * 포장 수량을 재료 기준 단위로 바꾼다 (대파 1단 = 1000 g 이면 2단 → 2000 g).
	 *
	 * @return 포장 환산(base_qty_per_pack)을 모르면 empty
	 */
	public Optional<BigDecimal> toBaseQty(BigDecimal packQty) {
		return Optional.ofNullable(baseQtyPerPack).map(packQty::multiply);
	}

	/** 단가가 지금 값과 다른지. 처음 정하는 단가(지금 null)도 바뀐 것이다. */
	public boolean priceDiffers(BigDecimal price) {
		return this.price == null || this.price.compareTo(price) != 0;
	}

	public void rename(String productName) {
		this.productName = productName;
	}

	public void changePackSize(BigDecimal packSize) {
		this.packSize = packSize;
	}

	public void changePackUnit(String packUnit) {
		this.packUnit = packUnit;
	}

	public void changeMoq(BigDecimal moq) {
		this.moq = moq;
	}

	public void changePrice(BigDecimal price) {
		this.price = price;
	}

	/** 영수증 확정에서 사장님이 고친 매칭이 사전을 덮는다 (설계 4.2절, 지라 112). 과거 문서 줄은 자기 재료를 그대로 가진다. */
	public void relinkIngredient(long ingredientId) {
		this.ingredientId = ingredientId;
	}

	public void changeBaseQtyPerPack(BigDecimal baseQtyPerPack) {
		this.baseQtyPerPack = baseQtyPerPack;
	}
}
