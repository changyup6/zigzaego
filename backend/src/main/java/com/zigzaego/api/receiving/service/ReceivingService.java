package com.zigzaego.api.receiving.service;

import static com.zigzaego.api.global.response.Amounts.qty;

import com.zigzaego.api.global.config.StoreClock;
import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.exception.CommonErrorCode;
import com.zigzaego.api.global.store.ResourceIds;
import com.zigzaego.api.ingredient.entity.IngredientUnit;
import com.zigzaego.api.onboarding.dto.RecipeUnit;
import com.zigzaego.api.price.dto.UnitPrices;
import com.zigzaego.api.purchaseorder.service.PackSizeFillRescaler;
import com.zigzaego.api.receiving.dto.PackReceipt;
import com.zigzaego.api.receiving.dto.ReceiveRequest;
import com.zigzaego.api.receiving.dto.ReceivingFeedResponse;
import com.zigzaego.api.receiving.dto.ReceivingFeedResponse.HistoryLine;
import com.zigzaego.api.receiving.dto.ReceivingFeedResponse.HistoryStatus;
import com.zigzaego.api.receiving.dto.ReceivingFeedResponse.IncomingLine;
import com.zigzaego.api.receiving.exception.ReceivingErrorCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 입고 탭 피드와 발주 줄 입고 확인 (S15P31A308-71, docs/plans/2026-10-19_입고_확인_API_제안.md).
 *
 * <p>입고 확인 = 한 트랜잭션에서 (제안 문서 2절 "서버에서 일어나는 일")
 * <ol>
 *   <li>{@code purchase_order_item.received_qty · received_unit_price · received_at} 기록 (DB 칸은 포장 단위 —
 *       주소 경로는 기준 단위 요청을 포장 개수 · 포장 1개 원 정수로 바꿔 쓴다, DECISIONS #42 · #57 · #55)</li>
 *   <li>재고는 따로 쓰지 않는다 — 추정 재고가 received_qty × base_qty_per_pack 를 이미 더한다 (DECISIONS #41 ①)</li>
 *   <li>{@code price_history} source=RECEIVE 1행. 포장 1개 단가, 단위는 포장 이름(모르면 PACK) —
 *       {@code SupplierProductService} 의 MANUAL 기록과 같은 규칙. 단가를 안 바꿔도 1행(실제로 산 값)</li>
 *   <li>그 재료의 첫 입고면 {@code ingredient.unit_status = LOCKED} (설계 3.1절)</li>
 * </ol>
 * 영수증 줄 확정(112)이 같은 입고 예정 줄을 찾으면 {@link #recordReceipt} 를 부른다 (주소 없음).
 *
 * <p>모든 SQL 에 store_id 를 건다 (ADR-0002). 날짜 경계는 가게 시간대({@code store.timezone}).
 */
@Service
@RequiredArgsConstructor
public class ReceivingService {

	/** 최근 발주 범위: 오늘 포함 14일 · 최대 50줄 (제안 문서 4절 3번 제안값). */
	static final int HISTORY_DAYS = 14;
	static final int HISTORY_LIMIT = 50;

	/** 포장 단위를 모를 때 단가 기록의 단위 (SupplierProductService.UNKNOWN_PACK_UNIT 와 같은 값). */
	static final String UNKNOWN_PACK_UNIT = "PACK";

	private static final int QTY_SCALE = 3;

	private static final String TIMEZONE_SQL = "SELECT timezone FROM store WHERE id = :storeId";

	/** 입고 예정 = 보낸(SENT) 발주서의, 입고 확인 안 되고 안 옴 처리도 안 된 줄 (V1 purchase_order_item.cancelled_at COMMENT). DRAFT 는 메시지만 만든 것이라 빼고(DECISIONS #63), CANCELLED 도 뺀다. */
	private static final String INCOMING_SQL = """
			SELECT poi.id, poi.po_id, poi.ingredient_id, sp.product_name,
			       COALESCE(s.name, CASE po.source WHEN 'SIKBOM' THEN '식봄' WHEN 'MARKETBOM' THEN '마켓봄' WHEN 'ORDERLINK' THEN '오더링크' ELSE '쿠팡' END) AS supplier_name, sp.pack_unit,
			       sp.base_qty_per_pack, i.unit, poi.qty, CASE WHEN po.source = 'SUPPLIER' THEN COALESCE(poi.unit_price, sp.price) ELSE poi.unit_price END AS price, poi.expected_date
			FROM purchase_order_item poi
			JOIN purchase_order po ON po.id = poi.po_id AND po.store_id = :storeId
			LEFT JOIN supplier s ON s.id = po.supplier_id AND s.store_id = :storeId
			JOIN supplier_product sp ON sp.id = poi.supplier_product_id AND sp.store_id = :storeId
			JOIN ingredient i ON i.id = poi.ingredient_id AND i.store_id = :storeId
			WHERE poi.store_id = :storeId
			  AND poi.received_at IS NULL AND poi.cancelled_at IS NULL
			  AND po.status = 'SENT'
			ORDER BY poi.expected_date NULLS LAST, poi.id
			""";

	private static final String HISTORY_SQL = """
			SELECT poi.id, poi.po_id, po.created_at, sp.product_name, i.unit, r.recommended_qty, sp.base_qty_per_pack,
			       poi.qty, COALESCE(s.name, CASE po.source WHEN 'SIKBOM' THEN '식봄' WHEN 'MARKETBOM' THEN '마켓봄' WHEN 'ORDERLINK' THEN '오더링크' ELSE '쿠팡' END) AS supplier_name, po.status, poi.cancelled_at, poi.received_qty, poi.received_at
			FROM purchase_order_item poi
			JOIN purchase_order po ON po.id = poi.po_id AND po.store_id = :storeId
			LEFT JOIN supplier s ON s.id = po.supplier_id AND s.store_id = :storeId
			JOIN supplier_product sp ON sp.id = poi.supplier_product_id AND sp.store_id = :storeId
			JOIN ingredient i ON i.id = poi.ingredient_id AND i.store_id = :storeId
			LEFT JOIN recommendation r ON r.id = poi.recommendation_id AND r.store_id = :storeId
			WHERE poi.store_id = :storeId AND po.created_at >= :since
			ORDER BY po.created_at DESC, poi.id DESC
			LIMIT :limit
			""";

	/** 첫 입고 여부. 가게 체크리스트(StoreService has_receive)와 같은 정의. */
	private static final String FIRST_RECEIPT_SQL = """
			SELECT EXISTS (SELECT 1 FROM purchase_order_item poi
			               WHERE poi.store_id = :storeId AND poi.received_at IS NOT NULL)
			    OR EXISTS (SELECT 1 FROM purchase_doc pd
			               WHERE pd.store_id = :storeId AND pd.kind = 'RECEIVE' AND pd.parse_status = 'CONFIRMED')
			""";

	/** 같은 줄을 동시에 두 번 확인하지 않게 줄을 잠그고 읽는다. */
	private static final String LOCK_ITEM_SQL = """
			SELECT poi.po_id, poi.ingredient_id, poi.supplier_product_id,
			       poi.received_at IS NOT NULL AS received, poi.auto_received,
			       (poi.cancelled_at IS NOT NULL OR po.status = 'CANCELLED') AS cancelled,
			       CASE WHEN po.source = 'SUPPLIER' THEN COALESCE(poi.unit_price, sp.price) ELSE poi.unit_price END AS price,
			       po.source <> 'SUPPLIER' AS market_order, sp.pack_unit, sp.base_qty_per_pack
			FROM purchase_order_item poi
			JOIN purchase_order po ON po.id = poi.po_id AND po.store_id = :storeId
			JOIN supplier_product sp ON sp.id = poi.supplier_product_id AND sp.store_id = :storeId
			WHERE poi.id = :itemId AND poi.store_id = :storeId
			FOR UPDATE OF poi
			""";

	private static final String RECORD_RECEIPT_SQL = """
			UPDATE purchase_order_item
			SET received_qty = :receivedQty, received_unit_price = :unitPrice, received_at = :receivedAt,
			    auto_received = :autoReceived
			WHERE id = :itemId AND store_id = :storeId
			""";

	private static final String INSERT_PRICE_SQL = """
			INSERT INTO price_history (store_id, supplier_product_id, price, unit, observed_at, source)
			VALUES (:storeId, :productId, :price, :unit, :observedAt, 'RECEIVE')
			""";

	/**
	 * 포장 크기가 빈 거래처 상품에 요청 basePerPack 을 채운다. 비어 있을 때만 — 다른 요청이 먼저 채웠으면 0행이고,
	 * 그때는 채워진 값을 다시 읽어 쓴다(READ_PACK_SIZE_SQL).
	 */
	private static final String FILL_PACK_SIZE_SQL = """
			UPDATE supplier_product SET base_qty_per_pack = :basePerPack
			WHERE id = :productId AND store_id = :storeId AND base_qty_per_pack IS NULL
			""";

	private static final String READ_PACK_SIZE_SQL = """
			SELECT base_qty_per_pack FROM supplier_product WHERE id = :productId AND store_id = :storeId
			""";

	/** 이미 LOCKED 면 그대로 — 첫 입고에서만 바뀐다. */
	private static final String LOCK_UNIT_SQL = """
			UPDATE ingredient SET unit_status = 'LOCKED'
			WHERE id = :ingredientId AND store_id = :storeId AND unit_status <> 'LOCKED'
			""";

	private final NamedParameterJdbcTemplate jdbc;
	private final StoreClock clock;
	private final Validator validator;
	private final PackSizeFillRescaler packSizeFillRescaler;

	/** 입고 탭 한 화면치. */
	@Transactional(readOnly = true)
	public ReceivingFeedResponse feed(long storeId) {
		ZoneId zone = storeZone(storeId);
		LocalDate today = LocalDate.now(clock.of(storeId).withZone(zone));
		MapSqlParameterSource params = new MapSqlParameterSource("storeId", storeId);

		List<IncomingLine> incoming = jdbc.query(INCOMING_SQL, params, (rs, i) -> incomingLine(rs, today));
		MapSqlParameterSource historyParams = new MapSqlParameterSource("storeId", storeId)
				.addValue("since", today.minusDays(HISTORY_DAYS - 1L).atStartOfDay(zone).toOffsetDateTime())
				.addValue("limit", HISTORY_LIMIT);
		List<HistoryLine> history = jdbc.query(HISTORY_SQL, historyParams, (rs, i) -> historyLine(rs, zone));
		boolean firstReceiptDone = Boolean.TRUE.equals(jdbc.queryForObject(FIRST_RECEIPT_SQL, params, Boolean.class));
		return new ReceivingFeedResponse(incoming, history, firstReceiptDone);
	}

	/**
	 * 주소로 들어온 입고 확인. 발주서 id 와 줄 id 가 맞아야 한다.
	 *
	 * @return 바뀐 피드 전체
	 * @throws BusinessException 남의 가게 · 없는 · 숫자 아닌 id 이거나 줄이 그 발주서 것이 아니면 404 COMMON_NOT_FOUND,
	 *         {@link ReceivingErrorCode} 409
	 */
	@Transactional
	public ReceivingFeedResponse receive(long storeId, String rawOrderId, String rawItemId, ReceiveRequest request) {
		long orderId = ResourceIds.parse(rawOrderId);
		long itemId = ResourceIds.parse(rawItemId);
		LockedItem item = lockOpenItem(storeId, orderId, itemId);
		BigDecimal packSize = packSize(storeId, item, request.basePerPack());
		write(storeId, itemId, item, toPacks(request.receivedQty(), packSize),
				toPackPrice(request.unitPrice(), packSize), true, null, false);
		return feed(storeId);
	}

	/**
	 * 포장 1개 = 기준 단위 몇. 거래처 상품 값이 있으면 그것(요청 basePerPack 은 쓰지 않는다), 비어 있으면 요청 값을
	 * 상품에 채우고 쓴다(그 상품의 발주 줄은 {@link PackSizeFillRescaler} 로 포장 개수로 바꾼다). 둘 다 없으면 400 RECEIVING_UNIT_REQUIRED (113 묶음 확정의 RECEIPT_UNIT_REQUIRED 와 같은 방식).
	 * 채운 뒤 아래 환산이 400 이면 주소 경로 트랜잭션이 통째로 롤백된다.
	 */
	private BigDecimal packSize(long storeId, LockedItem item, BigDecimal requested) {
		if (item.basePerPack() != null) {
			return item.basePerPack();
		}
		if (requested == null) {
			throw new BusinessException(ReceivingErrorCode.UNIT_REQUIRED);
		}
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("storeId", storeId)
				.addValue("productId", item.productId())
				.addValue("basePerPack", requested);
		if (jdbc.update(FILL_PACK_SIZE_SQL, params) == 1) {
			// 포장 환산 없이 만든 발주 줄(이 줄 포함)은 qty 가 기준 단위 양이다 — 포장 개수로 바꿔 소급 변경을 막는다 (지라 301)
			packSizeFillRescaler.filled(storeId, item.productId(), requested);
			return requested;
		}
		return jdbc.queryForObject(READ_PACK_SIZE_SQL, params, BigDecimal.class);
	}

	/**
	 * 받은 양(기준 단위) ÷ 포장 크기 → 포장 개수, DB 칸 NUMERIC(14,3) 에 맞춰 소수 셋째 자리 반올림(5 이상 올림, #42 와 같은 규칙).
	 * 추정 재고는 이 값 × 포장 크기를 더하므로, 나누어떨어지지 않으면 최대 포장 크기 × 0.0005 만큼 받은 양과 어긋난다
	 * (3000 g 캔에 1000 g → 0.333캔 → 999 g). 0 으로 반올림되거나 정수부가 칸을 넘으면 400.
	 */
	private static BigDecimal toPacks(BigDecimal receivedQty, BigDecimal packSize) {
		BigDecimal packs = receivedQty.divide(packSize, QTY_SCALE, RoundingMode.HALF_UP);
		if (packs.signum() <= 0) {
			throw invalid("receivedQty=" + receivedQty.toPlainString() + " (포장 1개 " + packSize.toPlainString()
					+ " 의 소수 셋째 자리 아래라 포장 개수가 0 이 됩니다)");
		}
		if (integerDigits(packs) > PackReceipt.PACKS_INTEGER_DIGITS) {
			throw invalid("receivedQty=" + shortValue(receivedQty.toPlainString()) + " (포장 개수가 너무 큽니다)");
		}
		return packs;
	}

	/**
	 * 단위 단가 × 포장 크기 → 포장 1개 값, 원 정수(#55)로 반올림(5 이상 올림). null · 0 은 null(거래처 상품 단가를 쓴다).
	 * 0원으로 반올림되거나 정수부가 칸 NUMERIC(12,2) 을 넘으면 400.
	 */
	private static BigDecimal toPackPrice(BigDecimal unitPrice, BigDecimal packSize) {
		if (unitPrice == null || unitPrice.signum() == 0) {
			return null;
		}
		BigDecimal packPrice = unitPrice.multiply(packSize).setScale(0, RoundingMode.HALF_UP);
		if (packPrice.signum() <= 0) {
			throw invalid("unitPrice=" + unitPrice.toPlainString() + " (포장 1개 값이 0원이 됩니다)");
		}
		if (integerDigits(packPrice) > PackReceipt.PRICE_INTEGER_DIGITS) {
			throw invalid("unitPrice=" + shortValue(unitPrice.toPlainString()) + " (포장 1개 값이 너무 큽니다)");
		}
		return packPrice;
	}

	private static long integerDigits(BigDecimal value) {
		return (long) value.precision() - value.scale();
	}

	private static BusinessException invalid(String detail) {
		return new BusinessException(CommonErrorCode.INVALID_INPUT,
				CommonErrorCode.INVALID_INPUT.getMessage() + " " + detail);
	}

	/**
	 * 입고 예정 줄 하나를 입고 확인한다 — 받은 양 · 단가 · 시각 기록, price_history RECEIVE 1행, 첫 입고면 단위 LOCKED.
	 * 영수증 줄 확정(112)이 같은 줄을 찾았을 때 부르는 공개 메서드. 부르는 쪽 트랜잭션에 참여한다.
	 *
	 * @param receivedPacks    받은 포장 수 (0 보다 크고 소수 셋째 자리까지, 끝자리 0 은 무시 — 4.000 = 4)
	 * @param unitPricePerPack 포장 1개 단가(원 정수, 끝자리 0 은 무시 — DB NUMERIC(12,2) 의 11700.00 = 11700). null · 0 이면 거래처 상품의 지금 단가, 그것도 없으면 단가 없이 기록
	 *                         (price_history 는 쓰지 않는다)
	 * @throws BusinessException 남의 가게 · 없는 줄이면 404 COMMON_NOT_FOUND, 값이 틀리면 400 COMMON_INVALID_INPUT,
	 *         이미 입고 확인했으면 409 RECEIVING_ALREADY_RECEIVED, 취소된 줄 · 발주서면 409 RECEIVING_CANCELLED.
	 *         이 검사들은 모두 쓰기 전에 끝나서(줄 잠금 읽기만 함) 던져도 쓴 것이 없다 — 그래서 부르는 쪽 트랜잭션을
	 *         rollback-only 로 만들지 않는다(noRollbackFor). 영수증 확정은 그 줄만 건너뛰고 나머지를 커밋할 수 있다.
	 *         쓰는 도중의 DB 오류(BusinessException 이 아님)는 그대로 롤백한다.
	 */
	@Transactional(noRollbackFor = BusinessException.class)
	public void recordReceipt(long storeId, long itemId, BigDecimal receivedPacks, BigDecimal unitPricePerPack) {
		recordReceipt(storeId, itemId, receivedPacks, unitPricePerPack, true, null);
	}

	/**
	 * {@link #recordReceipt(long, long, BigDecimal, BigDecimal)} 와 같고, {@code price_history} RECEIVE 행을 쓸지와 입고 날짜를 고른다.
	 * 영수증 확정(112)은 같은 단가를 이미 DOC 행(근거 줄 doc_line_id)으로 쓰므로 false 로 불러 단가 기록이 두 번 들어가지 않게 한다.
	 *
	 * @param writePriceHistory false 면 price_history 를 쓰지 않는다 (received_* · 단위 LOCKED 는 그대로)
	 * @param receivedOn        실제로 받은 날(영수증 문서 날짜, 가게 시간대). null 이면 지금. 있으면 {@code received_at} =
	 *                          min(지금, 그날 끝 시각) — 추정 재고 ①(received_at &gt; 실사 시각)이 영수증 줄의 ②(문서 날짜 ≥ 실사일)와
	 *                          같은 실사 판정이 되고, 주기형 입고일((received_at AT TIME ZONE tz)::date)이 문서 날짜가 된다.
	 *                          지금보다 미래로는 찍지 않는다
	 */
	@Transactional(noRollbackFor = BusinessException.class)
	public void recordReceipt(long storeId, long itemId, BigDecimal receivedPacks, BigDecimal unitPricePerPack,
			boolean writePriceHistory, LocalDate receivedOn) {
		recordReceipt(storeId, itemId, receivedPacks, unitPricePerPack, writePriceHistory, receivedOn, false);
	}

	/**
	 * 위와 같고, 입고가 자동 입고(203 배치)로 채워진 것인지 {@code purchase_order_item.auto_received} 에 쓴다 (S15P31A308-238).
	 * 자동 입고 줄은 나중에 영수증이 {@link #overwriteAutoReceipt} 로 덮을 수 있다. 수동 입고 · 영수증 연결은 false.
	 */
	@Transactional(noRollbackFor = BusinessException.class)
	public void recordReceipt(long storeId, long itemId, BigDecimal receivedPacks, BigDecimal unitPricePerPack,
			boolean writePriceHistory, LocalDate receivedOn, boolean autoReceived) {
		// PackReceipt 생성자가 끝자리 0 을 뗀다 — DB 에서 읽은 11700.00 · 4.000 도 4 · 11700 과 같은 판정
		PackReceipt packs = new PackReceipt(receivedPacks, unitPricePerPack);
		requireValid(packs);
		LockedItem item = lockOpenItem(storeId, null, itemId);
		write(storeId, itemId, item, packs.receivedPacks(), packs.unitPricePerPack(), writePriceHistory, receivedOn,
				autoReceived);
	}

	/**
	 * 자동 입고된 줄의 받은 양 · 단가 · 시각을 영수증 값으로 덮고 {@code auto_received = false} 로 바꾼다 (S15P31A308-238).
	 * 줄을 잠근 뒤 자동 입고 줄이 아니면(그 사이 다른 영수증 확정이 먼저 덮었거나 입고 전 · 취소 — 수동 입고 PATCH 는 이미 입고된 줄에 409 라 덮지 못한다) 아무것도 쓰지 않고 false.
	 * price_history 는 쓰지 않는다 — 영수증 확정이 DOC 행을 이미 썼다. 부르는 쪽 트랜잭션에 참여한다.
	 *
	 * @param receivedOn 영수증 문서 날짜(가게 시간대) — received_at = min(지금, 그날 끝)
	 * @return 덮었으면 true
	 */
	@Transactional(noRollbackFor = BusinessException.class)
	public boolean overwriteAutoReceipt(long storeId, long itemId, BigDecimal receivedPacks, BigDecimal unitPricePerPack,
			LocalDate receivedOn) {
		PackReceipt packs = new PackReceipt(receivedPacks, unitPricePerPack);
		requireValid(packs);
		LockedItem item = lockItem(storeId, itemId);
		if (!item.autoReceived() || !item.received() || item.cancelled()) {
			return false;
		}
		write(storeId, itemId, item, packs.receivedPacks(), packs.unitPricePerPack(), false, receivedOn, false);
		return true;
	}

	/** 받은 날의 끝 시각(가게 시간대, DB 마이크로초 정밀도로 다음날 00:00 직전)과 지금 중 이른 쪽. */
	private OffsetDateTime receivedAt(long storeId, LocalDate receivedOn, OffsetDateTime now) {
		if (receivedOn == null) {
			return now;
		}
		OffsetDateTime endOfDay = receivedOn.plusDays(1).atStartOfDay(storeZone(storeId)).toOffsetDateTime()
				.minusNanos(1_000);
		return endOfDay.isBefore(now) ? endOfDay : now;
	}

	/**
	 * 줄을 잠그고 읽어 입고 확인할 수 있는지 본다 — 없거나 남의 가게 · 다른 발주서의 줄이면 404, 이미 입고 409, 취소 409.
	 * 읽기만 하므로 던져도 쓴 것이 없다.
	 */
	private LockedItem lockOpenItem(long storeId, Long expectedOrderId, long itemId) {
		LockedItem item = lockItem(storeId, itemId);
		if (expectedOrderId != null && item.orderId() != expectedOrderId) {
			throw new BusinessException(CommonErrorCode.NOT_FOUND);
		}
		if (item.received()) {
			throw new BusinessException(ReceivingErrorCode.ALREADY_RECEIVED);
		}
		if (item.cancelled()) {
			throw new BusinessException(ReceivingErrorCode.CANCELLED);
		}
		return item;
	}

	/**
	 * 포장 개수 · 포장 1개 값(null · 0 이면 거래처 상품 단가)을 기록하고 price_history · 단위 LOCKED 까지.
	 * writePriceHistory · receivedOn 은 {@link #recordReceipt(long, long, BigDecimal, BigDecimal, boolean, LocalDate)} 와 같다.
	 */
	private void write(long storeId, long itemId, LockedItem item, BigDecimal receivedPacks,
			BigDecimal unitPricePerPack, boolean writePriceHistory, LocalDate receivedOn, boolean autoReceived) {
		BigDecimal price = unitPricePerPack == null || unitPricePerPack.signum() == 0
				? item.productPrice() : unitPricePerPack;
		OffsetDateTime now = OffsetDateTime.now(clock.of(storeId));
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("storeId", storeId)
				.addValue("itemId", itemId)
				.addValue("receivedQty", receivedPacks)
				.addValue("unitPrice", price)
				.addValue("receivedAt", receivedAt(storeId, receivedOn, now))
				.addValue("autoReceived", autoReceived)
				.addValue("productId", item.productId())
				.addValue("price", price)
				.addValue("unit", item.packUnit() != null ? item.packUnit() : UNKNOWN_PACK_UNIT)
				.addValue("observedAt", now)
				.addValue("ingredientId", item.ingredientId());
		jdbc.update(RECORD_RECEIPT_SQL, params);
		if (writePriceHistory && !item.marketOrder() && price != null) {
			jdbc.update(INSERT_PRICE_SQL, params);
		}
		jdbc.update(LOCK_UNIT_SQL, params);
	}

	/**
	 * 주소 경로는 요청 검증(@Valid, {@link ReceiveRequest} 기준 단위)이 먼저 거른다. 영수증 확정처럼 코드에서 부를 때는
	 * {@link PackReceipt}(포장 개수 · 포장 1개 원 정수)의 제약(@NotNull · @Positive · @Digits, #42 · #55)을 검증기로 돌린다.
	 * 값은 생성자에서 끝자리 0 을 뗀 뒤다. 코드 경로는 fieldErrors 를 담을 곳이 없어(BusinessException)
	 * 어느 칸이 틀렸는지를 메시지에 적는다.
	 */
	private void requireValid(PackReceipt packs) {
		Set<ConstraintViolation<PackReceipt>> violations = validator.validate(packs);
		if (!violations.isEmpty()) {
			String detail = violations.stream()
					.map(v -> v.getPropertyPath() + "=" + shortValue(v.getInvalidValue()) + " (" + v.getMessage() + ")")
					.sorted()
					.collect(Collectors.joining(", "));
			throw new BusinessException(CommonErrorCode.INVALID_INPUT,
					CommonErrorCode.INVALID_INPUT.getMessage() + " " + detail);
		}
	}

	/** 메시지에 넣을 값. 과학 표기 그대로(toString), 길면 앞 40자만. */
	private static String shortValue(Object value) {
		String text = String.valueOf(value);
		return text.length() <= 40 ? text : text.substring(0, 40) + "…";
	}

	private LockedItem lockItem(long storeId, long itemId) {
		try {
			return jdbc.queryForObject(LOCK_ITEM_SQL,
					new MapSqlParameterSource().addValue("storeId", storeId).addValue("itemId", itemId),
					(rs, i) -> new LockedItem(
							rs.getLong("po_id"),
							rs.getLong("ingredient_id"),
							rs.getLong("supplier_product_id"),
							rs.getBoolean("received"),
							rs.getBoolean("auto_received"),
							rs.getBoolean("cancelled"),
							rs.getBigDecimal("price"),
							rs.getBoolean("market_order"),
							rs.getString("pack_unit"),
							rs.getBigDecimal("base_qty_per_pack")));
		} catch (EmptyResultDataAccessException e) {
			throw new BusinessException(CommonErrorCode.NOT_FOUND);
		}
	}

	private ZoneId storeZone(long storeId) {
		try {
			return ZoneId.of(jdbc.queryForObject(TIMEZONE_SQL, new MapSqlParameterSource("storeId", storeId),
					String.class));
		} catch (EmptyResultDataAccessException e) {
			throw new BusinessException(CommonErrorCode.NOT_FOUND);
		}
	}

	private static IncomingLine incomingLine(ResultSet rs, LocalDate today) throws SQLException {
		BigDecimal basePerPack = rs.getBigDecimal("base_qty_per_pack");
		String unitLabel = RecipeUnit.of(IngredientUnit.valueOf(rs.getString("unit"))).label();
		Date expected = rs.getDate("expected_date");
		LocalDate expectedDate = expected == null ? null : expected.toLocalDate();
		return new IncomingLine(
				String.valueOf(rs.getLong("po_id")),
				String.valueOf(rs.getLong("id")),
				String.valueOf(rs.getLong("ingredient_id")),
				rs.getString("product_name"),
				rs.getString("supplier_name"),
				packLabel(rs.getString("pack_unit"), basePerPack, unitLabel),
				unitLabel,
				qty(basePerPack),
				qty(toBase(rs.getBigDecimal("qty"), basePerPack)),
				unitPrice(rs.getBigDecimal("price"), basePerPack),
				expectedDate == null ? null : expectedDate.toString(),
				today.equals(expectedDate));
	}

	private static HistoryLine historyLine(ResultSet rs, ZoneId zone) throws SQLException {
		OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
		OffsetDateTime receivedAt = rs.getObject("received_at", OffsetDateTime.class);
		BigDecimal basePerPack = rs.getBigDecimal("base_qty_per_pack");
		return new HistoryLine(
				String.valueOf(rs.getLong("po_id")),
				String.valueOf(rs.getLong("id")),
				createdAt.atZoneSameInstant(zone).toLocalDate().toString(),
				rs.getString("product_name"),
				RecipeUnit.of(IngredientUnit.valueOf(rs.getString("unit"))).label(),
				qty(rs.getBigDecimal("recommended_qty")),
				qty(toBase(rs.getBigDecimal("qty"), basePerPack)),
				rs.getString("supplier_name"),
				historyStatus(rs.getString("status"), rs.getObject("cancelled_at") != null, receivedAt != null),
				qty(toBase(rs.getBigDecimal("received_qty"), basePerPack)),
				receivedAt == null ? null
						: receivedAt.atZoneSameInstant(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
	}

	/**
	 * DB 포장 개수 → 기준 단위 양. 포장 크기를 모르면 null — 포장 개수를 기준 단위 양처럼 내지 않는다(#42).
	 * 추정 재고는 모를 때 1 을 곱하지만(InventoryEstimateService) 화면에 내는 수량은 그 가정을 쓰지 않는다.
	 */
	private static BigDecimal toBase(BigDecimal packs, BigDecimal basePerPack) {
		return packs == null || basePerPack == null ? null : packs.multiply(basePerPack);
	}

	/** 포장 1개 값 ÷ 포장 크기 → 단위 단가(원 / 기준 단위 1), 소수 넷째 자리(#57). 둘 중 하나라도 모르면 null. */
	private static BigDecimal unitPrice(BigDecimal packPrice, BigDecimal basePerPack) {
		return packPrice == null || basePerPack == null ? null
				: packPrice.divide(basePerPack, UnitPrices.SCALE, RoundingMode.HALF_UP);
	}

	/** 줄 취소 · 발주서 취소 → CANCELLED, 입고 확인 → RECEIVED, 그 밖은 발주서 상태(DRAFT · SENT). */
	private static HistoryStatus historyStatus(String orderStatus, boolean itemCancelled, boolean received) {
		if (received) {
			return HistoryStatus.RECEIVED;
		}
		if (itemCancelled || "CANCELLED".equals(orderStatus)) {
			return HistoryStatus.CANCELLED;
		}
		return HistoryStatus.valueOf(orderStatus);
	}

	/** "3000 g 캔". 포장 이름이 없으면 null, 포장 환산을 모르면 포장 이름만. */
	private static String packLabel(String packUnit, BigDecimal basePerPack, String unitLabel) {
		if (packUnit == null) {
			return null;
		}
		if (basePerPack == null) {
			return packUnit;
		}
		return basePerPack.stripTrailingZeros().toPlainString() + " " + unitLabel + " " + packUnit;
	}

	private record LockedItem(long orderId, long ingredientId, long productId, boolean received, boolean autoReceived,
			boolean cancelled,
			BigDecimal productPrice, boolean marketOrder, String packUnit, BigDecimal basePerPack) {
	}
}
