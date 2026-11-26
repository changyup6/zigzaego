package com.zigzaego.api.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.global.store.CurrentStoreProvider;
import com.zigzaego.api.onboarding.service.RecipeSetupService;
import com.zigzaego.api.recommendation.service.RecommendationService;
import com.zigzaego.api.support.StoreFixtures;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"app.demo-account.email=preparation@example.test", "app.demo-account.today=2026-11-12",
		"app.demo-preparation.poll-delay=3600000", "app.demo-preparation.initial-delay=3600000"})
class DemoPreparationTest {

	@Autowired WebApplicationContext context;
	@Autowired JdbcTemplate jdbc;
	@Autowired FakeCurrentStoreProvider currentStore;
	@Autowired DemoPreparationService preparation;
	@Autowired DemoPreparationWorker worker;
	@Autowired RecipeSetupService recipes;
	@Autowired PlatformTransactionManager transactionManager;
	@Autowired JsonMapper mapper;
	@MockitoSpyBean RecommendationService recommendations;
	MockMvc mvc;
	long demo;
	long ordinary;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		demo = insertStore("준비대상");
		ordinary = insertStore("일반가게");
		jdbc.update("UPDATE account SET email = 'archived-' || id || '-' || email WHERE email = ?", "preparation@example.test");
		long account = jdbc.queryForObject("INSERT INTO account(email,password_hash,name) VALUES (?, 'hash', '사장') RETURNING id",
				Long.class, "preparation@example.test");
		jdbc.update("INSERT INTO store_member(account_id,store_id,role) VALUES (?,?,'OWNER')", account, demo);
		currentStore.store = demo;
	}

	@Test
	void 조회는_준비를_시작하거나_빈_추천을_완료시키지_않는다() throws Exception {
		mvc.perform(get("/stores/me/demo-preparation").with(user("owner")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.eligible").value(true))
				.andExpect(jsonPath("$.data.status").value("NOT_STARTED"))
				.andExpect(jsonPath("$.data.attempt").value(0));
		assertThat(count("demo_preparation", demo)).isZero();
		mvc.perform(post("/recommendations/run").with(user("owner")))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("DEMO_DATA_PREPARING"));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM recommendation_run WHERE store_id=?", Integer.class, demo)).isZero();
	}

	@Test
	void 일반가게는_정상빈상태를_유지한다() throws Exception {
		currentStore.store = ordinary;
		mvc.perform(get("/stores/me/demo-preparation").with(user("owner")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.eligible").value(false));
		mvc.perform(post("/recommendations/run").with(user("owner")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.createdCount").value(0));
	}

	@Test
	void PREPARING에서_force도_추천과_완료캐시를_남기지_않는다() throws Exception {
		core(demo);
		preparation.register(demo);
		mvc.perform(post("/recommendations/run?force=true").with(user("owner")))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("DEMO_DATA_PREPARING"));
		assertThat(count("recommendation_run", demo)).isZero();
		assertThat(count("recommendation", demo)).isZero();
	}

	@Test
	void 같은가게_동시재시도_3회는_실패단계만_한번_다시시작한다() throws Exception {
		core(demo);
		preparation.register(demo);
		jdbc.update("UPDATE demo_preparation SET status='FAILED',error_code='DEMO_ATTACH_FAILED',retryable=true WHERE store_id=?", demo);
		try (var pool = Executors.newFixedThreadPool(3)) {
			CountDownLatch start = new CountDownLatch(1);
			var tasks = java.util.stream.IntStream.range(0, 3).mapToObj(i -> pool.submit(() -> {
				start.await();
				return preparation.retry(demo);
			})).toList();
			start.countDown();
			for (var task : tasks) {
				assertThat(task.get(10, TimeUnit.SECONDS).status()).isEqualTo("PREPARING");
			}
		}
		assertThat(preparation.get(demo).attempt()).isEqualTo(2);
		assertThat(count("recipe", demo)).isEqualTo(1);
		assertThat(count("recommendation", demo)).isZero();
	}

	@Test
	void 다른가게는_준비상태를_볼수없고_재시도해도_원래가게를_바꾸지_않는다() throws Exception {
		core(demo);
		preparation.register(demo);
		currentStore.store = ordinary;
		mvc.perform(get("/stores/me/demo-preparation").with(user("owner")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.eligible").value(false))
				.andExpect(jsonPath("$.data.status").value("NOT_STARTED"));
		mvc.perform(post("/stores/me/demo-preparation/retry?storeId=" + demo).with(user("owner")))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("DEMO_NOT_ELIGIBLE"));
		assertThat(preparation.get(demo).status()).isEqualTo("PREPARING");
		assertThat(count("demo_preparation", ordinary)).isZero();
	}

	@Test
	void 원본이없는_시작요청은_준비중으로_가장하지_않는다() throws Exception {
		mvc.perform(post("/stores/me/demo-preparation/retry").with(user("owner")))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("DEMO_ONBOARDING_REQUIRED"));
		assertThat(count("demo_preparation", demo)).isZero();
	}

	@Test
	void 자료가_commit되기전에는_계산하지_않고_commit후_추천과_READY를_함께_공개한다() throws Exception {
		long menu = core(demo);
		forecast(demo);
		preparation.register(demo);
		CountDownLatch written = new CountDownLatch(1);
		CountDownLatch commit = new CountDownLatch(1);
		try (var pool = Executors.newSingleThreadExecutor()) {
			var copy = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
				jdbc.update("INSERT INTO pos_sale(store_id,sold_date,menu_id,qty,channel) VALUES (?, '2026-11-05', ?, 10,'HALL')", demo, menu);
				jdbc.update("UPDATE demo_preparation SET status='DATA_READY',phase='RECOMMENDATION' WHERE store_id=?", demo);
				written.countDown();
				try { assertThat(commit.await(10, TimeUnit.SECONDS)).isTrue(); }
				catch (InterruptedException e) { throw new IllegalStateException(e); }
			}));
			assertThat(written.await(10, TimeUnit.SECONDS)).isTrue();
			worker.process(demo);
			assertThat(preparation.get(demo).status()).isEqualTo("PREPARING");
			assertThat(count("recommendation_run", demo)).isZero();
			commit.countDown();
			copy.get(10, TimeUnit.SECONDS);
		}
		doAnswer(invocation -> {
			assertThat(preparation.get(demo).status()).isEqualTo("DATA_READY");
			assertThat(count("pos_sale", demo)).isEqualTo(1);
			return invocation.callRealMethod();
		}).when(recommendations).run(anyLong(), any(), anyBoolean());
		worker.process(demo);
		assertThat(preparation.get(demo).status()).isEqualTo("READY");
		assertThat(count("recommendation_run", demo)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT run_date FROM recommendation_run WHERE store_id=?", LocalDate.class, demo))
				.isEqualTo(LocalDate.parse("2026-11-12"));
		assertThat(count("demo_preparation", ordinary)).isZero();
	}

	@Test
	void forecast누락은_완료가_아니며_오류와_재시도조건을_노출한다() throws Exception {
		core(demo);
		dataReady(demo);
		worker.process(demo);
		mvc.perform(get("/stores/me/demo-preparation").with(user("owner")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("FAILED"))
				.andExpect(jsonPath("$.data.errorCode").value("DEMO_FORECAST_MISSING"))
				.andExpect(jsonPath("$.data.retryable").value(true));
		assertThat(count("recommendation_run", demo)).isZero();
		assertThat(preparation.retry(demo).status()).isEqualTo("DATA_READY");
		forecast(demo);
		worker.process(demo);
		assertThat(preparation.get(demo).status()).isEqualTo("READY");
	}

	@Test
	void 추천실패는_롤백하고_재시도는_자료복사없이_추천만_다시한다() {
		orderingCore(demo);
		forecast(demo);
		dataReady(demo);
		var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
		doAnswer(invocation -> {
			Object result = invocation.callRealMethod();
			if (fail.getAndSet(false)) { throw new IllegalStateException("private upstream detail"); }
			return result;
		}).when(recommendations).run(anyLong(), any(), anyBoolean());
		worker.process(demo);
		assertThat(preparation.get(demo).status()).isEqualTo("FAILED");
		assertThat(preparation.get(demo).errorCode()).isEqualTo("DEMO_RECOMMENDATION_FAILED");
		assertThat(count("recommendation_run", demo)).isZero();
		assertThat(count("recommendation", demo)).isZero();
		assertThat(preparation.retry(demo).status()).isEqualTo("DATA_READY");
		worker.process(demo);
		assertThat(count("recommendation", demo)).isEqualTo(1);
		assertThat(preparation.get(demo).status()).isEqualTo("READY");
		assertThat(count("recipe", demo)).isEqualTo(1);
		assertThat(count("sales_forecast_week", demo)).isEqualTo(1);
	}

	@Test
	void DATA_READY에_동시worker3개도_추천을_한번만_계산한다() throws Exception {
		orderingCore(demo);
		forecast(demo);
		dataReady(demo);
		try (var pool = Executors.newFixedThreadPool(3)) {
			CountDownLatch start = new CountDownLatch(1);
			var tasks = java.util.stream.IntStream.range(0, 3).mapToObj(i -> pool.submit(() -> {
				start.await();
				worker.process(demo);
				return true;
			})).toList();
			start.countDown();
			for (var task : tasks) { task.get(10, TimeUnit.SECONDS); }
		}
		verify(recommendations, times(1)).run(demo, null, true);
		assertThat(count("recommendation", demo)).isEqualTo(1);
		assertThat(count("recommendation_run", demo)).isEqualTo(1);
		assertThat(preparation.get(demo).status()).isEqualTo("READY");
	}

	@Test
	void 레시피확정이_실제로_저장된_같은트랜잭션에서_준비를_등록하고_롤백도_함께한다() {
		String draft = """
				{"questions":[],"draft":{"menus":[{"id":"m1","name":"메뉴","kind":"MAIN","source":"NAVER","lines":[{"ingredientId":"i1","mult":1}]}],"ingredients":[{"id":"i1","name":"재료","unit":"G","kind":"BOM","storageType":"CHILLED","perServing":100,"confidence":0.8}]}}
				""";
		jdbc.update("INSERT INTO job(store_id,type,status,progress,result_json) VALUES (?,'ONBOARDING','DONE',1,?::jsonb)", demo, draft);
		new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
			recipes.confirm(demo, List.of());
			assertThat(preparation.get(demo).status()).isEqualTo("PREPARING");
			tx.setRollbackOnly();
		});
		assertThat(count("recipe", demo)).isZero();
		assertThat(count("demo_preparation", demo)).isZero();
		recipes.skip(demo);
		assertThat(count("recipe", demo)).isEqualTo(1);
		assertThat(preparation.get(demo).status()).isEqualTo("PREPARING");
	}

	@Test
	void 이전_조기실행의_빈완료캐시가_있어도_자료후_추천을_다시계산한다() {
		orderingCore(demo);
		forecast(demo);
		dataReady(demo);
		jdbc.update("INSERT INTO recommendation_run(store_id,run_date,started_at,finished_at,created_count,no_order_count) VALUES (?,'2026-11-12',now(),now(),0,0)", demo);
		worker.process(demo);
		assertThat(count("recommendation", demo)).isEqualTo(1);
		assertThat(count("recommendation_run", demo)).isEqualTo(1);
		assertThat(preparation.get(demo).status()).isEqualTo("READY");
	}

	@Test
	void 재가입으로_보관된_옛가게는_준비와_고정시계_대상에서_빠진다() {
		core(demo);
		dataReady(demo);
		jdbc.update("UPDATE account SET email='archived-' || id || '-' || email WHERE email=?", "preparation@example.test");
		assertThat(preparation.get(demo).eligible()).isFalse();
		worker.process(demo);
		assertThat(count("recommendation_run", demo)).isZero();
	}

	@Test
	void 인증없는_준비조회와_재시도는_거부한다() throws Exception {
		mvc.perform(get("/stores/me/demo-preparation")).andExpect(status().isUnauthorized());
		mvc.perform(post("/stores/me/demo-preparation/retry")).andExpect(status().isUnauthorized());
	}

	private void orderingCore(long store) {
		long menu = core(store);
		for (int day = 1; day <= 28; day++) {
			jdbc.update("INSERT INTO pos_sale(store_id,sold_date,menu_id,qty,channel) VALUES (?,?,?,10,'HALL')",
					store, LocalDate.parse("2026-11-12").minusDays(day), menu);
		}
		long ingredient = jdbc.queryForObject("SELECT ingredient_id FROM recipe WHERE store_id=?", Long.class, store);
		jdbc.update("INSERT INTO stock_count(store_id,ingredient_id,counted_at,qty) VALUES (?,?,'2026-11-11T00:00:00+09:00',1)", store, ingredient);
	}

	private long core(long store) {
		long menu = StoreFixtures.insertMenu(jdbc, store, "메뉴");
		long ingredient = StoreFixtures.insertIngredient(jdbc, store, "재료");
		StoreFixtures.insertRecipe(jdbc, store, menu, ingredient, "MANUAL");
		return menu;
	}

	private void dataReady(long store) {
		preparation.register(store);
		jdbc.update("UPDATE demo_preparation SET status='DATA_READY',phase='RECOMMENDATION' WHERE store_id=?", store);
	}

	@Test
	void 목요일부터_일요일까지의_부분주_예측도_자료준비를_완료한다() {
		core(demo);
		forecast(demo);
		jdbc.update("""
				UPDATE sales_forecast_week SET days=(
				SELECT jsonb_agg(d) FROM jsonb_array_elements(days) d WHERE d->>'date'>='2026-11-12')
				WHERE store_id=?
				""", demo);
		dataReady(demo);
		worker.process(demo);
		assertThat(preparation.get(demo).status()).isEqualTo("READY");
	}

	@Test
	void 내장_시연템플릿이_PREPARING에서_DATA_READY와_추천을_거쳐_READY까지_자동완결된다() throws Exception {
		demoCore(demo);
		preparation.register(demo);
		worker.poll();
		assertThat(preparation.get(demo).status()).isEqualTo("READY");
		assertThat(preparation.get(demo).phase()).isEqualTo("RECOMMENDATION");
		assertThat(count("ingredient", demo)).isEqualTo(28);
		assertThat(count("recipe", demo)).isEqualTo(86);
		assertThat(count("pos_sale", demo)).isEqualTo(6597);
		assertThat(count("purchase_order", demo)).isEqualTo(998);
		assertThat(count("stock_count", demo)).isEqualTo(44);
		assertThat(count("sales_forecast_week", demo)).isEqualTo(1);
		assertThat(count("store_market_quote", demo)).isEqualTo(384);
		assertThat(count("recommendation_run", demo)).isEqualTo(1);
		mvc.perform(get("/receiving").with(user("owner")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.incoming.length()").value(5));
	}

	@Test
	void 템플릿과_다른_메뉴재료면_ATTACH_실패후_재시도가_가능하다() {
		core(demo);
		preparation.register(demo);
		worker.processAttach(demo);
		var failed = preparation.get(demo);
		assertThat(failed.status()).isEqualTo("FAILED");
		assertThat(failed.phase()).isEqualTo("ATTACH");
		assertThat(failed.errorCode()).isEqualTo("DEMO_FINGERPRINT_MISMATCH");
		assertThat(failed.retryable()).isTrue();
		var retried = preparation.retry(demo);
		assertThat(retried.status()).isEqualTo("PREPARING");
		assertThat(retried.attempt()).isEqualTo(2);
	}

	@Test
	void PREPARING에_동시worker3개도_자료연결을_한번만_실행한다() throws Exception {
		demoCore(demo);
		preparation.register(demo);
		try (var pool = Executors.newFixedThreadPool(3)) {
			CountDownLatch start = new CountDownLatch(1);
			var tasks = java.util.stream.IntStream.range(0, 3).mapToObj(i -> pool.submit(() -> {
				start.await();
				worker.processAttach(demo);
				return true;
			})).toList();
			start.countDown();
			for (var task : tasks) { task.get(30, TimeUnit.SECONDS); }
		}
		assertThat(preparation.get(demo).status()).isEqualTo("DATA_READY");
		assertThat(count("pos_sale", demo)).isEqualTo(6597);
	}

	@Test
	void 온보딩_레시피_수량을_변경해도_시연템플릿이_적재되고_추천실행이_성공한다() throws Exception {
		demoCore(demo);
		jdbc.update("""
				UPDATE recipe r SET qty_per_serving = qty_per_serving * 2, source = 'OWNER_CONFIRMED'
				FROM ingredient i WHERE i.id = r.ingredient_id AND r.store_id = ? AND i.name IN ('만두피','새우','돼지고기','김치')
				""", demo);
		preparation.register(demo);
		worker.poll();
		assertThat(preparation.get(demo).status()).isEqualTo("READY");
		assertThat(count("pos_sale", demo)).isEqualTo(6597);
		mvc.perform(post("/recommendations/run").with(user("owner")))
				.andExpect(status().isOk());
	}

	private void demoCore(long store) throws Exception {
		String raw = new String(new ClassPathResource("demo-template/manifest.json").getInputStream().readAllBytes(),
				StandardCharsets.UTF_8);
		JsonNode semantic = mapper.readTree(raw).path("semantic");
		Map<String, Long> menuIds = new HashMap<>();
		for (JsonNode m : semantic.path("menu")) {
			long id = jdbc.queryForObject(
					"INSERT INTO menu(store_id,name,kind,active,price,source) VALUES (?,?,?,?,?,'POS') RETURNING id",
					Long.class, store, m.get(0).asString(), m.get(1).asString(), m.get(2).booleanValue(),
					m.get(3).decimalValue());
			menuIds.put(m.get(0).asString(), id);
		}
		Map<String, Long> ingIds = new HashMap<>();
		for (JsonNode ing : semantic.path("ingredient")) {
			long id = jdbc.queryForObject(
					"INSERT INTO ingredient(store_id,name,unit,kind,storage_type,storage_location,"
							+ "shelf_life_days,sourcing_type,price_sensitive,safety_days,category_id,category_source)"
							+ " VALUES (?,?,?,?,?,'FRIDGE',?,?,?,?, " + StoreFixtures.OTHER_CATEGORY_SQL + ",'AUTO') RETURNING id",
					Long.class, store, ing.get(0).asString(), ing.get(1).asString(), ing.get(2).asString(),
					ing.get(3).asString(), ing.get(4).asInt(), ing.get(5).asString(), ing.get(6).booleanValue(),
					ing.get(7).decimalValue(), store);
			ingIds.put(ing.get(0).asString(), id);
		}
		for (JsonNode r : semantic.path("recipe")) {
			jdbc.update(
					"INSERT INTO recipe(store_id,menu_id,ingredient_id,qty_per_serving,source) VALUES (?,?,?,?,'MANUAL')",
					store, menuIds.get(r.get(0).asString()), ingIds.get(r.get(1).asString()),
					new BigDecimal(r.get(2).decimalValue().toPlainString()));
		}
	}

	private void forecast(long store) {
		String days = java.util.stream.IntStream.range(0, 7)
				.mapToObj(i -> "{\"date\":\"" + LocalDate.parse("2026-11-09").plusDays(i) + "\",\"label\":\"요일\",\"value\":100}")
				.collect(java.util.stream.Collectors.joining(",", "[", "]"));
		jdbc.update("INSERT INTO sales_forecast_week(store_id,week_start,model,pct,reason,days,factors) VALUES (?,'2026-11-09','test',1,'fixture',?::jsonb,'[]')", store, days);
	}

	private int count(String table, long store) {
		return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE store_id=?", Integer.class, store);
	}

	private long insertStore(String name) {
		return com.zigzaego.api.auth.AuthTestSupport.insertStore(jdbc, name + System.nanoTime());
	}

	@TestConfiguration
	static class Config {
		@Bean @Primary FakeCurrentStoreProvider fakeCurrentStoreProvider() { return new FakeCurrentStoreProvider(); }
	}

	static class FakeCurrentStoreProvider implements CurrentStoreProvider {
		long store;
		@Override public long currentStoreId() { return store; }
	}
}
