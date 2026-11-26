package com.zigzaego.api.receiving;

import static com.zigzaego.api.receiving.ReceivingTestData.insertItem;
import static com.zigzaego.api.receiving.ReceivingTestData.insertOrder;
import static com.zigzaego.api.receiving.ReceivingTestData.insertProduct;
import static com.zigzaego.api.receiving.ReceivingTestData.insertSupplier;
import static com.zigzaego.api.receiving.ReceivingTestData.linkRecommendation;
import static com.zigzaego.api.receiving.ReceivingTestData.markCancelled;
import static com.zigzaego.api.receiving.ReceivingTestData.markReceived;
import static com.zigzaego.api.support.StoreFixtures.insertIngredient;
import static com.zigzaego.api.support.StoreFixtures.loginNewStore;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zigzaego.api.TestcontainersConfiguration;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** GET /receiving — 입고 예정 · 최근 발주(14일 · 50줄) · 첫 입고 여부 (S15P31A308-71, 제안 문서 2절). 오늘 = 서울 2026-10-14. */
@Import({TestcontainersConfiguration.class, FixedClockConfig.class})
@SpringBootTest
class ReceivingFeedTest {

	private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-14T12:00:00+09:00");

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PasswordEncoder passwordEncoder;

	private MockMvc mockMvc;

	private LoggedInStore mine;

	private long supplier;

	private long tomato;

	private long tomatoCan;

	@BeforeEach
	void setUp() throws Exception {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		mine = loginNewStore(jdbc, passwordEncoder, mockMvc, "입고가게");
		supplier = insertSupplier(jdbc, mine.storeId(), "OO상회");
		tomato = insertIngredient(jdbc, mine.storeId(), "토마토");
		tomatoCan = insertProduct(jdbc, mine.storeId(), supplier, tomato, "토마토 캔", "캔",
				new BigDecimal("3000"), new BigDecimal("11700"));
	}

	@Test
	void 입고_예정_줄을_제안_문서_모양으로_준다() throws Exception {
		long order = insertOrder(jdbc, mine.storeId(), supplier, "SENT", NOW.minusDays(1));
		long item = insertItem(jdbc, mine.storeId(), order, tomatoCan, tomato, new BigDecimal("4"),
				LocalDate.of(2026, 10, 14));

		feed(mine)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.incoming", hasSize(1)))
				.andExpect(jsonPath("$.data.incoming[0].orderId").value(String.valueOf(order)))
				.andExpect(jsonPath("$.data.incoming[0].itemId").value(String.valueOf(item)))
				.andExpect(jsonPath("$.data.incoming[0].ingredientId").value(String.valueOf(tomato)))
				.andExpect(jsonPath("$.data.incoming[0].name").value("토마토 캔"))
				.andExpect(jsonPath("$.data.incoming[0].supplier").value("OO상회"))
				.andExpect(jsonPath("$.data.incoming[0].packLabel").value("3000 g 캔"))
				.andExpect(jsonPath("$.data.incoming[0].unit").value("g"))
				.andExpect(jsonPath("$.data.incoming[0].basePerPack").value(3000.0))
				.andExpect(jsonPath("$.data.incoming[0].qty").value(12000.0))
				.andExpect(jsonPath("$.data.incoming[0].unitPrice").value(3.9))
				.andExpect(jsonPath("$.data.incoming[0].expectedDate").value("2026-10-14"))
				.andExpect(jsonPath("$.data.incoming[0].today").value(true))
				.andExpect(jsonPath("$.data.firstReceiptDone").value(false));
	}

	@Test
	void 포장_환산을_모르는_상품은_basePerPack_qty_unitPrice_null_내일_도착은_today_false() throws Exception {
		long unknown = insertProduct(jdbc, mine.storeId(), supplier, tomato, "토마토 낱개", null, null,
				new BigDecimal("900"));
		long order = insertOrder(jdbc, mine.storeId(), supplier, "SENT", NOW); // 입고 예정은 보낸 발주서만 (S15P31A308-200)
		insertItem(jdbc, mine.storeId(), order, unknown, tomato, new BigDecimal("2"), LocalDate.of(2026, 10, 15));

		feed(mine)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.incoming", hasSize(1)))
				.andExpect(jsonPath("$.data.incoming[0].basePerPack").value(nullValue()))
				.andExpect(jsonPath("$.data.incoming[0].packLabel").value(nullValue()))
				.andExpect(jsonPath("$.data.incoming[0].unit").value("g"))
				.andExpect(jsonPath("$.data.incoming[0].qty").value(nullValue()))
				.andExpect(jsonPath("$.data.incoming[0].unitPrice").value(nullValue()))
				.andExpect(jsonPath("$.data.incoming[0].today").value(false));
	}

