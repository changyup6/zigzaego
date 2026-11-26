package com.zigzaego.api.demo;

import com.zigzaego.api.global.config.StoreClock;
import com.zigzaego.api.global.exception.BusinessException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 상태 조회는 읽기만 한다. 준비 시작은 레시피 저장 트랜잭션 또는 명시적 POST뿐이다. */
@Service
@RequiredArgsConstructor
public class DemoPreparationService {

	private final JdbcTemplate jdbc;
	private final StoreClock clock;

	@Transactional(readOnly = true)
	public DemoPreparationResponse get(long storeId) {
		if (!clock.isDemoStore(storeId)) {
			return DemoPreparationResponse.notStarted(false);
		}
		return rows(storeId, false).stream().findFirst().orElse(DemoPreparationResponse.notStarted(true));
	}

	/** 레시피 확인/건너뛰기와 같은 트랜잭션. 기본 자료가 실제 저장된 경우만 등록한다. */
	@Transactional
	public void register(long storeId) {
		if (clock.isDemoStore(storeId) && hasCoreData(storeId)) {
			jdbc.update("""
					INSERT INTO demo_preparation(store_id,status) VALUES (?,'PREPARING')
					ON CONFLICT(store_id) DO NOTHING
					""", storeId);
		}
	}

	/** 이미 진행 중/완료면 같은 상태. FAILED 재시도는 행 잠금으로 한 번만 attempt를 올린다. */
	@Transactional
	public DemoPreparationResponse retry(long storeId) {
		if (!clock.isDemoStore(storeId)) {
			throw new BusinessException(DemoPreparationErrorCode.NOT_ELIGIBLE);
		}
		if (!hasCoreData(storeId)) {
			throw new BusinessException(DemoPreparationErrorCode.ONBOARDING_REQUIRED);
		}
		register(storeId);
		DemoPreparationResponse current = rows(storeId, true).getFirst();
		if (current.status().equals("FAILED") && current.retryable()) {
			String next = current.phase().equals("ATTACH") ? "PREPARING" : "DATA_READY";
			jdbc.update("""
					UPDATE demo_preparation SET status=?,attempt=attempt+1,error_code=NULL,
					    retryable=false,updated_at=now() WHERE store_id=?
					""", next, storeId);
		}
		return rows(storeId, false).getFirst();
	}

	/** 준비 전의 빈 실행 완료 캐시를 만들지 않는다. 일반 가게는 기존 계약 그대로다. */
	public void requireDataReady(long storeId) {
		DemoPreparationResponse current = get(storeId);
		if (!current.eligible() || current.status().equals("DATA_READY") || current.status().equals("READY")) {
			return;
		}
		throw new BusinessException(current.status().equals("FAILED")
				? DemoPreparationErrorCode.DATA_FAILED : DemoPreparationErrorCode.DATA_PREPARING);
	}

	boolean hasCoreData(long storeId) {
		return Boolean.TRUE.equals(jdbc.queryForObject("""
				SELECT EXISTS (SELECT 1 FROM recipe r
				JOIN menu m ON m.id=r.menu_id AND m.store_id=r.store_id
				JOIN ingredient i ON i.id=r.ingredient_id AND i.store_id=r.store_id
				WHERE r.store_id=?)
				""", Boolean.class, storeId));
	}

	private List<DemoPreparationResponse> rows(long storeId, boolean lock) {
		return jdbc.query("SELECT * FROM demo_preparation WHERE store_id=?" + (lock ? " FOR UPDATE" : ""),
				(rs, row) -> from(rs), storeId);
	}

	static DemoPreparationResponse from(ResultSet rs) throws SQLException {
		return new DemoPreparationResponse(true, rs.getString("status"), rs.getString("phase"), rs.getInt("attempt"),
				rs.getString("error_code"), rs.getBoolean("retryable"), rs.getObject("updated_at", OffsetDateTime.class));
	}
}
