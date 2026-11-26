package com.zigzaego.api.closingcheck;

import static com.zigzaego.api.support.StoreFixtures.OTHER_CATEGORY_SQL;
import static com.zigzaego.api.support.StoreFixtures.insertIngredient;
import static com.zigzaego.api.support.StoreFixtures.insertMenu;
import static com.zigzaego.api.support.StoreFixtures.loginNewStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.support.FixedClockConfig;
import com.zigzaego.api.support.StoreFixtures.LoggedInStore;
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

/**
 * GET /closing-check/items (S15P31A308-249). 오늘은 FixedClockConfig 의 서울 2026-10-14(수) 12:00.
 */
@Import({TestcontainersConfiguration.class, FixedClockConfig.class})
@SpringBootTest
class ClosingItemsTest {

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

	@BeforeEach
	void setUp() throws Exception {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		mine = loginNewStore(jdbc, passwordEncoder, mockMvc, "마감대상");
		other = loginNewStore(jdbc, passwordEncoder, mockMvc, "남의가게");
		supplier = jdbc.queryForObject("INSERT INTO supplier (store_id, name) VALUES (?, ?) RETURNING id", Long.class,
				mine.storeId(), "채소가게-" + System.nanoTime());
	}

	@Test
	void 재료_8개_가게에서_우선순위대로_5개만_온다() throws Exception {
		// ① 실사 필요: 실사 없음. 양파만 포장 단위가 있어 같은 순위에서 앞
		long 양파 = insertIngredient(jdbc, mine.storeId(), "양파");
		long 대파 = insertIngredient(jdbc, mine.storeId(), "대파");
		long 마늘 = insertIngredient(jdbc, mine.storeId(), "마늘");
		receive(양파, product(양파, "망", "1000"), "1.45");
		// ② 실사 차이 반복: 20% · 15% 부족, 마지막은 맞음
		long 우유 = insertIngredient(jdbc, mine.storeId(), "우유");
		count(우유, "2026-10-01T09:00:00+09:00", "800", "1000");
		count(우유, "2026-10-03T09:00:00+09:00", "850", "1000");
		count(우유, "2026-10-10T09:00:00+09:00", "500", "500");
		// ③ 소진 예상 3일 이내: 하루 1000 g 쓰는데 1500 g 남음
		long 감자 = insertIngredient(jdbc, mine.storeId(), "감자");
		sellDaily(감자);
		count(감자, "2026-10-13T09:00:00+09:00", "2500", null);
		// ④ 마지막 실사 7일 넘음 — 6번째라 잘린다
		long 당근 = insertIngredient(jdbc, mine.storeId(), "당근");
		count(당근, "2026-10-01T09:00:00+09:00", "100", null);
		// 대상 아님: 최근 실사 · 넉넉함 / 오늘 이미 실사(0 g 이라 소진 예상이 오늘이어도) / 주기형
		long 버섯 = insertIngredient(jdbc, mine.storeId(), "버섯");
		count(버섯, "2026-10-12T09:00:00+09:00", "100", null);
		long 호박 = insertIngredient(jdbc, mine.storeId(), "호박");
		count(호박, "2026-10-14T09:00:00+09:00", "0", null);
		jdbc.update("INSERT INTO ingredient (store_id, name, unit, price_sensitive, sourcing_type, kind, storage_type,"
				+ " storage_location, category_id, category_source) VALUES (?, '쌀', 'G', false, 'GENERIC', 'CYCLE', 'AMBIENT',"
				+ " 'WAREHOUSE', " + OTHER_CATEGORY_SQL + ", 'AUTO')", mine.storeId(), mine.storeId());
		insertIngredient(jdbc, other.storeId(), "남의양파");

		items(mine)
				.andExpect(jsonPath("$.data", hasSize(5)))
				.andExpect(jsonPath("$.data[*].ingredientId").value(contains(
						String.valueOf(양파), String.valueOf(대파), String.valueOf(마늘), String.valueOf(우유),
						String.valueOf(감자))))
				.andExpect(jsonPath("$.data[*].reason").value(contains(
						"NEEDS_COUNT", "NEEDS_COUNT", "NEEDS_COUNT", "REPEATED_LOSS", "RUNNING_OUT")));
		assertThat(당근).isPositive();
	}

