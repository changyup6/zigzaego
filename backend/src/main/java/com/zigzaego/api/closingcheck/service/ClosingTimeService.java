package com.zigzaego.api.closingcheck.service;

import com.zigzaego.api.closingcheck.dto.ClosingTimeDto;
import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.exception.CommonErrorCode;
import java.sql.Time;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 요일별 마감 시각 (S15P31A308-248, DECISIONS #88 ①). 줄이 없는 요일은 휴무 또는 미설정 — 그날은 마감 알림을 보내지 않는다.
 * 온보딩의 네이버 플레이스 영업시간 수집(S15P31A308-323)도 이 표에 넣는다.
 */
@Service
@RequiredArgsConstructor
public class ClosingTimeService {

	private static final String LIST_SQL = """
			SELECT day_of_week, close_time FROM store_closing_time WHERE store_id = :storeId ORDER BY day_of_week
			""";
	private static final String DELETE_SQL = "DELETE FROM store_closing_time WHERE store_id = :storeId";
	private static final String INSERT_SQL = """
			INSERT INTO store_closing_time (store_id, day_of_week, close_time) VALUES (:storeId, :day, :time)
			""";

	private final NamedParameterJdbcTemplate jdbc;

	@Transactional(readOnly = true)
	public List<ClosingTimeDto> list(long storeId) {
		return jdbc.query(LIST_SQL, new MapSqlParameterSource("storeId", storeId), (rs, i) -> new ClosingTimeDto(
				rs.getInt("day_of_week"), rs.getTime("close_time").toLocalTime().toString().substring(0, 5)));
	}

	/**
	 * 전체를 바꾼다(빠진 요일은 지운다).
	 *
	 * @throws BusinessException 같은 요일 두 번은 400 COMMON_INVALID_INPUT
	 */
	@Transactional
	public void replace(long storeId, List<ClosingTimeDto> times) {
		Set<Integer> days = new HashSet<>();
		for (ClosingTimeDto t : times) {
			if (!days.add(t.dayOfWeek())) {
				throw new BusinessException(CommonErrorCode.INVALID_INPUT,
						CommonErrorCode.INVALID_INPUT.getMessage() + " closingTimes (요일 " + t.dayOfWeek() + " 이 두 번 들어 있습니다)");
			}
		}
		MapSqlParameterSource store = new MapSqlParameterSource("storeId", storeId);
		// DemoTemplateAttacher(store FOR UPDATE -> store_closing_time)와 잠금 순서를 맞춰 교착(40P01)을 막는다.
		jdbc.queryForList("SELECT id FROM store WHERE id = :storeId FOR KEY SHARE", store, Long.class);
		jdbc.update(DELETE_SQL, store);
		jdbc.batchUpdate(INSERT_SQL, times.stream()
				.map(t -> (SqlParameterSource) new MapSqlParameterSource()
						.addValue("storeId", storeId)
						.addValue("day", t.dayOfWeek())
						.addValue("time", Time.valueOf(LocalTime.parse(t.closeTime()))))
				.toArray(SqlParameterSource[]::new));
	}
}
