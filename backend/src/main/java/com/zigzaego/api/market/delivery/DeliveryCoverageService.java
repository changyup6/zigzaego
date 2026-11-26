package com.zigzaego.api.market.delivery;

import com.zigzaego.api.account.entity.Store;
import com.zigzaego.api.account.repository.StoreRepository;
import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 가게 주소(시군구) → 배달 가능 판매처 판정 (S15P31A308-230). 가게는 id 가 곧 스코프다(ADR-0002). */
@Service
@RequiredArgsConstructor
public class DeliveryCoverageService {

	private final StoreRepository storeRepository;
	private final MarketSiteDeliveryAreaJdbcRepository areaRepository;

	/**
	 * @throws BusinessException 가게가 없으면 404 COMMON_NOT_FOUND
	 */
	@Transactional(readOnly = true)
	public DeliveryCoverage coverageFor(long storeId) {
		Store store = storeRepository.findById(storeId).orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
		return DeliveryCoverage.of(areaRepository.findAll(), store.getSigungu());
	}
}
