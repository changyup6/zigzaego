package com.zigzaego.api.supplier.service;

import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.store.ResourceIds;
import com.zigzaego.api.supplier.dto.SupplierCreateRequest;
import com.zigzaego.api.supplier.dto.SupplierResponse;
import com.zigzaego.api.supplier.dto.SupplierUpdateRequest;
import com.zigzaego.api.supplier.entity.Supplier;
import com.zigzaego.api.supplier.exception.SupplierErrorCode;
import com.zigzaego.api.supplier.repository.SupplierRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 거래처 CRUD. 모든 조회 · 쓰기를 로그인한 가게로 좁힌다 — 다른 가게 거래처 · 없는 id · 숫자가 아닌 id 는 모두 404 (ADR-0002).
 * 이름 말고는 모두 비울 수 있다. 영수증으로 처음 생긴 거래처는 채널 · 리드타임 · 발주 주기를 모른다 (설계 6절).
 */
@Service
@RequiredArgsConstructor
public class SupplierService {

	/** 거래처 상품 · 발주서 · 매입 문서는 거래처를 지우면 갈 곳이 없어지는 기록이라 삭제를 막는다. */
	private static final String IN_USE_SQL = """
			SELECT EXISTS (SELECT 1 FROM supplier_product WHERE store_id = :storeId AND supplier_id = :supplierId)
			    OR EXISTS (SELECT 1 FROM purchase_order WHERE store_id = :storeId AND supplier_id = :supplierId)
			    OR EXISTS (SELECT 1 FROM purchase_doc WHERE store_id = :storeId AND supplier_id = :supplierId)
			""";

	private final SupplierRepository supplierRepository;
	private final NamedParameterJdbcTemplate jdbc;

	@Transactional(readOnly = true)
	public List<SupplierResponse> listForStore(long storeId) {
		return supplierRepository.findAllByStoreIdOrderByIdAsc(storeId).stream().map(SupplierResponse::of).toList();
	}

	@Transactional(readOnly = true)
	public SupplierResponse getForStore(long storeId, String rawId) {
		return SupplierResponse.of(supplierRepository.getInStore(ResourceIds.parse(rawId), storeId));
	}

	/**
	 * @throws BusinessException 같은 이름의 거래처가 있으면 409 SUPPLIER_DUPLICATE_NAME
	 */
	@Transactional
	public SupplierResponse create(long storeId, SupplierCreateRequest request) {
		String name = request.name().strip();
		requireUniqueName(storeId, name);
		Supplier supplier = Supplier.create(storeId, name);
		supplier.changeChannel(request.channel());
		supplier.changeLeadTimeDays(request.leadTimeDays());
		supplier.changeOrderCycleDays(request.orderCycleDays());
		supplier.changeCutoffTime(request.cutoffTime());
		if (request.deliveryDays() != null) {
			supplier.changeDeliveryDays(request.deliveryDays());
		}
		supplier.changeMinOrderAmount(request.minOrderAmount());
		supplier.changeMessageTemplate(request.messageTemplate());
		supplier.changeEmail(request.email());
		supplier.changeShippingFee(request.shippingFee());
		supplier.changeFreeShippingMin(request.freeShippingMin());
		return SupplierResponse.of(supplierRepository.save(supplier));
	}

	@Transactional
	public SupplierResponse update(long storeId, String rawId, SupplierUpdateRequest request) {
		Supplier supplier = supplierRepository.getInStore(ResourceIds.parse(rawId), storeId);
		if (request.name() != null) {
			String name = request.name().strip();
			if (!name.equals(supplier.getName())) {
				requireUniqueName(storeId, name);
				supplier.rename(name);
			}
		}
		if (request.channel() != null) {
			supplier.changeChannel(request.channel());
		}
		if (request.leadTimeDays() != null) {
			supplier.changeLeadTimeDays(request.leadTimeDays());
		}
		if (request.orderCycleDays() != null) {
			supplier.changeOrderCycleDays(request.orderCycleDays());
		}
		if (request.cutoffTime() != null) {
			supplier.changeCutoffTime(request.cutoffTime());
		}
		if (request.deliveryDays() != null) {
			supplier.changeDeliveryDays(request.deliveryDays());
		}
		if (request.minOrderAmount() != null) {
			supplier.changeMinOrderAmount(request.minOrderAmount());
		}
		if (request.messageTemplate() != null) {
			supplier.changeMessageTemplate(request.messageTemplate());
		}
		if (request.email() != null) {
			supplier.changeEmail(request.email());
		}
		if (request.shippingFee() != null) {
			supplier.changeShippingFee(request.shippingFee());
		}
		if (request.freeShippingMin() != null) {
			supplier.changeFreeShippingMin(request.freeShippingMin());
		}
		return SupplierResponse.of(supplier);
	}

	/**
	 * @throws BusinessException 거래처 상품 · 발주서 · 매입 문서가 달려 있으면 409 SUPPLIER_IN_USE
	 */
	@Transactional
	public void delete(long storeId, String rawId) {
		Supplier supplier = supplierRepository.getInStore(ResourceIds.parse(rawId), storeId);
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("storeId", storeId)
				.addValue("supplierId", supplier.getId());
		if (Boolean.TRUE.equals(jdbc.queryForObject(IN_USE_SQL, params, Boolean.class))) {
			throw new BusinessException(SupplierErrorCode.IN_USE);
		}
		supplierRepository.delete(supplier);
	}

	private void requireUniqueName(long storeId, String name) {
		if (supplierRepository.existsByStoreIdAndName(storeId, name)) {
			throw new BusinessException(SupplierErrorCode.DUPLICATE_NAME);
		}
	}
}
