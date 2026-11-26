package com.zigzaego.api.purchasedoc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.auth.AuthTestSupport;
import com.zigzaego.api.llm.GmsClient;
import com.zigzaego.api.llm.LlmRequest;
import com.zigzaego.api.purchasedoc.dto.ReceiptUpload;
import com.zigzaego.api.purchasedoc.job.ReceiptAnalyzer;
import com.zigzaego.api.purchasedoc.job.S3MockContainer;
import com.zigzaego.api.purchasedoc.ocr.FakeOcrClient;
import com.zigzaego.api.purchasedoc.ocr.OcrClient;
import com.zigzaego.api.purchasedoc.ocr.OcrImage;
import com.zigzaego.api.purchasedoc.service.ReceiptJobService;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 영수증 업로드 job 을 S3 저장소로 (ADR-0013). DB · job 실행기 · 저장소(S3Mock)는 실제, OCR · GMS 는 목. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"app.receipt.storage=s3", "app.receipt.s3.bucket=" + ReceiptJobS3Test.BUCKET})
class ReceiptJobS3Test {

	static final String BUCKET = "receipts-job";

	private static final S3Client CLIENT;

	static {
		S3MockContainer.useDummyAwsCredentials();
		CLIENT = S3MockContainer.client();
		S3MockContainer.createBucket(CLIENT, BUCKET);
	}

	@DynamicPropertySource
	static void s3(DynamicPropertyRegistry registry) {
		registry.add("app.receipt.s3.endpoint", S3MockContainer::endpoint);
	}

	@AfterAll
	static void closeClient() {
		CLIENT.close();
	}

	@Autowired
	private ReceiptJobService receiptJobService;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JsonMapper json;

	@MockitoBean
	private GmsClient gmsClient;

	@MockitoBean
	private OcrClient ocrClient;

	@MockitoSpyBean
	private S3Client s3;

	private final FakeOcrClient fakeOcr = new FakeOcrClient(JsonMapper.builder().build());

	@BeforeEach
	void setUp() {
		given(gmsClient.completeJson(any(LlmRequest.class))).willAnswer(invocation -> {
			LlmRequest request = invocation.getArgument(0);
			return ReceiptAnalyzer.NORMALIZE_SCHEMA.equals(request.schemaName()) ? receipt() : noMatch(request);
		});
		given(ocrClient.recognize(any(OcrImage.class))).willAnswer(inv -> fakeOcr.recognize(inv.getArgument(0)));
	}

	@Test
	void upload_원본은_file_path_키로_버킷에_있고_분석이_그_객체를_읽는다() {
		long storeId = insertStore("s3-정상");

		long jobId = start(storeId, List.of(jpg("a.jpg")));
		Map<String, Object> job = awaitFinished(jobId);

		assertThat(job.get("status")).isEqualTo("DONE");
		Map<String, Object> doc = docs(jobId).getFirst();
		assertThat(doc.get("parse_status")).isEqualTo("PARSED");
		String key = (String) doc.get("file_path");
		assertThat(key).startsWith(storeId + "/" + jobId + "/").endsWith(".jpg");
		assertThat(CLIENT.getObjectAsBytes(b -> b.bucket(BUCKET).key(key)).asByteArray())
				.containsExactly(0xFF, 0xD8, 1, 2);
	}

	@Test
	void fileMissing_분석_전에_객체가_지워지면_그_장만_RECEIPT_FILE_MISSING() throws Exception {
		long storeId = insertStore("s3-없음");
		CountDownLatch firstOcr = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		given(ocrClient.recognize(any(OcrImage.class))).willAnswer(inv -> {
			if (firstOcr.getCount() > 0) {
				firstOcr.countDown();
				release.await(10, TimeUnit.SECONDS); // 첫 장을 붙잡은 사이 둘째 장 객체를 지운다
			}
			return fakeOcr.recognize(inv.getArgument(0));
		});

		long jobId = start(storeId, List.of(jpg("a.jpg"), jpg("b.jpg")));
		assertThat(firstOcr.await(10, TimeUnit.SECONDS)).isTrue();
		String second = (String) docs(jobId).get(1).get("file_path");
		CLIENT.deleteObject(b -> b.bucket(BUCKET).key(second));
		release.countDown();
		Map<String, Object> job = awaitFinished(jobId);

		assertThat(job.get("status")).isEqualTo("DONE");
		assertThat(docs(jobId).get(0).get("parse_status")).isEqualTo("PARSED");
		assertThat(docs(jobId).get(1).get("parse_status")).isEqualTo("FAILED");
		assertThat(docs(jobId).get(1).get("error")).isEqualTo(ReceiptAnalyzer.FILE_MISSING);
	}

