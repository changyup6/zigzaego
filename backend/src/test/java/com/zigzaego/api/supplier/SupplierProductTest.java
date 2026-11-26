package com.zigzaego.api.supplier;

import static org.assertj.core.api.Assertions.assertThat;

import com.zigzaego.api.supplier.entity.SupplierProduct;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** 포장 단위 → 재료 기준 단위 변환 (설계 3절). */
class SupplierProductTest {

	@Test
	void 대파_1단이_1000g이면_2단은_2000g() {
		SupplierProduct product = SupplierProduct.create(1L, 1L, 1L, "대파 1단");
		product.changeBaseQtyPerPack(new BigDecimal("1000"));

		assertThat(product.toBaseQty(new BigDecimal("2"))).hasValueSatisfying(
				qty -> assertThat(qty).isEqualByComparingTo("2000"));
	}

	@Test
	void 반_단처럼_소수_포장도_바꾼다() {
		SupplierProduct product = SupplierProduct.create(1L, 1L, 1L, "대파 1단");
		product.changeBaseQtyPerPack(new BigDecimal("1000"));

		assertThat(product.toBaseQty(new BigDecimal("0.5"))).hasValueSatisfying(
				qty -> assertThat(qty).isEqualByComparingTo("500"));
	}

	@Test
	void 포장_환산을_모르면_비어_있다() {
		SupplierProduct product = SupplierProduct.create(1L, 1L, 1L, "대파 1단");

		assertThat(product.toBaseQty(new BigDecimal("2"))).isEmpty();
	}
}
