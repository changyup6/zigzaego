package com.zigzaego.api.purchaseorder;

import static com.zigzaego.api.support.StoreFixtures.insertIngredient;
import static com.zigzaego.api.support.StoreFixtures.loginNewStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.support.FixedClockConfig;
import com.zigzaego.api.support.StoreFixtures.LoggedInStore;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

/**
 * 발주 이력 월별 조회 GET /purchase-orders/history (S15P31A308-202). 가게 2개, 가게 시간대 서울, 오늘 2026-10-14.
 * 발주서는 표에 직접 넣는다(상태 SENT · CANCELLED · DRAFT, sent_at 은 넣거나 비운다).
 */
@Import({TestcontainersConfiguration.class, FixedClockConfig.class})
@SpringBootTest
class PurchaseOrderHistoryTest {

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PasswordEncoder passwordEncoder;

	private MockMvc mockMvc;

	private LoggedInStore mine;

	private LoggedInStore other;

	private long supplier;

	private long tomato;

	/** 포장 3000 g 캔, 포장 1개 11,700원. */
	private long tomatoCan;

	@BeforeEach
	void setUp() throws Exception {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		mine = loginNewStore(jdbc, passwordEncoder, mockMvc, "이력가게");
		other = loginNewStore(jdbc, passwordEncoder, mockMvc, "남의가게");
		supplier = insertSupplier(mine.storeId(), "한빛농산");
		tomato = insertIngredient(jdbc, mine.storeId(), "토마토");
		tomatoCan = insertProduct(mine.storeId(), supplier, tomato, "토마토 캔", "캔", new BigDecimal("3000"),
				new BigDecimal("11700"));
	}

	// ---------------------------------------------------------------- [조회]

	@Test
	void 조회_SENT_RECEIVED_CANCELLED_가_섞인_달은_발주일_내림차순_같은_날은_줄_id_내림차순_합계는_취소_뺀_합() throws Exception {
		long o1 = insertOrder(mine.storeId(), supplier, "SENT", at("2026-10-09T18:00"), at("2026-10-10T09:00"));
		long a = insertItem(mine.storeId(), o1, tomatoCan, tomato, "4");
		long o2 = insertOrder(mine.storeId(), supplier, "SENT", at("2026-10-12T08:00"), at("2026-10-12T10:00"));
		long b = insertItem(mine.storeId(), o2, tomatoCan, tomato, "4");
		markReceived(b, "3.5", "11000.50");
		long c = insertItem(mine.storeId(), o2, tomatoCan, tomato, "2");
		markCancelled(c);
		long o3 = insertOrder(mine.storeId(), supplier, "CANCELLED", at("2026-10-13T08:00"), at("2026-10-13T09:00"));
		long d = insertItem(mine.storeId(), o3, tomatoCan, tomato, "1");

		history(mine, "2026-10")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.month").value("2026-10"))
				.andExpect(jsonPath("$.data.lines[*].itemId").value(contains(
						String.valueOf(d), String.valueOf(c), String.valueOf(b), String.valueOf(a))))
				.andExpect(jsonPath("$.data.lines[*].status").value(contains("CANCELLED", "CANCELLED", "RECEIVED", "SENT")))
				.andExpect(jsonPath("$.data.lines[*].date").value(contains("2026-10-13", "2026-10-12", "2026-10-12", "2026-10-10")))
				// 받은 줄은 받은 양, 아니면 주문 양 (포장 수 × 3000 g)
				.andExpect(jsonPath("$.data.lines[*].qty").value(contains(3000.0, 6000.0, 10500.0, 12000.0)))
				.andExpect(jsonPath("$.data.lines[*].unit").value(contains("g", "g", "g", "g")))
				.andExpect(jsonPath("$.data.lines[*].supplierName").value(contains("한빛농산", "한빛농산", "한빛농산", "한빛농산")))
				.andExpect(jsonPath("$.data.lines[*].ingredientName").value(contains("토마토", "토마토", "토마토", "토마토")))
				.andExpect(jsonPath("$.data.lines[0].orderId").value(String.valueOf(o3)))
				// 11700 · 2 × 11700 · 3.5 × 11000.50 = 38501.75 → 38502 · 4 × 11700
				.andExpect(jsonPath("$.data.lines[*].amount").value(contains(11700, 23400, 38502, 46800)))
				.andExpect(jsonPath("$.data.totalAmount").value(38502 + 46800));
	}

