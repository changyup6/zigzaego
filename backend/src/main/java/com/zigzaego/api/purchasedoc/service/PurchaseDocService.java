package com.zigzaego.api.purchasedoc.service;

import com.zigzaego.api.purchasedoc.entity.PurchaseDoc;
import com.zigzaego.api.purchasedoc.repository.PurchaseDocRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 매입 문서(영수증). 조회 API 와 응답 모양은 영수증 업로드 job(S15P31A308-111)에서 정한다. */
@Service
@RequiredArgsConstructor
public class PurchaseDocService {

	private final PurchaseDocRepository purchaseDocRepository;

	/**
	 * 현재 가게의 매입 문서 한 장.
	 *
	 * @throws com.zigzaego.api.global.exception.BusinessException 없거나 다른 가게 문서면 404 COMMON_NOT_FOUND (ADR-0002)
	 */
	@Transactional(readOnly = true)
	public PurchaseDoc getForStore(long storeId, long id) {
		return purchaseDocRepository.getInStore(id, storeId);
	}
}
