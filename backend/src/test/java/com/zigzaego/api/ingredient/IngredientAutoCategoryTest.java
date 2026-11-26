package com.zigzaego.api.ingredient;

import static com.zigzaego.api.support.StoreFixtures.insertIngredient;
import static com.zigzaego.api.support.StoreFixtures.loginNewStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.ingredient.service.IngredientCategoryBackup;
import com.zigzaego.api.llm.GmsClient;
import com.zigzaego.api.llm.LlmRequest;
import com.zigzaego.api.support.LlmFakes;
import com.zigzaego.api.support.StoreFixtures.LoggedInStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 재료 카테고리 자동 분류 API 경로 (S15P31A308-274): {@code POST /ingredients} 가 이름으로 분류하고, {@code POST /ingredient-categories/auto-assign}
 * 이 기타인 재료를 다시 분류한다. 사장님이 고른 것(OWNER)과 남의 가게는 건드리지 않고, 백업 파일로 DB 를 다시 만들어도 LLM 없이 되살린다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "app.ingredient-category.backup-dir=build/test-data/ingredient-categories-api")
class IngredientAutoCategoryTest {

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private IngredientCategoryProperties properties;

	@MockitoBean
	private GmsClient gmsClient;

	private MockMvc mockMvc;

	private LoggedInStore mine;

	private LoggedInStore other;

	@BeforeEach
	void setUp() throws Exception {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		mine = loginNewStore(jdbc, passwordEncoder, mockMvc, "내가게");
		other = loginNewStore(jdbc, passwordEncoder, mockMvc, "남의가게");
		Files.deleteIfExists(backupFile());
	}

	@Test
	void 재료를_만들면_이름으로_분류한다_재료표에_있으면_LLM_없이_없으면_LLM() throws Exception {
		mockMvc.perform(post("/ingredients").header(HttpHeaders.AUTHORIZATION, mine.bearer())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\": \"스파게티\", \"unit\": \"G\", \"kind\": \"BOM\", \"storageType\": \"AMBIENT\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.category.code").value("GRAIN_NOODLE"))
				.andExpect(jsonPath("$.data.category.name").value("곡물·면·떡"));
		verifyNoInteractions(gmsClient);

