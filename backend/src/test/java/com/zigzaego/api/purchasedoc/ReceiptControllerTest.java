package com.zigzaego.api.purchasedoc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.auth.AuthTestSupport;
import com.zigzaego.api.llm.GmsClient;
import com.zigzaego.api.llm.LlmErrorCode;
import com.zigzaego.api.llm.LlmException;
import com.zigzaego.api.llm.LlmRequest;
import com.zigzaego.api.purchasedoc.dto.ReceiptUpload;
import com.zigzaego.api.purchasedoc.job.ReceiptAnalyzer;
import com.zigzaego.api.purchasedoc.job.ReceiptFileStorage;
import com.zigzaego.api.purchasedoc.service.ReceiptJobService;
import com.zigzaego.api.support.StoreFixtures;
import com.zigzaego.api.support.StoreFixtures.LoggedInStore;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 영수증 HTTP 주소 8개 (지라 S15P31A308-113 컨트롤러). MockMvc 로 실제 주소를 부른다 — DB · job 실행기 · 파일 저장 · 가짜 글자
 * 인식(CLOVA 키 없음 + allow-fake-ocr=true)은 실제, GMS 만 목. 서블릿 업로드 상한은 MockMvc 가 거치지 않으므로 그 1건만 실제 HTTP.
 * job 실행기는 1개 — 다른 job 으로 실행기를 붙잡아 새 job 을 QUEUED 에 묶어 둘 수 있게.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"app.receipt.storage-dir=build/test-receipts", "app.job.executor.pool-size=1"})
class ReceiptControllerTest {

	private static final String SHRIMP = "냉동 칵테일새우 1kg";
	private static final String COLA = "콜라 355ml/24캔";
	private static final byte[] JPG = {(byte) 0xFF, (byte) 0xD8, 1, 2};
	private static final byte[] PDF = "%PDF-1.4 test".getBytes(StandardCharsets.US_ASCII);

	@Autowired
	private WebApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JsonMapper json;

	@Autowired
	private ReceiptJobService receiptJobService;

	@Autowired
	private ReceiptFileStorage storage;

	@LocalServerPort
	private int port;

	@MockitoBean
	private GmsClient gmsClient;

	private MockMvc mockMvc;
	private LoggedInStore storeA;
	private LoggedInStore storeB;

	/** true 면 정형화가 LLM_FAILED 로 실패한다 → 그 장 FAILED. */
	private volatile boolean failNormalize;
	/** null 이 아니면 GMS 호출이 이 문이 열릴 때까지 기다린다 — 실행기(1개)를 붙잡아 다음 job 을 QUEUED 에 묶는다. */
	private volatile CountDownLatch gate;

	@BeforeEach
	void setUp() throws Exception {
		mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
		storeA = StoreFixtures.loginNewStore(jdbc, passwordEncoder, mockMvc, "영수증A");
		storeB = StoreFixtures.loginNewStore(jdbc, passwordEncoder, mockMvc, "영수증B");
		failNormalize = false;
		gate = null;
		given(gmsClient.completeJson(any(LlmRequest.class))).willAnswer(invocation -> {
			CountDownLatch g = gate;
			if (g != null && !g.await(20, TimeUnit.SECONDS)) {
				throw new IllegalStateException("gate 가 20초 안에 열리지 않았다");
			}
			LlmRequest request = invocation.getArgument(0);
			if (ReceiptAnalyzer.NORMALIZE_SCHEMA.equals(request.schemaName())) {
				if (failNormalize) {
					throw new LlmException(LlmErrorCode.FAILED);
				}
				return receipt();
			}
			return json.readTree("{\"items\":[]}"); // 사전 밖 줄은 재료 제안 없음 → 모두 확인 전(PENDING)
		});
	}

	// ── 데이터 ────────────────────────────────────────────────

	/** 가짜 영수증 글자를 정형화한 결과 4줄 · 2026-10-18 · 위글리마트(등록 거래처 아님). */
	private JsonNode receipt() {
		ObjectNode out = json.createObjectNode();
		out.put("supplier", "위글리마트");
		out.put("date", "2026-10-18");
		ArrayNode items = out.putArray("items");
		items.add(item("대파 1단", "단", 2, 3980, 7960));
		items.add(item(SHRIMP, null, 1, 12900, 12900));
		items.add(item("다진마늘 500g", null, 1, 5500, 5500));
		items.add(item("키친타올 6롤", null, 1, 4200, 4200));
		return out;
	}

	private ObjectNode item(String name, String unit, int qty, int price, int amount) {
		ObjectNode item = json.createObjectNode();
		item.put("name", name);
		if (unit == null) {
			item.putNull("unit");
		} else {
			item.put("unit", unit);
		}
		item.put("qty", qty);
		item.put("price", price);
		item.put("amount", amount);
		return item;
	}

	private long insertIngredient(long storeId, String name, String unitStatus) {
		return jdbc.queryForObject("""
				INSERT INTO ingredient (store_id, name, unit, unit_status, price_sensitive, sourcing_type, kind, storage_type, storage_location,
				                        category_id, category_source)
				VALUES (?, ?, 'G', ?, false, 'GENERIC', 'BOM', 'CHILLED', 'FRIDGE',
				        (SELECT id FROM ingredient_category WHERE store_id = ? AND code = 'OTHER'), 'AUTO') RETURNING id""", Long.class, storeId, name,
				unitStatus, storeId);
	}

	private long insertSupplier(long storeId, String name) {
		return jdbc.queryForObject("INSERT INTO supplier (store_id, name) VALUES (?, ?) RETURNING id", Long.class,
				storeId, name);
	}

	private long insertProduct(long storeId, long supplierId, long ingredientId, String name) {
		return jdbc.queryForObject("""
				INSERT INTO supplier_product (store_id, supplier_id, ingredient_id, product_name, base_qty_per_pack, price)
				VALUES (?, ?, ?, ?, 1000, 15000) RETURNING id""", Long.class, storeId, supplierId, ingredientId, name);
	}

