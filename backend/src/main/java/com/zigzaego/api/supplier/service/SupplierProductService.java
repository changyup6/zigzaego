package com.zigzaego.api.supplier.service;

import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.store.ResourceIds;
import com.zigzaego.api.ingredient.entity.Ingredient;
import com.zigzaego.api.ingredient.repository.IngredientRepository;
import com.zigzaego.api.purchaseorder.service.PackSizeFillRescaler;
import com.zigzaego.api.supplier.dto.SupplierProductCreateRequest;
import com.zigzaego.api.supplier.dto.SupplierProductResponse;
import com.zigzaego.api.supplier.dto.SupplierProductUpdateRequest;
import com.zigzaego.api.supplier.entity.Supplier;
import com.zigzaego.api.supplier.entity.SupplierProduct;
import com.zigzaego.api.supplier.exception.SupplierErrorCode;
import com.zigzaego.api.supplier.repository.SupplierProductRepository;
import com.zigzaego.api.supplier.repository.SupplierRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 거래처 상품 CRUD. 거래처 · 재료 · 상품 모두 로그인한 가게로 좁힌다 — 다른 가게 id 는 모두 404 (ADR-0002).
 *
 * <ul>
 *   <li>영수증 상품명 사전(설계 4.2절): 한 거래처 안에서 상품명은 하나(409), 목록은 상품명 그대로 찾을 수 있다</li>
 *   <li>단가: 처음 정하거나 지금 값과 다르게 바꾸면 price_history 에 MANUAL 한 줄 (3주 계획 5절)</li>
 *   <li>포장 환산: base_qty_per_pack 로 포장 수량 → 재료 기준 단위 (설계 3절, {@link SupplierProduct#toBaseQty})</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class SupplierProductService {

	/** 포장 단위를 모를 때 단가 기록의 단위. 단가는 늘 포장 1개 값이라 재료 기준 단위(g · ml)를 적으면 g 당 단가로 읽힌다. */
	static final String UNKNOWN_PACK_UNIT = "PACK";

	/** 단가 기록의 단위: 포장 단위 이름, 모르면 {@link #UNKNOWN_PACK_UNIT}. 단가는 포장 1개 값이다. */
	private static final String INSERT_PRICE_SQL = """
			INSERT INTO price_history (store_id, supplier_product_id, price, unit, observed_at, source)
			VALUES (:storeId, :productId, :price, :unit, now(), 'MANUAL')
			""";

	/** 추천 · 발주 · 매입 줄은 상품을 지우면 근거가 사라지는 기록이라 삭제를 막는다. 단가 이력은 상품과 함께 지운다. */
	private static final String IN_USE_SQL = """
			SELECT EXISTS (SELECT 1 FROM purchase_order_item WHERE store_id = :storeId AND supplier_product_id = :productId)
			    OR EXISTS (SELECT 1 FROM purchase_doc_line WHERE store_id = :storeId AND supplier_product_id = :productId)
			    OR EXISTS (SELECT 1 FROM recommendation WHERE store_id = :storeId AND supplier_product_id = :productId)
			""";

	private static final String DELETE_PRICES_SQL =
			"DELETE FROM price_history WHERE store_id = :storeId AND supplier_product_id = :productId";

	private final SupplierProductRepository productRepository;
	private final SupplierRepository supplierRepository;
	private final IngredientRepository ingredientRepository;
	private final PackSizeFillRescaler packSizeFillRescaler;
	private final NamedParameterJdbcTemplate jdbc;

	/** 조건은 모두 고를 수 있다. 상품명은 앞뒤 공백을 빼고 똑같은 것만. 다른 가게 거래처 · 재료 id 면 404. */
	@Transactional(readOnly = true)
	public List<SupplierProductResponse> listForStore(long storeId, String rawSupplierId, String rawIngredientId,
			String productName) {
		Long supplierId = rawSupplierId == null ? null
				: supplierRepository.getInStore(ResourceIds.parse(rawSupplierId), storeId).getId();
		Long ingredientId = rawIngredientId == null ? null
				: ingredientRepository.getInStore(ResourceIds.parse(rawIngredientId), storeId).getId();
		String name = productName == null || productName.isBlank() ? null : productName.strip();
		// 상품명만 주는 영수증 사전 조회는 인덱스를 타는 단순 쿼리로 (IS NULL 조건 쿼리는 일반 계획에서 인덱스를 못 탄다)
		List<SupplierProduct> products = supplierId == null && ingredientId == null && name != null
				? productRepository.findAllByStoreIdAndProductNameOrderByIdAsc(storeId, name)
				: productRepository.search(storeId, supplierId, ingredientId, name);
		if (products.isEmpty()) {
			return List.of();
		}
		Map<Long, Supplier> suppliers = supplierRepository
				.findAllByStoreIdAndIdIn(storeId, products.stream().map(SupplierProduct::getSupplierId).distinct().toList())
				.stream().collect(Collectors.toMap(Supplier::getId, Function.identity()));
		Map<Long, Ingredient> ingredients = ingredientRepository
				.findAllByStoreIdAndIdIn(storeId, products.stream().map(SupplierProduct::getIngredientId).distinct().toList())
				.stream().collect(Collectors.toMap(Ingredient::getId, Function.identity()));
		return products.stream()
				.map(p -> SupplierProductResponse.of(p, suppliers.get(p.getSupplierId()), ingredients.get(p.getIngredientId())))
				.toList();
	}

	@Transactional(readOnly = true)
	public SupplierProductResponse getForStore(long storeId, String rawId) {
		return toResponse(storeId, productRepository.getInStore(ResourceIds.parse(rawId), storeId));
	}

	/**
	 * @throws BusinessException 거래처 · 재료가 없거나 다른 가게면 404, 그 거래처에 같은 상품명이 있으면 409
	 *         SUPPLIER_PRODUCT_DUPLICATE_NAME
	 */
	@Transactional
	public SupplierProductResponse create(long storeId, SupplierProductCreateRequest request) {
		Supplier supplier = supplierRepository.getInStore(ResourceIds.parse(request.supplierId()), storeId);
		Ingredient ingredient = ingredientRepository.getInStore(ResourceIds.parse(request.ingredientId()), storeId);
		String productName = request.productName().strip();
		requireUniqueName(storeId, supplier.getId(), productName);

		SupplierProduct product = SupplierProduct.create(storeId, supplier.getId(), ingredient.getId(), productName);
		product.changePackSize(request.packSize());
		product.changePackUnit(blankToNull(request.packUnit()));
		product.changeMoq(request.moq());
		product.changeBaseQtyPerPack(request.baseQtyPerPack());
		product = productRepository.save(product);
		if (request.price() != null) {
			changePrice(product, ingredient, request.price());
		}
		if (Boolean.TRUE.equals(request.primary())) {
			ingredient.changePrimarySupplierProduct(product.getId());
		}
		return SupplierProductResponse.of(product, supplier, ingredient);
	}

	@Transactional
	public SupplierProductResponse update(long storeId, String rawId, SupplierProductUpdateRequest request) {
		SupplierProduct product = productRepository.getInStore(ResourceIds.parse(rawId), storeId);
		Ingredient ingredient = ingredientRepository.getInStore(product.getIngredientId(), storeId);
		if (request.productName() != null) {
			String productName = request.productName().strip();
			if (!productName.equals(product.getProductName())) {
				requireUniqueName(storeId, product.getSupplierId(), productName);
				product.rename(productName);
			}
		}
		if (request.packSize() != null) {
			product.changePackSize(request.packSize());
		}
		if (request.packUnit() != null) {
			product.changePackUnit(blankToNull(request.packUnit()));
		}
		if (request.moq() != null) {
			product.changeMoq(request.moq());
		}
		if (request.baseQtyPerPack() != null) {
			boolean filling = product.getBaseQtyPerPack() == null;
			product.changeBaseQtyPerPack(request.baseQtyPerPack());
			if (filling) {
				// 비어 있다 채우면 그동안의 발주 줄(qty = 기준 단위 양)을 포장 개수로 (지라 301). 값 → 값 수정은 정정이라 줄은 그대로
				packSizeFillRescaler.filled(storeId, product.getId(), request.baseQtyPerPack());
			}
		}
		if (request.price() != null && product.priceDiffers(request.price())) {
			changePrice(product, ingredient, request.price());
		}
		if (Boolean.TRUE.equals(request.primary())) {
			ingredient.changePrimarySupplierProduct(product.getId());
		} else if (Boolean.FALSE.equals(request.primary()) && ingredient.isPrimarySupplierProduct(product.getId())) {
			ingredient.changePrimarySupplierProduct(null);
		}
		return SupplierProductResponse.of(product, supplierRepository.getInStore(product.getSupplierId(), storeId),
				ingredient);
	}

	/**
	 * 상품과 그 단가 이력을 지운다. 재료의 대표 상품이었으면 대표 상품을 비운다.
	 *
	 * @throws BusinessException 추천 · 발주 · 매입 줄에 쓰였으면 409 SUPPLIER_PRODUCT_IN_USE
	 */
	@Transactional
	public void delete(long storeId, String rawId) {
		SupplierProduct product = productRepository.getInStore(ResourceIds.parse(rawId), storeId);
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("storeId", storeId)
				.addValue("productId", product.getId());
		if (Boolean.TRUE.equals(jdbc.queryForObject(IN_USE_SQL, params, Boolean.class))) {
			throw new BusinessException(SupplierErrorCode.PRODUCT_IN_USE);
		}
		Ingredient ingredient = ingredientRepository.getInStore(product.getIngredientId(), storeId);
		if (ingredient.isPrimarySupplierProduct(product.getId())) {
			ingredient.changePrimarySupplierProduct(null);
		}
		jdbc.update(DELETE_PRICES_SQL, params);
		// Hibernate 는 UPDATE(대표 상품 비우기)를 DELETE 보다 먼저 내보낸다 — FK 가 걸리지 않는다
		productRepository.delete(product);
	}

	private void changePrice(SupplierProduct product, Ingredient ingredient, BigDecimal price) {
		product.changePrice(price);
		String unit = product.getPackUnit() != null ? product.getPackUnit() : UNKNOWN_PACK_UNIT;
		jdbc.update(INSERT_PRICE_SQL, new MapSqlParameterSource()
				.addValue("storeId", product.getStoreId())
				.addValue("productId", product.getId())
				.addValue("price", price)
				.addValue("unit", unit));
	}

	private SupplierProductResponse toResponse(long storeId, SupplierProduct product) {
		return SupplierProductResponse.of(product,
				supplierRepository.getInStore(product.getSupplierId(), storeId),
				ingredientRepository.getInStore(product.getIngredientId(), storeId));
	}

	private void requireUniqueName(long storeId, long supplierId, String productName) {
		if (productRepository.existsByStoreIdAndSupplierIdAndProductName(storeId, supplierId, productName)) {
			throw new BusinessException(SupplierErrorCode.DUPLICATE_PRODUCT_NAME);
		}
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}
}
