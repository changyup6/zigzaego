package com.zigzaego.api.purchaseorder;

import static com.zigzaego.api.support.StoreFixtures.loginNewStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.recommendation.dto.RecommendationResponse;
import com.zigzaego.api.recommendation.service.RecommendationService;
import com.zigzaego.api.support.FixedClockConfig;
import com.zigzaego.api.support.StoreFixtures;
import com.zigzaego.api.support.StoreFixtures.LoggedInStore;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * POST /purchase-orders/{id}/send (S15P31A308-200, DECISIONS #63). 고정 시계 2026-10-14 12:00 (서울).
 * DRAFT → SENT · sent_at 기록, 두 번째는 그대로 200, CANCELLED 는 409, 남의 가게 · 없는 id · 숫자 아닌 id 는 404 (ADR-0002).
 * 보낸(SENT) 발주서만 입고 예정(GET /receiving incoming)과 추천 onOrder 에 들어간다.
 */
@Import({TestcontainersConfiguration.class, FixedClockConfig.class})
@SpringBootTest
class PurchaseOrderSendTest {

	private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-14T12:00:00+09:00");
	private static final ZoneOffset KST = ZoneOffset.ofHours(9);

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private RecommendationService recommendationService;

	@Autowired
	private JsonMapper json;

	private MockMvc mockMvc;

	private LoggedInStore mine;
	private LoggedInStore other;
	private long supplier;
	private long 대파;
	private long 대파1단;

	@BeforeEach
	void setUp() throws Exception {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		mine = loginNewStore(jdbc, passwordEncoder, mockMvc, "보냄내가게");
		other = loginNewStore(jdbc, passwordEncoder, mockMvc, "보냄남의가게");
		supplier = insertSupplier(mine.storeId(), "농산", 1);
		대파 = insertIngredient(mine.storeId(), "대파");
		대파1단 = insertProduct(mine.storeId(), supplier, 대파, "대파", "단", "1000");
	}

	// ---------------------------------------------------------------- [보냄]

	@Test
	void 보냄_DRAFT_발주서를_보내면_200_SENT_sentAt_채워지고_DB_도_SENT() throws Exception {
		long order = insertOrder(mine.storeId(), supplier, "DRAFT");
		long item = insertItem(mine.storeId(), order, 대파1단, 대파, "2");

		send(mine, String.valueOf(order))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.id").value(String.valueOf(order)))
				.andExpect(jsonPath("$.data.supplierId").value(String.valueOf(supplier)))
				.andExpect(jsonPath("$.data.supplierName").value("농산"))
				.andExpect(jsonPath("$.data.status").value("SENT"))
				.andExpect(jsonPath("$.data.sentAt").value("2026-10-14T12:00:00+09:00"))
				.andExpect(jsonPath("$.data.createdAt").value("2026-10-13T12:00:00+09:00"))
				.andExpect(jsonPath("$.data.items", hasSize(1)))
				.andExpect(jsonPath("$.data.items[0].id").value(String.valueOf(item)))
				.andExpect(jsonPath("$.data.items[0].ingredientName").value("대파"))
				.andExpect(jsonPath("$.data.items[0].recommendationId").value(nullValue()))
				.andExpect(jsonPath("$.data.items[0].qty").value(2000.0));

		assertThat(statusOf(order)).isEqualTo("SENT");
		assertThat(sentAtOf(order)).isNotNull();
	}

	@Test
	void 보냄_만든_발주서는_DRAFT_이고_sentAt_null() throws Exception {
		long rec = jdbc.queryForObject(
				"INSERT INTO recommendation (store_id, ingredient_id, run_date, est_stock, on_order, lead_time_demand,"
						+ " safety_stock, reorder_point, recommended_qty, status, final_qty, forecast_model, ss_method)"
						+ " VALUES (?, ?, ?, 0, 0, 0, 0, 0, 1000, 'APPROVED', 1600, 'dow_avg', 'DAYS_N') RETURNING id",
				Long.class, mine.storeId(), 대파, FixedClockConfig.TODAY);
		jdbc.update("UPDATE ingredient SET primary_supplier_product_id = ? WHERE id = ?", 대파1단, 대파);

		mockMvc.perform(post("/purchase-orders/from-recommendations")
						.header(HttpHeaders.AUTHORIZATION, mine.bearer()))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data[0].status").value("DRAFT"))
				.andExpect(jsonPath("$.data[0].sentAt").value(nullValue()))
				.andExpect(jsonPath("$.data[0].items[0].recommendationId").value(String.valueOf(rec)));
	}

	// ---------------------------------------------------------------- [경계]

	@Test
	void 경계_두_번째_send_는_200_이고_sent_at_은_첫_값_그대로() throws Exception {
		long order = insertOrder(mine.storeId(), supplier, "DRAFT");
		insertItem(mine.storeId(), order, 대파1단, 대파, "1");
		send(mine, String.valueOf(order)).andExpect(status().isOk());
		OffsetDateTime first = OffsetDateTime.parse("2026-10-14T09:30:00+09:00");
		jdbc.update("UPDATE purchase_order SET sent_at = ? WHERE id = ?", first, order); // 첫 보냄 시각을 구별되게

		send(mine, String.valueOf(order))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("SENT"))
				.andExpect(jsonPath("$.data.sentAt").value("2026-10-14T09:30:00+09:00"));

		assertThat(statusOf(order)).isEqualTo("SENT");
		assertThat(sentAtOf(order).toInstant()).isEqualTo(first.toInstant());
	}

	@Test
	void 경계_CANCELLED_발주서는_409_PURCHASE_ORDER_CANCELLED_상태_불변() throws Exception {
		long order = insertOrder(mine.storeId(), supplier, "CANCELLED");

		send(mine, String.valueOf(order))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("PURCHASE_ORDER_CANCELLED"));

		assertThat(statusOf(order)).isEqualTo("CANCELLED");
		assertThat(sentAtOf(order)).isNull();
	}

	@Test
	void 경계_다른_가게_발주서는_404_상태_불변() throws Exception {
		long otherSupplier = insertSupplier(other.storeId(), "남의농산", 1);
		long otherOrder = insertOrder(other.storeId(), otherSupplier, "DRAFT");

		send(mine, String.valueOf(otherOrder))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));

		assertThat(statusOf(otherOrder)).isEqualTo("DRAFT");
		assertThat(sentAtOf(otherOrder)).isNull();
	}

	@Test
	void 경계_없는_id_는_404() throws Exception {
		send(mine, "999999999")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));
	}

	@Test
	void 경계_숫자_아닌_id_는_404() throws Exception {
		send(mine, "abc")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));
	}

	@Test
	void 경계_토큰_없으면_401_상태_불변() throws Exception {
		long order = insertOrder(mine.storeId(), supplier, "DRAFT");

		mockMvc.perform(post("/purchase-orders/{id}/send", String.valueOf(order)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("COMMON_UNAUTHORIZED"));

		assertThat(statusOf(order)).isEqualTo("DRAFT");
	}

	// ---------------------------------------------------------------- [입고 예정]

	/**
	 * 추천 손계산(RecommendationServiceTest 손계산_DAYS_N_발주와 같은 데이터, 기준일 2026-10-10):
	 * 추정 재고 2500 ≤ ROP 4500 이라 입고 예정 1단(1,000g)이 있어도 추천 줄이 생긴다 — onOrder 값만 비교한다.
	 */
	@Test
	void 입고_예정_DRAFT_줄은_incoming_과_onOrder_에_없고_send_뒤에는_둘_다에_들어간다() throws Exception {
		LocalDate runDate = LocalDate.of(2026, 10, 10);
		long 돈까스 = jdbc.queryForObject(
				"INSERT INTO menu (store_id, name, kind, source) VALUES (?, '돈까스', 'MAIN', 'POS') RETURNING id",
				Long.class, mine.storeId());
		jdbc.update("INSERT INTO recipe (store_id, menu_id, ingredient_id, qty_per_serving, source)"
				+ " VALUES (?, ?, ?, 150, 'MANUAL')", mine.storeId(), 돈까스, 대파);
		jdbc.update("UPDATE ingredient SET primary_supplier_product_id = ?, safety_days = 2 WHERE id = ?", 대파1단, 대파);
		jdbc.update("UPDATE supplier SET order_cycle_days = 1 WHERE id = ?", supplier);
		for (int day = 3; day <= 9; day++) {
			jdbc.update("INSERT INTO pos_sale (store_id, sold_date, menu_id, qty, channel) VALUES (?, ?, ?, 10, 'HALL')",
					mine.storeId(), LocalDate.of(2026, 10, day), 돈까스);
		}
		jdbc.update("INSERT INTO stock_count (store_id, ingredient_id, counted_at, qty) VALUES (?, ?, ?, 4000)",
				mine.storeId(), 대파, OffsetDateTime.of(2026, 10, 9, 0, 0, 0, 0, KST));

		long order = insertOrder(mine.storeId(), supplier, "DRAFT");
		long item = insertItem(mine.storeId(), order, 대파1단, 대파, "1");

		feed(mine)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.incoming", hasSize(0)));
		assertThat(onOrder(runDate)).isEqualByComparingTo("0");

		send(mine, String.valueOf(order)).andExpect(status().isOk());

		feed(mine)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.incoming", hasSize(1)))
				.andExpect(jsonPath("$.data.incoming[0].orderId").value(String.valueOf(order)))
				.andExpect(jsonPath("$.data.incoming[0].itemId").value(String.valueOf(item)));
		// 같은 날 다시 계산하면 기존 추천을 두므로, 추천 · 실행 기록을 지우고 다시 돌린다
		jdbc.update("DELETE FROM recommendation WHERE store_id = ?", mine.storeId());
		jdbc.update("DELETE FROM recommendation_run WHERE store_id = ?", mine.storeId());
		assertThat(onOrder(runDate)).isEqualByComparingTo("1000");
	}

	// ---------------------------------------------------------------- [Swagger]

	@Test
	void swagger_send_경로와_200_401_404_409_응답_PurchaseOrderResponse_sentAt() throws Exception {
		JsonNode docs = json.readTree(mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

		JsonNode sendOp = docs.path("paths").path("/purchase-orders/{id}/send").path("post");
		assertThat(sendOp.isMissingNode()).as(docs.path("paths").propertyNames().toString()).isFalse();
		assertThat(List.copyOf(sendOp.path("responses").propertyNames()))
				.as(sendOp.toString()).contains("200", "401", "404", "409");
		JsonNode sentAt = docs.path("components").path("schemas").path("PurchaseOrderResponse")
				.path("properties").path("sentAt");
		assertThat(sentAt.isMissingNode()).isFalse();
		assertThat(sentAt.path("description").asString()).isNotBlank();
	}

	// ---------------------------------------------------------------- helpers

	private ResultActions send(LoggedInStore store, String id) throws Exception {
		return mockMvc.perform(post("/purchase-orders/{id}/send", id).header(HttpHeaders.AUTHORIZATION, store.bearer()));
	}

	private ResultActions feed(LoggedInStore store) throws Exception {
		return mockMvc.perform(get("/receiving").header(HttpHeaders.AUTHORIZATION, store.bearer()));
	}

	private BigDecimal onOrder(LocalDate runDate) {
		List<RecommendationResponse> recs = recommendationService.run(mine.storeId(), runDate).recommendations();
		assertThat(recs).hasSize(1);
		return recs.getFirst().onOrder();
	}

	private String statusOf(long orderId) {
		return jdbc.queryForObject("SELECT status FROM purchase_order WHERE id = ?", String.class, orderId);
	}

	private OffsetDateTime sentAtOf(long orderId) {
		return jdbc.queryForObject("SELECT sent_at FROM purchase_order WHERE id = ?", OffsetDateTime.class, orderId);
	}

	private long insertSupplier(long storeId, String name, Integer leadTimeDays) {
		return jdbc.queryForObject(
				"INSERT INTO supplier (store_id, name, lead_time_days) VALUES (?, ?, ?) RETURNING id",
				Long.class, storeId, name, leadTimeDays);
	}

	private long insertIngredient(long storeId, String name) {
		return jdbc.queryForObject(
				"INSERT INTO ingredient (store_id, name, unit, price_sensitive, sourcing_type, kind, storage_type, storage_location, category_id, category_source)"
						+ " VALUES (?, ?, 'G', false, 'GENERIC', 'BOM', 'CHILLED', 'FRIDGE', " + StoreFixtures.OTHER_CATEGORY_SQL + ", 'AUTO') RETURNING id",
				Long.class, storeId, name, storeId);
	}

	private long insertProduct(long storeId, long supplierId, long ingredientId, String name, String packUnit,
			String basePerPack) {
		return jdbc.queryForObject(
				"INSERT INTO supplier_product (store_id, supplier_id, ingredient_id, product_name, pack_unit, base_qty_per_pack)"
						+ " VALUES (?, ?, ?, ?, ?, CAST(? AS NUMERIC)) RETURNING id",
				Long.class, storeId, supplierId, ingredientId, name, packUnit, basePerPack);
	}

	/** 어제(서울 10/13 12:00) 만든 발주서 한 장. */
	private long insertOrder(long storeId, long supplierId, String status) {
		return jdbc.queryForObject(
				"INSERT INTO purchase_order (store_id, supplier_id, status, message_text, created_at)"
						+ " VALUES (?, ?, ?, '메시지', ?) RETURNING id",
				Long.class, storeId, supplierId, status, NOW.minusDays(1));
	}

	/** 아직 입고 확인 안 된 발주 줄. qty 는 포장 단위. */
	private long insertItem(long storeId, long orderId, long productId, long ingredientId, String packQty) {
		return jdbc.queryForObject(
				"INSERT INTO purchase_order_item (store_id, po_id, supplier_product_id, ingredient_id, qty, expected_date)"
						+ " VALUES (?, ?, ?, ?, CAST(? AS NUMERIC), ?) RETURNING id",
				Long.class, storeId, orderId, productId, ingredientId, packQty, LocalDate.of(2026, 10, 15));
	}
}