	/** job 상태를 정해 넣는다 (RUNNING · FAILED 등). */
	private long insertJob(long storeId, String status, String error) {
		return jdbc.queryForObject("INSERT INTO job (store_id, type, status, error) VALUES (?, 'RECEIPT', ?, ?) RETURNING id",
				Long.class, storeId, status, error);
	}

	/** 읽은 영수증 문서(RECEIVE · 2026-10-12). */
	private long insertParsedDoc(long storeId, Long supplierId) {
		return jdbc.queryForObject("""
				INSERT INTO purchase_doc (store_id, supplier_id, doc_type, doc_date, parse_status, kind)
				VALUES (?, ?, 'RECEIPT', DATE '2026-10-12', 'PARSED', 'RECEIVE') RETURNING id""", Long.class, storeId,
				supplierId);
	}

	private long insertLine(long storeId, long docId, int lineNo, String name, Long ingredientId, Long productId,
			String status, boolean autoConfirmed) {
		String raw = autoConfirmed ? "{\"dictionary\": true, \"autoConfirmed\": true}" : "{\"dictionary\": false}";
		return jdbc.queryForObject("""
				INSERT INTO purchase_doc_line (store_id, doc_id, line_no, raw_name, qty, unit_price, ingredient_id,
				                               supplier_product_id, status, raw)
				VALUES (?, ?, ?, ?, 1, 15000, ?, ?, ?, CAST(? AS JSONB)) RETURNING id""", Long.class, storeId, docId,
				lineNo, name, ingredientId, productId, status, raw);
	}

