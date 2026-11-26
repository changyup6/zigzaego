package com.zigzaego.api.llm;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 가짜 GMS 서버(JDK HttpServer)에 대고 요청 모양 · 재시도 · 실패 코드를 본다. */
class GmsClientTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String SCHEMA = """
			{"type":"object","properties":{"ok":{"type":"boolean"}},"required":["ok"],"additionalProperties":false}""";

	private HttpServer server;
	private final Deque<Reply> replies = new ArrayDeque<>();
	private final List<Captured> requests = new ArrayList<>();
	private final List<Duration> slept = new ArrayList<>();

	private record Reply(int status, String body) {
	}

	private record Captured(String path, String authorization, String body) {
	}

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			synchronized (requests) {
				requests.add(new Captured(exchange.getRequestURI().getPath(),
						exchange.getRequestHeaders().getFirst("Authorization"), body));
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

	private GmsClient client(String key) {
		String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
		GmsProperties properties = new GmsProperties(key, baseUrl, "test-model", Duration.ofSeconds(5),
				List.of(Duration.ZERO, Duration.ZERO));
		return new GmsClient(RestClient.builder(), properties, JSON, slept::add);
	}

	private static LlmRequest request(List<LlmImage> images) {
		return new LlmRequest("시스템 지시", "사용자 글", images, "test_schema", JSON.readTree(SCHEMA));
	}

	private static String ok(String content) {
		return JSON.writeValueAsString(JSON.createObjectNode().set("choices", JSON.createArrayNode()
				.add(JSON.createObjectNode().set("message", JSON.createObjectNode().put("content", content)))));
	}

	@Test
	void complete_는_usage_토큰_수를_같이_주고_없으면_0() {
		ObjectNode withUsageBody = (ObjectNode) JSON.readTree(ok("{\"ok\":true}"));
		withUsageBody.putObject("usage").put("prompt_tokens", 1234).put("completion_tokens", 56).put("total_tokens", 1290);
		replies.add(new Reply(200, JSON.writeValueAsString(withUsageBody)));
		replies.add(new Reply(200, ok("{\"ok\":false}")));

		LlmResult withUsage = client("k").complete(request(List.of()));
		assertThat(withUsage.output().get("ok").asBoolean()).isTrue();
		assertThat(withUsage.promptTokens()).isEqualTo(1234);
		assertThat(withUsage.completionTokens()).isEqualTo(56);

		LlmResult noUsage = client("k").complete(request(List.of()));
		assertThat(noUsage.output().get("ok").asBoolean()).isFalse();
		assertThat(noUsage.promptTokens()).isZero();
		assertThat(noUsage.completionTokens()).isZero();
	}

	@Test
	void 요청_본문에_model_messages_strict_스키마_이미지_data_URI_Bearer_키() {
		replies.add(new Reply(200, ok("{\"ok\":true}")));

		JsonNode result = client("secret-key").completeJson(
				request(List.of(new LlmImage("image/png", new byte[] {1, 2, 3}))));

		assertThat(result.get("ok").asBoolean()).isTrue();
		assertThat(requests).hasSize(1);
		Captured captured = requests.get(0);
		assertThat(captured.path()).isEqualTo("/v1/chat/completions");
		assertThat(captured.authorization()).isEqualTo("Bearer secret-key");
		JsonNode body = JSON.readTree(captured.body());
		assertThat(body.get("model").asString()).isEqualTo("test-model");
		assertThat(body.get("messages").get(0).get("role").asString()).isEqualTo("system");
		assertThat(body.get("messages").get(0).get("content").asString()).isEqualTo("시스템 지시");
		JsonNode user = body.get("messages").get(1);
		assertThat(user.get("role").asString()).isEqualTo("user");
		assertThat(user.get("content").get(0).get("text").asString()).isEqualTo("사용자 글");
		assertThat(user.get("content").get(1).get("type").asString()).isEqualTo("image_url");
		assertThat(user.get("content").get(1).get("image_url").get("url").asString())
				.isEqualTo("data:image/png;base64,AQID");
		JsonNode format = body.get("response_format");
		assertThat(format.get("type").asString()).isEqualTo("json_schema");
		assertThat(format.get("json_schema").get("name").asString()).isEqualTo("test_schema");
		assertThat(format.get("json_schema").get("strict").asBoolean()).isTrue();
		assertThat(format.get("json_schema").get("schema")).isEqualTo(JSON.readTree(SCHEMA));
	}

	@Test
	void 오백_두_번_뒤_성공이면_결과를_돌려주고_3번_부른다() {
		replies.add(new Reply(500, "{}"));
		replies.add(new Reply(500, "{}"));
		replies.add(new Reply(200, ok("{\"ok\":true}")));

		JsonNode result = client("k").completeJson(request(List.of()));

		assertThat(result.get("ok").asBoolean()).isTrue();
		assertThat(requests).hasSize(3);
		assertThat(slept).hasSize(2);
	}

	@Test
	void 오백_세_번이면_LLM_FAILED_3번_부른다() {
		replies.add(new Reply(500, "{}"));
		replies.add(new Reply(500, "{}"));
		replies.add(new Reply(500, "{}"));

		assertThatThrownBy(() -> client("k").completeJson(request(List.of())))
				.isInstanceOf(LlmException.class)
				.extracting(e -> ((LlmException) e).getErrorCode().getCode())
				.isEqualTo("LLM_FAILED");
		assertThat(requests).hasSize(3);
	}

	@Test
	void 사백이면_재시도_없이_실패_1번_부른다() {
		replies.add(new Reply(400, "{\"error\":\"bad\"}"));
		replies.add(new Reply(200, ok("{\"ok\":true}")));

		assertThatThrownBy(() -> client("k").completeJson(request(List.of())))
				.isInstanceOf(LlmException.class)
				.extracting(e -> ((LlmException) e).getErrorCode().getCode())
				.isEqualTo("LLM_FAILED");
		assertThat(requests).hasSize(1);
	}

	@Test
	void 사백이십구는_재시도한다() {
		replies.add(new Reply(429, "{}"));
		replies.add(new Reply(200, ok("{\"ok\":true}")));

		assertThat(client("k").completeJson(request(List.of())).get("ok").asBoolean()).isTrue();
		assertThat(requests).hasSize(2);
	}

	@Test
	void 깨진_JSON_뒤_정상이면_성공() {
		replies.add(new Reply(200, ok("{\"ok\": tr")));
		replies.add(new Reply(200, ok("{\"ok\":true}")));

		assertThat(client("k").completeJson(request(List.of())).get("ok").asBoolean()).isTrue();
		assertThat(requests).hasSize(2);
	}

	@Test
	void 깨진_JSON_두_번이면_LLM_BAD_OUTPUT() {
		replies.add(new Reply(200, ok("not json")));
		replies.add(new Reply(200, ok("still not json")));

		assertThatThrownBy(() -> client("k").completeJson(request(List.of())))
				.isInstanceOf(LlmException.class)
				.extracting(e -> ((LlmException) e).getErrorCode().getCode())
				.isEqualTo("LLM_BAD_OUTPUT");
		assertThat(requests).hasSize(2);
	}

	@Test
	void 키가_비면_호출_없이_LLM_UNAVAILABLE() {
		assertThatThrownBy(() -> client("").completeJson(request(List.of())))
				.isInstanceOf(LlmException.class)
				.extracting(e -> ((LlmException) e).getErrorCode().getCode())
				.isEqualTo("LLM_UNAVAILABLE");
		assertThat(requests).isEmpty();
	}
}
