package com.zigzaego.api.supplier;

import static com.zigzaego.api.support.StoreFixtures.insertIngredient;
import static com.zigzaego.api.support.StoreFixtures.loginNewStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.support.StoreFixtures.LoggedInStore;
import java.math.BigDecimal;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** 거래처 상품 CRUD (S15P31A308-45). 가게 2개를 넣어 남의 가게 상품 · 거래처 · 재료가 404 인지 본다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SupplierProductControllerTest {

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PasswordEncoder passwordEncoder;

	private MockMvc mockMvc;

	private LoggedInStore mine;

	private LoggedInStore other;

	private String supplier;

	private long greenOnion;

	@BeforeEach
	void setUp() throws Exception {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		mine = loginNewStore(jdbc, passwordEncoder, mockMvc, "내가게");
		other = loginNewStore(jdbc, passwordEncoder, mockMvc, "남의가게");
		supplier = createSupplier(mine, "한빛농산");
		greenOnion = insertIngredient(jdbc, mine.storeId(), "대파");
	}

	@Test
	void 대파_1단_1000g_상품을_만들면_포장_환산과_재료가_붙는다() throws Exception {
		call(post("/supplier-products"), mine, """
				{"supplierId": "%s", "ingredientId": "%d", "productName": "대파 1단", "packSize": 1, "packUnit": "단",
				 "moq": 2, "price": 3500, "baseQtyPerPack": 1000, "primary": true}
				""".formatted(supplier, greenOnion))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.supplierId").value(supplier))
				.andExpect(jsonPath("$.data.supplierName").value("한빛농산"))
				.andExpect(jsonPath("$.data.ingredientId").value(String.valueOf(greenOnion)))
				.andExpect(jsonPath("$.data.ingredientName").value("대파"))
				.andExpect(jsonPath("$.data.unit").value("G"))
				.andExpect(jsonPath("$.data.productName").value("대파 1단"))
				.andExpect(jsonPath("$.data.packUnit").value("단"))
				.andExpect(jsonPath("$.data.moq").value(2.0))
				.andExpect(jsonPath("$.data.price").value(3500))
				.andExpect(jsonPath("$.data.baseQtyPerPack").value(1000.0))
				.andExpect(jsonPath("$.data.primary").value(true));

		assertThat(jdbc.queryForObject("SELECT primary_supplier_product_id IS NOT NULL FROM ingredient WHERE id = ?",
				Boolean.class, greenOnion)).isTrue();
	}

	@Test
	void 같은_거래처_같은_상품명은_409_다른_거래처는_된다() throws Exception {
		createProduct(supplier, "대파 1단", null);

		call(post("/supplier-products"), mine, """
				{"supplierId": "%s", "ingredientId": "%d", "productName": " 대파 1단 "}
				""".formatted(supplier, greenOnion))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("SUPPLIER_PRODUCT_DUPLICATE_NAME"));

		String another = createSupplier(mine, "바다수산");
		createProduct(another, "대파 1단", null);

		String renamed = createProduct(supplier, "대파 한단", null);
		call(patch("/supplier-products/{id}", renamed), mine, """
				{"productName": "대파 1단"}
				""")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("SUPPLIER_PRODUCT_DUPLICATE_NAME"));
	}

	@Test
	void 단가를_고치면_단가_이력이_한_줄_생긴다() throws Exception {
		String id = createProduct(supplier, "대파 1단", 3500);
		long productId = Long.parseLong(id);
		assertThat(priceRows(productId)).hasSize(1);

		call(patch("/supplier-products/{id}", id), mine, """
				{"price": 3800}
				""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.price").value(3800));

		List<Map<String, Object>> rows = priceRows(productId);
		assertThat(rows).hasSize(2);
		assertThat((BigDecimal) rows.get(1).get("price")).isEqualByComparingTo("3800");
		assertThat(rows.get(1).get("source")).isEqualTo("MANUAL");
		assertThat(rows.get(1).get("unit")).isEqualTo("단");

		// 같은 단가 · 단가 없는 수정은 이력을 늘리지 않는다
		call(patch("/supplier-products/{id}", id), mine, """
				{"price": 3800}
				""").andExpect(status().isOk());
		call(patch("/supplier-products/{id}", id), mine, """
				{"moq": 3}
				""").andExpect(status().isOk());
		assertThat(priceRows(productId)).hasSize(2);
	}

	@Test
	void 단가_없이_만든_상품에_처음_단가를_넣어도_한_줄() throws Exception {
		String id = createProduct(supplier, "대파 1단", null);
		assertThat(priceRows(Long.parseLong(id))).isEmpty();

		call(patch("/supplier-products/{id}", id), mine, """
				{"price": 3500}
				""").andExpect(status().isOk());
		assertThat(priceRows(Long.parseLong(id))).hasSize(1);
		// 포장 단위를 모르면 PACK — 단가는 포장 1개 값이라 g 같은 기준 단위로 적지 않는다
		assertThat(priceRows(Long.parseLong(id)).get(0).get("unit")).isEqualTo("PACK");
	}

	@Test
	void 단가와_수량_입력_오류는_400() throws Exception {
		call(post("/supplier-products"), mine, """
				{"supplierId": "%s", "ingredientId": "%d", "productName": "대파", "price": 3500.5}
				""".formatted(supplier, greenOnion))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
		call(post("/supplier-products"), mine, """
				{"supplierId": "%s", "ingredientId": "%d", "productName": "대파", "baseQtyPerPack": 0}
				""".formatted(supplier, greenOnion))
				.andExpect(status().isBadRequest());
		call(post("/supplier-products"), mine, """
				{"supplierId": "%s", "ingredientId": "%d", "productName": "대파", "price": -1}
				""".formatted(supplier, greenOnion))
				.andExpect(status().isBadRequest());
	}

	@Test
	void 상품명으로_사전_조회한다() throws Exception {
		String id = createProduct(supplier, "대파 1단", null);
		createProduct(supplier, "양파 1망", null);

		call(get("/supplier-products").param("productName", " 대파 1단 "), mine, null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data", hasSize(1)))
				.andExpect(jsonPath("$.data[0].id").value(id))
				.andExpect(jsonPath("$.data[0].ingredientName").value("대파"));
		call(get("/supplier-products").param("productName", "대파"), mine, null)
				.andExpect(jsonPath("$.data", empty()));
		call(get("/supplier-products").param("supplierId", supplier), mine, null)
				.andExpect(jsonPath("$.data", hasSize(2)));
		call(get("/supplier-products").param("ingredientId", String.valueOf(greenOnion)), mine, null)
				.andExpect(jsonPath("$.data", hasSize(2)));
		call(get("/supplier-products"), other, null)
				.andExpect(jsonPath("$.data", empty()));
	}

	@Test
	void 지우면_단가_이력과_대표_상품도_비운다_발주에_쓰였으면_409() throws Exception {
		String id = JsonPath.read(call(post("/supplier-products"), mine, """
						{"supplierId": "%s", "ingredientId": "%d", "productName": "대파 1단", "price": 3500, "primary": true}
						""".formatted(supplier, greenOnion))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString(), "$.data.id");
		long productId = Long.parseLong(id);

		call(delete("/supplier-products/{id}", id), mine, null).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM supplier_product WHERE id = ?", Long.class, productId))
				.isZero();
		assertThat(priceRows(productId)).isEmpty();
		assertThat(jdbc.queryForObject("SELECT primary_supplier_product_id FROM ingredient WHERE id = ?", Long.class,
				greenOnion)).isNull();

		String used = createProduct(supplier, "대파 2단", null);
		long orderId = jdbc.queryForObject("INSERT INTO purchase_order (store_id, supplier_id, status)"
				+ " VALUES (?, ?, 'DRAFT') RETURNING id", Long.class, mine.storeId(), Long.parseLong(supplier));
		jdbc.update("INSERT INTO purchase_order_item (store_id, po_id, supplier_product_id, ingredient_id, qty)"
				+ " VALUES (?, ?, ?, ?, 1)", mine.storeId(), orderId, Long.parseLong(used), greenOnion);
		call(delete("/supplier-products/{id}", used), mine, null)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("SUPPLIER_PRODUCT_IN_USE"));
	}

	@Test
	void primary_false_면_대표_상품을_비운다() throws Exception {
		String id = JsonPath.read(call(post("/supplier-products"), mine, """
						{"supplierId": "%s", "ingredientId": "%d", "productName": "대파 1단", "primary": true}
						""".formatted(supplier, greenOnion))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString(), "$.data.id");

		call(patch("/supplier-products/{id}", id), mine, """
				{"primary": false}
				""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.primary").value(false));
		assertThat(jdbc.queryForObject("SELECT primary_supplier_product_id FROM ingredient WHERE id = ?", Long.class,
				greenOnion)).isNull();
	}

	@Test
	void 남의_가게_상품_거래처_재료는_모두_404() throws Exception {
		String theirSupplier = createSupplier(other, "남의거래처");
		long theirIngredient = insertIngredient(jdbc, other.storeId(), "남의대파");
		String theirProduct = JsonPath.read(call(post("/supplier-products"), other, """
						{"supplierId": "%s", "ingredientId": "%d", "productName": "대파 1단", "price": 1000}
						""".formatted(theirSupplier, theirIngredient))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString(), "$.data.id");

		call(get("/supplier-products/{id}", theirProduct), mine, null)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));
		call(patch("/supplier-products/{id}", theirProduct), mine, """
				{"price": 1}
				""")
				.andExpect(status().isNotFound());
		call(delete("/supplier-products/{id}", theirProduct), mine, null).andExpect(status().isNotFound());
		// 남의 거래처 · 남의 재료로 만들 수 없다
		call(post("/supplier-products"), mine, """
				{"supplierId": "%s", "ingredientId": "%d", "productName": "대파 1단"}
				""".formatted(theirSupplier, greenOnion))
				.andExpect(status().isNotFound());
		call(post("/supplier-products"), mine, """
				{"supplierId": "%s", "ingredientId": "%d", "productName": "대파 1단"}
				""".formatted(supplier, theirIngredient))
				.andExpect(status().isNotFound());
		call(get("/supplier-products").param("supplierId", theirSupplier), mine, null)
				.andExpect(status().isNotFound());

		assertThat(jdbc.queryForObject("SELECT price FROM supplier_product WHERE id = ?", BigDecimal.class,
				Long.parseLong(theirProduct))).isEqualByComparingTo("1000");
	}

	@Test
	void 응답의_모르는_값은_null() throws Exception {
		String id = createProduct(supplier, "대파 1단", null);
		call(get("/supplier-products/{id}", id), mine, null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.price").value(nullValue()))
				.andExpect(jsonPath("$.data.baseQtyPerPack").value(nullValue()))
				.andExpect(jsonPath("$.data.primary").value(false));
	}

	private List<Map<String, Object>> priceRows(long productId) {
		return jdbc.queryForList(
				"SELECT price, unit, source FROM price_history WHERE supplier_product_id = ? ORDER BY id", productId);
	}

	private String createSupplier(LoggedInStore store, String name) throws Exception {
		return JsonPath.read(call(post("/suppliers"), store, """
						{"name": "%s"}
						""".formatted(name))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString(), "$.data.id");
	}

	private String createProduct(String supplierId, String productName, Integer price) throws Exception {
		String body = price == null
				? """
				{"supplierId": "%s", "ingredientId": "%d", "productName": "%s"}
				""".formatted(supplierId, greenOnion, productName)
				: """
				{"supplierId": "%s", "ingredientId": "%d", "productName": "%s", "packUnit": "단", "price": %d}
				""".formatted(supplierId, greenOnion, productName, price);
		return JsonPath.read(call(post("/supplier-products"), mine, body)
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString(), "$.data.id");
	}

	private ResultActions call(MockHttpServletRequestBuilder request, LoggedInStore store, String body)
			throws Exception {
		request.header(HttpHeaders.AUTHORIZATION, store.bearer());
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mockMvc.perform(request);
	}
}