	private long count(String table, long storeId) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE store_id = ?", Long.class, storeId);
	}

	private String parseStatus(long docId) {
		return jdbc.queryForObject("SELECT parse_status FROM purchase_doc WHERE id = ?", String.class, docId);
	}

	private static String matchId(String name) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(name.getBytes(StandardCharsets.UTF_8));
	}

	private static String decode(String matchId) {
		return new String(Base64.getUrlDecoder().decode(matchId), StandardCharsets.UTF_8);
	}

	// ── 요청 · 기다리기 ────────────────────────────────────────

	private MockMultipartHttpServletRequestBuilder upload(LoggedInStore store, MockMultipartFile... files) {
		MockMultipartHttpServletRequestBuilder builder = multipart("/receipts");
		for (MockMultipartFile file : files) {
			builder.file(file);
		}
		builder.header(HttpHeaders.AUTHORIZATION, store.bearer());
		return builder;
	}

	private static MockMultipartFile jpg(String name) {
		return new MockMultipartFile("files", name, "image/jpeg", JPG);
	}

	private JsonNode body(MvcResult result) throws Exception {
		return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
	}

	private JsonNode board(LoggedInStore store) throws Exception {
		return body(mockMvc.perform(get("/receipts").header(HttpHeaders.AUTHORIZATION, store.bearer()))
				.andExpect(status().isOk()).andReturn()).get("data");
	}

	/** 올리고 job 이 끝날 때까지 기다린다. @return 응답 data */
	private JsonNode uploadAndWait(LoggedInStore store, MockMultipartFile... files) throws Exception {
		JsonNode data = body(mockMvc.perform(upload(store, files)).andExpect(status().isAccepted()).andReturn())
				.get("data");
		awaitFinished(Long.parseLong(data.get("jobId").asString()));
		return data;
	}

	private String jobStatus(long jobId) {
		return jdbc.queryForObject("SELECT status FROM job WHERE id = ?", String.class, jobId);
	}

	private void awaitStatus(long jobId, Set<String> statuses) {
		long deadline = System.currentTimeMillis() + 20_000;
		while (System.currentTimeMillis() < deadline) {
			if (statuses.contains(jobStatus(jobId))) {
				return;
			}
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(e);
			}
		}
		fail("job %d 이 20초 안에 %s 가 되지 않았다: %s", jobId, statuses, jobStatus(jobId));
	}

	private void awaitFinished(long jobId) {
		awaitStatus(jobId, Set.of("DONE", "FAILED"));
	}

	/** 다른 가게 job 하나로 실행기(1개)를 붙잡는다. 돌려준 문을 열면 풀린다. */
	private CountDownLatch holdExecutor() {
		CountDownLatch latch = new CountDownLatch(1);
		gate = latch;
		long other = AuthTestSupport.insertStore(jdbc, "붙잡기-" + System.nanoTime());
		long blocker = Long.parseLong(receiptJobService.start(other,
				List.of(new ReceiptUpload("hold.jpg", "image/jpeg", JPG))).id());
		awaitStatus(blocker, Set.of("RUNNING"));
		return latch;
	}

	private void release(CountDownLatch latch) {
		gate = null;
		latch.countDown();
	}

	private static JsonNode fileOf(JsonNode board, long docId) {
		for (JsonNode file : board.get("files")) {
			if (file.get("id").asString().equals(String.valueOf(docId))) {
				return file;
			}
		}
		return null;
	}

	/** FAILED 장 하나(정형화 실패)를 만든다. @return 문서 id */
	private long failedDoc(LoggedInStore store) throws Exception {
		failNormalize = true;
		JsonNode data = uploadAndWait(store, jpg("bad.jpg"));
		failNormalize = false;
		long docId = Long.parseLong(data.get("files").get(0).get("id").asString());
		assertThat(parseStatus(docId)).isEqualTo("FAILED");
		return docId;
	}

	// ── 올리기 · 목록 · 상세 · Swagger ───────────────────────────

	@Test
	void upload_jpg_pdf_두_장_202_jobId_문자열_파일_이름_그대로_st_대기_읽는중_완료() throws Exception {
		mockMvc.perform(upload(storeA, jpg("IMG_2031.jpg"),
						new MockMultipartFile("files", "거래명세서.pdf", "application/pdf", PDF)))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.jobId").isString())
				.andExpect(jsonPath("$.data.files.length()").value(2))
				.andExpect(jsonPath("$.data.files[0].file").value("IMG_2031.jpg"))
				.andExpect(jsonPath("$.data.files[1].file").value("거래명세서.pdf"))
				.andDo(r -> {
					for (JsonNode file : body(r).get("data").get("files")) {
						assertThat(file.get("st").asString()).isIn("wait", "read", "done");
						assertThat(file.get("id").isString()).isTrue();
					}
					awaitFinished(Long.parseLong(body(r).get("data").get("jobId").asString()));
				});
		assertThat(count("job", storeA.storeId())).isEqualTo(1);
		assertThat(count("purchase_doc", storeA.storeId())).isEqualTo(2);
	}

	@Test
	void upload_실행_전_QUEUED_장도_파일_이름과_st_wait() throws Exception {
		CountDownLatch latch = holdExecutor();
		try {
			mockMvc.perform(upload(storeA, jpg("IMG_9.jpg")))
					.andExpect(status().isAccepted())
					.andExpect(jsonPath("$.data.files[0].st").value("wait"))
					.andExpect(jsonPath("$.data.files[0].file").value("IMG_9.jpg"));
		} finally {
			release(latch);
		}
	}

	@Test
	void list_job_이_끝나면_done_RECEIVE_4줄_시각_0900_묶음_id_는_base64url_이고_풀면_name() throws Exception {
		JsonNode uploaded = uploadAndWait(storeA, jpg("IMG_2031.jpg"),
				new MockMultipartFile("files", "b.pdf", "application/pdf", PDF));

		JsonNode board = board(storeA);

		assertThat(board.get("jobId").asString()).isEqualTo(uploaded.get("jobId").asString());
		assertThat(board.get("files")).hasSize(2);
		for (JsonNode file : board.get("files")) {
			assertThat(file.get("st").asString()).isEqualTo("done");
			assertThat(file.get("kind").asString()).isEqualTo("RECEIVE");
			assertThat(file.get("docDate").asString()).isEqualTo("2026-10-18");
			assertThat(file.get("lineCount").asInt()).isEqualTo(4);
			assertThat(file.get("at").asString()).endsWith("+09:00");
			assertThat(file.get("error").isNull()).isTrue();
		}
		assertThat(board.get("files").get(0).get("file").asString()).isEqualTo("IMG_2031.jpg");
		assertThat(board.get("matches").size()).isGreaterThanOrEqualTo(1);
		for (JsonNode match : board.get("matches")) {
			String id = match.get("id").asString();
			assertThat(id).matches("^[A-Za-z0-9_-]+$");
			assertThat(decode(id)).isEqualTo(match.get("name").asString());
		}
		assertThat(board.get("matches").findValuesAsString("name")).contains(SHRIMP);
		assertThat(board.has("options")).isTrue();
		assertThat(board.get("readyDocs").isArray()).isTrue();
	}

	@Test
	void list_빈_가게는_jobId_null_빈_목록() throws Exception {
		JsonNode board = board(storeB);

		assertThat(board.get("jobId").isNull()).isTrue();
		assertThat(board.get("files")).isEmpty();
		assertThat(board.get("matches")).isEmpty();
		assertThat(board.get("readyDocs")).isEmpty();
		assertThat(board.get("options").isArray()).isTrue();
	}

	@Test
	void list_CONFIRMED_장은_7일_지나면_빠지고_확인할_장은_남는다() throws Exception {
		long job = insertJob(storeA.storeId(), "DONE", null);
		long old = jdbc.queryForObject("""
				INSERT INTO purchase_doc (store_id, parse_status, job_id, created_at)
				VALUES (?, 'CONFIRMED', ?, now() - interval '8 days') RETURNING id""", Long.class, storeA.storeId(), job);
		long oldParsed = jdbc.queryForObject("""
				INSERT INTO purchase_doc (store_id, parse_status, job_id, created_at)
				VALUES (?, 'PARSED', ?, now() - interval '30 days') RETURNING id""", Long.class, storeA.storeId(), job);
		long recent = jdbc.queryForObject("""
				INSERT INTO purchase_doc (store_id, parse_status, job_id, created_at)
				VALUES (?, 'CONFIRMED', ?, now() - interval '6 days') RETURNING id""", Long.class, storeA.storeId(), job);

		JsonNode board = board(storeA);

		assertThat(fileOf(board, old)).isNull();
		assertThat(fileOf(board, oldParsed)).isNotNull();
		assertThat(fileOf(board, recent)).isNotNull();
		assertThat(fileOf(board, recent).get("file").isNull()).isTrue(); // 결과에 이름이 없으면 null
	}

	@Test
	void detail_한_장_4줄_lineNo_1부터_id_문자열_거래처는_인식한_상호() throws Exception {
		JsonNode uploaded = uploadAndWait(storeA, jpg("IMG_2031.jpg"));
		String docId = uploaded.get("files").get(0).get("id").asString();

		JsonNode data = body(mockMvc.perform(get("/receipts/{docId}", docId)
						.header(HttpHeaders.AUTHORIZATION, storeA.bearer()))
				.andExpect(status().isOk()).andReturn()).get("data");

		assertThat(data.get("id").asString()).isEqualTo(docId);
		assertThat(data.get("file").asString()).isEqualTo("IMG_2031.jpg");
		assertThat(data.get("st").asString()).isEqualTo("done");
		assertThat(data.get("supplierName").asString()).isEqualTo("위글리마트");
		assertThat(data.get("lines")).hasSize(4);
		for (int i = 0; i < 4; i++) {
			JsonNode line = data.get("lines").get(i);
			assertThat(line.get("lineNo").asInt()).isEqualTo(i + 1);
			assertThat(line.get("id").isString()).isTrue();
			assertThat(line.get("status").asString()).isEqualTo("PENDING");
		}
		JsonNode first = data.get("lines").get(0);
		assertThat(first.get("rawName").asString()).isEqualTo("대파 1단");
		assertThat(first.get("unitPrice").isIntegralNumber()).isTrue();
		assertThat(first.get("unitPrice").asLong()).isEqualTo(3980);
		assertThat(first.get("amount").asLong()).isEqualTo(7960);
		assertThat(first.get("qty").decimalValue()).isEqualByComparingTo("2");
	}

	@Test
	void swagger_영수증_주소_경로_6개_메서드_8개() throws Exception {
		JsonNode docs = body(mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn());
		JsonNode paths = docs.get("paths");

		Map<String, Set<String>> expected = Map.of(
				"/receipts", Set.of("get", "post"),
				"/receipts/{docId}", Set.of("get", "delete"),
				"/receipts/{docId}/retry", Set.of("post"),
				"/receipts/matches/{matchId}/confirm", Set.of("post"),
				"/receipts/lines/confirm", Set.of("post"),
				"/receipts/docs/confirm", Set.of("post"));
		List<String> receiptPaths = new ArrayList<>();
		int methods = 0;
		for (String path : paths.propertyNames()) {
			if (path.equals("/receipts") || path.startsWith("/receipts/")) {
				receiptPaths.add(path);
				Set<String> names = Set.copyOf(paths.get(path).propertyNames());
				assertThat(names).as(path).containsAll(expected.get(path));
				methods += expected.get(path).size();
			}
		}
		assertThat(receiptPaths).containsExactlyInAnyOrderElementsOf(expected.keySet());
		assertThat(methods).isEqualTo(8);
		assertThat(paths.get("/receipts").get("post").get("requestBody").get("content").has("multipart/form-data"))
				.as(paths.get("/receipts").get("post").toString()).isTrue();
		JsonNode uploadSchema = paths.get("/receipts").get("post").get("requestBody").get("content")
				.get("multipart/form-data").path("schema");
		if (uploadSchema.has("$ref")) {
			String name = uploadSchema.get("$ref").asString().replace("#/components/schemas/", "");
			uploadSchema = docs.get("components").get("schemas").get(name);
		}
		assertThat(uploadSchema.path("properties").path("files").toString()).contains("binary");
		assertThat(paths.get("/receipts").get("post").get("responses").has("202")).isTrue();
	}

	// ── 다시 읽기 ─────────────────────────────────────────────

	@Test
	void retry_FAILED_장_200_st_wait_새_jobId_끝나면_done_줄_생김_job_id_새_job() throws Exception {
		long docId = failedDoc(storeA);
		String oldJob = board(storeA).get("jobId").asString();

		CountDownLatch latch = holdExecutor();
		JsonNode data;
		try {
			data = body(mockMvc.perform(post("/receipts/{docId}/retry", docId)
							.header(HttpHeaders.AUTHORIZATION, storeA.bearer()))
					.andExpect(status().isOk()).andReturn()).get("data");
		} finally {
			release(latch);
		}

		String newJob = data.get("jobId").asString();
		assertThat(newJob).isNotEqualTo(oldJob);
		assertThat(fileOf(data, docId).get("st").asString()).isEqualTo("wait");
		assertThat(fileOf(data, docId).get("file").asString()).isEqualTo("bad.jpg");
		assertThat(fileOf(data, docId).get("error").isNull()).isTrue();
		awaitFinished(Long.parseLong(newJob));
		JsonNode after = fileOf(board(storeA), docId);
		assertThat(after.get("st").asString()).isEqualTo("done");
		assertThat(after.get("lineCount").asInt()).isEqualTo(4);
		assertThat(after.get("file").asString()).isEqualTo("bad.jpg");
		assertThat(jdbc.queryForObject("SELECT job_id FROM purchase_doc WHERE id = ?", Long.class, docId))
				.isEqualTo(Long.parseLong(newJob));
		assertThat(count("purchase_doc_line", storeA.storeId())).isEqualTo(4);
	}

	@Test
	void retry_done_장은_409_RECEIPT_NOT_FAILED() throws Exception {
		String docId = uploadAndWait(storeA, jpg("ok.jpg")).get("files").get(0).get("id").asString();
		long jobs = count("job", storeA.storeId());

		mockMvc.perform(post("/receipts/{docId}/retry", docId).header(HttpHeaders.AUTHORIZATION, storeA.bearer()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("RECEIPT_NOT_FAILED"));
		assertThat(count("job", storeA.storeId())).isEqualTo(jobs);
		assertThat(count("purchase_doc_line", storeA.storeId())).isEqualTo(4);
	}

	@Test
	void retry_같은_FAILED_장을_동시에_두_번_하나만_200_다른_하나_409_job_1개만_늘어남() throws Exception {
		long docId = failedDoc(storeA);
		long jobs = count("job", storeA.storeId());

		CountDownLatch latch = holdExecutor();
		List<Integer> statuses = new ArrayList<>();
		List<String> codes = new ArrayList<>();
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			CountDownLatch startLine = new CountDownLatch(1);
			Callable<MvcResult> call = () -> {
				startLine.await();
				return mockMvc.perform(post("/receipts/{docId}/retry", docId)
						.header(HttpHeaders.AUTHORIZATION, storeA.bearer())).andReturn();
			};
			List<Future<MvcResult>> futures = List.of(pool.submit(call), pool.submit(call));
			startLine.countDown();
			for (Future<MvcResult> future : futures) {
				MvcResult result = future.get(30, TimeUnit.SECONDS);
				statuses.add(result.getResponse().getStatus());
				if (result.getResponse().getStatus() != 200) {
					codes.add(JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8),
							"$.error.code"));
				}
			}
			// 이어서 한 번 더 눌러도 409
			mockMvc.perform(post("/receipts/{docId}/retry", docId).header(HttpHeaders.AUTHORIZATION, storeA.bearer()))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.error.code").value("RECEIPT_NOT_FAILED"));
		} finally {
			pool.shutdownNow();
			release(latch);
		}

		assertThat(statuses).containsExactlyInAnyOrder(200, 409);
		assertThat(codes).containsExactly("RECEIPT_NOT_FAILED");
		assertThat(count("job", storeA.storeId())).isEqualTo(jobs + 1);
	}

	@Test
	void retry_job_이_SERVER_RESTARTED_로_끝난_PROCESSING_장은_fail_로_보이고_다시_읽기_200() throws Exception {
		long job = insertJob(storeA.storeId(), "FAILED", "SERVER_RESTARTED");
		String path = storage.save(storeA.storeId(), job, "image/jpeg", JPG);
		long docId = jdbc.queryForObject("""
				INSERT INTO purchase_doc (store_id, parse_status, job_id, file_path)
				VALUES (?, 'PROCESSING', ?, ?) RETURNING id""", Long.class, storeA.storeId(), job, path);

		JsonNode before = fileOf(board(storeA), docId);
		assertThat(before.get("st").asString()).isEqualTo("fail");
		assertThat(before.get("error").asString()).isEqualTo("SERVER_RESTARTED");

		JsonNode data = body(mockMvc.perform(post("/receipts/{docId}/retry", docId)
						.header(HttpHeaders.AUTHORIZATION, storeA.bearer()))
				.andExpect(status().isOk()).andReturn()).get("data");
		awaitFinished(Long.parseLong(data.get("jobId").asString()));
		assertThat(parseStatus(docId)).isEqualTo("PARSED");
		assertThat(fileOf(board(storeA), docId).get("st").asString()).isEqualTo("done");
	}

	@Test
	void retry_job_사유도_없이_끊긴_PENDING_장은_error_RECEIPT_INTERRUPTED() throws Exception {
		long job = insertJob(storeA.storeId(), "DONE", null);
		long docId = StoreFixtures.insertReceipt(jdbc, storeA.storeId(), job);

		JsonNode file = fileOf(board(storeA), docId);

		assertThat(file.get("st").asString()).isEqualTo("fail");
		assertThat(file.get("error").asString()).isEqualTo("RECEIPT_INTERRUPTED");
	}

	// ── 지우기 ────────────────────────────────────────────────

	@Test
	void delete_PARSED_장_200_문서_줄_행_0_원본_파일은_남고_그_장_줄로만_된_묶음이_빠진다() throws Exception {
		String docId = uploadAndWait(storeA, jpg("del.jpg")).get("files").get(0).get("id").asString();
		String path = jdbc.queryForObject("SELECT file_path FROM purchase_doc WHERE id = ?", String.class,
				Long.parseLong(docId));
		assertThat(board(storeA).get("matches").findValuesAsString("name")).contains(SHRIMP);

		JsonNode data = body(mockMvc.perform(delete("/receipts/{docId}", docId)
						.header(HttpHeaders.AUTHORIZATION, storeA.bearer()))
				.andExpect(status().isOk()).andReturn()).get("data");

		assertThat(fileOf(data, Long.parseLong(docId))).isNull();
		assertThat(data.get("matches")).isEmpty();
		assertThat(count("purchase_doc", storeA.storeId())).isZero();
		assertThat(count("purchase_doc_line", storeA.storeId())).isZero();
		assertThat(Files.exists(storage.resolve(path))).isTrue();
	}

	@Test
	void delete_사장님이_줄_하나를_정한_장은_409_RECEIPT_ALREADY_CONFIRMED_행_그대로() throws Exception {
		String docId = uploadAndWait(storeA, jpg("a.jpg")).get("files").get(0).get("id").asString();
		JsonNode detail = body(mockMvc.perform(get("/receipts/{docId}", docId)
				.header(HttpHeaders.AUTHORIZATION, storeA.bearer())).andReturn()).get("data");
		String lineId = detail.get("lines").get(3).get("id").asString();
		mockMvc.perform(post("/receipts/lines/confirm")
						.header(HttpHeaders.AUTHORIZATION, storeA.bearer())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"lines\":[{\"lineId\":\"" + lineId + "\",\"action\":\"IGNORE\"}]}"))
				.andExpect(status().isOk());

		mockMvc.perform(delete("/receipts/{docId}", docId).header(HttpHeaders.AUTHORIZATION, storeA.bearer()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("RECEIPT_ALREADY_CONFIRMED"));
		assertThat(count("purchase_doc", storeA.storeId())).isEqualTo(1);
		assertThat(count("purchase_doc_line", storeA.storeId())).isEqualTo(4);
	}

	@Test
	void delete_사장님이_줄_하나를_재료로_확정한_장은_409_RECEIPT_ALREADY_CONFIRMED_가격_이력_그대로() throws Exception {
		long greenOnion = insertIngredient(storeA.storeId(), "대파", "ESTIMATED");
		String docId = uploadAndWait(storeA, jpg("a.jpg")).get("files").get(0).get("id").asString();
		JsonNode detail = body(mockMvc.perform(get("/receipts/{docId}", docId)
				.header(HttpHeaders.AUTHORIZATION, storeA.bearer())).andReturn()).get("data");
		String lineId = detail.get("lines").get(0).get("id").asString();
		mockMvc.perform(post("/receipts/lines/confirm")
						.header(HttpHeaders.AUTHORIZATION, storeA.bearer())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"lines\":[{\"lineId\":\"" + lineId + "\",\"action\":\"INGREDIENT\",\"ingredientId\":\""
								+ greenOnion + "\",\"baseQtyPerPack\":1000}]}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.confirm.confirmedLineIds[0]").value(lineId));
		long priceRows = count("price_history", storeA.storeId());

		mockMvc.perform(delete("/receipts/{docId}", docId).header(HttpHeaders.AUTHORIZATION, storeA.bearer()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("RECEIPT_ALREADY_CONFIRMED"));
		assertThat(count("purchase_doc", storeA.storeId())).isEqualTo(1);
		assertThat(count("purchase_doc_line", storeA.storeId())).isEqualTo(4);
		assertThat(count("price_history", storeA.storeId())).isEqualTo(priceRows);
	}

	@Test
	void delete_job_이_RUNNING_인_PENDING_장은_409_RECEIPT_IN_PROGRESS() throws Exception {
		long job = insertJob(storeA.storeId(), "RUNNING", null);
		long docId = StoreFixtures.insertReceipt(jdbc, storeA.storeId(), job);

		mockMvc.perform(delete("/receipts/{docId}", docId).header(HttpHeaders.AUTHORIZATION, storeA.bearer()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("RECEIPT_IN_PROGRESS"));
		assertThat(count("purchase_doc", storeA.storeId())).isEqualTo(1);
		assertThat(fileOf(board(storeA), docId).get("st").asString()).isEqualTo("wait");
	}

	@Test
	void delete_자동_확정_줄만_있는_장은_지워지고_CONFIRMED_문서는_409() throws Exception {
		long shrimp = insertIngredient(storeA.storeId(), "칵테일새우", "LOCKED");
		long supplier = insertSupplier(storeA.storeId(), "OO수산");
		long product = insertProduct(storeA.storeId(), supplier, shrimp, SHRIMP);
		long auto = insertParsedDoc(storeA.storeId(), supplier);
		insertLine(storeA.storeId(), auto, 1, SHRIMP, shrimp, product, "CONFIRMED", true);
		long confirmed = insertParsedDoc(storeA.storeId(), supplier);
		jdbc.update("UPDATE purchase_doc SET parse_status = 'CONFIRMED' WHERE id = ?", confirmed);

		mockMvc.perform(delete("/receipts/{docId}", auto).header(HttpHeaders.AUTHORIZATION, storeA.bearer()))
				.andExpect(status().isOk());
		mockMvc.perform(delete("/receipts/{docId}", confirmed).header(HttpHeaders.AUTHORIZATION, storeA.bearer()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error.code").value("RECEIPT_ALREADY_CONFIRMED"));
		assertThat(count("purchase_doc", storeA.storeId())).isEqualTo(1);
		assertThat(count("purchase_doc_line", storeA.storeId())).isZero();
	}

	// ── 확정 3종 ─────────────────────────────────────────────

	@Test
	void confirm_같은_상품명_3문서_묶음_확정_200_gain_3000_묶음_빠짐_세_문서_CONFIRMED() throws Exception {
		long shrimp = insertIngredient(storeA.storeId(), "칵테일새우", "ESTIMATED");
		long supplier = insertSupplier(storeA.storeId(), "OO수산");
		List<Long> docs = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			long doc = insertParsedDoc(storeA.storeId(), supplier);
			insertLine(storeA.storeId(), doc, 1, SHRIMP, null, null, "PENDING", false);
			docs.add(doc);
		}
		assertThat(board(storeA).get("matches").findValuesAsString("id")).contains(matchId(SHRIMP));

		MvcResult result = mockMvc.perform(post("/receipts/matches/{matchId}/confirm", matchId(SHRIMP))
						.header(HttpHeaders.AUTHORIZATION, storeA.bearer())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"choice\":\"" + shrimp + "\",\"basePerPack\":1000}"))
				.andExpect(status().isOk())
				.andReturn();
		JsonNode data = body(result).get("data");

		assertThat(data.get("gain").decimalValue()).isEqualByComparingTo("3000");
		assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("\"gain\":3000.000");
		assertThat(data.get("skippedLineIds")).isEmpty();
		assertThat(data.get("matches").findValuesAsString("id")).doesNotContain(matchId(SHRIMP));
		assertThat(data.has("files")).isTrue();
		for (long doc : docs) {
			assertThat(parseStatus(doc)).isEqualTo("CONFIRMED");
		}
	}

	@Test
	void confirm_이름에_슬래시가_든_묶음도_같은_주소로_200() throws Exception {
		long doc = insertParsedDoc(storeA.storeId(), null);
		long line = insertLine(storeA.storeId(), doc, 1, COLA, null, null, "PENDING", false);
		assertThat(board(storeA).get("matches").findValuesAsString("name")).containsExactly(COLA);

		mockMvc.perform(post("/receipts/matches/{matchId}/confirm", matchId(COLA))
						.header(HttpHeaders.AUTHORIZATION, storeA.bearer())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"choice\":\"ignore\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.matches.length()").value(0))
				.andExpect(jsonPath("$.data.gain").value(0));
		assertThat(jdbc.queryForObject("SELECT status FROM purchase_doc_line WHERE id = ?", String.class, line))
				.isEqualTo("IGNORED");
	}

	@Test
	void confirm_줄_하나_IGNORE_200_ignoredLineIds_1개() throws Exception {
		long doc = insertParsedDoc(storeA.storeId(), null);
		long line = insertLine(storeA.storeId(), doc, 1, "기타", null, null, "PENDING", false);
		insertLine(storeA.storeId(), doc, 2, "기타2", null, null, "PENDING", false);

		mockMvc.perform(post("/receipts/lines/confirm")
						.header(HttpHeaders.AUTHORIZATION, storeA.bearer())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"lines\":[{\"lineId\":\"" + line + "\",\"action\":\"IGNORE\"}]}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.confirm.ignoredLineIds.length()").value(1))
				.andExpect(jsonPath("$.data.confirm.ignoredLineIds[0]").value(String.valueOf(line)))
				.andExpect(jsonPath("$.data.matches.length()").value(1));
	}

	@Test
	void confirm_줄이_모두_자동_확정된_문서를_docs_confirm_200_CONFIRMED_readyDocs_에서_빠짐() throws Exception {
		long shrimp = insertIngredient(storeA.storeId(), "칵테일새우", "LOCKED");
		long supplier = insertSupplier(storeA.storeId(), "OO수산");
		long product = insertProduct(storeA.storeId(), supplier, shrimp, SHRIMP);
		long ready = insertParsedDoc(storeA.storeId(), supplier);
		insertLine(storeA.storeId(), ready, 1, SHRIMP, shrimp, product, "CONFIRMED", true);
		assertThat(board(storeA).get("readyDocs").findValuesAsString("docId")).containsExactly(String.valueOf(ready));

		mockMvc.perform(post("/receipts/docs/confirm")
						.header(HttpHeaders.AUTHORIZATION, storeA.bearer())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"docIds\":[\"" + ready + "\"]}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.readyDocs.length()").value(0))
				.andExpect(jsonPath("$.data.confirm.confirmedDocIds[0]").value(String.valueOf(ready)));
		assertThat(parseStatus(ready)).isEqualTo("CONFIRMED");
	}

	@Test
	void confirm_오류는_서비스_코드_그대로_빈_목록_400_깨진_JSON_400_enum_밖_400() throws Exception {
		mockMvc.perform(post("/receipts/docs/confirm").header(HttpHeaders.AUTHORIZATION, storeA.bearer())
						.contentType(MediaType.APPLICATION_JSON).content("{\"docIds\":[]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("RECEIPT_DOC_REQUIRED"));
		mockMvc.perform(post("/receipts/lines/confirm").header(HttpHeaders.AUTHORIZATION, storeA.bearer())
						.contentType(MediaType.APPLICATION_JSON).content("{\"lines\":[]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("RECEIPT_LINE_REQUIRED"));
		mockMvc.perform(post("/receipts/lines/confirm").header(HttpHeaders.AUTHORIZATION, storeA.bearer())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"lines\":[{\"lineId\":\"1\",\"action\":\"EAT\"}]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_MALFORMED_REQUEST"));
		mockMvc.perform(post("/receipts/matches/{matchId}/confirm", matchId(SHRIMP))
						.header(HttpHeaders.AUTHORIZATION, storeA.bearer())
						.contentType(MediaType.APPLICATION_JSON).content("{\"choice\":"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("COMMON_MALFORMED_REQUEST"));
		mockMvc.perform(post("/receipts/matches/{matchId}/confirm", matchId(SHRIMP))
						.header(HttpHeaders.AUTHORIZATION, storeA.bearer())
						.contentType(MediaType.APPLICATION_JSON).content("{\"choice\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("RECEIPT_INGREDIENT_REQUIRED"));
	}

	// ── 가게 경계 ─────────────────────────────────────────────

	@Test
	void scope_가게_B_가_A_의_docId_로_상세_다시읽기_지우기_모두_404_A_행_그대로() throws Exception {
		long docId = failedDoc(storeA);

		mockMvc.perform(get("/receipts/{docId}", docId).header(HttpHeaders.AUTHORIZATION, storeB.bearer()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));
		mockMvc.perform(post("/receipts/{docId}/retry", docId).header(HttpHeaders.AUTHORIZATION, storeB.bearer()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));
		mockMvc.perform(delete("/receipts/{docId}", docId).header(HttpHeaders.AUTHORIZATION, storeB.bearer()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));

		assertThat(parseStatus(docId)).isEqualTo("FAILED");
		assertThat(count("purchase_doc", storeA.storeId())).isEqualTo(1);
		assertThat(count("job", storeA.storeId())).isEqualTo(1);
		assertThat(count("job", storeB.storeId())).isZero();
	}

	@Test
	void scope_가게_B_의_목록에_A_의_장_묶음_0개() throws Exception {
		uploadAndWait(storeA, jpg("a.jpg"));

		JsonNode board = board(storeB);

		assertThat(board.get("files")).isEmpty();
		assertThat(board.get("matches")).isEmpty();
		assertThat(board.get("readyDocs")).isEmpty();
		assertThat(board.get("jobId").isNull()).isTrue();
	}

	@Test
	void scope_A_에만_있는_묶음을_B_가_확정하면_404_RECEIPT_MATCH_NOT_FOUND_A_줄_그대로() throws Exception {
		long doc = insertParsedDoc(storeA.storeId(), null);
		long line = insertLine(storeA.storeId(), doc, 1, SHRIMP, null, null, "PENDING", false);

		mockMvc.perform(post("/receipts/matches/{matchId}/confirm", matchId(SHRIMP))
						.header(HttpHeaders.AUTHORIZATION, storeB.bearer())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"choice\":\"ignore\"}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("RECEIPT_MATCH_NOT_FOUND"));
		assertThat(jdbc.queryForObject("SELECT status FROM purchase_doc_line WHERE id = ?", String.class, line))
				.isEqualTo("PENDING");
	}

	// ── 거절 ─────────────────────────────────────────────────

	@Test
	void reject_docId_가_숫자가_아니면_404_COMMON_NOT_FOUND() throws Exception {
		mockMvc.perform(get("/receipts/abc").header(HttpHeaders.AUTHORIZATION, storeA.bearer()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));
		mockMvc.perform(post("/receipts/abc/retry").header(HttpHeaders.AUTHORIZATION, storeA.bearer()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));
		mockMvc.perform(delete("/receipts/abc").header(HttpHeaders.AUTHORIZATION, storeA.bearer()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("COMMON_NOT_FOUND"));
	}

	@Test
	void reject_base64url_이_아닌_matchId_는_404_RECEIPT_MATCH_NOT_FOUND() throws Exception {
		// 알파벳 밖 글자, 패딩 붙은 표기, 깨진 UTF-8(0xFF), 빈 이름이 아닌 짧은 조각
		for (String bad : List.of("!!!", "a.b", matchId(SHRIMP) + "=", "_w", "A")) {
			mockMvc.perform(post("/receipts/matches/{matchId}/confirm", bad)
							.header(HttpHeaders.AUTHORIZATION, storeA.bearer())
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"choice\":\"ignore\"}"))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.error.code").value("RECEIPT_MATCH_NOT_FOUND"));
		}
	}

	@Test
	void reject_퍼센트가_든_matchId_는_컨트롤러_전에_보안_방화벽이_400_빈_본문() throws Exception {
		// %%% 는 주소에 %25%25%25 로 실린다. Spring Security 기본 방화벽(StrictHttpFirewall)이 인코딩된 % 를 거절한다 —
		// SecurityConfig 는 이 가지에서 고치지 않는다(계약 "하지 않을 것"). base64url 묶음 id 에는 % 가 나오지 않는다
		mockMvc.perform(post("/receipts/matches/{matchId}/confirm", "%%%")
						.header(HttpHeaders.AUTHORIZATION, storeA.bearer())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"choice\":\"ignore\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(r -> assertThat(r.getResponse().getContentAsString()).isEmpty());
	}

	@Test
	void reject_토큰_없으면_401_COMMON_UNAUTHORIZED() throws Exception {
		mockMvc.perform(get("/receipts"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.error.code").value("COMMON_UNAUTHORIZED"));
	}

	@Test
	void reject_파트_없는_올리기는_400_RECEIPT_FILE_REQUIRED_빈_파일도() throws Exception {
		mockMvc.perform(multipart("/receipts").header(HttpHeaders.AUTHORIZATION, storeA.bearer()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("RECEIPT_FILE_REQUIRED"));
		mockMvc.perform(upload(storeA, new MockMultipartFile("files", "empty.jpg", "image/jpeg", new byte[0])))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("RECEIPT_FILE_REQUIRED"));
		assertThat(count("job", storeA.storeId())).isZero();
	}

	@Test
	void reject_multipart_가_아닌_올리기도_500_이_아니라_400_RECEIPT_FILE_REQUIRED() throws Exception {
		mockMvc.perform(post("/receipts").header(HttpHeaders.AUTHORIZATION, storeA.bearer())
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("RECEIPT_FILE_REQUIRED"));
		mockMvc.perform(post("/receipts").header(HttpHeaders.AUTHORIZATION, storeA.bearer()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("RECEIPT_FILE_REQUIRED"));
		assertThat(count("job", storeA.storeId())).isZero();
	}

	@Test
	void reject_51장은_400_RECEIPT_TOO_MANY_FILES_job_문서_0() throws Exception {
		MockMultipartFile[] files = new MockMultipartFile[51];
		for (int i = 0; i < files.length; i++) {
			files[i] = jpg("p" + i + ".jpg");
		}
		mockMvc.perform(upload(storeA, files))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("RECEIPT_TOO_MANY_FILES"));
		assertThat(count("job", storeA.storeId())).isZero();
		assertThat(count("purchase_doc", storeA.storeId())).isZero();
	}

	@Test
	void reject_text_plain_과_HEIC_는_400_RECEIPT_FILE_NOT_SUPPORTED_10MB_초과는_RECEIPT_FILE_TOO_LARGE() throws Exception {
		mockMvc.perform(upload(storeA, new MockMultipartFile("files", "a.txt", "text/plain", new byte[] {1})))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("RECEIPT_FILE_NOT_SUPPORTED"));
		mockMvc.perform(upload(storeA, new MockMultipartFile("files", "a.heic", "image/heic", new byte[] {1})))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("RECEIPT_FILE_NOT_SUPPORTED"));
		mockMvc.perform(upload(storeA, new MockMultipartFile("files", "big.jpg", "image/jpeg",
						new byte[10 * 1024 * 1024 + 1])))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error.code").value("RECEIPT_FILE_TOO_LARGE"));
		assertThat(count("job", storeA.storeId())).isZero();
	}

	@Test
	void reject_요청_21MB_는_서블릿_상한에서_400_COMMON_INVALID_INPUT() throws Exception {
		String boundary = "----receipt" + System.nanoTime();
		byte[] head = ("--" + boundary + "\r\n"
				+ "Content-Disposition: form-data; name=\"files\"; filename=\"big.jpg\"\r\n"
				+ "Content-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8);
		byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
		long size = 21L * 1024 * 1024;
		InputStream zeros = new InputStream() {
			private long left = size;

			@Override
			public int read() {
				if (left <= 0) {
					return -1;
				}
				left--;
				return 0;
			}

			@Override
			public int read(byte[] b, int off, int len) {
				if (left <= 0) {
					return -1;
				}
				int n = (int) Math.min(len, left);
				Arrays.fill(b, off, off + n, (byte) 0);
				left -= n;
				return n;
			}
		};
		InputStream body = new SequenceInputStream(new SequenceInputStream(new ByteArrayInputStream(head), zeros),
				new ByteArrayInputStream(tail));
		// 길이를 알리지 않고(청크) 보낸다 — 서버가 20MB 를 읽고 거절한 뒤 남은 1MB 를 버리고 응답한다
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/receipts"))
				.header(HttpHeaders.AUTHORIZATION, storeA.bearer())
				.header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=" + boundary)
				.POST(HttpRequest.BodyPublishers.ofInputStream(() -> body))
				.build();
		HttpResponse<String> response;
		try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
			response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		}

		assertThat(response.statusCode()).isEqualTo(400);
		JsonNode error = json.readTree(response.body()).get("error");
		assertThat(error.get("code").asString()).isEqualTo("COMMON_INVALID_INPUT");
		assertThat(error.get("message").asString()).isEqualTo("업로드 파일이 너무 큽니다.");
		assertThat(count("job", storeA.storeId())).isZero();
		assertThat(count("purchase_doc", storeA.storeId())).isZero();
	}

	// ── 가짜 인식 닫힘 ────────────────────────────────────────

	@Test
	void closed_allow_fake_ocr_false_면_올리기_다시읽기_503_job_문서_그대로_읽기는_열림() throws Exception {
		long failed = failedDoc(storeA);
		long jobs = count("job", storeA.storeId());
		long docs = count("purchase_doc", storeA.storeId());
		Object target = AopTestUtils.getUltimateTargetObject(receiptJobService);
		ReflectionTestUtils.setField(target, "allowFakeOcr", false);
		try {
			mockMvc.perform(upload(storeA, jpg("x.jpg")))
					.andExpect(status().isServiceUnavailable())
					.andExpect(jsonPath("$.error.code").value("RECEIPT_OCR_NOT_CONFIGURED"));
			mockMvc.perform(post("/receipts/{docId}/retry", failed).header(HttpHeaders.AUTHORIZATION, storeA.bearer()))
					.andExpect(status().isServiceUnavailable())
					.andExpect(jsonPath("$.error.code").value("RECEIPT_OCR_NOT_CONFIGURED"));
			mockMvc.perform(get("/receipts").header(HttpHeaders.AUTHORIZATION, storeA.bearer()))
					.andExpect(status().isOk());
		} finally {
			ReflectionTestUtils.setField(target, "allowFakeOcr", true);
		}
		assertThat(count("job", storeA.storeId())).isEqualTo(jobs);
		assertThat(count("purchase_doc", storeA.storeId())).isEqualTo(docs);
		assertThat(parseStatus(failed)).isEqualTo("FAILED");
	}
}
