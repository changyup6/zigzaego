package com.zigzaego.api.receiving;

import static com.zigzaego.api.receiving.ReceivingTestData.insertItem;
import static com.zigzaego.api.receiving.ReceivingTestData.insertOrder;
import static com.zigzaego.api.receiving.ReceivingTestData.insertProduct;
import static com.zigzaego.api.receiving.ReceivingTestData.insertSupplier;
import static com.zigzaego.api.support.StoreFixtures.insertIngredient;
import static com.zigzaego.api.support.StoreFixtures.loginNewStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.receiving.service.ReceivingService;
import com.zigzaego.api.support.FixedClockConfig;
import com.zigzaego.api.support.StoreFixtures.LoggedInStore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * PATCH /purchase-orders/{orderId}/items/{itemId}/receive 정상 경로 (S15P31A308-71, 제안 문서 2절 "서버에서 일어나는 일").
 * received_* 기록 · price_history RECEIVE 1행(포장 1개 단가) · 첫 입고면 LOCKED · 응답 = 바뀐 피드.
 */
@Import({TestcontainersConfiguration.class, FixedClockConfig.class})
@SpringBootTest
class ReceivingConfirmTest {

	private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-14T12:00:00+09:00");

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private ReceivingService receivingService;

	@Autowired
	private TransactionTemplate transactionTemplate;

	private MockMvc mockMvc;

	private LoggedInStore mine;

	private long supplier;

	private long tomato;

	private long tomatoCan;

	private long order;

	@BeforeEach
	void setUp() throws Exception {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		mine = loginNewStore(jdbc, passwordEncoder, mockMvc, "입고가게");
		supplier = insertSupplier(jdbc, mine.storeId(), "OO상회");
		tomato = insertIngredient(jdbc, mine.storeId(), "토마토");
		tomatoCan = insertProduct(jdbc, mine.storeId(), supplier, tomato, "토마토 캔", "캔",
				new BigDecimal("3000"), new BigDecimal("11700"));
		order = insertOrder(jdbc, mine.storeId(), supplier, "SENT", NOW.minusDays(1));
	}