	@Test
	void 취소된_줄_취소된_발주서_입고된_줄_남의_가게_줄은_입고_예정에서_빠진다() throws Exception {
		long order = insertOrder(jdbc, mine.storeId(), supplier, "SENT", NOW.minusDays(1));
		long keep = insertItem(jdbc, mine.storeId(), order, tomatoCan, tomato, new BigDecimal("4"), null);
		long cancelled = insertItem(jdbc, mine.storeId(), order, tomatoCan, tomato, new BigDecimal("1"), null);
		markCancelled(jdbc, cancelled, NOW.minusHours(1));
		long received = insertItem(jdbc, mine.storeId(), order, tomatoCan, tomato, new BigDecimal("1"), null);
		markReceived(jdbc, received, new BigDecimal("1"), new BigDecimal("11700"), NOW.minusHours(2));
		long cancelledOrder = insertOrder(jdbc, mine.storeId(), supplier, "CANCELLED", NOW.minusDays(1));
		insertItem(jdbc, mine.storeId(), cancelledOrder, tomatoCan, tomato, new BigDecimal("1"), null);

		LoggedInStore other = loginNewStore(jdbc, passwordEncoder, mockMvc, "남의가게");
		long otherSupplier = insertSupplier(jdbc, other.storeId(), "남의상회");
		long otherIngredient = insertIngredient(jdbc, other.storeId(), "토마토");
		long otherProduct = insertProduct(jdbc, other.storeId(), otherSupplier, otherIngredient, "토마토 캔", null,
				null, null);
		long otherOrder = insertOrder(jdbc, other.storeId(), otherSupplier, "SENT", NOW);
		insertItem(jdbc, other.storeId(), otherOrder, otherProduct, otherIngredient, new BigDecimal("9"), null);

		feed(mine)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.incoming", hasSize(1)))
				.andExpect(jsonPath("$.data.incoming[0].itemId").value(String.valueOf(keep)))
				.andExpect(jsonPath("$.data.incoming[0].expectedDate").value(nullValue()))
				.andExpect(jsonPath("$.data.incoming[0].today").value(false))
				.andExpect(jsonPath("$.data.history", hasSize(4)))
				.andExpect(jsonPath("$.data.firstReceiptDone").value(true));
	}

	@Test
	void 최근_발주는_상태_추천량_입고량을_주고_14일_밖은_뺀다() throws Exception {
		long sent = insertOrder(jdbc, mine.storeId(), supplier, "SENT", NOW.minusDays(1));
		long incoming = insertItem(jdbc, mine.storeId(), sent, tomatoCan, tomato, new BigDecimal("5"), null);
		linkRecommendation(jdbc, mine.storeId(), tomato, incoming, LocalDate.of(2026, 10, 13), new BigDecimal("18000"));

		long received = insertOrder(jdbc, mine.storeId(), supplier, "SENT", NOW.minusDays(3));
		long receivedItem = insertItem(jdbc, mine.storeId(), received, tomatoCan, tomato, new BigDecimal("5"), null);
		markReceived(jdbc, receivedItem, new BigDecimal("5"), new BigDecimal("11700"),
				OffsetDateTime.parse("2026-10-12T09:10:00+09:00"));

		long draft = insertOrder(jdbc, mine.storeId(), supplier, "DRAFT", NOW.minusDays(5));
		insertItem(jdbc, mine.storeId(), draft, tomatoCan, tomato, new BigDecimal("2"), null);

		long rejected = insertOrder(jdbc, mine.storeId(), supplier, "SENT", NOW.minusDays(7));
		long rejectedItem = insertItem(jdbc, mine.storeId(), rejected, tomatoCan, tomato, new BigDecimal("1"), null);
		markCancelled(jdbc, rejectedItem, NOW.minusDays(6));

		// 서울 10/1 00:00 = 오늘 − 13일 → 들어감, 9/30 23:59 → 빠짐
		long edgeIn = insertOrder(jdbc, mine.storeId(), supplier, "CANCELLED",
				OffsetDateTime.parse("2026-10-01T00:00:00+09:00"));
		insertItem(jdbc, mine.storeId(), edgeIn, tomatoCan, tomato, new BigDecimal("1"), null);
		long edgeOut = insertOrder(jdbc, mine.storeId(), supplier, "SENT",
				OffsetDateTime.parse("2026-09-30T23:59:00+09:00"));
		insertItem(jdbc, mine.storeId(), edgeOut, tomatoCan, tomato, new BigDecimal("1"), null);

		feed(mine)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.history", hasSize(5)))
				.andExpect(jsonPath("$.data.history[0].orderId").value(String.valueOf(sent)))
				.andExpect(jsonPath("$.data.history[0].itemId").value(String.valueOf(incoming)))
				.andExpect(jsonPath("$.data.history[0].date").value("2026-10-13"))
				.andExpect(jsonPath("$.data.history[0].name").value("토마토 캔"))
				.andExpect(jsonPath("$.data.history[0].unit").value("g"))
				.andExpect(jsonPath("$.data.history[0].recommendedQty").value(18000.0))
				.andExpect(jsonPath("$.data.history[0].finalQty").value(15000.0))
				.andExpect(jsonPath("$.data.history[0].supplier").value("OO상회"))
				.andExpect(jsonPath("$.data.history[0].status").value("SENT"))
				.andExpect(jsonPath("$.data.history[0].receivedQty").value(nullValue()))
				.andExpect(jsonPath("$.data.history[0].receivedAt").value(nullValue()))
				.andExpect(jsonPath("$.data.history[1].status").value("RECEIVED"))
				.andExpect(jsonPath("$.data.history[1].recommendedQty").value(nullValue()))
				.andExpect(jsonPath("$.data.history[1].receivedQty").value(15000.0))
				.andExpect(jsonPath("$.data.history[1].receivedAt").value("2026-10-12T09:10:00+09:00"))
				.andExpect(jsonPath("$.data.history[2].status").value("DRAFT"))
				.andExpect(jsonPath("$.data.history[3].status").value("CANCELLED"))
				.andExpect(jsonPath("$.data.history[4].orderId").value(String.valueOf(edgeIn)))
				.andExpect(jsonPath("$.data.history[4].date").value("2026-10-01"))
				.andExpect(jsonPath("$.data.history[4].status").value("CANCELLED"));
	}

