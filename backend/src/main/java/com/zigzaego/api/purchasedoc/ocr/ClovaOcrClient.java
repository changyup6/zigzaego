package com.zigzaego.api.purchasedoc.ocr;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * CLOVA General OCR(표 추출)를 {@code POST {url}} 로 부른다 (S15P31A308-111, DECISIONS #32).
 * 요청 · 글자 만들기 규칙은 ai {@code weglee_ai/ocr/clova.py}(S15P31A308-109) 와 같다:
 * 표 밖 글자는 {@code lineBreak} 기준 줄, 표 안 글자는 빼고 표는 (rowIndex, columnIndex) 격자의 행마다 탭 구분 줄.
 *
 * <ul>
 *   <li>429 · 5xx · 연결 실패 · 타임아웃: {@code retryDelays} 간격으로 다시 부른다. 다 쓰면 {@code OCR_FAILED}</li>
 *   <li>그 밖의 4xx · 응답이 JSON 아님 · {@code inferResult} 가 SUCCESS 아님: {@code OCR_FAILED}</li>
 *   <li>글자가 하나도 없으면 {@code OCR_EMPTY} — 빈 글자를 LLM 에 넘기지 않는다</li>
 *   <li>url · secret 이 비면 부르지 않고 {@code OCR_UNAVAILABLE}</li>
 * </ul>
 * 호출 사이는 {@code minInterval} 이상 띄운다(권장 1 tps). 시크릿은 로그에 남기지 않는다.
 */
@Slf4j
public class ClovaOcrClient implements OcrClient {

	static final String SECRET_HEADER = "X-OCR-SECRET";
	/** 표 격자 상한. 영수증 표는 수십 행 · 열 몇 개라 넉넉하다. */
	static final int MAX_TABLE_ROWS = 1000;
	static final int MAX_TABLE_COLS = 50;

	/** 재시도 · 간격 대기. 테스트에서 실제로 기다리지 않게 바꿔 끼운다. */
	@FunctionalInterface
	public interface Sleeper {
		void sleep(Duration duration) throws InterruptedException;
	}

	private final RestClient restClient;
	private final ClovaOcrProperties properties;
	private final JsonMapper jsonMapper;
	private final Sleeper sleeper;
	private final LongSupplier clock;
	private long lastCallAt = Long.MIN_VALUE;

	public ClovaOcrClient(RestClient.Builder builder, ClovaOcrProperties properties, JsonMapper jsonMapper,
			Sleeper sleeper, LongSupplier clock) {
		this.restClient = builder.build();
		this.properties = properties;
		this.jsonMapper = jsonMapper;
		this.sleeper = sleeper;
		this.clock = clock;
	}

	@Override
	public OcrResult recognize(OcrImage image) {
		if (!properties.configured()) {
			throw new OcrException(OcrErrorCode.UNAVAILABLE);
		}
		String body = jsonMapper.writeValueAsString(requestBody(image));
		List<Duration> delays = properties.retryDelays();
		int failures = 0;
		while (true) {
			try {
				JsonNode response = call(body);
				String text = text(response);
				if (text.isBlank()) {
					throw new OcrException(OcrErrorCode.EMPTY);
				}
				return new OcrResult(text, response);
			} catch (RetryableOcrException e) {
				if (failures >= delays.size()) {
					log.error("CLOVA OCR 호출 실패, 재시도 소진: {}", e.getMessage());
					throw new OcrException(OcrErrorCode.FAILED);
				}
				log.warn("CLOVA OCR 호출 실패, {} 뒤 다시 시도 ({}/{}): {}", delays.get(failures), failures + 1,
						delays.size(), e.getMessage());
				sleep(delays.get(failures));
				failures++;
			}
		}
	}

	private ObjectNode requestBody(OcrImage image) {
		ObjectNode body = jsonMapper.createObjectNode();
		body.put("version", "V2");
		body.put("requestId", UUID.randomUUID().toString());
		body.put("timestamp", System.currentTimeMillis());
		body.put("enableTableDetection", true);
		body.putArray("images").addObject()
				.put("format", image.format())
				.put("name", "page")
				.put("data", Base64.getEncoder().encodeToString(image.bytes()));
		return body;
	}

	private synchronized JsonNode call(String body) {
		waitInterval();
		String response;
		try {
			response = restClient.post()
					.uri(properties.url())
					.header(SECRET_HEADER, properties.secret())
					.contentType(MediaType.APPLICATION_JSON)
					.body(body)
					.retrieve()
					.body(String.class);
		} catch (HttpClientErrorException e) {
			if (e.getStatusCode().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)) {
				throw new RetryableOcrException("HTTP 429");
			}
			log.error("CLOVA OCR 호출 거절 {}", e.getStatusCode());
			throw new OcrException(OcrErrorCode.FAILED);
		} catch (HttpServerErrorException e) {
			throw new RetryableOcrException("HTTP " + e.getStatusCode().value());
		} catch (ResourceAccessException e) {
			throw new RetryableOcrException(e.getClass().getSimpleName() + ": " + e.getMessage());
		} catch (RestClientException e) {
			log.error("CLOVA OCR 호출 실패: {}", e.getMessage());
			throw new OcrException(OcrErrorCode.FAILED);
		} finally {
			lastCallAt = clock.getAsLong();
		}
		try {
			JsonNode node = jsonMapper.readTree(response == null ? "" : response);
			if (node == null || !node.isObject()) {
				throw new OcrException(OcrErrorCode.FAILED);
			}
			return node;
		} catch (JacksonException e) {
			log.error("CLOVA OCR 응답이 JSON 이 아님");
			throw new OcrException(OcrErrorCode.FAILED);
		}
	}

	private void waitInterval() {
		long interval = properties.minInterval().toMillis();
		if (interval <= 0 || lastCallAt == Long.MIN_VALUE) {
			return;
		}
		long wait = lastCallAt + interval - clock.getAsLong();
		if (wait > 0) {
			sleep(Duration.ofMillis(wait));
		}
	}

	/** CLOVA 응답 → 글자 덩어리. ai {@code clova_text} 와 같은 규칙. */
	static String text(JsonNode response) {
		List<String> lines = new ArrayList<>();
		for (JsonNode image : response.path("images")) {
			String result = image.path("inferResult").asString("SUCCESS");
			if (!"SUCCESS".equals(result)) {
				log.error("CLOVA OCR inferResult {}: {}", result, image.path("message").asString(""));
				throw new OcrException(OcrErrorCode.FAILED);
			}
			List<JsonNode> boxes = new ArrayList<>();
			for (JsonNode table : image.path("tables")) {
				if (table.has("boundingPoly")) {
					boxes.add(table.get("boundingPoly"));
				}
			}
			List<String> current = new ArrayList<>();
			for (JsonNode field : image.path("fields")) {
				double[] center = center(field.path("boundingPoly"));
				if (center != null && boxes.stream().anyMatch(box -> inside(center, box))) {
					continue; // 표 안 글자는 아래 표 줄로 나온다
				}
				current.add(field.path("inferText").asString(""));
				if (field.path("lineBreak").asBoolean(false)) {
					lines.add(String.join(" ", current));
					current = new ArrayList<>();
				}
			}
			if (!current.isEmpty()) {
				lines.add(String.join(" ", current));
			}
			for (JsonNode table : image.path("tables")) {
				lines.addAll(tableLines(table));
			}
		}
		return String.join("\n", lines);
	}

	private static double[] center(JsonNode poly) {
		JsonNode vertices = poly.path("vertices");
		if (!vertices.isArray() || vertices.isEmpty()) {
			return null;
		}
		double x = 0;
		double y = 0;
		for (JsonNode v : vertices) {
			x += v.path("x").asDouble(0);
			y += v.path("y").asDouble(0);
		}
		return new double[] {x / vertices.size(), y / vertices.size()};
	}

	private static boolean inside(double[] point, JsonNode poly) {
		JsonNode vertices = poly.path("vertices");
		if (!vertices.isArray() || vertices.isEmpty()) {
			return false;
		}
		double minX = Double.MAX_VALUE;
		double maxX = -Double.MAX_VALUE;
		double minY = Double.MAX_VALUE;
		double maxY = -Double.MAX_VALUE;
		for (JsonNode v : vertices) {
			double x = v.path("x").asDouble(0);
			double y = v.path("y").asDouble(0);
			minX = Math.min(minX, x);
			maxX = Math.max(maxX, x);
			minY = Math.min(minY, y);
			maxY = Math.max(maxY, y);
		}
		return minX <= point[0] && point[0] <= maxX && minY <= point[1] && point[1] <= maxY;
	}

	/** 표 칸을 (rowIndex, columnIndex) 격자에 놓는다. 합친 칸은 첫 칸에 글자, 나머지는 빈칸. 빈 행은 뺀다. */
	private static List<String> tableLines(JsonNode table) {
		JsonNode cells = table.path("cells");
		if (!cells.isArray() || cells.isEmpty()) {
			return List.of();
		}
		int rows = 0;
		int cols = 0;
		for (JsonNode cell : cells) {
			if (!validCell(cell)) {
				continue;
			}
			rows = Math.max(rows, cell.path("rowIndex").asInt(0) + Math.max(cell.path("rowSpan").asInt(1), 1));
			cols = Math.max(cols, cell.path("columnIndex").asInt(0) + Math.max(cell.path("columnSpan").asInt(1), 1));
		}
		rows = Math.min(rows, MAX_TABLE_ROWS);
		cols = Math.min(cols, MAX_TABLE_COLS);
		String[][] grid = new String[rows][cols];
		for (String[] row : grid) {
			java.util.Arrays.fill(row, "");
		}
		for (JsonNode cell : cells) {
			if (!validCell(cell)) {
				continue;
			}
			List<String> words = new ArrayList<>();
			for (JsonNode line : cell.path("cellTextLines")) {
				for (JsonNode word : line.path("cellWords")) {
					words.add(word.path("inferText").asString(""));
				}
			}
			grid[cell.path("rowIndex").asInt(0)][cell.path("columnIndex").asInt(0)] = String.join(" ", words);
		}
		List<String> lines = new ArrayList<>();
		for (String[] row : grid) {
			boolean any = false;
			for (String value : row) {
				any |= !value.isEmpty();
			}
			if (any) {
				lines.add(String.join("\t", row));
			}
		}
		return lines;
	}

	/** 칸 위치가 음수이거나 격자 상한(영수증 한 장에 나올 수 없는 크기) 밖이면 버린다 — 메모리를 지키려고. */
	private static boolean validCell(JsonNode cell) {
		int row = cell.path("rowIndex").asInt(0);
		int col = cell.path("columnIndex").asInt(0);
		int rowSpan = cell.path("rowSpan").asInt(1);
		int colSpan = cell.path("columnSpan").asInt(1);
		return row >= 0 && col >= 0 && row < MAX_TABLE_ROWS && col < MAX_TABLE_COLS
				&& rowSpan <= MAX_TABLE_ROWS && colSpan <= MAX_TABLE_COLS;
	}

	private void sleep(Duration delay) {
		try {
			sleeper.sleep(delay);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new OcrException(OcrErrorCode.FAILED);
		}
	}

	/** 429 · 5xx · 연결 실패 · 타임아웃. */
	private static final class RetryableOcrException extends RuntimeException {
		RetryableOcrException(String message) {
			super(message);
		}
	}
}