	@Test
	void saveFails_둘째_장_저장이_실패하면_job_문서가_남지_않고_먼저_올린_객체도_지운다() {
		long storeId = insertStore("s3-실패");
		AtomicInteger puts = new AtomicInteger();
		doAnswer(inv -> {
			if (puts.incrementAndGet() == 2) {
				throw SdkClientException.create("저장소 응답 없음");
			}
			return inv.callRealMethod();
		}).when(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));

		assertThatThrownBy(() -> start(storeId, List.of(jpg("a.jpg"), jpg("b.jpg"))))
				.isInstanceOf(UncheckedIOException.class);

		assertThat(jdbc.queryForObject("SELECT count(*) FROM job WHERE store_id = ?", Long.class, storeId)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_doc WHERE store_id = ?", Long.class, storeId))
				.isZero();
		assertThat(puts.get()).isEqualTo(2);
		assertThat(CLIENT.listObjectsV2(b -> b.bucket(BUCKET).prefix(storeId + "/")).contents()).isEmpty();
	}

	// ── 데이터 · job ──────────────────────────────────────────

	private long insertStore(String label) {
		String name = label + "-" + System.nanoTime();
		return AuthTestSupport.insertStore(jdbc, name);
	}

	private static ReceiptUpload jpg(String name) {
		return new ReceiptUpload(name, "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, 1, 2});
	}

	private JsonNode receipt() {
		ObjectNode out = json.createObjectNode();
		out.put("supplier", "위글리마트");
		out.put("date", "2026-10-18");
		ObjectNode item = out.putArray("items").addObject();
		item.put("name", "대파 1단");
		item.put("unit", "단");
		item.put("qty", new BigDecimal("2"));
		item.put("price", new BigDecimal("3980"));
		item.put("amount", new BigDecimal("7960"));
		return out;
	}

	/** 매칭 요청의 줄마다 "맞는 재료 없음". */
	private JsonNode noMatch(LlmRequest request) {
		String user = request.user();
		int from = user.indexOf("영수증 줄:\n") + "영수증 줄:\n".length();
		int to = user.indexOf("\n\n위 영수증 줄마다");
		ObjectNode out = json.createObjectNode();
		ArrayNode items = out.putArray("items");
		for (JsonNode line : json.readTree(user.substring(from, to))) {
			ObjectNode row = items.addObject();
			row.put("line", line.get("line").asInt());
			row.putNull("ingredient").put("is_new_periodic", false);
			row.putNull("pack");
			row.put("confidence", 0.3);
			row.put("reason", "테스트");
		}
		return out;
	}

	private long start(long storeId, List<ReceiptUpload> uploads) {
		return Long.parseLong(receiptJobService.start(storeId, uploads).id());
	}

	private Map<String, Object> awaitFinished(long jobId) {
		long deadline = System.currentTimeMillis() + 15_000;
		while (System.currentTimeMillis() < deadline) {
			Map<String, Object> row = jdbc.queryForMap("SELECT status, error FROM job WHERE id = ?", jobId);
			if (Set.of("DONE", "FAILED").contains((String) row.get("status"))) {
				return row;
			}
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(e);
			}
		}
		return fail("job %d 이 15초 안에 끝나지 않았다", jobId);
	}

	private List<Map<String, Object>> docs(long jobId) {
		return jdbc.queryForList("SELECT id, file_path, parse_status, error FROM purchase_doc WHERE job_id = ? ORDER BY id",
				jobId);
	}
}
