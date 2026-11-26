package com.zigzaego.api.supplier;

import static com.zigzaego.api.support.StoreFixtures.insertIngredient;
import static com.zigzaego.api.support.StoreFixtures.loginNewStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
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

/** 거래처 CRUD (S15P31A308-45). 실제 로그인 토큰으로 부르고 가게 2개를 넣어 남의 가게 거래처가 404 인지 본다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SupplierControllerTest {

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PasswordEncoder passwordEncoder;

	private MockMvc mockMvc;

	private LoggedInStore mine;

	private LoggedInStore other;

	@BeforeEach
	void setUp() throws Exception {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		mine = loginNewStore(jdbc, passwordEncoder, mockMvc, "내가게");
		other = loginNewStore(jdbc, passwordEncoder, mockMvc, "남의가게");
	}

	@Test
	void 만들면_모든_칸을_돌려주고_같은_이름은_409() throws Exception {
		call(post("/suppliers"), mine, """
				{"name": "한빛농산", "channel": "KAKAO", "leadTimeDays": 1, "orderCycleDays": 2,
				 "cutoffTime": "21:00", "deliveryDays": ["FRI", "MON", "WED", "MON"],
				 "minOrderAmount": 50000, "messageTemplate": "{품목} 부탁드립니다", "email": "order@example.com",
				 "shippingFee": 3000, "freeShippingMin": 30000}
				""")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.name").value("한빛농산"))
				.andExpect(jsonPath("$.data.channel").value("KAKAO"))
				.andExpect(jsonPath("$.data.leadTimeDays").value(1))
				.andExpect(jsonPath("$.data.orderCycleDays").value(2))
				.andExpect(jsonPath("$.data.cutoffTime").value("21:00"))
				.andExpect(jsonPath("$.data.deliveryDays", contains("MON", "WED", "FRI")))
				.andExpect(jsonPath("$.data.minOrderAmount").value(50000))
				.andExpect(jsonPath("$.data.messageTemplate").value("{품목} 부탁드립니다"))
				.andExpect(jsonPath("$.data.email").value("order@example.com"))
				.andExpect(jsonPath("$.data.shippingFee").value(3000))
				.andExpect(jsonPath("$.data.freeShippingMin").value(30000));

		call(post("/suppliers"), mine, """
				{"name": " 한빛농산 "}
				""")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("SUPPLIER_DUPLICATE_NAME"));
		// 다른 가게는 같은 이름을 쓸 수 있다
		call(post("/suppliers"), other, """
				{"name": "한빛농산"}
				""")
				.andExpect(status().isCreated());
	}

	@Test
	void 이름만으로_만들면_나머지는_모름() throws Exception {
		call(post("/suppliers"), mine, """
				{"name": "동네마트"}
				""")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.channel").value(nullValue()))
				.andExpect(jsonPath("$.data.leadTimeDays").value(nullValue()))
				.andExpect(jsonPath("$.data.cutoffTime").value(nullValue()))
				.andExpect(jsonPath("$.data.deliveryDays", empty()))
				.andExpect(jsonPath("$.data.minOrderAmount").value(nullValue()));
	}

	@Test
	void 입력_오류는_400() throws Exception {
		call(post("/suppliers"), mine, """
				{"name": "가", "minOrderAmount": 100.5}
				""")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
		call(post("/suppliers"), mine, """
				{"name": "가", "orderCycleDays": 0}
				""")
				.andExpect(status().isBadRequest());
		call(post("/suppliers"), mine, """
				{"name": "가", "email": "not-an-email"}
				""")
				.andExpect(status().isBadRequest());
		call(post("/suppliers"), mine, """
				{"name": "가", "channel": "FAX"}
				""")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_MALFORMED_REQUEST"));
		call(post("/suppliers"), mine, """
				{"name": "가", "deliveryDays": ["MONDAY"]}
				""")
				.andExpect(status().isBadRequest());
		call(post("/suppliers"), mine, """
				{"channel": "KAKAO"}
				""")
				.andExpect(status().isBadRequest());
	}

	@Test
	void 고치면_보낸_칸만_바뀐다() throws Exception {
		String id = create(mine, """
				{"name": "한빛농산", "channel": "KAKAO", "leadTimeDays": 1, "deliveryDays": ["MON"]}
				""");

		call(patch("/suppliers/{id}", id), mine, """
				{"channel": "PHONE", "cutoffTime": "20:30", "deliveryDays": ["THU", "TUE"], "minOrderAmount": 70000}
				""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.name").value("한빛농산"))
				.andExpect(jsonPath("$.data.channel").value("PHONE"))
				.andExpect(jsonPath("$.data.leadTimeDays").value(1))
				.andExpect(jsonPath("$.data.cutoffTime").value("20:30"))
				.andExpect(jsonPath("$.data.deliveryDays", contains("TUE", "THU")))
				.andExpect(jsonPath("$.data.minOrderAmount").value(70000));

		call(patch("/suppliers/{id}", id), mine, """
				{"deliveryDays": []}
				""")
				.andExpect(jsonPath("$.data.deliveryDays", empty()));
		assertThat(jdbc.queryForObject("SELECT delivery_days IS NULL FROM supplier WHERE id = ?", Boolean.class,
				Long.parseLong(id))).isTrue();

		create(mine, """
				{"name": "바다수산"}
				""");
		call(patch("/suppliers/{id}", id), mine, """
				{"name": "바다수산"}
				""")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("SUPPLIER_DUPLICATE_NAME"));
	}

	@Test
	void 상품이_달린_거래처는_삭제_409_없으면_지운다() throws Exception {
		String empty = create(mine, """
				{"name": "빈거래처"}
				""");
		String used = create(mine, """
				{"name": "한빛농산"}
				""");
		long ingredient = insertIngredient(jdbc, mine.storeId(), "대파");
		call(post("/supplier-products"), mine, """
				{"supplierId": "%s", "ingredientId": "%d", "productName": "대파 1단"}
				""".formatted(used, ingredient))
				.andExpect(status().isCreated());

		call(delete("/suppliers/{id}", used), mine, null)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("SUPPLIER_IN_USE"));
		call(delete("/suppliers/{id}", empty), mine, null)
				.andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM supplier WHERE id = ?", Long.class, Long.parseLong(empty)))
				.isZero();
	}

	@Test
	void 남의_가게_거래처는_조회_수정_삭제_모두_404_목록에도_없다() throws Exception {
		String theirs = create(other, """
				{"name": "남의거래처", "leadTimeDays": 2}
				""");

		call(get("/suppliers/{id}", theirs), mine, null)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));
		call(patch("/suppliers/{id}", theirs), mine, """
				{"leadTimeDays": 9}
				""")
				.andExpect(status().isNotFound());
		call(delete("/suppliers/{id}", theirs), mine, null).andExpect(status().isNotFound());
		call(get("/suppliers/abc"), mine, null).andExpect(status().isNotFound());
		call(get("/suppliers"), mine, null).andExpect(jsonPath("$.data", empty()));

		assertThat(jdbc.queryForObject("SELECT lead_time_days FROM supplier WHERE id = ?", Integer.class,
				Long.parseLong(theirs))).isEqualTo(2);
		call(get("/suppliers/{id}", theirs), other, null).andExpect(status().isOk());
	}

	@Test
	void 토큰이_없으면_401() throws Exception {
		mockMvc.perform(get("/suppliers")).andExpect(status().isUnauthorized());
	}

	@Test
	void 금액은_원_단위_정수로_저장한다() throws Exception {
		String id = create(mine, """
				{"name": "한빛농산", "minOrderAmount": 50000}
				""");
		assertThat(jdbc.queryForObject("SELECT min_order_amount FROM supplier WHERE id = ?", BigDecimal.class,
				Long.parseLong(id))).isEqualByComparingTo("50000");
	}

	private String create(LoggedInStore store, String body) throws Exception {
		return JsonPath.read(call(post("/suppliers"), store, body)
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