	@Test
	void 입고_확인하면_받은_양_단가_시각을_적고_바뀐_피드를_준다() throws Exception {
		long item = insertItem(jdbc, mine.storeId(), order, tomatoCan, tomato, new BigDecimal("4"),
				LocalDate.of(2026, 10, 14));

		receive(order, item, """
				{"receivedQty": 10500, "unitPrice": 4}
				""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.incoming", hasSize(0)))
				.andExpect(jsonPath("$.data.history", hasSize(1)))
				.andExpect(jsonPath("$.data.history[0].itemId").value(String.valueOf(item)))
				.andExpect(jsonPath("$.data.history[0].status").value("RECEIVED"))
				.andExpect(jsonPath("$.data.history[0].receivedQty").value(10500.0))
				.andExpect(jsonPath("$.data.history[0].receivedAt").value("2026-10-14T12:00:00+09:00"))
				.andExpect(jsonPath("$.data.firstReceiptDone").value(true));

		Map<String, Object> row = jdbc.queryForMap(
				"SELECT received_qty, received_unit_price, received_at FROM purchase_order_item WHERE id = ?", item);
		assertThat((BigDecimal) row.get("received_qty")).isEqualByComparingTo("3.5");
		assertThat((BigDecimal) row.get("received_unit_price")).isEqualByComparingTo("12000");
		assertThat(jdbc.queryForObject("SELECT received_at FROM purchase_order_item WHERE id = ?",
				OffsetDateTime.class, item).withOffsetSameInstant(ZoneOffset.UTC))
				.isEqualTo(NOW.withOffsetSameInstant(ZoneOffset.UTC));
	}

	@Test
	void 입고_확인은_price_history_에_RECEIVE_한_줄_포장_1개_단가로() throws Exception {
		long item = insertItem(jdbc, mine.storeId(), order, tomatoCan, tomato, new BigDecimal("4"), null);

		receive(order, item, """
				{"receivedQty": 12000, "unitPrice": 4}
				""").andExpect(status().isOk());

		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT store_id, price, unit, source, observed_at FROM price_history WHERE supplier_product_id = ?",
				tomatoCan);
		assertThat(rows).hasSize(1);
		assertThat(((Number) rows.get(0).get("store_id")).longValue()).isEqualTo(mine.storeId());
		assertThat((BigDecimal) rows.get(0).get("price")).isEqualByComparingTo("12000");
		assertThat(rows.get(0).get("unit")).isEqualTo("캔");
		assertThat(rows.get(0).get("source")).isEqualTo("RECEIVE");
		assertThat(jdbc.queryForObject("SELECT observed_at FROM price_history WHERE supplier_product_id = ?",
				OffsetDateTime.class, tomatoCan).toInstant()).isEqualTo(NOW.toInstant());
	}

	@Test
	void 단가를_안_바꿔도_price_history_한_줄_포장_단위를_모르면_PACK() throws Exception {
		long loose = insertProduct(jdbc, mine.storeId(), supplier, tomato, "토마토 낱개", null, null,
				new BigDecimal("900"));
		long item = insertItem(jdbc, mine.storeId(), order, loose, tomato, new BigDecimal("10"), null);

		receive(order, item, """
				{"receivedQty": 10, "unitPrice": 900, "basePerPack": 1}
				""").andExpect(status().isOk());

		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT price, unit, source FROM price_history WHERE supplier_product_id = ?", loose);
		assertThat(rows).hasSize(1);
		assertThat((BigDecimal) rows.get(0).get("price")).isEqualByComparingTo("900");
		assertThat(rows.get(0).get("unit")).isEqualTo("PACK");
		assertThat(rows.get(0).get("source")).isEqualTo("RECEIVE");
	}

	@Test
	void 첫_입고면_재료_단위가_LOCKED() throws Exception {
		jdbc.update("UPDATE ingredient SET unit_status = 'CONFIRMED' WHERE id = ?", tomato);
		long item = insertItem(jdbc, mine.storeId(), order, tomatoCan, tomato, new BigDecimal("4"), null);

		receive(order, item, """
				{"receivedQty": 12000, "unitPrice": 3.9}
				""").andExpect(status().isOk());

		assertThat(unitStatus(tomato)).isEqualTo("LOCKED");
	}

	@Test
	void 다른_재료는_건드리지_않는다() throws Exception {
		long onion = insertIngredient(jdbc, mine.storeId(), "양파");
		long item = insertItem(jdbc, mine.storeId(), order, tomatoCan, tomato, new BigDecimal("4"), null);

		receive(order, item, """
				{"receivedQty": 12000, "unitPrice": 3.9}
				""").andExpect(status().isOk());

		assertThat(unitStatus(tomato)).isEqualTo("LOCKED");
		assertThat(unitStatus(onion)).isEqualTo("ESTIMATED");
	}

	@Test
	void 영수증_확정이_부를_공개_메서드도_같은_기록을_남긴다() {
		long item = insertItem(jdbc, mine.storeId(), order, tomatoCan, tomato, new BigDecimal("4"), null);

		receivingService.recordReceipt(mine.storeId(), item, new BigDecimal("4"), new BigDecimal("11500"));

		assertThat(jdbc.queryForObject("SELECT received_qty FROM purchase_order_item WHERE id = ?",
				BigDecimal.class, item)).isEqualByComparingTo("4");
		assertThat(jdbc.queryForObject("SELECT received_unit_price FROM purchase_order_item WHERE id = ?",
				BigDecimal.class, item)).isEqualByComparingTo("11500");
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM price_history WHERE supplier_product_id = ? AND source = 'RECEIVE'",
				Integer.class, tomatoCan)).isEqualTo(1);
		assertThat(unitStatus(tomato)).isEqualTo("LOCKED");
	}

	/** 112 는 DB 에서 읽은 값(NUMERIC(12,2) 단가 = 11700.00, NUMERIC(14,3) 수량 = 4.000)을 그대로 넘긴다. */
	@Test
	void 공개_메서드는_DB_에서_읽은_끝자리_0_붙은_수량_단가를_받는다() {
		long item = insertItem(jdbc, mine.storeId(), order, tomatoCan, tomato, new BigDecimal("4"), null);
		BigDecimal priceFromDb = jdbc.queryForObject("SELECT CAST(11500 AS NUMERIC(12,2))", BigDecimal.class);
		BigDecimal qtyFromDb = jdbc.queryForObject("SELECT CAST(4 AS NUMERIC(14,3))", BigDecimal.class);
		assertThat(priceFromDb.scale()).isEqualTo(2);
		assertThat(qtyFromDb.scale()).isEqualTo(3);

		receivingService.recordReceipt(mine.storeId(), item, qtyFromDb, priceFromDb);

		assertThat(jdbc.queryForObject("SELECT received_qty FROM purchase_order_item WHERE id = ?",
				BigDecimal.class, item)).isEqualByComparingTo("4");
		assertThat(jdbc.queryForObject("SELECT received_unit_price FROM purchase_order_item WHERE id = ?",
				BigDecimal.class, item)).isEqualByComparingTo("11500");
		assertThat(jdbc.queryForObject("SELECT price FROM price_history WHERE supplier_product_id = ?",
				BigDecimal.class, tomatoCan)).isEqualByComparingTo("11500");
	}

	/** 영수증 확정(112)은 한 트랜잭션에서 여러 줄을 확정한다 — 한 줄이 409 여도 그 줄만 건너뛰고 나머지를 커밋할 수 있어야 한다. */
	@Test
	void 공개_메서드가_409_를_던져도_부르는_쪽_트랜잭션은_커밋된다() {
		long received = insertItem(jdbc, mine.storeId(), order, tomatoCan, tomato, new BigDecimal("1"), null);
		ReceivingTestData.markReceived(jdbc, received, BigDecimal.ONE, new BigDecimal("11700"), NOW.minusHours(1));
		long cancelled = insertItem(jdbc, mine.storeId(), order, tomatoCan, tomato, new BigDecimal("1"), null);
		ReceivingTestData.markCancelled(jdbc, cancelled, NOW.minusHours(1));
		long next = insertItem(jdbc, mine.storeId(), order, tomatoCan, tomato, new BigDecimal("4"), null);

		List<String> skipped = new ArrayList<>();
		transactionTemplate.executeWithoutResult(status -> {
			for (long id : new long[] {received, cancelled, 999_999_999L, next}) {
				try {
					receivingService.recordReceipt(mine.storeId(), id, new BigDecimal("4"), null);
				} catch (BusinessException e) {
					skipped.add(e.getErrorCode().getCode());
				}
			}
		});

		assertThat(skipped).containsExactly("RECEIVING_ALREADY_RECEIVED", "RECEIVING_CANCELLED", "COMMON_NOT_FOUND");
		assertThat(jdbc.queryForObject("SELECT received_qty FROM purchase_order_item WHERE id = ?",
				BigDecimal.class, next)).isEqualByComparingTo("4");
		assertThat(ReceivingTestData.priceRowCount(jdbc, tomatoCan)).isEqualTo(1);
	}

	@Test
	void 공개_메서드가_400_을_던져도_부르는_쪽_트랜잭션은_커밋된다() {
		long next = insertItem(jdbc, mine.storeId(), order, tomatoCan, tomato, new BigDecimal("4"), null);

		transactionTemplate.executeWithoutResult(status -> {
			try {
				receivingService.recordReceipt(mine.storeId(), next, BigDecimal.ZERO, null);
			} catch (BusinessException e) {
				assertThat(e.getErrorCode().getCode()).isEqualTo("COMMON_INVALID_INPUT");
			}
			receivingService.recordReceipt(mine.storeId(), next, new BigDecimal("4"), null);
		});

		assertThat(jdbc.queryForObject("SELECT received_qty FROM purchase_order_item WHERE id = ?",
				BigDecimal.class, next)).isEqualByComparingTo("4");
	}

	private String unitStatus(long ingredientId) {
		return jdbc.queryForObject("SELECT unit_status FROM ingredient WHERE id = ?", String.class, ingredientId);
	}

	private ResultActions receive(long orderId, long itemId, String body) throws Exception {
		return mockMvc.perform(patch("/purchase-orders/{orderId}/items/{itemId}/receive", orderId, itemId)
				.header(HttpHeaders.AUTHORIZATION, mine.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}
}
