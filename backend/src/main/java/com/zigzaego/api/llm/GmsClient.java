package com.zigzaego.api.llm;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * GMS(SSAFY 제공 LLM 창구)를 OpenAI 호환 {@code POST {baseUrl}/chat/completions} 로 부른다 (지라 S15P31A308-93).
 * 응답 모양은 {@code response_format.json_schema}(strict)로 강제하고, {@code choices[0].message.content} 를 JSON 으로 읽는다.
 *
 * <ul>
 *   <li>429 · 5xx · 연결 실패 · 타임아웃: {@code retryDelays} 간격으로 다시 부른다(기본 2번 더). 다 쓰면 {@code LLM_FAILED}</li>
 *   <li>그 밖의 4xx: 다시 불러도 같으므로 바로 {@code LLM_FAILED}</li>
 *   <li>응답 글이 JSON 이 아니면 1번 더 부른다. 또 깨지면 {@code LLM_BAD_OUTPUT}</li>
 *   <li>키 · 주소 · 모델이 비면 부르지 않고 {@code LLM_UNAVAILABLE}</li>
 * </ul>
 * 키는 로그에 남기지 않는다.
 */
@Slf4j
public class GmsClient {

	private static final String PATH = "/chat/completions";
	private static final int BAD_OUTPUT_RETRIES = 1;

	/** 재시도 사이 대기. 테스트에서 실제로 기다리지 않게 바꿔 끼운다. */
	@FunctionalInterface
	public interface Sleeper {
		void sleep(Duration duration) throws InterruptedException;
	}

	private final RestClient restClient;
	private final GmsProperties properties;
	private final JsonMapper jsonMapper;
	private final Sleeper sleeper;

	public GmsClient(RestClient.Builder builder, GmsProperties properties, JsonMapper jsonMapper, Sleeper sleeper) {
		this.restClient = builder.build();
		this.properties = properties;
		this.jsonMapper = jsonMapper;
		this.sleeper = sleeper;
	}

	/**
	 * @return 스키마 모양의 JSON
	 * @throws LlmException {@code LLM_UNAVAILABLE} · {@code LLM_FAILED} · {@code LLM_BAD_OUTPUT}
	 */
	public JsonNode completeJson(LlmRequest request) {
		return complete(request).output();
	}

	/** {@link #completeJson} 과 같되 응답 {@code usage} 의 토큰 수도 돌려준다 (배치 비용 기록용, S15P31A308-229). */
	public LlmResult complete(LlmRequest request) {
		if (!properties.configured()) {
			throw new LlmException(LlmErrorCode.UNAVAILABLE);
		}
		String body = jsonMapper.writeValueAsString(requestBody(request));
		List<Duration> delays = properties.retryDelays();
		int transportFailures = 0;
		int badOutputs = 0;
		while (true) {
			try {
				Reply reply = call(body);
				return new LlmResult(parseContent(reply.content()), reply.promptTokens(), reply.completionTokens());
			} catch (RetryableGmsException e) {
				if (transportFailures >= delays.size()) {
					log.error("GMS 호출 실패, 재시도 소진 ({}): {}", request.schemaName(), e.getMessage());
					throw new LlmException(LlmErrorCode.FAILED);
				}
				log.warn("GMS 호출 실패, {} 뒤 다시 시도 ({}/{}): {}", delays.get(transportFailures), transportFailures + 1,
						delays.size(), e.getMessage());
				sleep(delays.get(transportFailures));
				transportFailures++;
			} catch (BadOutputException e) {
				if (badOutputs >= BAD_OUTPUT_RETRIES) {
					log.error("GMS 응답이 JSON 이 아님 ({})", request.schemaName());
					throw new LlmException(LlmErrorCode.BAD_OUTPUT);
				}
				log.warn("GMS 응답이 JSON 이 아님, 다시 부른다 ({})", request.schemaName());
				badOutputs++;
			}
		}
	}

	private ObjectNode requestBody(LlmRequest request) {
		ObjectNode body = jsonMapper.createObjectNode();
		body.put("model", properties.model());
		ArrayNode messages = body.putArray("messages");
		messages.addObject().put("role", "system").put("content", request.system());
		ObjectNode user = messages.addObject().put("role", "user");
		if (request.images().isEmpty()) {
			user.put("content", request.user());
		} else {
			ArrayNode content = user.putArray("content");
			content.addObject().put("type", "text").put("text", request.user());
			for (LlmImage image : request.images()) {
				String dataUri = "data:" + image.mimeType() + ";base64,"
						+ Base64.getEncoder().encodeToString(image.bytes());
				content.addObject().put("type", "image_url").putObject("image_url").put("url", dataUri);
			}
		}
		ObjectNode schema = body.putObject("response_format").put("type", "json_schema").putObject("json_schema");
		schema.put("name", request.schemaName());
		schema.set("schema", request.schema());
		schema.put("strict", true);
		return body;
	}

	/** 응답 글({@code choices[0].message.content})과 {@code usage} 토큰 수 (없으면 0). */
	private record Reply(String content, long promptTokens, long completionTokens) {
	}

	/** 응답 글({@code choices[0].message.content})을 돌려준다. 다시 부르면 될 수도 있는 실패는 {@link RetryableGmsException}. */
	private Reply call(String body) {
		String response;
		try {
			response = restClient.post()
					.uri(properties.baseUrl().replaceAll("/+$", "") + PATH)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.key())
					.contentType(MediaType.APPLICATION_JSON)
					.body(body)
					.retrieve()
					.body(String.class);
		} catch (HttpClientErrorException e) {
			if (e.getStatusCode().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)) {
				throw new RetryableGmsException("HTTP 429");
			}
			log.error("GMS 호출 거절 {}", e.getStatusCode());
			throw new LlmException(LlmErrorCode.FAILED);
		} catch (HttpServerErrorException e) {
			throw new RetryableGmsException("HTTP " + e.getStatusCode().value());
		} catch (ResourceAccessException e) {
			throw new RetryableGmsException(e.getClass().getSimpleName() + ": " + e.getMessage());
		} catch (RestClientException e) {
			log.error("GMS 호출 실패: {}", e.getMessage());
			throw new LlmException(LlmErrorCode.FAILED);
		}
		JsonNode root;
		try {
			root = jsonMapper.readTree(response == null ? "" : response);
		} catch (JacksonException e) {
			throw new BadOutputException();
		}
		JsonNode content = root.path("choices").path(0).path("message").path("content");
		if (!content.isString()) {
			throw new BadOutputException();
		}
		JsonNode usage = root.path("usage");
		return new Reply(content.asString(), usage.path("prompt_tokens").asLong(0), usage.path("completion_tokens").asLong(0));
	}

	private JsonNode parseContent(String content) {
		try {
			JsonNode node = jsonMapper.readTree(content);
			if (node == null || !node.isObject()) {
				throw new BadOutputException();
			}
			return node;
		} catch (JacksonException e) {
			throw new BadOutputException();
		}
	}

	private void sleep(Duration delay) {
		try {
			sleeper.sleep(delay);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new LlmException(LlmErrorCode.FAILED);
		}
	}

	/** 429 · 5xx · 연결 실패 · 타임아웃. */
	private static final class RetryableGmsException extends RuntimeException {
		RetryableGmsException(String message) {
			super(message);
		}
	}

	/** 응답 글이 JSON 객체가 아님. */
	private static final class BadOutputException extends RuntimeException {
		BadOutputException() {
			super("bad output");
		}
	}
}
