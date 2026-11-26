package com.zigzaego.api.purchaseorder;

import static com.zigzaego.api.support.StoreFixtures.OTHER_CATEGORY_SQL;
import static com.zigzaego.api.support.StoreFixtures.loginNewStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.support.FixedClockConfig;
import com.zigzaego.api.support.StoreFixtures.LoggedInStore;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
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
 * 발주서 주문처(거래처 · 식봄 · 쿠팡)와 줄 단가 저장 (S15P31A308-201, DECISIONS #90). 고정 시계 2026-10-14 12:00 (서울).
 */
@Import({TestcontainersConfiguration.class, FixedClockConfig.class})
@SpringBootTest
class PurchaseOrderSourceTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PasswordEncoder passwordEncoder;

	private MockMvc mockMvc;

	private LoggedInStore mine;
	private LoggedInStore other;
	private long 농산;
	private long 대파;
	private long 양파;
	private long 계란;
	private long 부추;
	private long 무명재료; // 식봄에 상품은 있지만 개 단위뿐이고 품목군이 없어 재료(g) 단위로 못 바꾼다
	private long 소금; // 식봄 상품이 없다
	private long 대파상품;
	private long 양파상품;
	private long 계란상품;

	@BeforeEach
	void setUp() throws Exception {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		mine = loginNewStore(jdbc, passwordEncoder, mockMvc, "주문처내가게");
		other = loginNewStore(jdbc, passwordEncoder, mockMvc, "주문처남의가게");
		jdbc.update("UPDATE store SET display_name = '한빛식당' WHERE id = ?", mine.storeId());

		농산 = jdbc.queryForObject("INSERT INTO supplier (store_id, name, lead_time_days, message_template)"
				+ " VALUES (?, '농산', 2, NULL) RETURNING id", Long.class, mine.storeId());
		대파 = insertIngredient(mine.storeId(), "대파");
		양파 = insertIngredient(mine.storeId(), "양파");
		계란 = insertIngredient(mine.storeId(), "계란");
		부추 = insertIngredient(mine.storeId(), "부추");
		무명재료 = insertIngredient(mine.storeId(), "무명재료");
		소금 = insertIngredient(mine.storeId(), "소금");
		대파상품 = insertProduct(mine.storeId(), 대파, "대파", "단", "1000", "2500");
		양파상품 = insertProduct(mine.storeId(), 양파, "양파", "망", "1000", "3000");
		계란상품 = insertProduct(mine.storeId(), 계란, "계란", "판", "1800", "6000");
		long 부추상품 = insertProduct(mine.storeId(), 부추, "부추", "단", "500", "1500");
		long 무명상품 = insertProduct(mine.storeId(), 무명재료, "무명재료", "봉", "100", "800");
		long 소금상품 = insertProduct(mine.storeId(), 소금, "소금", "봉", "1000", "900");
		setPrimary(대파, 대파상품);
		setPrimary(양파, 양파상품);
		setPrimary(계란, 계란상품);
		setPrimary(부추, 부추상품);
		setPrimary(무명재료, 무명상품);
		setPrimary(소금, 소금상품);

		jdbc.update("DELETE FROM market_product");
		insertMarket("식봄", "서울 중구", "A1", "계란(중란/30알/국내산)판", "1판/30구 / 판", 5690, "개", "189.7");
		insertMarket("식봄", "서울 중구", "A2", "계란(특란/30알/국내산)판", "30알 / 판", 6190, "개", "206.3");
		insertMarket("마켓컬리", "전국", "K1", "[KF365] 무항생제 달걀 L(대란) 20구", null, 7320, "개", "366.0");
		insertMarket("식봄", "서울 중구", "M1", "무명재료 1개입", null, 1000, "개", "100.0");
	}

	// ---------------------------------------------------------------- 나눔

	@Test
	void 나눔_세_주문처_각_한_장이고_같은_거래처_줄은_한_장으로_묶인다() throws Exception {
		long r대파 = rec(대파, "1600");
		long r양파 = rec(양파, "1000");
		long r계란 = rec(계란, "3600");
		long r부추 = rec(부추, "500");

		create(mine, items(item(r대파, "SUPPLIER"), item(r양파, null), item(r계란, "SIKBOM"), item(r부추, "COUPANG")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data", hasSize(3)))
				.andExpect(jsonPath("$.data[0].source").value("SUPPLIER"))
				.andExpect(jsonPath("$.data[0].supplierId").value(String.valueOf(농산)))
				.andExpect(jsonPath("$.data[0].supplierName").value("농산"))
				.andExpect(jsonPath("$.data[0].status").value("DRAFT"))
				.andExpect(jsonPath("$.data[0].items", hasSize(2))) // 대파 + 양파(source 생략 = SUPPLIER)
				.andExpect(jsonPath("$.data[1].source").value("SIKBOM"))
				.andExpect(jsonPath("$.data[1].supplierId").value(nullValue()))
				.andExpect(jsonPath("$.data[1].supplierName").value(nullValue()))
				.andExpect(jsonPath("$.data[1].status").value("DRAFT"))
				.andExpect(jsonPath("$.data[2].source").value("COUPANG"))
				.andExpect(jsonPath("$.data[2].supplierId").value(nullValue()))
				.andExpect(jsonPath("$.data[2].status").value("DRAFT"));

		assertThat(jdbc.queryForList("SELECT source FROM purchase_order WHERE store_id = ? ORDER BY id", String.class,
				mine.storeId())).containsExactly("SUPPLIER", "SIKBOM", "COUPANG");
	}

	// ---------------------------------------------------------------- 단가

	@Test
	void 단가_거래처는_상품_가격_식봄은_식봄_1위_환산이고_이력_금액은_가격이_바뀌어도_그대로() throws Exception {
		long r대파 = rec(대파, "1600"); // 포장 1000 → 2단
		long r계란 = rec(계란, "3600"); // 포장 1800 → 2판

		// 식봄 1위 단가 · 환산 계수는 offers API 가 주는 값 그대로 쓴다 (예: 189.7 원/개 × 1/60 × 1800)
		String offers = mockMvc.perform(get("/ingredients/{id}/offers", String.valueOf(계란))
						.header(HttpHeaders.AUTHORIZATION, mine.bearer()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		BigDecimal best = new BigDecimal(JsonPath.read(offers, "$.data.sites[0].bestUnitPrice").toString());
		BigDecimal factor = new BigDecimal(JsonPath.read(offers, "$.data.toIngredientUnitFactor").toString());
		long expected = best.multiply(factor).multiply(new BigDecimal("1800")).setScale(0, RoundingMode.HALF_UP).longValueExact();
		assertThat(expected).isBetween(5690L, 5692L);

		String body = create(mine, items(item(r대파, "SUPPLIER"), item(r계란, "SIKBOM")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data[0].items[0].unitPrice").value(2500))
				.andExpect(jsonPath("$.data[0].items[0].productUrl").value(nullValue()))
				.andExpect(jsonPath("$.data[1].items[0].unitPrice").value(expected))
				.andReturn().getResponse().getContentAsString();

		assertThat(jdbc.queryForObject("SELECT unit_price FROM purchase_order_item WHERE recommendation_id = ?",
				BigDecimal.class, r대파)).isEqualByComparingTo("2500");
		assertThat(jdbc.queryForObject("SELECT unit_price FROM purchase_order_item WHERE recommendation_id = ?",
				BigDecimal.class, r계란)).isEqualByComparingTo(String.valueOf(expected));

		send(mine, JsonPath.read(body, "$.data[0].id"));
		send(mine, JsonPath.read(body, "$.data[1].id"));
		jdbc.update("UPDATE supplier_product SET price = 9999 WHERE id = ?", 대파상품); // 요청 뒤 가격이 바뀐다

		history(mine)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.lines", hasSize(2)))
				.andExpect(jsonPath("$.data.lines[?(@.ingredientName=='대파')].amount").value(5000)) // 2500 × 2단, 9999 가 아니다
				.andExpect(jsonPath("$.data.lines[?(@.ingredientName=='계란')].amount").value((int) (expected * 2)));
	}

	// ---------------------------------------------------------------- source 없음

	@Test
	void source_없음_recommendationIds_만이나_본문_없이도_거래처별_SUPPLIER_그대로() throws Exception {
		long r대파 = rec(대파, "1600");
		create(mine, "{\"recommendationIds\":[\"" + r대파 + "\"]}")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data", hasSize(1)))
				.andExpect(jsonPath("$.data[0].source").value("SUPPLIER"))
				.andExpect(jsonPath("$.data[0].supplierName").value("농산"))
				.andExpect(jsonPath("$.data[0].items[0].unitPrice").value(2500));
		rec(양파, "1000");
		create(mine, null)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data", hasSize(1)))
				.andExpect(jsonPath("$.data[0].source").value("SUPPLIER"));
	}

	// ---------------------------------------------------------------- 경계 409

	@Test
	void 경계_식봄_상품이_없거나_단위를_못_바꾸면_409_이고_다른_줄까지_발주서가_하나도_안_생긴다() throws Exception {
		long r대파 = rec(대파, "1600");
		long r소금 = rec(소금, "1000");
		long r무명 = rec(무명재료, "100");

		create(mine, items(item(r대파, "SUPPLIER"), item(r소금, "SIKBOM")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("PURCHASE_ORDER_SOURCE_PRICE_UNSET"));
		assertNothingCreated();

		create(mine, items(item(r대파, "SUPPLIER"), item(r무명, "SIKBOM")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("PURCHASE_ORDER_SOURCE_PRICE_UNSET"));
		assertNothingCreated();
	}

	// ---------------------------------------------------------------- 경계

	@Test
	void 경계_items_와_recommendationIds_를_함께_주면_400() throws Exception {
		long r = rec(대파, "1600");
		create(mine, "{\"recommendationIds\":[\"" + r + "\"],\"items\":[{\"recommendationId\":\"" + r + "\"}]}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
		assertNothingCreated();
	}

	@Test
	void 경계_items_안_같은_추천_두_번이면_400_DUPLICATE() throws Exception {
		long r = rec(대파, "1600");
		create(mine, items(item(r, "SUPPLIER"), item(r, "SIKBOM")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("PURCHASE_ORDER_RECOMMENDATION_DUPLICATE"));
		assertNothingCreated();
	}

	@Test
	void 경계_알_수_없는_source_는_400() throws Exception {
		long r = rec(대파, "1600");
		create(mine, items(item(r, "AMAZON")))
				.andExpect(status().isBadRequest());
		assertNothingCreated();
	}

	@Test
	void 경계_남의_가게_추천_id_는_404_승인_전_추천은_409() throws Exception {
		long mineRec = rec(대파, "1600");
		create(other, items(item(mineRec, "COUPANG")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));
		long pending = jdbc.queryForObject(
				"INSERT INTO recommendation (store_id, ingredient_id, run_date, est_stock, on_order, lead_time_demand,"
						+ " safety_stock, reorder_point, recommended_qty, status, forecast_model, ss_method)"
						+ " VALUES (?, ?, ?, 0, 0, 0, 0, 0, 1000, 'PENDING', 'dow_avg', 'DAYS_N') RETURNING id",
				Long.class, mine.storeId(), 양파, TODAY);
		create(mine, items(item(mineRec, "SUPPLIER"), item(pending, "SIKBOM")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("PURCHASE_ORDER_NOT_APPROVED"));
		assertNothingCreated();
	}

	// ---------------------------------------------------------------- 쿠팡

	@Test
	void 쿠팡_줄은_가격_없이_201_링크는_검색_주소_이력_금액은_null() throws Exception {
		long r부추 = rec(부추, "500");
		String body = create(mine, items(item(r부추, "COUPANG")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data[0].source").value("COUPANG"))
				.andExpect(jsonPath("$.data[0].items[0].unitPrice").value(nullValue()))
				.andExpect(jsonPath("$.data[0].items[0].productUrl", containsString("coupang.com")))
				.andExpect(jsonPath("$.data[0].items[0].productUrl", containsString("q=")))
				.andExpect(jsonPath("$.data[0].messageText", containsString("- 부추 1단 — https://www.coupang.com/")))
				.andReturn().getResponse().getContentAsString();
		assertThat(jdbc.queryForObject("SELECT unit_price FROM purchase_order_item WHERE recommendation_id = ?",
				BigDecimal.class, r부추)).isNull();

		send(mine, JsonPath.read(body, "$.data[0].id"));
		history(mine)
				.andExpect(jsonPath("$.data.lines", hasSize(1)))
				.andExpect(jsonPath("$.data.lines[0].supplierName").value("쿠팡"))
				.andExpect(jsonPath("$.data.lines[0].amount").value(nullValue()))
				.andExpect(jsonPath("$.data.totalAmount").value(0));
	}

	// ---------------------------------------------------------------- 메시지 · 이력 · 입고 예정

	@Test
	void 메시지_식봄은_줄마다_링크_거래처는_기존_그대로_이력과_입고_예정에_식봄_줄이_보인다() throws Exception {
		long r대파 = rec(대파, "1600");
		long r계란 = rec(계란, "3600");
		String body = create(mine, items(item(r대파, "SUPPLIER"), item(r계란, "SIKBOM")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data[0].messageText",
						org.hamcrest.Matchers.is("안녕하세요, 한빛식당입니다.\n10/16(금) 배송으로 아래 부탁드립니다.\n- 대파 2단\n감사합니다.")))
				.andExpect(jsonPath("$.data[1].messageText").value("- 계란 2판 — https://x/A1"))
				.andExpect(jsonPath("$.data[1].items[0].productUrl").value("https://x/A1"))
				.andExpect(jsonPath("$.data[1].items[0].expectedDate").value("2026-10-15")) // 거래처 리드타임이 없어 기본 1일
				.andReturn().getResponse().getContentAsString();

		send(mine, JsonPath.read(body, "$.data[0].id"));
		send(mine, JsonPath.read(body, "$.data[1].id"));

		history(mine)
				.andExpect(jsonPath("$.data.lines", hasSize(2)))
				.andExpect(jsonPath("$.data.lines[?(@.ingredientName=='계란')].supplierName").value("식봄"))
				.andExpect(jsonPath("$.data.lines[?(@.ingredientName=='대파')].supplierName").value("농산"));
		mockMvc.perform(get("/receiving").header(HttpHeaders.AUTHORIZATION, mine.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.incoming", hasSize(2)))
				.andExpect(jsonPath("$.data.incoming[?(@.name=='계란')].supplier").value("식봄"));
	}

	// ---------------------------------------------------------------- 감사 1라운드 보강

	@Autowired
	private com.zigzaego.api.receiving.service.ReceivingService receivingService;

	@Test
	void 식봄_쿠팡_줄을_자동_입고해도_받은_단가와_이력_금액은_발주_때_단가_그대로다() throws Exception {
		long r계란 = rec(계란, "3600");
		long r부추 = rec(부추, "500");
		String body = create(mine, items(item(r계란, "SIKBOM"), item(r부추, "COUPANG")))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		send(mine, JsonPath.read(body, "$.data[0].id"));
		send(mine, JsonPath.read(body, "$.data[1].id"));

		// 입고 예정 unitPrice: 계란은 식봄 단가(포장 5691 / 1800 ≈ 3.16), sp.price 6000 / 1800 ≈ 3.33 이 아니다. 쿠팡은 null.
		String incoming = mockMvc.perform(get("/receiving").header(HttpHeaders.AUTHORIZATION, mine.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.incoming[?(@.name=='부추')].unitPrice").value(org.hamcrest.Matchers.contains(nullValue())))
				.andReturn().getResponse().getContentAsString();
		java.util.List<Number> egg = JsonPath.read(incoming, "$.data.incoming[?(@.name=='계란')].unitPrice");
		assertThat(egg.getFirst().doubleValue()).isBetween(3.15, 3.18);

		for (java.util.Map<String, Object> row : jdbc.queryForList(
				"SELECT id, qty, expected_date FROM purchase_order_item WHERE store_id = ? ORDER BY id", mine.storeId())) {
			receivingService.recordReceipt(mine.storeId(), ((Number) row.get("id")).longValue(), (BigDecimal) row.get("qty"),
					null, false, ((java.sql.Date) row.get("expected_date")).toLocalDate(), true);
		}

		assertThat(jdbc.queryForList("SELECT received_unit_price FROM purchase_order_item WHERE store_id = ? ORDER BY id",
				BigDecimal.class, mine.storeId())).hasSize(2).satisfiesExactly(
						a -> assertThat(a).isEqualByComparingTo("5691"), b -> assertThat(b).isNull());
		history(mine)
				.andExpect(jsonPath("$.data.lines[?(@.ingredientName=='계란')].amount").value(org.hamcrest.Matchers.contains(11382)))
				.andExpect(jsonPath("$.data.lines[?(@.ingredientName=='부추')].amount").value(org.hamcrest.Matchers.contains(nullValue())));
	}

	@Test
	void 거래처_줄도_가격이_바뀐_뒤_자동_입고되면_받은_단가와_이력_금액은_발주_때_단가_그대로다() throws Exception {
		long r대파 = rec(대파, "1600"); // 포장 1000 → 2단, 발주 때 2500
		String body = create(mine, items(item(r대파, "SUPPLIER")))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		send(mine, JsonPath.read(body, "$.data[0].id"));
		jdbc.update("UPDATE supplier_product SET price = 9999 WHERE id = ?", 대파상품); // 보낸 뒤 가격이 바뀐다

		for (java.util.Map<String, Object> row : jdbc.queryForList(
				"SELECT id, qty, expected_date FROM purchase_order_item WHERE store_id = ? ORDER BY id", mine.storeId())) {
			receivingService.recordReceipt(mine.storeId(), ((Number) row.get("id")).longValue(), (BigDecimal) row.get("qty"),
					null, false, ((java.sql.Date) row.get("expected_date")).toLocalDate(), true);
		}

		assertThat(jdbc.queryForObject("SELECT received_unit_price FROM purchase_order_item WHERE recommendation_id = ?",
				BigDecimal.class, r대파)).isEqualByComparingTo("2500");
		history(mine)
				.andExpect(jsonPath("$.data.lines[?(@.ingredientName=='대파')].amount").value(org.hamcrest.Matchers.contains(5000)));
	}

	@Test
	void 식봄_줄을_손으로_입고해_단가를_넣어도_거래처_상품_단가_이력은_쓰지_않는다() throws Exception {
		long r계란 = rec(계란, "3600");
		String body = create(mine, items(item(r계란, "SIKBOM"))).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String orderId = JsonPath.read(body, "$.data[0].id");
		send(mine, orderId);
		String itemId = JsonPath.read(body, "$.data[0].items[0].id");
		int before = jdbc.queryForObject("SELECT COUNT(*) FROM price_history WHERE supplier_product_id = ?", Integer.class,
				계란상품);

		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
						.patch("/purchase-orders/{o}/items/{i}/receive", orderId, itemId)
						.header(HttpHeaders.AUTHORIZATION, mine.bearer()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"receivedQty\":3600,\"unitPrice\":7}"))
				.andExpect(status().isOk());

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM price_history WHERE supplier_product_id = ?", Integer.class,
				계란상품)).isEqualTo(before);
		assertThat(jdbc.queryForObject("SELECT received_unit_price FROM purchase_order_item WHERE store_id = ?",
				BigDecimal.class, mine.storeId())).isNotNull();
	}

	@Test
	void 쿠팡_링크는_이름이_길어도_500자를_넘지_않고_요청이_성공한다() throws Exception {
		long ing = insertIngredient(mine.storeId(), "가".repeat(60));
		long p = insertProduct(mine.storeId(), ing, "긴상품", "봉", "100", "800");
		setPrimary(ing, p);
		long r = rec(ing, "100");

		String body = create(mine, items(item(r, "COUPANG"))).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String url = JsonPath.read(body, "$.data[0].items[0].productUrl");
		assertThat(url.length()).isLessThanOrEqualTo(500);
		assertThat(url).startsWith("https://www.coupang.com/np/search?q=%EA%B0%80");
		assertThat((String) JsonPath.read(body, "$.data[0].messageText")).contains(url);
	}

	@Test
	void items를_빈_목록으로_보내면_400이고_아무것도_만들지_않는다() throws Exception {
		rec(대파, "1600");
		rec(양파, "1000");

		create(mine, "{\"items\":[]}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
		assertNothingCreated();
	}

	// ---------------------------------------------------------------- helpers

	@Test
	void 신규몰_포장환산가격과_source를_동일하게_저장하고_가게를_격리한다() throws Exception {
		for (String[] pair : new String[][]{{"MARKETBOM", "마켓봄"}, {"ORDERLINK", "오더링크"}, {"COUPANG", "쿠팡"}}) {
			long ingredient = insertIngredient(mine.storeId(), "전용" + pair[0]);
			long product = insertProduct(mine.storeId(), ingredient, "거래처1kg" + pair[0], "팩", "1000", "5000");
			setPrimary(ingredient, product);
			jdbc.update("INSERT INTO store_market_quote(store_id,ingredient_id,site,collected_on,unit_price,pack_qty,pack_price,product_name,spec,url,synthetic) VALUES (?,?,?,'2026-10-14',3,2000,6000,'합성 2kg','2kg','https://example.invalid/demo',true)", mine.storeId(), ingredient, pair[1]);
			String body = create(mine, items(item(rec(ingredient, "3000"), pair[0])))
					.andExpect(status().isCreated()).andExpect(jsonPath("$.data[0].source").value(pair[0]))
					.andExpect(jsonPath("$.data[0].items[0].unitPrice").value(3000))
					.andExpect(jsonPath("$.data[0].items[0].qty").value(3000))
					.andReturn().getResponse().getContentAsString();
			String id = JsonPath.read(body, "$.data[0].id");
			send(mine, id);
			mockMvc.perform(post("/purchase-orders/{id}/send", id).header(HttpHeaders.AUTHORIZATION, other.bearer())).andExpect(status().isNotFound());
		}
		history(mine).andExpect(status().isOk()).andExpect(jsonPath("$.data.totalAmount").value(27000));
	}

	@Test
	void 오래된_가게시세는_발주단가로_저장하지않는다() throws Exception {
		jdbc.update("INSERT INTO store_market_quote(store_id,ingredient_id,site,collected_on,unit_price,pack_qty,pack_price,product_name,url,synthetic) VALUES (?,?,'마켓봄','2026-10-13',3,1000,3000,'시연','https://example.invalid/demo',true)", mine.storeId(), 양파);
		create(mine, items(item(rec(양파,"1000"),"MARKETBOM"))).andExpect(status().isConflict());
		assertNothingCreated();
	}

	private void assertNothingCreated() {
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order WHERE store_id = ?", Integer.class,
				mine.storeId())).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order_item WHERE store_id = ?", Integer.class,
				mine.storeId())).isZero();
	}

	private ResultActions create(LoggedInStore store, String body) throws Exception {
		var request = post("/purchase-orders/from-recommendations").header(HttpHeaders.AUTHORIZATION, store.bearer());
		if (body != null) {
			request = request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mockMvc.perform(request);
	}

	private void send(LoggedInStore store, String orderId) throws Exception {
		mockMvc.perform(post("/purchase-orders/{id}/send", orderId).header(HttpHeaders.AUTHORIZATION, store.bearer()))
				.andExpect(status().isOk());
	}

	private ResultActions history(LoggedInStore store) throws Exception {
		return mockMvc.perform(get("/purchase-orders/history").param("month", "2026-10")
				.header(HttpHeaders.AUTHORIZATION, store.bearer()));
	}

	private static String item(long recommendationId, String source) {
		return "{\"recommendationId\":\"" + recommendationId + "\""
				+ (source == null ? "" : ",\"source\":\"" + source + "\"") + "}";
	}

	private static String items(String... items) {
		return "{\"items\":[" + String.join(",", items) + "]}";
	}

	private long insertIngredient(long storeId, String name) {
		return jdbc.queryForObject(
				"INSERT INTO ingredient (store_id, name, unit, price_sensitive, sourcing_type, kind, storage_type, storage_location,"
						+ " category_id, category_source)"
						+ " VALUES (?, ?, 'G', false, 'GENERIC', 'BOM', 'CHILLED', 'FRIDGE', " + OTHER_CATEGORY_SQL + ", 'AUTO') RETURNING id",
				Long.class, storeId, name, storeId);
	}

	private long insertProduct(long storeId, long ingredientId, String name, String packUnit, String basePerPack, String price) {
		return jdbc.queryForObject(
				"INSERT INTO supplier_product (store_id, supplier_id, ingredient_id, product_name, pack_unit, base_qty_per_pack, price)"
						+ " VALUES (?, ?, ?, ?, ?, CAST(? AS NUMERIC), CAST(? AS NUMERIC)) RETURNING id",
				Long.class, storeId, 농산, ingredientId, name, packUnit, basePerPack, price);
	}

	private void setPrimary(long ingredientId, long productId) {
		jdbc.update("UPDATE ingredient SET primary_supplier_product_id = ? WHERE id = ? AND store_id = ?",
				productId, ingredientId, mine.storeId());
	}

	/** 오늘 승인 추천 한 건 (최종 수량 finalQty). */
	private long rec(long ingredientId, String finalQty) {
		return jdbc.queryForObject(
				"INSERT INTO recommendation (store_id, ingredient_id, run_date, est_stock, on_order, lead_time_demand,"
						+ " safety_stock, reorder_point, recommended_qty, status, final_qty, forecast_model, ss_method)"
						+ " VALUES (?, ?, ?, 0, 0, 0, 0, 0, 1000, 'APPROVED', CAST(? AS NUMERIC), 'dow_avg', 'DAYS_N') RETURNING id",
				Long.class, mine.storeId(), ingredientId, TODAY, finalQty);
	}

	private void insertMarket(String site, String region, String productNo, String name, String spec, long price, String unit,
			String unitPrice100) {
		jdbc.update("INSERT INTO market_product (site, store_region, product_no, product_name, spec, price, unit, "
						+ "unit_price_100, url, collected_on) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '2026-10-14'::date)",
				site, region, productNo, name, spec, price, unit, new BigDecimal(unitPrice100), "https://x/" + productNo);
	}
}