		when(gmsClient.complete(any(LlmRequest.class)))
				.thenReturn(LlmFakes.categoryAnswer(Map.of("수제 함박 패티", "가공식품")));
		mockMvc.perform(post("/ingredients").header(HttpHeaders.AUTHORIZATION, mine.bearer())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\": \"수제 함박 패티\", \"unit\": \"EA\", \"kind\": \"BOM\", \"storageType\": \"FROZEN\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.category.code").value("PROCESSED"));
		assertThat(jdbc.queryForList("SELECT category_source FROM ingredient WHERE store_id = ?", String.class,
				mine.storeId())).containsOnly("AUTO");

		// 커밋 뒤 백업 파일에 두 재료가 있다
		assertThat(Files.readString(backupFile(), StandardCharsets.UTF_8))
				.contains(",스파게티,GRAIN_NOODLE,곡물·면·떡,AUTO")
				.contains(",수제 함박 패티,PROCESSED,가공식품,AUTO");
	}

	@Test
	void auto_assign_은_자동_분류로_기타인_재료만_다시_분류하고_OWNER_와_남의_가게는_건드리지_않는다() throws Exception {
		long pasta = insertIngredient(jdbc, mine.storeId(), "스파게티");
		long sauce = insertIngredient(jdbc, mine.storeId(), "수제 소스");
		long unknown = insertIngredient(jdbc, mine.storeId(), "정체불명");
		long ownerOther = insertIngredient(jdbc, mine.storeId(), "사장님기타");
		long ownerMeat = insertIngredient(jdbc, mine.storeId(), "사장님육류");
		long theirs = insertIngredient(jdbc, other.storeId(), "스파게티");
		jdbc.update("UPDATE ingredient SET category_source = 'OWNER' WHERE id = ?", ownerOther);
		jdbc.update("UPDATE ingredient SET category_source = 'OWNER', category_id = ? WHERE id = ?",
				categoryId(mine, "MEAT"), ownerMeat);
		when(gmsClient.complete(any(LlmRequest.class)))
				.thenReturn(LlmFakes.categoryAnswer(Map.of("수제 소스", "양념·소스", "정체불명", "없음")));

		mockMvc.perform(post("/ingredient-categories/auto-assign").header(HttpHeaders.AUTHORIZATION, mine.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.assignedCount").value(2))
				.andExpect(jsonPath("$.data.otherCount").value(2));

		ArgumentCaptor<LlmRequest> request = ArgumentCaptor.forClass(LlmRequest.class);
		verify(gmsClient).complete(request.capture());
		assertThat(request.getValue().user()).contains("수제 소스").contains("정체불명")
				.doesNotContain("스파게티").doesNotContain("사장님");
		assertThat(categoryCodeOf(pasta)).isEqualTo("GRAIN_NOODLE");
		assertThat(categoryCodeOf(sauce)).isEqualTo("SAUCE");
		assertThat(categoryCodeOf(unknown)).isEqualTo("OTHER");
		assertThat(categoryCodeOf(ownerOther)).isEqualTo("OTHER");
		assertThat(categoryCodeOf(ownerMeat)).isEqualTo("MEAT");
		assertThat(categoryCodeOf(theirs)).isEqualTo("OTHER");
		assertThat(jdbc.queryForMap("SELECT category_source FROM ingredient WHERE id = ?", ownerOther))
				.containsEntry("category_source", "OWNER");
		assertThat(jdbc.queryForList("SELECT category_source FROM ingredient WHERE id IN (?, ?, ?)", String.class,
				pasta, sauce, unknown)).containsOnly("AUTO");

		// DB 를 다시 만든 셈: 재료를 기타 · AUTO 로 되돌리고 LLM 을 끊어도 백업 파일로 되살아난다 — 사장님이 고른 것은 출처까지
		assertThat(Files.readString(backupFile(), StandardCharsets.UTF_8))
				.contains(",수제 소스,SAUCE,양념·소스,AUTO").contains(",사장님육류,MEAT,육류,OWNER");
		jdbc.update("UPDATE ingredient SET category_id = ?, category_source = 'AUTO' WHERE id IN (?, ?, ?)",
				categoryId(mine, "OTHER"), pasta, sauce, ownerMeat);
		reset(gmsClient);
		// 그 사이 다른 가게가 재료를 만들어 백업을 다시 써도(합쳐 쓰기) 아직 되살리지 않은 줄이 지워지지 않는다
		mockMvc.perform(post("/ingredients").header(HttpHeaders.AUTHORIZATION, other.bearer())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\": \"양파\", \"unit\": \"G\", \"kind\": \"BOM\", \"storageType\": \"FRESH\"}"))
				.andExpect(status().isCreated());
		assertThat(Files.readString(backupFile(), StandardCharsets.UTF_8))
				.contains(",수제 소스,SAUCE,양념·소스,AUTO").contains(",사장님육류,MEAT,육류,OWNER").contains(",양파,VEGETABLE,");

		mockMvc.perform(post("/ingredient-categories/auto-assign").header(HttpHeaders.AUTHORIZATION, mine.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.assignedCount").value(3))
				.andExpect(jsonPath("$.data.otherCount").value(2));

		assertThat(categoryCodeOf(sauce)).isEqualTo("SAUCE");
		assertThat(categoryCodeOf(pasta)).isEqualTo("GRAIN_NOODLE");
		assertThat(categoryCodeOf(ownerMeat)).isEqualTo("MEAT");
		assertThat(jdbc.queryForMap("SELECT category_source FROM ingredient WHERE id = ?", ownerMeat))
				.containsEntry("category_source", "OWNER");
		verify(gmsClient).complete(request.capture());           // 백업에 없는 '정체불명' 만 LLM
		assertThat(request.getValue().user()).contains("정체불명").doesNotContain("수제 소스").doesNotContain("사장님");
	}

	private Path backupFile() {
		return properties.backupDirPath().resolve(IngredientCategoryBackup.LATEST);
	}

	private long categoryId(LoggedInStore store, String code) {
		return jdbc.queryForObject("SELECT id FROM ingredient_category WHERE store_id = ? AND code = ?", Long.class,
				store.storeId(), code);
	}

	private String categoryCodeOf(long ingredientId) {
		return jdbc.queryForObject("SELECT c.code FROM ingredient i JOIN ingredient_category c ON c.id = i.category_id"
				+ " WHERE i.id = ?", String.class, ingredientId);
	}
}