	@Test
	void 조회_보낸_시각이_없는_발주서는_만든_날이_발주일() throws Exception {
		long o = insertOrder(mine.storeId(), supplier, "SENT", at("2026-10-05T10:00"), null);
		insertItem(mine.storeId(), o, tomatoCan, tomato, "1");

		history(mine, "2026-10")
				.andExpect(jsonPath("$.data.lines", hasSize(1)))
				.andExpect(jsonPath("$.data.lines[0].date").value("2026-10-05"));
	}

	@Test
	void 조회_month_를_생략하면_가게_시간대_이번_달() throws Exception {
		long o = insertOrder(mine.storeId(), supplier, "SENT", at("2026-10-14T09:00"), at("2026-10-14T09:00"));
		insertItem(mine.storeId(), o, tomatoCan, tomato, "1");

		mockMvc.perform(get("/purchase-orders/history").header(HttpHeaders.AUTHORIZATION, mine.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.month").value("2026-10"))
				.andExpect(jsonPath("$.data.lines", hasSize(1)));
	}

	// ---------------------------------------------------------------- [금액]

	@Test
	void 금액_받은_줄은_받은_포장_수_곱하기_받은_단가_아닌_줄은_주문_포장_수_곱하기_지금_가격_원_정수_반올림() throws Exception {
		long o = insertOrder(mine.storeId(), supplier, "SENT", at("2026-10-01T10:00"), at("2026-10-01T10:00"));
		long received = insertItem(mine.storeId(), o, tomatoCan, tomato, "9");
		markReceived(received, "0.333", "11700"); // 3896.1 → 3896 (지금 가격이 아니라 받은 단가)
		jdbc.update("UPDATE supplier_product SET price = 1001 WHERE id = ?", tomatoCan);
		long open = insertItem(mine.storeId(), o, tomatoCan, tomato, "1.5"); // 1.5 × 1001 = 1501.5 → 1502

		history(mine, "2026-10")
				.andExpect(jsonPath("$.data.lines[*].itemId").value(contains(String.valueOf(open), String.valueOf(received))))
				.andExpect(jsonPath("$.data.lines[*].amount").value(contains(1502, 3896)))
				.andExpect(jsonPath("$.data.totalAmount").value(1502 + 3896));
	}

	@Test
	void 금액_가격_없는_상품은_null_합계는_0_으로_센다_포장_크기를_모르면_qty_null_unit_기준_단위() throws Exception {
		long cream = insertIngredient(jdbc, mine.storeId(), "생크림");
		long creamPack = insertProduct(mine.storeId(), supplier, cream, "생크림 1L", "팩", null, null);
		long o = insertOrder(mine.storeId(), supplier, "SENT", at("2026-10-02T10:00"), at("2026-10-02T10:00"));
		long priced = insertItem(mine.storeId(), o, tomatoCan, tomato, "1");
		long unpriced = insertItem(mine.storeId(), o, creamPack, cream, "2");
		long receivedNoPrice = insertItem(mine.storeId(), o, tomatoCan, tomato, "1");
		markReceived(receivedNoPrice, "1", null);

		history(mine, "2026-10")
				.andExpect(jsonPath("$.data.lines[*].itemId").value(contains(
						String.valueOf(receivedNoPrice), String.valueOf(unpriced), String.valueOf(priced))))
				.andExpect(jsonPath("$.data.lines[0].amount").value(nullValue()))
				.andExpect(jsonPath("$.data.lines[1].amount").value(nullValue()))
				.andExpect(jsonPath("$.data.lines[1].qty").value(nullValue()))
				.andExpect(jsonPath("$.data.lines[1].unit").value("g"))
				.andExpect(jsonPath("$.data.lines[1].ingredientName").value("생크림"))
				.andExpect(jsonPath("$.data.lines[2].amount").value(11700))
				.andExpect(jsonPath("$.data.totalAmount").value(11700));
	}

	@Test
	void 금액_포장_크기를_모르고_가격은_있는_상품은_qty_null_unit_기준_단위_amount_는_주문_포장_수_곱하기_가격() throws Exception {
		long cream = insertIngredient(jdbc, mine.storeId(), "생크림");
		long creamPack = insertProduct(mine.storeId(), supplier, cream, "생크림 1L", "팩", null, new BigDecimal("4999"));
		long o = insertOrder(mine.storeId(), supplier, "SENT", at("2026-10-03T10:00"), at("2026-10-03T10:00"));
		long tomatoLine = insertItem(mine.storeId(), o, tomatoCan, tomato, "1");
		long creamLine = insertItem(mine.storeId(), o, creamPack, cream, "2.5"); // 2.5 × 4999 = 12497.5 → 12498

		history(mine, "2026-10")
				.andExpect(jsonPath("$.data.lines[*].itemId").value(contains(
						String.valueOf(creamLine), String.valueOf(tomatoLine))))
				.andExpect(jsonPath("$.data.lines[0].status").value("SENT"))
				.andExpect(jsonPath("$.data.lines[0].qty").value(nullValue()))
				.andExpect(jsonPath("$.data.lines[0].unit").value("g"))
				.andExpect(jsonPath("$.data.lines[0].amount").value(12498))
				.andExpect(jsonPath("$.data.totalAmount").value(12498 + 11700));
	}

	// ---------------------------------------------------------------- [경계]

	@Test
	void 경계_1일_0시는_그_달_말일_23시59분도_그_달_다음_달_1일_0시와_전달_말일은_빠진다() throws Exception {
		long first = itemSentAt(mine, "2026-10-01T00:00");
		long last = itemSentAt(mine, "2026-10-31T23:59:59");
		itemSentAt(mine, "2026-11-01T00:00");
		itemSentAt(mine, "2026-09-30T23:59:59");

		history(mine, "2026-10")
				.andExpect(jsonPath("$.data.lines[*].itemId").value(contains(String.valueOf(last), String.valueOf(first))))
				.andExpect(jsonPath("$.data.lines[*].date").value(contains("2026-10-31", "2026-10-01")));
	}

	@Test
	void 경계_빈_달은_빈_배열_합계_0() throws Exception {
		itemSentAt(mine, "2026-10-01T10:00");

		history(mine, "2026-08")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.month").value("2026-08"))
				.andExpect(jsonPath("$.data.lines", hasSize(0)))
				.andExpect(jsonPath("$.data.totalAmount").value(0));
	}

	@Test
	void 경계_DRAFT_발주서_줄은_빠진다() throws Exception {
		long sent = itemSentAt(mine, "2026-10-03T10:00");
		long draft = insertOrder(mine.storeId(), supplier, "DRAFT", at("2026-10-04T10:00"), null);
		insertItem(mine.storeId(), draft, tomatoCan, tomato, "3");

		history(mine, "2026-10")
				.andExpect(jsonPath("$.data.lines[*].itemId").value(contains(String.valueOf(sent))))
				.andExpect(jsonPath("$.data.totalAmount").value(11700));
	}

	@Test
	void 경계_다른_가게_줄은_빠진다() throws Exception {
		long otherSupplier = insertSupplier(other.storeId(), "남의상회");
		long otherIngredient = insertIngredient(jdbc, other.storeId(), "남의토마토");
		long otherProduct = insertProduct(other.storeId(), otherSupplier, otherIngredient, "남의 캔", "캔",
				new BigDecimal("1000"), new BigDecimal("5000"));
		long otherOrder = insertOrder(other.storeId(), otherSupplier, "SENT", at("2026-10-05T10:00"), at("2026-10-05T10:00"));
		long otherItem = insertItem(other.storeId(), otherOrder, otherProduct, otherIngredient, "2");
		long mineItem = itemSentAt(mine, "2026-10-06T10:00");

		history(mine, "2026-10")
				.andExpect(jsonPath("$.data.lines[*].itemId").value(contains(String.valueOf(mineItem))))
				.andExpect(jsonPath("$.data.totalAmount").value(11700))
				.andExpect(jsonPath("$.data.earliestMonth").value("2026-10"));
		history(other, "2026-10")
				.andExpect(jsonPath("$.data.lines[*].itemId").value(contains(String.valueOf(otherItem))))
				.andExpect(jsonPath("$.data.totalAmount").value(10000));
	}

	@ParameterizedTest
	@ValueSource(strings = {"2026-13", "2026-1", "abc", "2026-00", "2026-10-01", "+2026-10", ""})
	void 경계_month_형식이_틀리거나_없는_달이면_400_COMMON_INVALID_INPUT(String month) throws Exception {
		history(mine, month)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
	}

	@Test
	void 경계_토큰이_없으면_401() throws Exception {
		mockMvc.perform(get("/purchase-orders/history").param("month", "2026-10"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("COMMON_UNAUTHORIZED"));
	}

	// ---------------------------------------------------------------- [earliestMonth]

	@Test
	void earliestMonth_는_DRAFT_를_뺀_가장_이른_발주의_달() throws Exception {
		long draft = insertOrder(mine.storeId(), supplier, "DRAFT", at("2026-07-10T10:00"), null);
		insertItem(mine.storeId(), draft, tomatoCan, tomato, "1");
		itemSentAt(mine, "2026-09-01T00:00");
		itemSentAt(mine, "2026-10-01T10:00");
		long cancelledLater = insertOrder(mine.storeId(), supplier, "CANCELLED", at("2026-09-20T10:00"), null);
		insertItem(mine.storeId(), cancelledLater, tomatoCan, tomato, "1");

		history(mine, "2026-08")
				.andExpect(jsonPath("$.data.earliestMonth").value("2026-09"));
	}

	@Test
	void earliestMonth_는_DRAFT_만_있거나_기록이_없으면_null() throws Exception {
		history(mine, "2026-10").andExpect(jsonPath("$.data.earliestMonth").value(nullValue()));

		long draft = insertOrder(mine.storeId(), supplier, "DRAFT", at("2026-10-10T10:00"), null);
		insertItem(mine.storeId(), draft, tomatoCan, tomato, "1");

		history(mine, "2026-10")
				.andExpect(jsonPath("$.data.earliestMonth").value(nullValue()))
				.andExpect(jsonPath("$.data.lines", hasSize(0)));
	}

	// ---------------------------------------------------------------- [Swagger]

	@Test
	void swagger_에_history_경로와_month_파라미터_200_400_401_이_보인다() throws Exception {
		String docs = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		Map<String, Object> op = JsonPath.read(docs, "$.paths['/purchase-orders/history'].get");
		assertThat(op).isNotNull();
		List<String> params = JsonPath.read(docs, "$.paths['/purchase-orders/history'].get.parameters[*].name");
		assertThat(params).contains("month");
		Map<String, Object> responses = JsonPath.read(docs, "$.paths['/purchase-orders/history'].get.responses");
		assertThat(responses).containsKeys("200", "400", "401");
	}

	// ---------------------------------------------------------------- 도우미

	private ResultActions history(LoggedInStore store, String month) throws Exception {
		return mockMvc.perform(get("/purchase-orders/history").param("month", month)
				.header(HttpHeaders.AUTHORIZATION, store.bearer()));
	}

	/** 서울 시각 "2026-10-01T10:00" → OffsetDateTime. */
	private static OffsetDateTime at(String seoulLocal) {
		return java.time.LocalDateTime.parse(seoulLocal).atZone(FixedClockConfig.SEOUL).toOffsetDateTime();
	}

	/** 그 시각에 보낸 SENT 발주서 한 장 + 토마토 캔 1개 줄. */
	private long itemSentAt(LoggedInStore store, String seoulLocal) {
		long o = insertOrder(store.storeId(), supplier, "SENT", at(seoulLocal).minusHours(1), at(seoulLocal));
		return insertItem(store.storeId(), o, tomatoCan, tomato, "1");
	}

	private long insertSupplier(long storeId, String name) {
		return jdbc.queryForObject("INSERT INTO supplier (store_id, name) VALUES (?, ?) RETURNING id",
				Long.class, storeId, name);
	}

	private long insertProduct(long storeId, long supplierId, long ingredientId, String name, String packUnit,
			BigDecimal baseQtyPerPack, BigDecimal price) {
		return jdbc.queryForObject("""
				INSERT INTO supplier_product (store_id, supplier_id, ingredient_id, product_name, pack_unit,
				                              base_qty_per_pack, price)
				VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
				""", Long.class, storeId, supplierId, ingredientId, name, packUnit, baseQtyPerPack, price);
	}

	private long insertOrder(long storeId, long supplierId, String status, OffsetDateTime createdAt,
			OffsetDateTime sentAt) {
		return jdbc.queryForObject("""
				INSERT INTO purchase_order (store_id, supplier_id, status, created_at, sent_at)
				VALUES (?, ?, ?, ?, ?) RETURNING id
				""", Long.class, storeId, supplierId, status, createdAt, sentAt);
	}

	/** 포장 qty 개 주문한 줄 (입고 확인 전). */
	private long insertItem(long storeId, long orderId, long productId, long ingredientId, String qty) {
		return jdbc.queryForObject("""
				INSERT INTO purchase_order_item (store_id, po_id, supplier_product_id, ingredient_id, qty)
				VALUES (?, ?, ?, ?, ?) RETURNING id
				""", Long.class, storeId, orderId, productId, ingredientId, new BigDecimal(qty));
	}

	private void markReceived(long itemId, String packs, String packPrice) {
		jdbc.update("UPDATE purchase_order_item SET received_qty = ?, received_unit_price = ?, received_at = ? WHERE id = ?",
				new BigDecimal(packs), packPrice == null ? null : new BigDecimal(packPrice), at("2026-10-14T09:00"), itemId);
	}

	private void markCancelled(long itemId) {
		jdbc.update("UPDATE purchase_order_item SET cancelled_at = ? WHERE id = ?", at("2026-10-14T09:00"), itemId);
	}
}
