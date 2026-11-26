package com.zigzaego.api.receiving.service;

import com.zigzaego.api.global.config.StoreClock;
import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.exception.CommonErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 도착 예정일 자동 입고 (S15P31A308-203, DECISIONS #62 — 화면에서 입고 확인을 뺐으므로 보낸 발주는 예정일에 주문 수량대로 들어온 것으로 본다).
 *
 * <p>대상 = 보낸(SENT) 발주서의, 안 옴 처리도 입고도 안 된 줄 중 예정일이 가게 시간대의 오늘보다 이전인(지난 날) 줄 — 다음 날 처리.
 * 예정일 당일에는 입고 예정(GET /receiving incoming, today=true)에 그대로 남고, 다음 날 새벽 배치에서 입고로 바뀐다. 줄마다
 * {@link ReceivingService#recordReceipt(long, long, BigDecimal, BigDecimal, boolean, LocalDate)} 를
 * 주문 수량(포장 개수) · 단가 null(거래처 상품의 지금 가격) · price_history 안 씀(관측한 단가가 아님, 112 영수증 경로와 같은 false) ·
 * 받은 날 = 예정일로 부른다. received_at = min(지금, 예정일 끝 시각)인데 예정일이 늘 지난 날이므로 실제로는 늘 예정일
 * 23:59:59.999999(가게 시간대) — 그날의 어느 실사보다 뒤다. 첫 입고면 단위 LOCKED 까지 수동 입고와 같다.
 *
 * <p>이 클래스는 트랜잭션을 열지 않는다 — 줄마다 recordReceipt 의 트랜잭션이 따로 커밋된다. recordReceipt 가 줄을 잠그고
 * 이미 입고 · 취소인지 다시 보므로(409), 다른 요청 · 다른 실행이 먼저 처리한 줄은 그 줄만 건너뛴다(INFO, skipped). 그래서 같은 날
 * 여러 번 돌아도 결과가 같다. 그 밖의 오류(400 · 404 · 예외)는 ERROR 로 남기고(failed) 다음 줄로 간다.
 * 모든 SQL 에 store_id 를 건다 (ADR-0002).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoReceiveService {

	private static final String TIMEZONE_SQL = "SELECT timezone FROM store WHERE id = :storeId";

	private static final String DUE_ITEMS_SQL = """
			SELECT poi.id, poi.qty, poi.expected_date
			FROM purchase_order_item poi
			JOIN purchase_order po ON po.id = poi.po_id AND po.store_id = :storeId
			WHERE poi.store_id = :storeId
			  AND po.status = 'SENT'
			  AND poi.cancelled_at IS NULL AND poi.received_at IS NULL
			  AND poi.expected_date IS NOT NULL AND poi.expected_date < :today
			ORDER BY poi.expected_date, poi.id
			""";

	private final NamedParameterJdbcTemplate jdbc;
	private final StoreClock clock;
	private final ReceivingService receivingService;

	/**
	 * 가게 하나의 예정일이 지난 줄을 입고 처리한다.
	 *
	 * @return 가게의 오늘 · 입고한 줄 수 · 건너뛴 줄 수(409) · 실패한 줄 수(그 밖의 오류)
	 * @throws BusinessException 없는 가게면 404 COMMON_NOT_FOUND (배치는 가게 단위로 잡는다)
	 */
	public Result receiveDue(long storeId) {
		LocalDate today = LocalDate.now(clock.of(storeId).withZone(storeZone(storeId)));
		List<DueItem> due = jdbc.query(DUE_ITEMS_SQL,
				new MapSqlParameterSource().addValue("storeId", storeId).addValue("today", today),
				(rs, i) -> new DueItem(rs.getLong("id"), rs.getBigDecimal("qty"),
						rs.getDate("expected_date").toLocalDate()));
		int received = 0;
		int skipped = 0;
		int failed = 0;
		for (DueItem item : due) {
			try {
				receivingService.recordReceipt(storeId, item.id(), item.qty(), null, false, item.expectedDate(),
						true);
				received++;
			} catch (BusinessException e) {
				if (e.getErrorCode().getStatus() == HttpStatus.CONFLICT) {
					// 이미 입고 · 취소(409) — 조회와 잠금 사이에 다른 쪽이 처리했다. 그 줄만 건너뛴다
					skipped++;
					log.info("자동 입고 건너뜀 — storeId={}, itemId={}, code={}", storeId, item.id(),
							e.getErrorCode().getCode());
				} else {
					// 400 · 404 등 — 처리 못 한 줄. 다음 실행에서 다시 대상이 된다
					failed++;
					log.error("자동 입고 줄 실패 — storeId={}, itemId={}, code={}", storeId, item.id(),
							e.getErrorCode().getCode(), e);
				}
			} catch (RuntimeException e) {
				failed++;
				log.error("자동 입고 줄 실패 — storeId={}, itemId={}", storeId, item.id(), e);
			}
		}
		return new Result(today, received, skipped, failed);
	}

	private ZoneId storeZone(long storeId) {
		try {
			return ZoneId.of(jdbc.queryForObject(TIMEZONE_SQL, new MapSqlParameterSource("storeId", storeId),
					String.class));
		} catch (EmptyResultDataAccessException e) {
			throw new BusinessException(CommonErrorCode.NOT_FOUND);
		}
	}

	/** @param today 가게 시간대의 오늘 */
	public record Result(LocalDate today, int received, int skipped, int failed) {
	}

	private record DueItem(long id, BigDecimal qty, LocalDate expectedDate) {
	}
}
