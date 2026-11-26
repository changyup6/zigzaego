package com.zigzaego.api.global.store;

import static com.zigzaego.api.support.StoreFixtures.insertIngredient;
import static com.zigzaego.api.support.StoreFixtures.insertJob;
import static com.zigzaego.api.support.StoreFixtures.insertMenu;
import static com.zigzaego.api.support.StoreFixtures.insertReceipt;
import static com.zigzaego.api.support.StoreFixtures.loginNewStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.global.exception.BusinessException;
import com.zigzaego.api.global.exception.CommonErrorCode;
import com.zigzaego.api.purchasedoc.entity.PurchaseDoc;
import com.zigzaego.api.purchasedoc.service.PurchaseDocService;
import com.zigzaego.api.support.StoreFixtures.LoggedInStore;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 가게 2개 격리 (ADR-0002, S15P31A308-35). 가짜 가게 provider 없이 실제 로그인 토큰으로 부른다.
 * 가게 A 계정으로 가게 B 의 메뉴 · 재료 · job · 영수증 id 를 넣으면 404 — 403 으로 존재를 알려 주지 않는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class StoreIsolationTest {

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private PurchaseDocService purchaseDocService;

	private MockMvc mockMvc;

	private LoggedInStore storeA;

	private LoggedInStore storeB;

	/** 가게 B 의 리소스 경로. */
	private Map<String, String> pathsOfB;

	private long receiptOfB;

	@BeforeEach
	void setUp() throws Exception {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		storeA = loginNewStore(jdbc, passwordEncoder, mockMvc, "가게A");
		storeB = loginNewStore(jdbc, passwordEncoder, mockMvc, "가게B");

		long jobOfB = insertJob(jdbc, storeB.storeId());
		receiptOfB = insertReceipt(jdbc, storeB.storeId(), jobOfB);
		pathsOfB = Map.of(
				"/menus/" + insertMenu(jdbc, storeB.storeId(), "김치찌개"), "메뉴",
				"/ingredients/" + insertIngredient(jdbc, storeB.storeId(), "등심"), "재료",
				"/jobs/" + jobOfB, "job");
	}

	@Test
	void 남의_가게_메뉴_재료_job은_404_공통_응답() throws Exception {
		for (String path : pathsOfB.keySet()) {
			mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, storeA.bearer()))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.success").value(false))
					.andExpect(jsonPath("$.data").value(nullValue()))
					.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));
		}
	}

	@Test
	void 내_가게_메뉴_재료_job은_200() throws Exception {
		for (String path : pathsOfB.keySet()) {
			String id = path.substring(path.lastIndexOf('/') + 1);
			mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, storeB.bearer()))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.success").value(true))
					.andExpect(jsonPath("$.data.id").value(id));
		}
	}

	@Test
	void 메뉴_재료_응답_모양() throws Exception {
		long menuId = insertMenu(jdbc, storeA.storeId(), "된장찌개");
		long ingredientId = insertIngredient(jdbc, storeA.storeId(), "두부");

		mockMvc.perform(get("/menus/{id}", menuId).header(HttpHeaders.AUTHORIZATION, storeA.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.id").value(String.valueOf(menuId)))
				.andExpect(jsonPath("$.data.name").value("된장찌개"))
				.andExpect(jsonPath("$.data.kind").value("MAIN"))
				.andExpect(jsonPath("$.data.active").value(true));
		mockMvc.perform(get("/ingredients/{id}", ingredientId).header(HttpHeaders.AUTHORIZATION, storeA.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.id").value(String.valueOf(ingredientId)))
				.andExpect(jsonPath("$.data.name").value("두부"))
				.andExpect(jsonPath("$.data.unit").value("G"))
				.andExpect(jsonPath("$.data.kind").value("BOM"))
				.andExpect(jsonPath("$.data.unitStatus").value("ESTIMATED"))
				.andExpect(jsonPath("$.data.storageType").value("CHILLED"));
	}

	@Test
	void 남의_가게_영수증은_서비스에서_404() {
		assertThatThrownBy(() -> purchaseDocService.getForStore(storeA.storeId(), receiptOfB))
				.isInstanceOfSatisfying(BusinessException.class, e -> {
					assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
					assertThat(e.getErrorCode().getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
				});
	}

	@Test
	void 내_가게_영수증은_서비스에서_읽힌다() {
		PurchaseDoc doc = purchaseDocService.getForStore(storeB.storeId(), receiptOfB);

		assertThat(doc.getId()).isEqualTo(receiptOfB);
		assertThat(doc.getStoreId()).isEqualTo(storeB.storeId());
	}

	@Test
	void 없는_id_숫자가_아닌_id도_404() throws Exception {
		for (String path : new String[] {"/menus/999999999", "/menus/abc", "/ingredients/999999999", "/ingredients/-1"}) {
			mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, storeA.bearer()))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));
		}
	}

	@Test
	void 토큰이_없으면_401() throws Exception {
		for (String path : pathsOfB.keySet()) {
			mockMvc.perform(get(path))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.error.code").value("COMMON_UNAUTHORIZED"));
		}
	}
}