	@Test
	void 포장_환산을_모르는_상품은_추천량만_기준_단위로_주고_주문량_입고량은_null() throws Exception {
		long unknown = insertProduct(jdbc, mine.storeId(), supplier, tomato, "토마토 낱개", null, null, null);
		long order = insertOrder(jdbc, mine.storeId(), supplier, "SENT", NOW.minusDays(1));
		long item = insertItem(jdbc, mine.storeId(), order, unknown, tomato, new BigDecimal("5"), null);
		linkRecommendation(jdbc, mine.storeId(), tomato, item, LocalDate.of(2026, 10, 13), new BigDecimal("18000"));
		markReceived(jdbc, item, new BigDecimal("5"), new BigDecimal("900"), NOW.minusHours(1));

		feed(mine)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.history", hasSize(1)))
				.andExpect(jsonPath("$.data.history[0].recommendedQty").value(18000.0))
				.andExpect(jsonPath("$.data.history[0].finalQty").value(nullValue()))
				.andExpect(jsonPath("$.data.history[0].receivedQty").value(nullValue()));
	}

	@Test
	void 최근_발주는_최대_50줄_새것부터() throws Exception {
		long older = insertOrder(jdbc, mine.storeId(), supplier, "SENT", NOW.minusDays(2));
		for (int i = 0; i < 30; i++) {
			insertItem(jdbc, mine.storeId(), older, tomatoCan, tomato, BigDecimal.ONE, null);
		}
		long newer = insertOrder(jdbc, mine.storeId(), supplier, "SENT", NOW.minusDays(1));
		for (int i = 0; i < 30; i++) {
			insertItem(jdbc, mine.storeId(), newer, tomatoCan, tomato, BigDecimal.ONE, null);
		}

		feed(mine)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.history", hasSize(50)))
				.andExpect(jsonPath("$.data.history[0].orderId").value(String.valueOf(newer)))
				.andExpect(jsonPath("$.data.history[29].orderId").value(String.valueOf(newer)))
				.andExpect(jsonPath("$.data.history[30].orderId").value(String.valueOf(older)))
				.andExpect(jsonPath("$.data.incoming", hasSize(60)));
	}

	@Test
	void 발주가_없으면_빈_목록과_firstReceiptDone_false() throws Exception {
		feed(mine)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.incoming", hasSize(0)))
				.andExpect(jsonPath("$.data.history", hasSize(0)))
				.andExpect(jsonPath("$.data.firstReceiptDone").value(false));
	}

	@Test
	void 로그인_없이는_401() throws Exception {
		mockMvc.perform(get("/receiving")).andExpect(status().isUnauthorized());
	}

	private ResultActions feed(LoggedInStore store) throws Exception {
		return mockMvc.perform(get("/receiving").header(HttpHeaders.AUTHORIZATION, store.bearer()));
	}
}
