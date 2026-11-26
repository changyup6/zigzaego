package com.zigzaego.api.purchaseorder.service;

import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 비어 있던 포장 환산이 채워질 때 그 상품의 발주 줄을 포장 단위로 바꿔, 기준 단위 양이 소급해 바뀌지 않게 한다 (S15P31A308-301).
 *
 * <p>포장 환산({@code supplier_product.base_qty_per_pack})을 모를 때 만든 발주 줄은 {@code qty} 에 기준 단위 양을 그대로 넣는다
 * ({@link PurchaseOrderService#toPackQty}) — 읽는 쪽은 전부 {@code qty × COALESCE(base_qty_per_pack, 1)} 이다. 나중에 포장 환산이
 * 채워지면 같은 줄이 × 포장 크기로 읽혀 입고 예정 · 입고량이 커진다(대파 2000 g → 2,000,000 g). 그래서 채우는 순간
 * {@code qty} · {@code received_qty} 를 포장 크기로 나누고 {@code received_unit_price}(기준 단위 1 값)는 곱해 포장 1개 값으로 둔다.
 *
 * <p>값 → 다른 값 수정은 부르지 않는다. 그때 줄의 qty 는 실제로 주문한 포장 개수라(메시지에 "2단"으로 나갔다) 포장 크기를 고친 것은
 * 정정이고, 줄을 바꾸면 오히려 틀린다. 소수 셋째 자리 반올림이라 나누어떨어지지 않으면 포장 크기 × 0.0005 까지 어긋난다
 * (입고 확인의 포장 개수 반올림과 같은 허용치). 0 으로 반올림되는 줄은 0.001 로 남긴다.
 *
 * <p>부르는 쪽 트랜잭션에 참여한다. 포장 환산을 실제로 null → 값으로 바꾼 경로에서만 부른다.
 */
@Component
@RequiredArgsConstructor
public class PackSizeFillRescaler {

	private static final String RESCALE_SQL = """
			UPDATE purchase_order_item
			SET qty = CASE WHEN qty > 0 THEN GREATEST(ROUND(qty / :pack, 3), 0.001) ELSE qty END,
			    received_qty = CASE WHEN received_qty > 0 THEN GREATEST(ROUND(received_qty / :pack, 3), 0.001)
			                        ELSE received_qty END,
			    received_unit_price = ROUND(received_unit_price * :pack, 0)
			WHERE store_id = :storeId AND supplier_product_id = :productId
			""";

	private final NamedParameterJdbcTemplate jdbc;

	/**
	 * @param packSize 새로 채운 포장 1개 = 기준 단위 양. 0 보다 커야 한다
	 * @return 바꾼 발주 줄 수
	 */
	public int filled(long storeId, long productId, BigDecimal packSize) {
		if (packSize == null || packSize.signum() <= 0) {
			throw new IllegalArgumentException("packSize must be > 0");
		}
		if (packSize.compareTo(BigDecimal.ONE) == 0) {
			return 0; // × 1 이라 읽는 값이 그대로다
		}
		return jdbc.update(RESCALE_SQL, new MapSqlParameterSource()
				.addValue("storeId", storeId)
				.addValue("productId", productId)
				.addValue("pack", packSize));
	}
}
