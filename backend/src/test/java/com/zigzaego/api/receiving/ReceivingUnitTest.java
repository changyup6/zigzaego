package com.zigzaego.api.receiving;

import static com.zigzaego.api.receiving.ReceivingTestData.insertItem;
import static com.zigzaego.api.receiving.ReceivingTestData.insertOrder;
import static com.zigzaego.api.receiving.ReceivingTestData.insertProduct;
import static com.zigzaego.api.receiving.ReceivingTestData.insertSupplier;
import static com.zigzaego.api.receiving.ReceivingTestData.linkRecommendation;
import static com.zigzaego.api.receiving.ReceivingTestData.priceRowCount;
import static com.zigzaego.api.support.StoreFixtures.insertIngredient;
import static com.zigzaego.api.support.StoreFixtures.loginNewStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.inventory.dto.InventoryEstimateResponse;
import com.zigzaego.api.inventory.service.InventoryEstimateService;
import com.zigzaego.api.support.FixedClockConfig;
import com.zigzaego.api.support.StoreFixtures.LoggedInStore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
 * 입고 확인 API 의 수량 · 단가 = 재료 기준 단위 (S15P31A308-71, DECISIONS #42 · #57 · #55).
 * 요청 receivedQty(기준 단위) · unitPrice(원 / 기준 단위 1)를 서버가 DB 칸 단위(포장 개수 · 포장 1개 원 정수)로 바꿔 쓰고,
 * 피드는 기준 단위로 준다. 토마토 캔 = 3000 g, 포장 1개 11700원.
 */
@Import({TestcontainersConfiguration.class, FixedClockConfig.class})
@SpringBootTest
class ReceivingUnitTest {

	private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-14T12:00:00+09:00");

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private InventoryEstimateService inventoryEstimateService;

	private MockMvc mockMvc;

	private LoggedInStore mine;

	private long supplier;

	private long tomato;

	private long tomatoCan;

	private long order;

	@BeforeEach
	void setUp() throws Exception {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		mine = loginNewStore(jdbc, passwordEncoder, mockMvc, "단위가게");
		supplier = insertSupplier(jdbc, mine.storeId(), "OO상회");
		tomato = insertIngredient(jdbc, mine.storeId(), "토마토");
		tomatoCan = insertProduct(jdbc, mine.storeId(), supplier, tomato, "토마토 캔", "캔",
				new BigDecimal("3000"), new BigDecimal("11700"));
		order = insertOrder(jdbc, mine.storeId(), supplier, "SENT", NOW.minusDays(1));
	}

	/** 6000 g · 4.25원/g → 포장 2개 · 포장 1개 12750원. price_history 도 포장 1개 값 1행. */
	@Test
	void unit_기준_단위로_받아_포장_개수와_포장_1개_원_정수로_저장한다() throws Exception {
		long item = insertItem(jdbc, mine.storeId(), order, tomatoCan, tomato, new BigDecimal("2"), null);

		receive(item, """
				{"receivedQty": 6000, "unitPrice": 4.25}
				""").andExpect(status().isOk());

		assertThat(column(item, "received_qty")).isEqualByComparingTo("2");
		assertThat(column(item, "received_unit_price")).isEqualByComparingTo("12750");
		assertThat(jdbc.queryForList("SELECT price FROM price_history WHERE supplier_product_id = ? AND source = 'RECEIVE'",
				BigDecimal.class, tomatoCan)).singleElement().satisfies(p -> assertThat(p).isEqualByComparingTo("12750"));
	}

	/** 추정 재고 증가 = receivedQty. 나누어떨어지지 않으면 포장 개수 소수 셋째 자리 반올림 오차만큼 남는다(1000 g → 0.333캔 → 999 g). */
	@Test
	void unit_추정_재고_증가는_receivedQty_이고_나눗셈_오차는_포장_크기의_0_0005배_안() throws Exception {
		long exact = insertItem(jdbc, mine.storeId(), order, tomatoCan, tomato, new BigDecimal("2"), null);
		receive(exact, """
				{"receivedQty": 6000}
				""").andExpect(status().isOk());
		assertThat(estimatedQty(tomato)).isEqualByComparingTo("6000");

		long onion = insertIngredient(jdbc, mine.storeId(), "양파");
		long onionBox = insertProduct(jdbc, mine.storeId(), supplier, onion, "양파 박스", "박스",
				new BigDecimal("3000"), new BigDecimal("9000"));
		long inexact = insertItem(jdbc, mine.storeId(), order, onionBox, onion, new BigDecimal("1"), null);
		receive(inexact, """
				{"receivedQty": 1000}
				""").andExpect(status().isOk());
		assertThat(column(inexact, "received_qty")).isEqualByComparingTo("0.333");
		assertThat(estimatedQty(onion)).isEqualByComparingTo("999");
		assertThat(new BigDecimal("1000").subtract(estimatedQty(onion)).abs())
				.isLessThanOrEqualTo(new BigDecimal("3000").multiply(new BigDecimal("0.0005")));
	}

	@Test
	void unit_포장_크기가_빈_상품은_basePerPack_없으면_400_RECEIVING_UNIT_REQUIRED_이고_아무것도_안_쓴다() throws Exception {
		long loose = insertProduct(jdbc, mine.storeId(), supplier, tomato, "토마토 낱개", null, null, new BigDecimal("900"));
		long item = insertItem(jdbc, mine.storeId(), order, loose, tomato, new BigDecimal("10"), null);

		receive(item, """
				{"receivedQty": 2000, "unitPrice": 0.45}
				""")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("RECEIVING_UNIT_REQUIRED"));

		assertThat(column(item, "received_qty")).isNull();
		assertThat(jdbc.queryForObject("SELECT received_at IS NULL FROM purchase_order_item WHERE id = ?",
				Boolean.class, item)).isTrue();
		assertThat(priceRowCount(jdbc, loose)).isZero();
		assertThat(basePerPack(loose)).isNull();
	}

	@Test
	void unit_포장_크기가_빈_상품은_basePerPack_을_받으면_그_칸을_채우고_저장한다() throws Exception {
		long loose = insertProduct(jdbc, mine.storeId(), supplier, tomato, "토마토 낱개", null, null, new BigDecimal("900"));
		long item = insertItem(jdbc, mine.storeId(), order, loose, tomato, new BigDecimal("10"), null);

		receive(item, """
				{"receivedQty": 2500, "unitPrice": 0.45, "basePerPack": 1000}
				""").andExpect(status().isOk());

		assertThat(basePerPack(loose)).isEqualByComparingTo("1000");
		assertThat(column(item, "received_qty")).isEqualByComparingTo("2.5");
		assertThat(column(item, "received_unit_price")).isEqualByComparingTo("450");
		assertThat(estimatedQty(tomato)).isEqualByComparingTo("2500");
	}

	/** 포장 크기가 이미 있으면 요청 basePerPack 은 쓰지 않는다(DB 값 우선, 칸을 덮어쓰지 않음). */
	@Test
	void unit_포장_크기가_있는_상품은_요청_basePerPack_을_무시한다() throws Exception {
		long item = insertItem(jdbc, mine.storeId(), order, tomatoCan, tomato, new BigDecimal("2"), null);

		receive(item, """
				{"receivedQty": 6000, "basePerPack": 1000}
				""").andExpect(status().isOk());

		assertThat(basePerPack(tomatoCan)).isEqualByComparingTo("3000");
		assertThat(column(item, "received_qty")).isEqualByComparingTo("2");
	}

	/** 3.3335원/g × 3000 g = 10000.5 → 10001원(5 이상 올림, #55 원 정수). 0 · 생략 = 거래처 상품 단가(포장 1개 11700원 그대로). */
	@Test
	void unit_단가_환산은_원_정수로_반올림하고_0_생략은_거래처_단가() throws Exception {
		long rounded = insertItem(jdbc, mine.storeId(), order, tomatoCan, tomato, new BigDecimal("1"), null);
		receive(rounded, """
				{"receivedQty": 3000, "unitPrice": 3.3335}
				""").andExpect(status().isOk());
		assertThat(column(rounded, "received_unit_price")).isEqualByComparingTo("10001");

		long zero = insertItem(jdbc, mine.storeId(), order, tomatoCan, tomato, new BigDecimal("1"), null);
		receive(zero, """
				{"receivedQty": 3000, "unitPrice": 0}
				""").andExpect(status().isOk());
		assertThat(column(zero, "received_unit_price")).isEqualByComparingTo("11700");

		long omitted = insertItem(jdbc, mine.storeId(), order, tomatoCan, tomato, new BigDecimal("1"), null);
		receive(omitted, """
				{"receivedQty": 3000}
				""").andExpect(status().isOk());
		assertThat(column(omitted, "received_unit_price")).isEqualByComparingTo("11700");
		assertThat(jdbc.queryForList("SELECT price FROM price_history WHERE supplier_product_id = ? ORDER BY id",
				BigDecimal.class, tomatoCan)).extracting(BigDecimal::toBigInteger)
				.containsExactly(new java.math.BigInteger("10001"), new java.math.BigInteger("11700"),
						new java.math.BigInteger("11700"));
	}

	/** 기준 단위로 바꾼 값이 DB 칸(포장 개수 NUMERIC(14,3) · 포장 1개 값 NUMERIC(12,2))에 0 이 되거나 넘치면 400. */
	@Test
	void unit_포장_개수가_0_으로_반올림되거나_포장_1개_값이_넘치면_400() throws Exception {
		long item = insertItem(jdbc, mine.storeId(), order, tomatoCan, tomato, new BigDecimal("1"), null);

		receive(item, """
				{"receivedQty": 1}
				""")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
		receive(item, """
				{"receivedQty": 3000, "unitPrice": 9999999}
				""")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
		receive(item, """
				{"receivedQty": 3000, "unitPrice": 0.0001}
				""")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));

		assertThat(column(item, "received_qty")).isNull();
		assertThat(priceRowCount(jdbc, tomatoCan)).isZero();

		// 같은 줄에 맞는 값이면 받는다 — 위 400 은 모양이 아니라 환산 결과 때문이다
		receive(item, """
				{"receivedQty": 3000, "unitPrice": 3.9}
				""").andExpect(status().isOk());
		assertThat(column(item, "received_unit_price")).isEqualByComparingTo("11700");
	}

	/** GET 수량 = 기준 단위 소수 셋째 자리, 단가 = 단위 단가 소수 넷째 자리. 포장 개수 · 포장 1개 값 칸은 없다. */
	@Test
	void unit_GET_응답은_기준_단위_수량_셋째_자리_단위_단가_넷째_자리() throws Exception {
		long tomatoCan2 = insertProduct(jdbc, mine.storeId(), supplier, tomato, "토마토 캔 2", "캔",
				new BigDecimal("3000"), new BigDecimal("11701"));
		long incoming = insertItem(jdbc, mine.storeId(), order, tomatoCan2, tomato, new BigDecimal("4"),
				LocalDate.of(2026, 10, 14));
		linkRecommendation(jdbc, mine.storeId(), tomato, incoming, LocalDate.of(2026, 10, 13), new BigDecimal("12345.6"));

		String body = mockMvc.perform(get("/receiving").header(HttpHeaders.AUTHORIZATION, mine.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.incoming[0].unit").value("g"))
				.andExpect(jsonPath("$.data.history[0].unit").value("g"))
				.andExpect(jsonPath("$.data.incoming[0].orderedPacks").doesNotExist())
				.andExpect(jsonPath("$.data.incoming[0].unitPricePerPack").doesNotExist())
				.andExpect(jsonPath("$.data.history[0].finalPacks").doesNotExist())
				.andReturn().getResponse().getContentAsString();

		assertThat(body).contains("\"qty\":12000.000", "\"unitPrice\":3.9003", "\"basePerPack\":3000.000",
				"\"recommendedQty\":12345.600", "\"finalQty\":12000.000", "\"receivedQty\":null");

		receive(incoming, """
				{"receivedQty": 11999.5}
				""").andExpect(status().isOk())
				.andExpect(jsonPath("$.data.history[0].receivedQty").value(12000.0));
	}

	private BigDecimal estimatedQty(long ingredientId) {
		return inventoryEstimateService.estimate(mine.storeId(), LocalDate.of(2026, 10, 14)).stream()
				.filter(r -> r.ingredientId().equals(String.valueOf(ingredientId)))
				.map(InventoryEstimateResponse::estimatedQty)
				.findFirst().orElseThrow();
	}

	private BigDecimal column(long itemId, String name) {
		return jdbc.queryForObject("SELECT " + name + " FROM purchase_order_item WHERE id = ?", BigDecimal.class, itemId);
	}

	private BigDecimal basePerPack(long productId) {
		return jdbc.queryForObject("SELECT base_qty_per_pack FROM supplier_product WHERE id = ?", BigDecimal.class,
				productId);
	}

	private ResultActions receive(long itemId, String body) throws Exception {
		return mockMvc.perform(patch("/purchase-orders/{orderId}/items/{itemId}/receive", order, itemId)
				.header(HttpHeaders.AUTHORIZATION, mine.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}
}
