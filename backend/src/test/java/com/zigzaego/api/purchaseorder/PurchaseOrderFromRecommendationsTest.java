package com.zigzaego.api.purchaseorder;

import static com.zigzaego.api.support.StoreFixtures.OTHER_CATEGORY_SQL;
import static com.zigzaego.api.support.StoreFixtures.loginNewStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.support.FixedClockConfig;
import com.zigzaego.api.support.StoreFixtures.LoggedInStore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * POST /purchase-orders/from-recommendations (S15P31A308-70). 고정 시계 2026-10-14 12:00 (서울).
 * 거래처 2곳의 승인 건 → 발주서 2장, 기준 단위 → 포장 단위 올림이 메시지에 적힌다. 남의 가게는 404 (ADR-0002).
 */
@Import({TestcontainersConfiguration.class, FixedClockConfig.class})
@SpringBootTest
class PurchaseOrderFromRecommendationsTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PasswordEncoder passwordEncoder;

	private MockMvc mockMvc;

	private LoggedInStore myStore;
	private LoggedInStore otherStore;
	private long 농산; // 템플릿 있음, 리드타임 2일
	private long 정육; // 템플릿 없음, 리드타임 모름
	private long 대파;
	private long 양파;
	private long 등심;
	private long 대파1단;
	private long 양파망;
	private long 등심상품;

	@BeforeEach
	void setUp() throws Exception {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		myStore = loginNewStore(jdbc, passwordEncoder, mockMvc, "발주내가게");
		otherStore = loginNewStore(jdbc, passwordEncoder, mockMvc, "발주남의가게");
		jdbc.update("UPDATE store SET display_name = '한빛식당' WHERE id = ?", myStore.storeId());

		농산 = insertSupplier(myStore.storeId(), "농산", 2, "{가게}입니다. {배송일} 배송 부탁드립니다.\n{품목}");
		정육 = insertSupplier(myStore.storeId(), "정육", null, null);
		대파 = insertIngredient(myStore.storeId(), "대파", "G");
		양파 = insertIngredient(myStore.storeId(), "양파", "G");
		등심 = insertIngredient(myStore.storeId(), "등심", "G");
		대파1단 = insertProduct(myStore.storeId(), 농산, 대파, "대파", "단", "1000");
		양파망 = insertProduct(myStore.storeId(), 농산, 양파, "양파 15kg", "망", "15000");
		등심상품 = insertProduct(myStore.storeId(), 정육, 등심, "등심", null, null);
		setPrimary(myStore.storeId(), 대파, 대파1단);
		setPrimary(myStore.storeId(), 등심, 등심상품);
	}

	@Test
	void 거래처_2곳_승인_건이_발주서_2장_포장_단위로_올림해_메시지에_적는다() throws Exception {
		long r대파 = insertRecommendation(myStore.storeId(), 대파, "APPROVED", "1600", null);
		long r양파 = insertRecommendation(myStore.storeId(), 양파, "APPROVED", "15000", 양파망); // 추천 상품 지정, 대표 상품 없음
		long r등심 = insertRecommendation(myStore.storeId(), 등심, "APPROVED", "2500.5", null);

		create(myStore, null)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data", hasSize(2)))
				.andExpect(jsonPath("$.data[0].supplierId").value(String.valueOf(농산)))
				.andExpect(jsonPath("$.data[0].supplierName").value("농산"))
				.andExpect(jsonPath("$.data[0].status").value("DRAFT"))
				.andExpect(jsonPath("$.data[0].createdAt").value("2026-10-14T12:00:00+09:00"))
				.andExpect(jsonPath("$.data[0].messageText")
						.value("한빛식당입니다. 10/16(금) 배송 부탁드립니다.\n- 대파 2단\n- 양파 15kg 1망"))
				.andExpect(jsonPath("$.data[0].items", hasSize(2)))
				.andExpect(jsonPath("$.data[0].items[0].recommendationId").value(String.valueOf(r대파)))
				.andExpect(jsonPath("$.data[0].items[0].ingredientName").value("대파"))
				.andExpect(jsonPath("$.data[0].items[0].productName").value("대파"))
				.andExpect(jsonPath("$.data[0].items[0].qty").value(2000.0)) // 기준 단위(#42) — 포장 표기는 메시지에만
				.andExpect(jsonPath("$.data[0].items[0].packQty").doesNotExist())
				.andExpect(jsonPath("$.data[0].items[0].expectedDate").value("2026-10-16"))
				.andExpect(jsonPath("$.data[0].items[1].recommendationId").value(String.valueOf(r양파)))
				.andExpect(jsonPath("$.data[0].items[1].qty").value(15000.0))
				.andExpect(jsonPath("$.data[1].supplierId").value(String.valueOf(정육)))
				.andExpect(jsonPath("$.data[1].messageText")
						.value("안녕하세요, 한빛식당입니다.\n10/15(목) 배송으로 아래 부탁드립니다.\n- 등심 2,500.5g\n감사합니다."))
				.andExpect(jsonPath("$.data[1].items[0].recommendationId").value(String.valueOf(r등심)))
				.andExpect(jsonPath("$.data[1].items[0].qty").value(2500.5))
				.andExpect(jsonPath("$.data[1].items[0].expectedDate").value("2026-10-15"))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("\"qty\":2000.000,")));

		List<Map<String, Object>> orders = jdbc.queryForList(
				"SELECT id, supplier_id, status, message_text FROM purchase_order WHERE store_id = ? ORDER BY id",
				myStore.storeId());
		assertThat(orders).hasSize(2);
		assertThat(orders.get(0).get("supplier_id")).isEqualTo(농산);
		assertThat(orders.get(0).get("status")).isEqualTo("DRAFT");
		List<Map<String, Object>> items = jdbc.queryForList(
				"SELECT po_id, supplier_product_id, ingredient_id, qty, expected_date, recommendation_id"
						+ " FROM purchase_order_item WHERE store_id = ? ORDER BY id", myStore.storeId());
		assertThat(items).hasSize(3);
		assertThat(items.get(0).get("po_id")).isEqualTo(orders.get(0).get("id"));
		assertThat(items.get(0).get("supplier_product_id")).isEqualTo(대파1단);
		assertThat((BigDecimal) items.get(0).get("qty")).isEqualByComparingTo("2"); // 포장 단위로 저장
		assertThat(items.get(0).get("expected_date").toString()).isEqualTo("2026-10-16");
		assertThat(items.get(0).get("recommendation_id")).isEqualTo(r대파);
		assertThat(items.get(2).get("po_id")).isEqualTo(orders.get(1).get("id"));
		assertThat((BigDecimal) items.get(2).get("qty")).isEqualByComparingTo("2500.5");
	}

	@Test
	void 만든_발주서를_보내면_그_재료의_입고_예정에_들어간다() throws Exception {
		insertRecommendation(myStore.storeId(), 대파, "APPROVED", "1600", null);
		create(myStore, null).andExpect(status().isCreated());
		// 입고 예정은 보낸(SENT) 발주서만 센다 (S15P31A308-200) — 만든 발주서를 보냄 기록한다
		long orderId = jdbc.queryForObject("SELECT id FROM purchase_order WHERE store_id = ?", Long.class,
				myStore.storeId());
		mockMvc.perform(post("/purchase-orders/{id}/send", orderId).header(HttpHeaders.AUTHORIZATION, myStore.bearer()))
				.andExpect(status().isOk());

		// 입고 예정량(RecommendationService.ON_ORDER_SQL)은 포장 수량 × base_qty_per_pack — 2단 × 1,000g = 2,000g
		BigDecimal onOrder = jdbc.queryForObject("""
				SELECT SUM(poi.qty * COALESCE(sp.base_qty_per_pack, 1))
				FROM purchase_order_item poi
				JOIN purchase_order po ON po.id = poi.po_id
				JOIN supplier_product sp ON sp.id = poi.supplier_product_id
				WHERE poi.store_id = ? AND poi.ingredient_id = ?
				  AND poi.received_at IS NULL AND poi.cancelled_at IS NULL AND po.status = 'SENT'
				""", BigDecimal.class, myStore.storeId(), 대파);
		assertThat(onOrder).isEqualByComparingTo("2000");
	}

	@Test
	void 같은_날_다시_부르면_이미_발주서가_된_승인_건은_빼고_빈_목록() throws Exception {
		long r = insertRecommendation(myStore.storeId(), 대파, "APPROVED", "1600", null);
		create(myStore, null).andExpect(status().isCreated()).andExpect(jsonPath("$.data", hasSize(1)));

		create(myStore, null).andExpect(status().isCreated()).andExpect(jsonPath("$.data", hasSize(0)));
		create(myStore, "{\"date\":\"2026-10-14\"}").andExpect(status().isCreated()).andExpect(jsonPath("$.data", hasSize(0)));
		create(myStore, "{\"recommendationIds\":[\"" + r + "\"]}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("PURCHASE_ORDER_ALREADY_ORDERED"));
		assertThat(countOrders(myStore)).isEqualTo(1);
	}

	@Test
	void id_목록으로_고르면_그_추천만_다른_날짜여도_된다() throws Exception {
		long r대파 = insertRecommendation(myStore.storeId(), 대파, "APPROVED", "1600", null);
		long r등심 = insertRecommendation(myStore.storeId(), 등심, "APPROVED", "500", null);
		jdbc.update("UPDATE recommendation SET run_date = '2026-10-13' WHERE id = ?", r등심);

		create(myStore, "{\"recommendationIds\":[\"" + r등심 + "\"]}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data", hasSize(1)))
				.andExpect(jsonPath("$.data[0].supplierId").value(String.valueOf(정육)))
				.andExpect(jsonPath("$.data[0].items[0].recommendationId").value(String.valueOf(r등심)));

		// 오늘 승인 건 중 아직 안 된 대파만 남았다
		create(myStore, null).andExpect(status().isCreated())
				.andExpect(jsonPath("$.data", hasSize(1)))
				.andExpect(jsonPath("$.data[0].items[0].recommendationId").value(String.valueOf(r대파)));
	}

	@Test
	void 승인_전_추천이_섞이면_409_아무것도_안_만든다() throws Exception {
		long approved = insertRecommendation(myStore.storeId(), 대파, "APPROVED", "1600", null);
		long pending = insertRecommendation(myStore.storeId(), 등심, "PENDING", null, null);
		long rejected = insertRecommendation(myStore.storeId(), 양파, "REJECTED", null, 양파망);

		for (long bad : new long[] {pending, rejected}) {
			create(myStore, "{\"recommendationIds\":[\"" + approved + "\",\"" + bad + "\"]}")
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.error.code").value("PURCHASE_ORDER_NOT_APPROVED"));
		}
		// 날짜 모드는 승인 건만 고른다
		create(myStore, null).andExpect(status().isCreated()).andExpect(jsonPath("$.data", hasSize(1)));
		assertThat(countOrders(myStore)).isEqualTo(1);
	}

	@Test
	void 환산_모르는_상품은_포장_이름이_있어도_기준_단위_나누어떨어지면_안_올리고_final_qty_없으면_추천_수량() throws Exception {
		jdbc.update("UPDATE supplier_product SET pack_unit = '박스', base_qty_per_pack = NULL WHERE id = ?", 등심상품);
		insertRecommendation(myStore.storeId(), 등심, "APPROVED", "1600", null);
		insertRecommendation(myStore.storeId(), 대파, "APPROVED", "2000", null);
		long noFinal = insertRecommendation(myStore.storeId(), 양파, "APPROVED", null, 양파망);
		jdbc.update("UPDATE recommendation SET recommended_qty = 15001 WHERE id = ?", noFinal); // 1망 + 1g → 2망

		create(myStore, null)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data", hasSize(2)))
				.andExpect(jsonPath("$.data[0].messageText")
						.value("한빛식당입니다. 10/16(금) 배송 부탁드립니다.\n- 대파 2단\n- 양파 15kg 2망"))
				.andExpect(jsonPath("$.data[0].items[1].qty").value(30000.0))
				.andExpect(jsonPath("$.data[1].messageText")
						.value("안녕하세요, 한빛식당입니다.\n10/15(목) 배송으로 아래 부탁드립니다.\n- 등심 1,600g\n감사합니다."));
	}

	@Test
	void 다른_재료에_붙은_상품을_가리키는_추천은_409_아무것도_안_만든다() throws Exception {
		insertRecommendation(myStore.storeId(), 대파, "APPROVED", "1600", 양파망); // 양파 상품으로 대파를 살 수 없다

		create(myStore, null)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("PURCHASE_ORDER_PRODUCT_UNSET"));
		assertThat(countOrders(myStore)).isZero();
	}

	@Test
	void 거래처_상품이_없는_재료가_있으면_409_아무것도_안_만든다() throws Exception {
		insertRecommendation(myStore.storeId(), 대파, "APPROVED", "1600", null);
		insertRecommendation(myStore.storeId(), 양파, "APPROVED", "3000", null); // 추천 상품도 대표 상품도 없다

		create(myStore, null)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("PURCHASE_ORDER_PRODUCT_UNSET"));
		assertThat(countOrders(myStore)).isZero();
	}

	@Test
	void 남의_가게_추천은_404_남의_가게_승인_건은_내_발주서에_안_들어간다() throws Exception {
		long mine = insertRecommendation(myStore.storeId(), 대파, "APPROVED", "1600", null);

		create(otherStore, "{\"recommendationIds\":[\"" + mine + "\"]}")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));
		create(otherStore, null).andExpect(status().isCreated()).andExpect(jsonPath("$.data", hasSize(0)));
		for (String id : new String[] {"999999999", "abc"}) {
			create(myStore, "{\"recommendationIds\":[\"" + id + "\"]}")
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));
		}
		assertThat(countOrders(myStore)).isZero();
		assertThat(countOrders(otherStore)).isZero();
	}

	@Test
	void 같은_id_두_번_날짜_형식_틀림은_400() throws Exception {
		long r = insertRecommendation(myStore.storeId(), 대파, "APPROVED", "1600", null);

		create(myStore, "{\"recommendationIds\":[\"" + r + "\",\"" + r + "\"]}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("PURCHASE_ORDER_RECOMMENDATION_DUPLICATE"));
		create(myStore, "{\"recommendationIds\":[\"" + r + "\",\"0" + r + "\"]}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("PURCHASE_ORDER_RECOMMENDATION_DUPLICATE"));
		create(myStore, "{\"date\":\"2026-13-01\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
		assertThat(countOrders(myStore)).isZero();
	}

	@Test
	void 인증_없으면_401() throws Exception {
		mockMvc.perform(post("/purchase-orders/from-recommendations"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("COMMON_UNAUTHORIZED"));
	}

	// ---------------------------------------------------------------- helpers

	private ResultActions create(LoggedInStore store, String body) throws Exception {
		var request = post("/purchase-orders/from-recommendations").header(HttpHeaders.AUTHORIZATION, store.bearer());
		if (body != null) {
			request = request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mockMvc.perform(request);
	}

	private int countOrders(LoggedInStore store) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order WHERE store_id = ?", Integer.class, store.storeId());
	}

	private long insertSupplier(long storeId, String name, Integer leadTimeDays, String template) {
		return jdbc.queryForObject(
				"INSERT INTO supplier (store_id, name, lead_time_days, message_template) VALUES (?, ?, ?, ?) RETURNING id",
				Long.class, storeId, name, leadTimeDays, template);
	}

	private long insertIngredient(long storeId, String name, String unit) {
		return jdbc.queryForObject(
				"INSERT INTO ingredient (store_id, name, unit, price_sensitive, sourcing_type, kind, storage_type, storage_location,"
						+ " category_id, category_source)"
						+ " VALUES (?, ?, ?, false, 'GENERIC', 'BOM', 'CHILLED', 'FRIDGE', " + OTHER_CATEGORY_SQL + ", 'AUTO') RETURNING id",
				Long.class, storeId, name, unit, storeId);
	}

	private long insertProduct(long storeId, long supplierId, long ingredientId, String name, String packUnit,
			String basePerPack) {
		return jdbc.queryForObject(
				"INSERT INTO supplier_product (store_id, supplier_id, ingredient_id, product_name, pack_unit, base_qty_per_pack)"
						+ " VALUES (?, ?, ?, ?, ?, CAST(? AS NUMERIC)) RETURNING id",
				Long.class, storeId, supplierId, ingredientId, name, packUnit, basePerPack);
	}

	private void setPrimary(long storeId, long ingredientId, long productId) {
		jdbc.update("UPDATE ingredient SET primary_supplier_product_id = ? WHERE id = ? AND store_id = ?",
				productId, ingredientId, storeId);
	}

	/** 오늘 추천 한 건. status 가 APPROVED 면 finalQty 가 최종 수량. productId 는 추천이 고른 상품(없으면 null). */
	private long insertRecommendation(long storeId, long ingredientId, String status, String finalQty, Long productId) {
		return jdbc.queryForObject(
				"INSERT INTO recommendation (store_id, ingredient_id, run_date, est_stock, on_order, lead_time_demand,"
						+ " safety_stock, reorder_point, recommended_qty, status, final_qty, supplier_product_id,"
						+ " forecast_model, ss_method)"
						+ " VALUES (?, ?, ?, 0, 0, 0, 0, 0, 1000, ?, CAST(? AS NUMERIC), ?, 'dow_avg', 'DAYS_N') RETURNING id",
				Long.class, storeId, ingredientId, TODAY, status, finalQty, productId);
	}
}
