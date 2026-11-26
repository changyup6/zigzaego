package com.zigzaego.api.account.service;

import com.zigzaego.api.account.dto.SetupStep;
import com.zigzaego.api.account.dto.SetupStepResponse;
import com.zigzaego.api.account.dto.StoreMeResponse;
import com.zigzaego.api.account.dto.StoreUpdateRequest;
import com.zigzaego.api.account.entity.Store;
import com.zigzaego.api.account.repository.StoreRepository;
import com.zigzaego.api.closingcheck.dto.ClosingTimeDto;
import com.zigzaego.api.closingcheck.service.ClosingTimeService;
import com.zigzaego.api.global.config.StoreClock;
import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.exception.CommonErrorCode;
import com.zigzaego.api.market.delivery.DeliveryCoverage;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 내 가게 정보와 가게 설정 체크리스트 (설계 2.1절, DECISIONS #50). 체크리스트는 저장하지 않고 조회 때 데이터로 판정한다.
 *
 * <ul>
 *   <li>ONBOARDING: 메뉴가 하나라도 있으면 끝. 메뉴는 사장님이 레시피 확인을 끝내거나 건너뛸 때 저장된다(설계 2.4절)</li>
 *   <li>RECIPE: 레시피 줄이 있으면 끝. 없으면 가장 최근에 끝난(DONE) 온보딩 job 의 {@code result_json.questions[]} 중
 *       {@code answer} 가 비어 있는 질문 수가 남은 개수. 0 이면 끝. 단 레시피 줄이 하나라도 있으면 질문과 무관하게 끝(remaining 0)</li>
 *   <li>POS: 포스기 업체(pos_vendor)가 설정됐거나 판매 기록이 있으면 끝</li>
 *   <li>SUPPLIER: 거래처가 하나라도 있으면 끝</li>
 *   <li>FIRST_RECEIVE: 발주 품목 입고 확인이 있거나, 사장님이 확인한(CONFIRMED) 입고(RECEIVE) 문서가 있으면 끝</li>
 *   <li>STOCK_COUNT: 재고 실사 기록이 하나라도 있으면 끝</li>
 * </ul>
 * 모든 하위 조회에 store_id 를 건다 (ADR-0002).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoreService {

	/** store.name · display_name 칸 길이. */
	private static final int MAX_PLACE_NAME_LENGTH = 100;

	private static final String CHECKLIST_SQL = """
			SELECT
			    EXISTS (SELECT 1 FROM menu m WHERE m.store_id = :storeId) AS has_menu,
			    EXISTS (SELECT 1 FROM recipe r WHERE r.store_id = :storeId) AS has_recipe,
			    (s.pos_vendor IS NOT NULL
			        OR EXISTS (SELECT 1 FROM pos_sale ps WHERE ps.store_id = :storeId)) AS has_pos,
			    EXISTS (SELECT 1 FROM supplier sp WHERE sp.store_id = :storeId) AS has_supplier,
			    (EXISTS (SELECT 1 FROM purchase_order_item poi
			             WHERE poi.store_id = :storeId AND poi.received_at IS NOT NULL)
			        OR EXISTS (SELECT 1 FROM purchase_doc pd
			                   WHERE pd.store_id = :storeId AND pd.kind = 'RECEIVE'
			                     AND pd.parse_status = 'CONFIRMED')) AS has_receive,
			    EXISTS (SELECT 1 FROM stock_count sc WHERE sc.store_id = :storeId) AS has_stock_count,
			    (SELECT j.result_json::text FROM job j
			     WHERE j.store_id = :storeId AND j.type = 'ONBOARDING' AND j.status = 'DONE'
			     ORDER BY j.finished_at DESC NULLS LAST, j.id DESC
			     LIMIT 1) AS onboarding_result
			FROM store s
			WHERE s.id = :storeId
			""";

	private final StoreRepository storeRepository;
	private final NamedParameterJdbcTemplate jdbc;
	private final JsonMapper jsonMapper;
	private final ClosingTimeService closingTimeService;
	private final StoreClock storeClock;

	/**
	 * 로그인한 가게의 정보와 체크리스트.
	 *
	 * @throws BusinessException 가게가 없으면 404 COMMON_NOT_FOUND
	 */
	@Transactional(readOnly = true)
	public StoreMeResponse getMine(long storeId) {
		// store 는 테넌트 루트라 id 자체가 가게 스코프다
		Store store = storeRepository.findById(storeId)
				.orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
		List<SetupStepResponse> checklist = jdbc.queryForObject(CHECKLIST_SQL,
				new MapSqlParameterSource("storeId", storeId),
				(rs, rowNum) -> List.of(
						SetupStepResponse.ofFlag(SetupStep.ONBOARDING, rs.getBoolean("has_menu")),
						recipeStep(rs.getString("onboarding_result"), rs.getBoolean("has_recipe")),
						SetupStepResponse.ofFlag(SetupStep.POS, rs.getBoolean("has_pos")),
						SetupStepResponse.ofFlag(SetupStep.SUPPLIER, rs.getBoolean("has_supplier")),
						SetupStepResponse.ofFlag(SetupStep.FIRST_RECEIVE, rs.getBoolean("has_receive")),
						SetupStepResponse.ofFlag(SetupStep.STOCK_COUNT, rs.getBoolean("has_stock_count"))));
		return StoreMeResponse.of(store, storeClock.today(storeId, ZoneId.of(store.getTimezone())), checklist,
				closingTimeService.list(storeId));
	}

	/**
	 * 내 가게 고치기. 보낸 칸만 바꾼다. 시군구는 공백을 정리해 넣고 빈 글자면 지운다 (S15P31A308-230). 가격 비교 켬/끔은 그대로 넣는다 (S15P31A308-205).
	 *
	 * @throws BusinessException 가게가 없으면 404 COMMON_NOT_FOUND
	 */
	@Transactional
	public StoreMeResponse updateMine(long storeId, StoreUpdateRequest request) {
		Store store = storeRepository.findById(storeId)
				.orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
		if (request.sigungu() != null) {
			store.updateSigungu(DeliveryCoverage.normalize(request.sigungu()));
		}
		if (request.priceCompare() != null) {
			store.updatePriceCompare(request.priceCompare());
		}
		return getMine(storeId);
	}

	/**
	 * 온보딩에서 고른 가게 이름을 가게 이름 · 화면 이름으로 쓴다. 가입은 가게 이름을 받지 않아 이메일 앞부분이 들어가 있다.
	 * 이름이 비었으면 기존 이름을 유지한다. 100자를 넘으면 코드 포인트 기준으로 자른다.
	 * 주소는 서버가 수집한 후보에서만 받는다. 새 장소에 확인 가능한 주소가 없으면 이전 주소를 비운다.
	 */
	@Transactional
	public void adoptPlace(long storeId, String placeId, String placeName, String address) {
		String name = placeName == null || placeName.isBlank() ? null : placeName.strip();
		if (name != null && name.codePointCount(0, name.length()) > MAX_PLACE_NAME_LENGTH) {
			name = name.substring(0, name.offsetByCodePoints(0, MAX_PLACE_NAME_LENGTH));
		}
		Store store = storeRepository.findById(storeId)
				.orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
		store.adoptPlace(placeId, name, address == null || address.isBlank() ? null : address.strip());
	}

	/**
	 * 요일별 마감 시각 전체를 바꾼다 (S15P31A308-248, DECISIONS #88 ①). 빠진 요일은 휴무.
	 *
	 * @throws BusinessException 가게가 없으면 404, 같은 요일 두 번은 400
	 */
	@Transactional
	public StoreMeResponse replaceClosingTimes(long storeId, List<ClosingTimeDto> closingTimes) {
		storeRepository.findById(storeId).orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
		closingTimeService.replace(storeId, closingTimes);
		return getMine(storeId);
	}

	private SetupStepResponse recipeStep(String onboardingResult, boolean hasRecipe) {
		if (hasRecipe) {
			// 레시피 확정은 질문의 answer 를 쓰지 않으므로 레시피 줄이 있으면 질문 배열과 무관하게 끝 (S15P31A308-283)
			return SetupStepResponse.ofFlag(SetupStep.RECIPE, true);
		}
		JsonNode questions = questions(onboardingResult);
		if (questions == null) {
			return SetupStepResponse.ofFlag(SetupStep.RECIPE, false);
		}
		int unanswered = 0;
		for (JsonNode question : questions) {
			JsonNode answer = question.get("answer");
			if (answer == null || answer.isNull()) {
				unanswered++;
			}
		}
		return new SetupStepResponse(SetupStep.RECIPE, unanswered == 0, unanswered);
	}

	/** 온보딩 결과의 질문 배열. 결과가 없거나 질문 배열이 없으면 null. */
	private JsonNode questions(String onboardingResult) {
		if (onboardingResult == null) {
			return null;
		}
		try {
			JsonNode questions = jsonMapper.readTree(onboardingResult).get("questions");
			return questions != null && questions.isArray() && !questions.isEmpty() ? questions : null;
		} catch (JacksonException e) {
			log.warn("온보딩 job result_json 을 읽지 못해 질문 수를 0 으로 본다", e);
			return null;
		}
	}
}
