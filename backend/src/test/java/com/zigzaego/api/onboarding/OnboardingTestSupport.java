package com.zigzaego.api.onboarding;

import static org.assertj.core.api.Assertions.fail;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.auth.AuthTestSupport;
import com.zigzaego.api.llm.GmsClient;
import com.zigzaego.api.llm.LlmRequest;
import com.zigzaego.api.onboarding.client.ApifyNaverPlaceClient;
import com.zigzaego.api.onboarding.dto.CollectedMenu;
import com.zigzaego.api.onboarding.job.MenuImageFetcher;
import com.zigzaego.api.support.StoreFixtures;
import com.zigzaego.api.support.StoreFixtures.LoggedInStore;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 온보딩 job · 레시피 통합 테스트 공용. 외부 호출 셋(Apify 수집기 · GMS · 메뉴 사진 받기)은 목으로 바꾼다 —
 * 목 구성이 같아야 스프링 컨텍스트를 테스트 클래스끼리 나눠 쓴다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
abstract class OnboardingTestSupport {

	@Autowired
	protected WebApplicationContext context;

	@Autowired
	protected JdbcTemplate jdbc;

	@Autowired
	protected PasswordEncoder passwordEncoder;

	@Autowired
	protected JsonMapper jsonMapper;

	@MockitoBean
	protected ApifyNaverPlaceClient placeClient;

	@MockitoBean
	protected GmsClient gmsClient;

	@MockitoBean
	protected MenuImageFetcher imageFetcher;

	protected MockMvc mockMvc;

	@BeforeEach
	void setUpMockMvc() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
	}

	protected LoggedInStore loginNewStore(String label) throws Exception {
		return StoreFixtures.loginNewStore(jdbc, passwordEncoder, mockMvc, label);
	}

	protected long insertStore(String name) {
		return AuthTestSupport.insertStore(jdbc, name);
	}

	protected Map<String, Object> readJob(long jobId) {
		return jdbc.queryForMap("SELECT status, progress, result_json::text AS result_json, error FROM job WHERE id = ?",
				jobId);
	}

	/** job 행이 조건을 만족할 때까지 50ms 간격으로 최대 15초 기다린다. */
	protected Map<String, Object> awaitJob(long jobId, Predicate<Map<String, Object>> condition) {
		long deadline = System.currentTimeMillis() + 15_000;
		while (System.currentTimeMillis() < deadline) {
			Map<String, Object> row = readJob(jobId);
			if (condition.test(row)) {
				return row;
			}
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(e);
			}
		}
		return fail("job %d 이 15초 안에 조건을 못 채웠다: %s", jobId, readJob(jobId));
	}

	protected Map<String, Object> awaitFinished(long jobId) {
		return awaitJob(jobId, row -> Set.of("DONE", "FAILED").contains((String) row.get("status")));
	}

	protected JsonNode resultOf(Map<String, Object> row) {
		return jsonMapper.readTree((String) row.get("result_json"));
	}

	protected long count(String table, long storeId) {
		return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE store_id = ?", Long.class, storeId);
	}

	protected JsonNode readResource(String path) throws IOException {
		try (InputStream in = new ClassPathResource(path).getInputStream()) {
			return jsonMapper.readTree(in);
		}
	}

	/** 위글리 실제 수집 응답 픽스처(apify/menus-wiggly.json, 32개)를 수집기가 돌려주는 모양으로. */
	protected List<CollectedMenu> wigglyMenus() throws IOException {
		List<CollectedMenu> menus = new ArrayList<>();
		for (JsonNode place : readResource("apify/menus-wiggly.json")) {
			for (JsonNode menu : place.path("menus")) {
				List<String> images = new ArrayList<>();
				menu.path("images").forEach(image -> images.add(image.asString()));
				String digits = menu.path("price").asString("").replaceAll("[^0-9]", "");
				String description = menu.path("description").isString() ? menu.path("description").asString() : null;
				menus.add(new CollectedMenu(menu.path("name").asString(),
						digits.isEmpty() ? null : Integer.valueOf(digits), description, images));
			}
		}
		return menus;
	}

	/** ai 데모 입력(demo_menus.json, 8개). */
	protected List<CollectedMenu> demoMenus() throws IOException {
		List<CollectedMenu> menus = new ArrayList<>();
		for (JsonNode menu : readResource("onboarding/demo_menus.json").path("menus")) {
			menus.add(new CollectedMenu(menu.path("name").asString(),
					menu.path("price").isNumber() ? menu.path("price").intValue() : null,
					menu.path("description").isString() ? menu.path("description").asString() : null, List.of()));
		}
		return menus;
	}

	/**
	 * 가짜 LLM. 구성 추출이면 받은 메뉴마다 ai 데모 형식으로 재료 2~4개를 기계적으로 만들고(표에 없는 재료 · 소스 포함),
	 * 분류면 받은 재료마다 한 줄씩 돌려준다.
	 */
	protected JsonNode mechanicalLlm(LlmRequest request) {
		JsonNode input = jsonMapper.readTree(between(request.user(), '[', ']'));
		ObjectNode out = jsonMapper.createObjectNode();
		if ("menu_extraction".equals(request.schemaName())) {
			String[][] pool = {
					{"파스타면", "BASE"}, {"특제 소스", "SAUCE"}, {"닭고기", "PROTEIN"}, {"트러플 오일", "TOPPING"},
					{"새우", "PROTEIN"}};
			ArrayNode menus = out.putArray("menus");
			int i = 0;
			for (JsonNode menu : input) {
				ObjectNode row = menus.addObject();
				row.put("menu", menu.path("name").asString());
				row.put("kind", "MAIN");
				row.putNull("duplicateOf");
				ArrayNode components = row.putArray("components");
				int n = 2 + (i % 3);
				for (int k = 0; k < n; k++) {
					String[] c = pool[(i + k) % pool.length];
					components.addObject().put("name", c[0]).put("role", c[1]).put("mult", 1);
				}
				i++;
			}
		} else {
			ArrayNode items = out.putArray("items");
			for (JsonNode ingredient : input) {
				items.addObject().put("name", ingredient.path("name").asString()).put("kind", "BOM").put("unit", "G")
						.put("storageType", "CHILLED").put("perServing", 30).put("confidence", 0.5);
			}
		}
		return out;
	}

	private static String between(String text, char open, char close) {
		return text.substring(text.indexOf(open), text.lastIndexOf(close) + 1);
	}
}
