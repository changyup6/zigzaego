package com.zigzaego.api.receiving;

import static com.zigzaego.api.receiving.ReceivingTestData.insertItem;
import static com.zigzaego.api.receiving.ReceivingTestData.insertOrder;
import static com.zigzaego.api.receiving.ReceivingTestData.insertProduct;
import static com.zigzaego.api.receiving.ReceivingTestData.insertSupplier;
import static com.zigzaego.api.receiving.ReceivingTestData.markCancelled;
import static com.zigzaego.api.receiving.ReceivingTestData.priceRowCount;
import static com.zigzaego.api.support.StoreFixtures.insertIngredient;
import static com.zigzaego.api.support.StoreFixtures.loginNewStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 입고 확인 경계 (S15P31A308-71, 제안 문서 2절 표 · 4절 제안값). 400 이면 아무것도 쓰지 않고, 409 · 404 도 마찬가지.
 */
@Import({TestcontainersConfiguration.class, FixedClockConfig.class})
@SpringBootTest
class ReceivingBoundaryTest {

	private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-14T12:00:00+09:00");

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private ReceivingService receivingService;

	private MockMvc mockMvc;

	private LoggedInStore mine;

	private long supplier;

	private long tomato;

	private long tomatoCan;

	private long order;

	private long item;

