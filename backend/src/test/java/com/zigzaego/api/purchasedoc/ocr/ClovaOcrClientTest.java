package com.zigzaego.api.purchasedoc.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 가짜 CLOVA 서버(JDK HttpServer)에 대고 요청 모양 · 글자 만들기 · 재시도 · 실패 코드를 본다 (지라 S15P31A308-111). */
class ClovaOcrClientTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** 표 밖 글자 3개(두 줄) + 표 안 글자 1개(표 줄로만 나와야 함) + 2x3 표. */
	private static final String MIXED = """
			{"images":[{"inferResult":"SUCCESS","fields":[
			  {"inferText":"위글리마트","lineBreak":true,"boundingPoly":{"vertices":[{"x":0,"y":0},{"x":100,"y":0},{"x":100,"y":10},{"x":0,"y":10}]}},
			  {"inferText":"2026-10-18","lineBreak":false,"boundingPoly":{"vertices":[{"x":0,"y":20},{"x":50,"y":20},{"x":50,"y":30},{"x":0,"y":30}]}},
			  {"inferText":"09:12","lineBreak":true,"boundingPoly":{"vertices":[{"x":60,"y":20},{"x":90,"y":20},{"x":90,"y":30},{"x":60,"y":30}]}},
			  {"inferText":"대파","lineBreak":true,"boundingPoly":{"vertices":[{"x":10,"y":110},{"x":40,"y":110},{"x":40,"y":120},{"x":10,"y":120}]}}
			],"tables":[{"boundingPoly":{"vertices":[{"x":0,"y":100},{"x":300,"y":100},{"x":300,"y":200},{"x":0,"y":200}]},
			  "cells":[
			    {"rowIndex":0,"columnIndex":0,"cellTextLines":[{"cellWords":[{"inferText":"대파"},{"inferText":"1단"}]}]},
			    {"rowIndex":0,"columnIndex":1,"cellTextLines":[{"cellWords":[{"inferText":"3,980"}]}]},
			    {"rowIndex":0,"columnIndex":2,"cellTextLines":[{"cellWords":[{"inferText":"2"}]}]},
			    {"rowIndex":1,"columnIndex":0,"cellTextLines":[{"cellWords":[{"inferText":"키친타올"}]}]},
			    {"rowIndex":1,"columnIndex":2,"cellTextLines":[{"cellWords":[{"inferText":"1"}]}]}
			  ]}]}]}""";

	private HttpServer server;
	private final Deque<Reply> replies = new ArrayDeque<>();
	private final List<Captured> requests = new ArrayList<>();
	private final List<Duration> slept = new ArrayList<>();

	private record Reply(int status, String body) {
	}

	private record Captured(String path, String secret, String body) {
	}

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			synchronized (requests) {
				requests.add(new Captured(exchange.getRequestURI().getPath(),
						exchange.getRequestHeaders().getFirst(ClovaOcrClient.SECRET_HEADER), body));
			}
			Reply reply = replies.isEmpty() ? new Reply(500, "{}") : replies.poll();
			byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(reply.status(), bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
		server.start();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	private ClovaOcrClient client(String secret, Duration minInterval, AtomicLong clock) {
		String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/custom/v1/1234/abcd/general";
		ClovaOcrProperties properties = new ClovaOcrProperties(url, secret, Duration.ofSeconds(5),
				List.of(Duration.ZERO, Duration.ZERO), minInterval);
		return new ClovaOcrClient(RestClient.builder(), properties, JSON, slept::add, clock::get);
	}

	private ClovaOcrClient client(String secret) {
		return client(secret, Duration.ZERO, new AtomicLong());
	}

	private static OcrImage image() {
		return new OcrImage("IMG_1.jpg", "image/png", new byte[] {1, 2, 3});
	}

	@Test
	void 요청_헤더_시크릿_본문_V2_표추출_base64_와_표_섞인_응답의_글자() {
		replies.add(new Reply(200, MIXED));

		OcrResult result = client("ocr-secret").recognize(image());

		assertThat(requests).hasSize(1);
		Captured captured = requests.get(0);
		assertThat(captured.path()).isEqualTo("/custom/v1/1234/abcd/general");
		assertThat(captured.secret()).isEqualTo("ocr-secret");
		JsonNode body = JSON.readTree(captured.body());
		assertThat(body.get("version").asString()).isEqualTo("V2");
		assertThat(body.get("enableTableDetection").asBoolean()).isTrue();
		assertThat(body.get("requestId").asString()).isNotBlank();
		assertThat(body.get("timestamp").isNumber()).isTrue();
		JsonNode img = body.get("images").get(0);
		assertThat(img.get("format").asString()).isEqualTo("png");
		assertThat(img.get("data").asString()).isEqualTo("AQID");

		// 표 밖 글자는 lineBreak 줄, 표 안 "대파" field 는 빠지고 표는 행마다 탭 구분(빈 칸 유지)
		assertThat(result.text()).isEqualTo("위글리마트\n2026-10-18 09:12\n대파 1단\t3,980\t2\n키친타올\t\t1");
		assertThat(result.raw().get("images")).hasSize(1);
	}

	@Test
	void 오백_오백_이백이면_세번_불러_성공() {
		replies.add(new Reply(500, "{}"));
		replies.add(new Reply(503, "{}"));
		replies.add(new Reply(200, MIXED));

		OcrResult result = client("s").recognize(image());

		assertThat(result.text()).startsWith("위글리마트");
		assertThat(requests).hasSize(3);
		assertThat(slept).containsExactly(Duration.ZERO, Duration.ZERO);
	}

	@Test
	void 표_칸_위치가_음수이거나_터무니없이_크면_그_칸만_버린다() {
		replies.add(new Reply(200, """
				{"images":[{"inferResult":"SUCCESS","fields":[],"tables":[{"cells":[
				  {"rowIndex":0,"columnIndex":0,"cellTextLines":[{"cellWords":[{"inferText":"대파"}]}]},
				  {"rowIndex":-1,"columnIndex":0,"cellTextLines":[{"cellWords":[{"inferText":"음수"}]}]},
				  {"rowIndex":2000000000,"columnIndex":0,"cellTextLines":[{"cellWords":[{"inferText":"거대"}]}]},
				  {"rowIndex":0,"columnIndex":1,"columnSpan":2000000000,"cellTextLines":[{"cellWords":[{"inferText":"넓음"}]}]}
				]}]}]}"""));

		assertThat(client("s").recognize(image()).text()).isEqualTo("대파");
	}

	@Test
	void 사백이십구는_다시_불러_성공() {
		replies.add(new Reply(429, "{}"));
		replies.add(new Reply(200, MIXED));

		assertThat(client("s").recognize(image()).text()).startsWith("위글리마트");
		assertThat(requests).hasSize(2);
	}

	@Test
	void 오백_세번이면_OCR_FAILED_호출_3번() {
		replies.add(new Reply(500, "{}"));
		replies.add(new Reply(500, "{}"));
		replies.add(new Reply(500, "{}"));

		assertThatThrownBy(() -> client("s").recognize(image()))
				.isInstanceOfSatisfying(OcrException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(OcrErrorCode.FAILED));
		assertThat(requests).hasSize(3);
	}

	@Test
	void 사백이면_재시도_없이_OCR_FAILED_호출_1번() {
		replies.add(new Reply(400, "{\"code\":\"0011\"}"));

		assertThatThrownBy(() -> client("s").recognize(image()))
				.isInstanceOfSatisfying(OcrException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(OcrErrorCode.FAILED));
		assertThat(requests).hasSize(1);
	}

	@Test
	void inferResult_FAILURE_면_OCR_FAILED() {
		replies.add(new Reply(200, "{\"images\":[{\"inferResult\":\"FAILURE\",\"message\":\"bad image\"}]}"));

		assertThatThrownBy(() -> client("s").recognize(image()))
				.isInstanceOfSatisfying(OcrException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(OcrErrorCode.FAILED));
		assertThat(requests).hasSize(1);
	}

	@Test
	void 글자가_없으면_OCR_EMPTY() {
		replies.add(new Reply(200, "{\"images\":[{\"inferResult\":\"SUCCESS\",\"fields\":[]}]}"));

		assertThatThrownBy(() -> client("s").recognize(image()))
				.isInstanceOfSatisfying(OcrException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(OcrErrorCode.EMPTY));
	}

	@Test
	void 응답이_JSON_아니면_OCR_FAILED() {
		replies.add(new Reply(200, "<html>"));

		assertThatThrownBy(() -> client("s").recognize(image()))
				.isInstanceOfSatisfying(OcrException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(OcrErrorCode.FAILED));
	}

	@Test
	void 시크릿이_비면_호출_없이_OCR_UNAVAILABLE() {
		assertThatThrownBy(() -> client(" ").recognize(image()))
				.isInstanceOfSatisfying(OcrException.class,
						e -> assertThat(e.getErrorCode()).isEqualTo(OcrErrorCode.UNAVAILABLE));
		assertThat(requests).isEmpty();
	}

	@Test
	void 호출_사이_최소_간격만큼_기다린다() {
		replies.add(new Reply(200, MIXED));
		replies.add(new Reply(200, MIXED));
		AtomicLong clock = new AtomicLong(10_000);
		ClovaOcrClient client = client("s", Duration.ofSeconds(1), clock);

		client.recognize(image());
		clock.addAndGet(300); // 0.3초 뒤 두 번째 호출 → 0.7초 기다린다
		client.recognize(image());

		assertThat(slept).containsExactly(Duration.ofMillis(700));
		assertThat(requests).hasSize(2);
	}
}