	@Test
	void 포장_단위가_있으면_포장_수로_0_5_단위_반올림_없으면_기준_단위() throws Exception {
		long 양파 = insertIngredient(jdbc, mine.storeId(), "양파");
		// 대표 상품은 박스(5000 g)지만 가장 최근 입고는 망(1000 g) — 최근 입고 기준
		long box = product(양파, "박스", "5000");
		jdbc.update("UPDATE ingredient SET primary_supplier_product_id = ? WHERE id = ?", box, 양파);
		receive(양파, product(양파, "망", "1000"), "1.45");
		long 달걀 = insertIngredient(jdbc, mine.storeId(), "달걀");
		count(달걀, "2026-10-01T09:00:00+09:00", "42", null);

		items(mine)
				.andExpect(jsonPath("$.data", hasSize(2)))
				.andExpect(jsonPath("$.data[0].name").value("양파"))
				.andExpect(jsonPath("$.data[0].estimatedQty").value(1450.0))
				.andExpect(jsonPath("$.data[0].baseUnit").value("G"))
				.andExpect(jsonPath("$.data[0].packUnit").value("망"))
				.andExpect(jsonPath("$.data[0].baseQtyPerPack").value(1000.0))
				.andExpect(jsonPath("$.data[0].estimatedPacks").value(1.5))
				.andExpect(jsonPath("$.data[0].step").value(0.5))
				.andExpect(jsonPath("$.data[1].name").value("달걀"))
				.andExpect(jsonPath("$.data[1].packUnit").isEmpty())
				.andExpect(jsonPath("$.data[1].baseQtyPerPack").isEmpty())
				.andExpect(jsonPath("$.data[1].estimatedPacks").isEmpty())
				.andExpect(jsonPath("$.data[1].step").value(1));
	}

	@Test
	void 확인할_재료가_없거나_남의_가게_재료뿐이면_빈_목록() throws Exception {
		insertIngredient(jdbc, other.storeId(), "남의양파");
		long 버섯 = insertIngredient(jdbc, mine.storeId(), "버섯");
		count(버섯, "2026-10-12T09:00:00+09:00", "100", null);

		items(mine).andExpect(jsonPath("$.data", hasSize(0)));
	}

	@Test
	void 인증_없으면_401() throws Exception {
		mockMvc.perform(get("/closing-check/items")).andExpect(status().isUnauthorized());
	}

	private long product(long ingredientId, String packUnit, String basePerPack) {
		return jdbc.queryForObject("INSERT INTO supplier_product (store_id, supplier_id, ingredient_id, product_name,"
				+ " pack_unit, base_qty_per_pack) VALUES (?, ?, ?, ?, ?, CAST(? AS NUMERIC)) RETURNING id", Long.class,
				mine.storeId(), supplier, ingredientId, "상품-" + System.nanoTime(), packUnit, basePerPack);
	}

	/** 10-13 에 입고 확인한 발주 줄. qty 는 포장 수. */
	private void receive(long ingredientId, long productId, String packs) {
		long po = jdbc.queryForObject("INSERT INTO purchase_order (store_id, supplier_id, status, sent_at)"
				+ " VALUES (?, ?, 'SENT', now()) RETURNING id", Long.class, mine.storeId(), supplier);
		jdbc.update("INSERT INTO purchase_order_item (store_id, po_id, supplier_product_id, ingredient_id, qty, received_qty,"
				+ " received_at) VALUES (?, ?, ?, ?, CAST(? AS NUMERIC), CAST(? AS NUMERIC), ?)", mine.storeId(), po, productId,
				ingredientId, packs, packs, OffsetDateTime.parse("2026-10-13T10:00:00+09:00"));
	}

	private void count(long ingredientId, String at, String qty, String estimated) {
		jdbc.update("INSERT INTO stock_count (store_id, ingredient_id, counted_at, qty, estimated_qty, method)"
				+ " VALUES (?, ?, ?, CAST(? AS NUMERIC), CAST(? AS NUMERIC), ?)", mine.storeId(), ingredientId,
				OffsetDateTime.parse(at), qty, estimated, estimated == null ? "FULL_COUNT" : "CORRECTED");
	}

	/** 10-01 ~ 10-13 매일 그 재료 1000 g (1인분 100 g × 10). */
	private void sellDaily(long ingredientId) {
		long menu = insertMenu(jdbc, mine.storeId(), "메뉴-" + ingredientId);
		jdbc.update("INSERT INTO recipe (store_id, menu_id, ingredient_id, qty_per_serving, source)"
				+ " VALUES (?, ?, ?, 100, 'MANUAL')", mine.storeId(), menu, ingredientId);
		for (LocalDate d = LocalDate.of(2026, 10, 1); d.isBefore(LocalDate.of(2026, 10, 14)); d = d.plusDays(1)) {
			jdbc.update("INSERT INTO pos_sale (store_id, sold_date, menu_id, qty) VALUES (?, ?, ?, 10)", mine.storeId(), d,
					menu);
		}
	}

	private ResultActions items(LoggedInStore store) throws Exception {
		return mockMvc.perform(get("/closing-check/items").header(HttpHeaders.AUTHORIZATION, store.bearer()))
				.andExpect(status().isOk());
	}

}
