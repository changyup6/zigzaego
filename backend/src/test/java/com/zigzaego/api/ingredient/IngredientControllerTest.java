package com.zigzaego.api.ingredient;

import static com.zigzaego.api.support.StoreFixtures.OTHER_CATEGORY_SQL;
import static com.zigzaego.api.support.StoreFixtures.insertIngredient;
import static com.zigzaego.api.support.StoreFixtures.insertMenu;
import static com.zigzaego.api.support.StoreFixtures.insertRecipe;
import static com.zigzaego.api.support.StoreFixtures.insertStockCount;
import static com.zigzaego.api.support.StoreFixtures.loginNewStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.support.StoreFixtures.LoggedInStore;
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

/** 재료 CRUD (S15P31A308-44). 실제 로그인 토큰으로 부르고 가게 2개를 넣어 남의 가게 재료가 404 인지 본다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class IngredientControllerTest {

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
	void 만들면_단위는_CONFIRMED_기본값이_채워진다() throws Exception {
		call(post("/ingredients"), mine, """
				{"name": " 등심 ", "unit": "G", "kind": "BOM", "storageType": "CHILLED", "maxOrderQty": 20000}
				""")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.name").value("등심"))
				.andExpect(jsonPath("$.data.unit").value("G"))
				.andExpect(jsonPath("$.data.unitStatus").value("CONFIRMED"))
				.andExpect(jsonPath("$.data.safetyDays").value(1.00))
				.andExpect(jsonPath("$.data.maxOrderQty").value(20000.000))
				.andExpect(jsonPath("$.data.priceSensitive").value(false))
				.andExpect(jsonPath("$.data.sourcingType").value("GENERIC"))
				.andExpect(jsonPath("$.data.storageLocation").value("FRIDGE"))
				// 자동 분류 (S15P31A308-274): 등심은 품목군 표에서 두 품목군(소 · 돼지)이 쓰는 별칭이라 표로 정하지 않고, 테스트엔 LLM 이 없어 기타
				.andExpect(jsonPath("$.data.category.id").value(String.valueOf(otherCategoryId(mine))))
				.andExpect(jsonPath("$.data.category.name").value("기타"))
				.andExpect(jsonPath("$.data.category.code").value("OTHER"))
				.andExpect(jsonPath("$.data.needsReview").value(true))
				.andExpect(jsonPath("$.data.reviewReasons", contains("NEEDS_COUNT")));

		call(post("/ingredients"), mine, """
				{"name": "냉동새우", "unit": "EA", "kind": "BOM", "storageType": "FROZEN"}
				""")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.priceSensitive").value(true))
				.andExpect(jsonPath("$.data.storageLocation").value("FREEZER"));
		call(post("/ingredients"), mine, """
				{"name": "파스타면", "unit": "G", "kind": "ONE_TO_ONE", "storageType": "AMBIENT"}
				""")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.storageLocation").value("SHELF"));
	}

	@Test
	void 같은_이름이면_409_주기형은_만들_수_없다() throws Exception {
		insertIngredient(jdbc, mine.storeId(), "양파");

		call(post("/ingredients"), mine, """
				{"name": "양파", "unit": "G", "kind": "BOM", "storageType": "FRESH"}
				""")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("INGREDIENT_DUPLICATE_NAME"));
		call(post("/ingredients"), mine, """
				{"name": "소스재료", "unit": "G", "kind": "CYCLE", "storageType": "AMBIENT"}
				""")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INGREDIENT_CYCLE_FROM_RECEIPT_ONLY"));
		call(post("/ingredients"), mine, """
				{"name": "", "unit": "G", "kind": "BOM"}
				""")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_INVALID_INPUT"));
	}

	@Test
	void LOCKED_재료의_단위를_바꾸면_409() throws Exception {
		long locked = insertIngredient(jdbc, mine.storeId(), "우유");
		jdbc.update("UPDATE ingredient SET unit = 'ML', unit_status = 'LOCKED' WHERE id = ?", locked);

		call(patch("/ingredients/{id}", locked), mine, """
				{"unit": "G"}
				""")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("INGREDIENT_UNIT_LOCKED"));
		assertThat(jdbc.queryForObject("SELECT unit FROM ingredient WHERE id = ?", String.class, locked)).isEqualTo("ML");

		// 같은 단위를 보내거나 다른 칸만 바꾸는 건 된다. LOCKED 는 그대로
		call(patch("/ingredients/{id}", locked), mine, """
				{"unit": "ML", "safetyDays": 2}
				""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.unit").value("ML"))
				.andExpect(jsonPath("$.data.unitStatus").value("LOCKED"))
				.andExpect(jsonPath("$.data.safetyDays").value(2.00));
	}

	@Test
	void 추정_단위는_사장님이_바꾸면_CONFIRMED() throws Exception {
		long estimated = insertIngredient(jdbc, mine.storeId(), "새우");

		call(patch("/ingredients/{id}", estimated), mine, """
				{"unit": "EA"}
				""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.unit").value("EA"))
				.andExpect(jsonPath("$.data.unitStatus").value("CONFIRMED"));
	}

	@Test
	void 주기형은_보관_방식과_함께_BOM으로만_옮기고_주기형으로는_못_옮긴다() throws Exception {
		long cycle = jdbc.queryForObject(
				"INSERT INTO ingredient (store_id, name, unit, price_sensitive, sourcing_type, kind, storage_location, category_id, category_source)"
						+ " VALUES (?, '토마토 홀', 'G', false, 'GENERIC', 'CYCLE', 'WAREHOUSE', " + OTHER_CATEGORY_SQL + ", 'AUTO') RETURNING id",
				Long.class, mine.storeId(), mine.storeId());
		long bom = insertIngredient(jdbc, mine.storeId(), "면");

		call(patch("/ingredients/{id}", cycle), mine, """
				{"kind": "BOM"}
				""")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INGREDIENT_STORAGE_TYPE_REQUIRED"));
		call(patch("/ingredients/{id}", cycle), mine, """
				{"kind": "BOM", "storageType": "AMBIENT"}
				""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.kind").value("BOM"))
				.andExpect(jsonPath("$.data.storageType").value("AMBIENT"));
		call(patch("/ingredients/{id}", bom), mine, """
				{"kind": "CYCLE"}
				""")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("INGREDIENT_CYCLE_FROM_RECEIPT_ONLY"));
	}

	@Test
	void 보관_위치만_보내면_위치만_바뀌고_다른_칸은_그대로다() throws Exception {
		long ingredient = insertIngredient(jdbc, mine.storeId(), "연어");
		Map<String, Object> before = jdbc.queryForMap(
				"SELECT name, unit, unit_status, kind, storage_type, safety_days, max_order_qty, price_sensitive,"
						+ " shelf_life_days, sourcing_type FROM ingredient WHERE id = ?", ingredient);

		call(patch("/ingredients/{id}", ingredient), mine, """
				{"storageLocation": "FREEZER"}
				""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.id").value(String.valueOf(ingredient)))
				.andExpect(jsonPath("$.data.storageLocation").value("FREEZER"))
				.andExpect(jsonPath("$.data.storageType").value(before.get("storage_type")));

		assertThat(jdbc.queryForObject("SELECT storage_location FROM ingredient WHERE id = ?", String.class, ingredient))
				.isEqualTo("FREEZER");
		assertThat(jdbc.queryForMap(
				"SELECT name, unit, unit_status, kind, storage_type, safety_days, max_order_qty, price_sensitive,"
						+ " shelf_life_days, sourcing_type FROM ingredient WHERE id = ?", ingredient))
				.isEqualTo(before);
	}

	@Test
	void 정해진_4개_밖의_보관_위치는_400_위치는_그대로() throws Exception {
		long ingredient = insertIngredient(jdbc, mine.storeId(), "버터");
		String before = jdbc.queryForObject("SELECT storage_location FROM ingredient WHERE id = ?", String.class, ingredient);

		call(patch("/ingredients/{id}", ingredient), mine, """
				{"storageLocation": "BASEMENT"}
				""")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_MALFORMED_REQUEST"));

		assertThat(jdbc.queryForObject("SELECT storage_location FROM ingredient WHERE id = ?", String.class, ingredient))
				.isEqualTo(before);
	}

	@Test
	void 남의_가게_재료의_보관_위치를_바꾸면_404_위치는_그대로() throws Exception {
		long theirs = insertIngredient(jdbc, other.storeId(), "남의연어");
		String before = jdbc.queryForObject("SELECT storage_location FROM ingredient WHERE id = ?", String.class, theirs);

		call(patch("/ingredients/{id}", theirs), mine, """
				{"storageLocation": "FREEZER"}
				""")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));

		assertThat(jdbc.queryForObject("SELECT storage_location FROM ingredient WHERE id = ?", String.class, theirs))
				.isEqualTo(before);
	}

	@Test
	void 확인_필요는_AI_레시피면_UNANSWERED_실사가_없으면_NEEDS_COUNT_목록_맨_위() throws Exception {
		long menu = insertMenu(jdbc, mine.storeId(), "파스타");
		long settled = insertIngredient(jdbc, mine.storeId(), "올리브유");
		long estimated = insertIngredient(jdbc, mine.storeId(), "면");
		insertRecipe(jdbc, mine.storeId(), menu, estimated, "AI");
		insertRecipe(jdbc, mine.storeId(), menu, settled, "MANUAL");
		insertStockCount(jdbc, mine.storeId(), settled);
		insertStockCount(jdbc, mine.storeId(), estimated);

		call(get("/ingredients"), mine, null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].id").value(String.valueOf(estimated)))
				.andExpect(jsonPath("$.data[0].needsReview").value(true))
				.andExpect(jsonPath("$.data[0].reviewReasons", contains("UNANSWERED")))
				.andExpect(jsonPath("$.data[0].storageLocation").value("FRIDGE"))
				.andExpect(jsonPath("$.data[1].id").value(String.valueOf(settled)))
				.andExpect(jsonPath("$.data[1].needsReview").value(false))
				.andExpect(jsonPath("$.data[1].reviewReasons", empty()));

		jdbc.update("DELETE FROM stock_count WHERE ingredient_id = ?", estimated);
		call(get("/ingredients/{id}", estimated), mine, null)
				.andExpect(jsonPath("$.data.reviewReasons", contains("UNANSWERED", "NEEDS_COUNT")));
	}

	@Test
	void 삭제하면_레시피_줄도_지우고_실사_기록이_있으면_409() throws Exception {
		long menu = insertMenu(jdbc, mine.storeId(), "샐러드");
		long free = insertIngredient(jdbc, mine.storeId(), "양상추");
		long counted = insertIngredient(jdbc, mine.storeId(), "치즈");
		insertRecipe(jdbc, mine.storeId(), menu, free, "MANUAL");
		insertStockCount(jdbc, mine.storeId(), counted);

		call(delete("/ingredients/{id}", free), mine, null).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM ingredient WHERE id = ?", Long.class, free)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM recipe WHERE ingredient_id = ?", Long.class, free)).isZero();

		call(delete("/ingredients/{id}", counted), mine, null)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("INGREDIENT_IN_USE"));
	}

	@Test
	void 남의_가게_재료는_조회_수정_삭제_모두_404_목록에도_없다() throws Exception {
		long theirs = insertIngredient(jdbc, other.storeId(), "남의재료");

		call(get("/ingredients/{id}", theirs), mine, null)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));
		call(patch("/ingredients/{id}", theirs), mine, """
				{"name": "바꿈"}
				""")
				.andExpect(status().isNotFound());
		call(delete("/ingredients/{id}", theirs), mine, null).andExpect(status().isNotFound());
		call(get("/ingredients"), mine, null).andExpect(jsonPath("$.data", empty()));

		assertThat(jdbc.queryForObject("SELECT name FROM ingredient WHERE id = ?", String.class, theirs))
				.isEqualTo("남의재료");
	}

	@Test
	void 재료의_카테고리는_자기_가게의_기타이고_남의_가게_카테고리는_보이지_않는다() throws Exception {
		long myOther = otherCategoryId(mine);
		long theirOther = otherCategoryId(other);
		assertThat(myOther).isNotEqualTo(theirOther);
		long mineOnion = insertIngredient(jdbc, mine.storeId(), "양파");
		insertIngredient(jdbc, other.storeId(), "남의양파");

		call(get("/ingredients"), mine, null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].id").value(String.valueOf(mineOnion)))
				.andExpect(jsonPath("$.data[0].category.id").value(String.valueOf(myOther)))
				.andExpect(jsonPath("$.data[0].category.name").value("기타"));
		call(get("/ingredients/{id}", mineOnion), mine, null)
				.andExpect(jsonPath("$.data.category.id").value(String.valueOf(myOther)));
		call(get("/ingredients"), other, null)
				.andExpect(jsonPath("$.data[0].category.id").value(String.valueOf(theirOther)));
	}

	@Test
	void 인증_없으면_401() throws Exception {
		mockMvc.perform(get("/ingredients"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("COMMON_UNAUTHORIZED"));
	}

	/** 그 가게의 '기타' 카테고리 id (가게마다 하나, V11 · AuthTestSupport.insertStore 가 넣는다). */
	private long otherCategoryId(LoggedInStore store) {
		return jdbc.queryForObject("SELECT id FROM ingredient_category WHERE store_id = ? AND code = 'OTHER'",
				Long.class, store.storeId());
	}

	private ResultActions call(
			MockHttpServletRequestBuilder request,
			LoggedInStore store, String body) throws Exception {
		request.header(HttpHeaders.AUTHORIZATION, store.bearer());
		if (body != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		return mockMvc.perform(request);
	}
}