	@BeforeEach
	void setUp() throws Exception {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		mine = loginNewStore(jdbc, passwordEncoder, mockMvc, "입고가게");
		supplier = insertSupplier(jdbc, mine.storeId(), "OO상회");
		tomato = insertIngredient(jdbc, mine.storeId(), "토마토");
		tomatoCan = insertProduct(jdbc, mine.storeId(), supplier, tomato, "토마토 캔", "캔",
				new BigDecimal("3000"), new BigDecimal("11700"));
		order = insertOrder(jdbc, mine.storeId(), supplier, "SENT", NOW.minusDays(1));
		item = insertItem(jdbc, mine.storeId(), order, tomatoCan, tomato, new BigDecimal("4"), null);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"{\"receivedQty\": 0, \"unitPrice\": 3.9}",
			"{\"receivedQty\": -1, \"unitPrice\": 3.9}",
			"{\"receivedQty\": 12000.0001, \"unitPrice\": 3.9}",
			"{\"unitPrice\": 3.9}",
			"{\"receivedQty\": 12000, \"unitPrice\": -1}",
			"{\"receivedQty\": 12000, \"unitPrice\": 3.90001}",
			"{\"receivedQty\": 12000, \"basePerPack\": 0}",
			"{\"receivedQty\": 12000, \"basePerPack\": 1.2345}"
	})
	void 잘못된_수량_단가는_400_COMMON_INVALID_INPUT_이고_아무것도_안_쓴다(String body) throws Exception {
		receive(mine, String.valueOf(order), String.valueOf(item), body)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));

		assertNothingWritten();
	}

	@Test
	void 공개_메서드도_정수_자릿수가_넘치는_수량_단가는_400_COMMON_INVALID_INPUT() {
		assertThat(serviceOutcome(item, "1E+12", "100")).isEqualTo("COMMON_INVALID_INPUT");
		assertThat(serviceOutcome(item, "1", "1E+11")).isEqualTo("COMMON_INVALID_INPUT");

		assertNothingWritten();
	}

	/**
	 * 주소 경로(기준 단위, 포장 3000 g): 끝자리 0 은 무시하고(12000.0000 = 12000, 4.000E+3 = 4000) 자릿수 · 부호는 400.
	 * 칸 모양이 틀리면 fieldErrors 가 붙고, 모양은 맞는데 포장 개수 · 포장 1개 값으로 바꾼 결과가 0 이거나 칸을 넘으면
	 * fieldErrors 없이 400. 기대: 200 이면 받고, 400 이면 COMMON_INVALID_INPUT.
	 */
	@ParameterizedTest
	@ValueSource(strings = {
			"12000.0000|3.9|200|", "12000.0001|3.9|400|field", "1500.125|3.9|200|", "4.000E+3|3.90000|200|",
			"1E+12|3.9|400|field", "100000000000|1|400|field", "99999999999.999|1|200|", "1.4|3.9|400|",
			"3000|1E+10|400|field", "3000|9999999999|400|", "3000|3.1234|200|", "3000|3.12345|400|field",
			"3000|1e0|200|", "3000|0.00|200|", "3000|0.0001|400|", "0|3.9|400|field", "-1|3.9|400|field",
			"3000|-1|400|field"
	})
	void 주소_경로의_400_판정(String row) throws Exception {
		String[] parts = row.split("\\|", -1);
		int expected = Integer.parseInt(parts[2]);
		long viaHttp = insertItem(jdbc, mine.storeId(), order, tomatoCan, tomato, new BigDecimal("4"), null);

		ResultActions http = receive(mine, String.valueOf(order), String.valueOf(viaHttp), """
				{"receivedQty": %s, "unitPrice": %s}
				""".formatted(parts[0], parts[1]));

		http.andExpect(status().is(expected));
		if (expected == 400) {
			http.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
			if ("field".equals(parts[3])) {
				http.andExpect(jsonPath("$.error.fieldErrors").isNotEmpty());
			}
		}
	}

	/**
	 * 공개 메서드(영수증 확정이 부름, 포장 단위): 검사 전에 끝자리 0 을 떼므로 숫자 값이 같으면 판정도 같다
	 * (11700.00 = 11700, 4.000 = 4, 1E+3 = 1000). 기대: 200 이면 "OK", 400 이면 COMMON_INVALID_INPUT.
	 */
	@ParameterizedTest
	@ValueSource(strings = {
			"1.2340|11700|200", "1.2345|11700|400", "1.125|11700|200", "1.0000|100|200", "0.0001|100|400",
			"4.000|11700.00|200", "1E+12|100|400", "100000000000|1|400", "99999999999.999|9999999999|200",
			"1|1E+11|400", "1|10000000000|400", "1|99999999999|400", "1|1e3|200", "1|100.0|200", "1|100.5|400",
			"1|11700.5|400", "1|0.00|200", "1E-3|1E+2|200", "0|100|400", "0.000|100|400", "-1|100|400", "1|-1|400"
	})
	void 공개_메서드의_400_판정(String triple) {
		String[] parts = triple.split("\\|");
		int expected = Integer.parseInt(parts[2]);
		long viaService = insertItem(jdbc, mine.storeId(), order, tomatoCan, tomato, new BigDecimal("4"), null);

		assertThat(serviceOutcome(viaService, parts[0], parts[1])).isEqualTo(expected == 200 ? "OK" : "COMMON_INVALID_INPUT");
	}

	/**
	 * 지수가 큰 숫자는 펼치지 않고 바로 400 이어야 한다 — 1E+99999999 를 1억 자리 정수로 펼치면 요청이 수십 초 묶이고
	 * 응답(rejectedValue)이 MB 단위가 된다. 두 경로, 수량 · 단가 두 칸 모두. 기대: 2초 안에 400 COMMON_INVALID_INPUT, 본문 2KB 이하.
	 */
	@ParameterizedTest
	@ValueSource(strings = {
			"1E+99999999|100", "1E+9999999|100", "1E+999999999|100",
			"1|1E+99999999", "1|1E+9999999", "1|1E+999999999"
	})
	void 지수가_큰_숫자는_펼치지_않고_바로_400(String pair) {
		String[] parts = pair.split("\\|");
		long viaHttp = insertItem(jdbc, mine.storeId(), order, tomatoCan, tomato, new BigDecimal("4"), null);
		long viaService = insertItem(jdbc, mine.storeId(), order, tomatoCan, tomato, new BigDecimal("4"), null);

		String body = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
			MvcResult result = receive(mine, String.valueOf(order), String.valueOf(viaHttp), """
					{"receivedQty": %s, "unitPrice": %s}
					""".formatted(parts[0], parts[1]))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"))
					.andReturn();
			return result.getResponse().getContentAsString();
		});
		assertThat(body.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(2048);

		BusinessException e = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> catchThrowableOfType(
				BusinessException.class,
				() -> receivingService.recordReceipt(mine.storeId(), viaService, new BigDecimal(parts[0]),
						new BigDecimal(parts[1]))));
		assertThat(e).isNotNull();
		assertThat(e.getErrorCode().getCode()).isEqualTo("COMMON_INVALID_INPUT");
		assertThat(e.getMessage().length()).isLessThanOrEqualTo(2048);
	}

	/** 0 은 지수가 붙어도 0 이다(0 의 precision 은 늘 1 이라 정수부 자릿수로 세면 안 된다). 기대: 두 경로 200 + 거래처 단가 11700. */
	@ParameterizedTest
	@ValueSource(strings = {"0E+10", "0E+11", "0.00E+12", "0E+99999999"})
	void 지수가_붙은_0_단가도_0_이라_거래처_상품_단가를_쓴다(String zero) throws Exception {
		long viaHttp = insertItem(jdbc, mine.storeId(), order, tomatoCan, tomato, new BigDecimal("4"), null);
		long viaService = insertItem(jdbc, mine.storeId(), order, tomatoCan, tomato, new BigDecimal("4"), null);

		receive(mine, String.valueOf(order), String.valueOf(viaHttp), """
				{"receivedQty": 3000, "unitPrice": %s}
				""".formatted(zero)).andExpect(status().isOk());
		receivingService.recordReceipt(mine.storeId(), viaService, BigDecimal.ONE, new BigDecimal(zero));

		for (long id : new long[] {viaHttp, viaService}) {
			assertThat(jdbc.queryForObject("SELECT received_unit_price FROM purchase_order_item WHERE id = ?",
					BigDecimal.class, id)).isEqualByComparingTo("11700");
		}
		assertThat(jdbc.queryForList("SELECT price FROM price_history WHERE supplier_product_id = ?",
				BigDecimal.class, tomatoCan)).hasSize(2).allSatisfy(p -> assertThat(p).isEqualByComparingTo("11700"));
	}

	/**
	 * 숫자는 1000자리(precision)까지 — HTTP 는 Jackson 숫자 길이 상한(1000자)에서 막힌다. 공개 메서드도 같은 상한에서 400 이고,
	 * 끝자리 0 떼기(길이 제곱 비용)를 하지 않는다. 기대: 2초 안에 COMMON_INVALID_INPUT, 메시지 2KB 이하.
	 */
	@ParameterizedTest
	@ValueSource(ints = {1_000, 100_000})
	void 공개_메서드도_1000자리가_넘는_숫자는_바로_400(int zeros) {
		BigDecimal packs = new BigDecimal("1." + "0".repeat(zeros));
		assertThat(packs.precision()).isGreaterThan(1000);

		BusinessException e = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> catchThrowableOfType(
				BusinessException.class,
				() -> receivingService.recordReceipt(mine.storeId(), item, packs, new BigDecimal("100"))));
		assertThat(e).isNotNull();
		assertThat(e.getErrorCode().getCode()).isEqualTo("COMMON_INVALID_INPUT");
		assertThat(e.getMessage().length()).isLessThanOrEqualTo(2048);

		BusinessException price = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> catchThrowableOfType(
				BusinessException.class,
				() -> receivingService.recordReceipt(mine.storeId(), item, BigDecimal.ONE,
						new BigDecimal("100." + "0".repeat(zeros)))));
		assertThat(price).isNotNull();
		assertThat(price.getErrorCode().getCode()).isEqualTo("COMMON_INVALID_INPUT");

		assertNothingWritten();
	}

	@Test
	void 단가_0_00_도_0_이라_거래처_상품_단가를_쓴다() throws Exception {
		receive(mine, String.valueOf(order), String.valueOf(item), """
				{"receivedQty": 3000, "unitPrice": 0.00}
				""").andExpect(status().isOk());
		assertThat(receivedUnitPrice()).isEqualByComparingTo("11700");

		long viaService = insertItem(jdbc, mine.storeId(), order, tomatoCan, tomato, new BigDecimal("4"), null);
		receivingService.recordReceipt(mine.storeId(), viaService, BigDecimal.ONE, new BigDecimal("0.00"));
		assertThat(jdbc.queryForObject("SELECT received_unit_price FROM purchase_order_item WHERE id = ?",
				BigDecimal.class, viaService)).isEqualByComparingTo("11700");
	}

	@Test
	void 소수_셋째_자리까지는_된다() throws Exception {
		receive(mine, String.valueOf(order), String.valueOf(item), """
				{"receivedQty": 3375.375, "unitPrice": 3.9}
				""").andExpect(status().isOk());

		assertThat(jdbc.queryForObject("SELECT received_qty FROM purchase_order_item WHERE id = ?",
				BigDecimal.class, item)).isEqualByComparingTo("1.125");
	}

	@Test
	void 단가_0_이면_거래처_상품_단가를_쓴다() throws Exception {
		receive(mine, String.valueOf(order), String.valueOf(item), """
				{"receivedQty": 12000, "unitPrice": 0}
				""").andExpect(status().isOk());

		assertThat(receivedUnitPrice()).isEqualByComparingTo("11700");
		assertThat(jdbc.queryForObject("SELECT price FROM price_history WHERE supplier_product_id = ?",
				BigDecimal.class, tomatoCan)).isEqualByComparingTo("11700");
	}

	@Test
	void 단가를_생략하면_거래처_상품_단가를_쓴다() throws Exception {
		receive(mine, String.valueOf(order), String.valueOf(item), """
				{"receivedQty": 12000}
				""").andExpect(status().isOk());

		assertThat(receivedUnitPrice()).isEqualByComparingTo("11700");
		assertThat(priceRowCount(jdbc, tomatoCan)).isEqualTo(1);
	}

	@Test
	void 단가도_거래처_단가도_없으면_단가는_비우고_price_history_는_안_쓴다() throws Exception {
		long loose = insertProduct(jdbc, mine.storeId(), supplier, tomato, "토마토 낱개", null, null, null);
		long looseItem = insertItem(jdbc, mine.storeId(), order, loose, tomato, new BigDecimal("2"), null);

		receive(mine, String.valueOf(order), String.valueOf(looseItem), """
				{"receivedQty": 2, "basePerPack": 1}
				""").andExpect(status().isOk());

		assertThat(jdbc.queryForObject("SELECT received_unit_price FROM purchase_order_item WHERE id = ?",
				BigDecimal.class, looseItem)).isNull();
		assertThat(jdbc.queryForObject("SELECT received_at IS NOT NULL FROM purchase_order_item WHERE id = ?",
				Boolean.class, looseItem)).isTrue();
		assertThat(priceRowCount(jdbc, loose)).isZero();
	}

	@Test
	void 같은_줄_두_번째_입고_확인은_409_이고_price_history_가_늘지_않는다() throws Exception {
		receive(mine, String.valueOf(order), String.valueOf(item), """
				{"receivedQty": 12000, "unitPrice": 3.9}
				""").andExpect(status().isOk());
		assertThat(priceRowCount(jdbc, tomatoCan)).isEqualTo(1);

		receive(mine, String.valueOf(order), String.valueOf(item), """
				{"receivedQty": 6000, "unitPrice": 3}
				""")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("RECEIVING_ALREADY_RECEIVED"));

		assertThat(priceRowCount(jdbc, tomatoCan)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT received_qty FROM purchase_order_item WHERE id = ?",
				BigDecimal.class, item)).isEqualByComparingTo("4");
		assertThat(receivedUnitPrice()).isEqualByComparingTo("11700");
	}

	@Test
	void 취소된_줄은_409_RECEIVING_CANCELLED() throws Exception {
		markCancelled(jdbc, item, NOW.minusHours(1));

		receive(mine, String.valueOf(order), String.valueOf(item), """
				{"receivedQty": 12000, "unitPrice": 3.9}
				""")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("RECEIVING_CANCELLED"));

		assertNothingWritten();
	}

	@Test
	void 취소된_발주서의_줄은_409_RECEIVING_CANCELLED() throws Exception {
		jdbc.update("UPDATE purchase_order SET status = 'CANCELLED' WHERE id = ?", order);

		receive(mine, String.valueOf(order), String.valueOf(item), """
				{"receivedQty": 12000, "unitPrice": 3.9}
				""")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("RECEIVING_CANCELLED"));

		assertNothingWritten();
	}

	@Test
	void 남의_가게_줄은_404() throws Exception {
		LoggedInStore other = loginNewStore(jdbc, passwordEncoder, mockMvc, "남의가게");

		receive(other, String.valueOf(order), String.valueOf(item), """
				{"receivedQty": 12000, "unitPrice": 3.9}
				""")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));

		assertNothingWritten();
	}

	@Test
	void 없는_id_다른_발주서의_줄_숫자_아닌_id_는_404() throws Exception {
		long anotherOrder = insertOrder(jdbc, mine.storeId(), supplier, "SENT", NOW);
		String body = """
				{"receivedQty": 12000, "unitPrice": 3.9}
				""";

		receive(mine, String.valueOf(order), "999999999", body)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));
		receive(mine, "999999999", String.valueOf(item), body)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));
		receive(mine, String.valueOf(anotherOrder), String.valueOf(item), body)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));
		receive(mine, "abc", String.valueOf(item), body)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));
		receive(mine, String.valueOf(order), "abc", body)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));

		assertNothingWritten();
	}

	private void assertNothingWritten() {
		assertThat(jdbc.queryForObject(
				"SELECT received_qty IS NULL AND received_at IS NULL AND received_unit_price IS NULL"
						+ " FROM purchase_order_item WHERE id = ?", Boolean.class, item)).isTrue();
		assertThat(priceRowCount(jdbc, tomatoCan)).isZero();
		assertThat(jdbc.queryForObject("SELECT unit_status FROM ingredient WHERE id = ?", String.class, tomato))
				.isEqualTo("ESTIMATED");
	}

	/** 공개 메서드 결과: "OK", BusinessException 이면 오류 코드, 그 밖의 예외면 클래스 이름. */
	private String serviceOutcome(long itemId, String packs, String price) {
		try {
			receivingService.recordReceipt(mine.storeId(), itemId, new BigDecimal(packs), new BigDecimal(price));
			return "OK";
		} catch (BusinessException e) {
			return e.getErrorCode().getCode();
		} catch (RuntimeException e) {
			return e.getClass().getName();
		}
	}

	private BigDecimal receivedUnitPrice() {
		return jdbc.queryForObject("SELECT received_unit_price FROM purchase_order_item WHERE id = ?",
				BigDecimal.class, item);
	}

	private ResultActions receive(LoggedInStore store, String orderId, String itemId, String body) throws Exception {
		return mockMvc.perform(patch("/purchase-orders/{orderId}/items/{itemId}/receive", orderId, itemId)
				.header(HttpHeaders.AUTHORIZATION, store.bearer())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}
}
