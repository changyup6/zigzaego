package com.zigzaego.api.job;

import static com.zigzaego.api.job.JobTestSupport.insertStore;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.support.FakeStoreConfig;
import com.zigzaego.api.support.FakeStoreConfig.FakeCurrentStoreProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@Import({TestcontainersConfiguration.class, FakeStoreConfig.class})
@SpringBootTest
class JobControllerTest {

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private FakeCurrentStoreProvider currentStore;

	private MockMvc mockMvc;

	private long myStoreId;

	private long otherStoreId;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.webAppContextSetup(context)
				.apply(springSecurity())
				.build();
		myStoreId = insertStore(jdbc, "내가게-" + System.nanoTime());
		otherStoreId = insertStore(jdbc, "남의가게-" + System.nanoTime());
		currentStore.storeId = myStoreId;
	}

	@Test
	void 내_가게_job은_200과_FE_Job_모양() throws Exception {
		long jobId = insertJob(myStoreId, "RUNNING", "0.35", null);

		mockMvc.perform(get("/jobs/{id}", jobId).with(user("owner")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.id").isString())
				.andExpect(jsonPath("$.data.id").value(String.valueOf(jobId)))
				.andExpect(jsonPath("$.data.type").value("ONBOARDING"))
				.andExpect(jsonPath("$.data.status").value("RUNNING"))
				.andExpect(jsonPath("$.data.progress").isNumber())
				.andExpect(jsonPath("$.data.progress").value(0.35))
				.andExpect(jsonPath("$.data.result").value(nullValue()))
				.andExpect(jsonPath("$.data.error").value(nullValue()))
				.andExpect(jsonPath("$.error").value(nullValue()));
	}

	@Test
	void 끝난_job의_result는_저장된_JSON_그대로() throws Exception {
		long jobId = insertJob(myStoreId, "DONE", "1", "{\"menus\":[{\"id\":\"1\",\"name\":\"김치찌개\"}]}");

		mockMvc.perform(get("/jobs/{id}", jobId).with(user("owner")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("DONE"))
				.andExpect(jsonPath("$.data.progress").value(1.0))
				.andExpect(jsonPath("$.data.result.menus[0].name").value("김치찌개"));
	}

	@Test
	void 남의_가게_job은_404() throws Exception {
		long jobId = insertJob(otherStoreId, "RUNNING", "0.5", null);

		mockMvc.perform(get("/jobs/{id}", jobId).with(user("owner")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));
	}

	@Test
	void 없는_id는_404() throws Exception {
		mockMvc.perform(get("/jobs/999999").with(user("owner")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));
	}

	@Test
	void 숫자가_아닌_id는_404() throws Exception {
		mockMvc.perform(get("/jobs/abc").with(user("owner")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));
	}

	@Test
	void 인증_없으면_401() throws Exception {
		long jobId = insertJob(myStoreId, "RUNNING", "0.35", null);

		mockMvc.perform(get("/jobs/{id}", jobId))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("COMMON_UNAUTHORIZED"));
	}

	private long insertJob(long storeId, String status, String progress, String resultJson) {
		return jdbc.queryForObject(
				"INSERT INTO job (store_id, type, status, progress, result_json)"
						+ " VALUES (?, 'ONBOARDING', ?, CAST(? AS NUMERIC), CAST(? AS JSONB)) RETURNING id",
				Long.class, storeId, status, progress, resultJson);
	}

}
