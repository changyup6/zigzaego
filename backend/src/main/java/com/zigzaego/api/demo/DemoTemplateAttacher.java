package com.zigzaego.api.demo;

import com.zigzaego.api.demo.DemoPreparationWorker.PreparationFailure;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.Date;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * classpath에 번들된 불변 시연 이력 템플릿(history.sql, market.sql, manifest.json)을 검증하고 대상 가게에 적재한다.
 * 외부 Python(demo_attach.py) 대신 BE 워커가 트랜잭션 안에서 직접 실행할 때 사용한다.
 */
@Slf4j
@Component
class DemoTemplateAttacher {

	private static final String TOKEN = "__DEMO_STORE_ID__";
	private static final String TEMPLATE_DIR = "demo-template/";
	private static final String SEMANTIC_SQL = """
			SELECT jsonb_build_object(
			    'menu', COALESCE((SELECT jsonb_agg(jsonb_build_array(name,kind,active,price)
			        ORDER BY name COLLATE "C") FROM menu WHERE store_id=?), '[]'::jsonb),
			    'ingredient', COALESCE((SELECT jsonb_agg(jsonb_build_array(name,unit,kind,storage_type,
			        shelf_life_days,sourcing_type,price_sensitive,safety_days)
			        ORDER BY name COLLATE "C") FROM ingredient WHERE store_id=?), '[]'::jsonb),
			    'recipe', COALESCE((SELECT jsonb_agg(jsonb_build_array(m.name,i.name,r.qty_per_serving)
			        ORDER BY m.name COLLATE "C",i.name COLLATE "C")
			        FROM recipe r JOIN menu m ON m.id=r.menu_id AND m.store_id=r.store_id
			        JOIN ingredient i ON i.id=r.ingredient_id AND i.store_id=r.store_id
			        WHERE r.store_id=?), '[]'::jsonb)
			)::text
			""";
	private static final Map<String, String> COUNT_QUERIES = Map.of(
			"pos_sale", "SELECT count(*) FROM pos_sale WHERE store_id=?",
			"supplier_product", "SELECT count(*) FROM supplier_product WHERE store_id=?",
			"purchase_doc", "SELECT count(*) FROM purchase_doc WHERE store_id=?",
			"purchase_doc_line", "SELECT count(*) FROM purchase_doc_line WHERE store_id=?",
			"purchase_order", "SELECT count(*) FROM purchase_order WHERE store_id=?",
			"purchase_order_item", "SELECT count(*) FROM purchase_order_item WHERE store_id=?",
			"stock_count", "SELECT count(*) FROM stock_count WHERE store_id=?",
			"price_history", "SELECT count(*) FROM price_history WHERE store_id=?");

	private final JdbcTemplate jdbc;
	private final JsonMapper mapper;
	private final JsonNode manifest;
	private final String historySql;
	private final String marketSql;
	private final String store89ExtraSql;

	DemoTemplateAttacher(JdbcTemplate jdbc, JsonMapper mapper) {
		this.jdbc = jdbc;
		this.mapper = mapper;
		try {
			this.manifest = mapper.readTree(readUtf8(TEMPLATE_DIR + "manifest.json"));
			JsonNode files = manifest.path("files");
			this.historySql = loadVerifiedSql(TEMPLATE_DIR + "history.sql", files.path("history.sql").asString());
			this.marketSql = files.hasNonNull("market.sql")
					? loadVerifiedSql(TEMPLATE_DIR + "market.sql", files.path("market.sql").asString())
					: null;
			ClassPathResource extra = new ClassPathResource(TEMPLATE_DIR + "store89-extra.sql");
			this.store89ExtraSql = extra.exists() ? readUtf8(TEMPLATE_DIR + "store89-extra.sql").replace("\r\n", "\n") : null;
		} catch (IOException e) {
			throw new IllegalStateException("시연 템플릿 리소스 로드 실패", e);
		}
	}

	/** 호출자 트랜잭션(REQUIRES_NEW) 안에서 시연 가게 검증 · 이력/시세 SQL · 주 예측 적재 · 건수 검증을 수행한다. */
	void attach(long storeId) {
		jdbc.execute("SET LOCAL lock_timeout = '3s'");
		jdbc.execute("SET LOCAL statement_timeout = '90s'");
		jdbc.queryForObject("SELECT id FROM store WHERE id=? FOR UPDATE", Long.class, storeId);
		verifySemantic(storeId);

		String sid = Long.toString(storeId);
		executeMultiSql(historySql.replace(TOKEN, sid));
		if (store89ExtraSql == null && marketSql != null) {
			executeMultiSql(marketSql.replace(TOKEN, sid));
		}
		upsertForecast(storeId);
		verifyCounts(storeId);
		verifyDateRange(storeId);
		if (store89ExtraSql != null) {
			executeMultiSql(store89ExtraSql.replace(TOKEN, sid));
		}
	}

	private void verifySemantic(long storeId) {
		String raw = jdbc.queryForObject(SEMANTIC_SQL, String.class, storeId, storeId, storeId);
		JsonNode actual = mapper.readTree(raw);
		JsonNode expected = manifest.path("semantic");
		for (String section : List.of("menu", "ingredient")) {
			if (!jsonEquals(actual.path(section), expected.path(section))) {
				log.warn("시연 템플릿 semantic 불일치 — storeId={}, section={}", storeId, section);
				throw new PreparationFailure("DEMO_FINGERPRINT_MISMATCH");
			}
		}
		if (!recipePairsMatch(actual.path("recipe"), expected.path("recipe"))) {
			log.warn("시연 템플릿 semantic 불일치 — storeId={}, section=recipe", storeId);
			throw new PreparationFailure("DEMO_FINGERPRINT_MISMATCH");
		}
	}

	/** 온보딩 레시피 질문 답변으로 1인분 수량(qty_per_serving)은 바뀔 수 있어 메뉴·재료 쌍과 양수 여부만 검증한다. */
	private static boolean recipePairsMatch(JsonNode actual, JsonNode expected) {
		if (!actual.isArray() || !expected.isArray() || actual.size() != expected.size()) {
			return false;
		}
		for (int i = 0; i < actual.size(); i++) {
			JsonNode a = actual.get(i);
			JsonNode e = expected.get(i);
			if (!a.isArray() || !e.isArray() || a.size() < 3 || e.size() < 2
					|| !a.get(0).equals(e.get(0)) || !a.get(1).equals(e.get(1))
					|| !a.get(2).isNumber() || a.get(2).decimalValue().signum() <= 0) {
				return false;
			}
		}
		return true;
	}

	private void executeMultiSql(String sql) {
		jdbc.execute((Connection conn) -> {
			try (Statement st = conn.createStatement()) {
				st.setQueryTimeout(90);
				st.execute(sql);
			}
			return null;
		});
	}

	private void upsertForecast(long storeId) {
		JsonNode f = manifest.path("forecast");
		String analysis = f.path("analysis").isNull() ? null : f.path("analysis").asString();
		jdbc.update("""
				INSERT INTO sales_forecast_week(store_id,week_start,model,pct,reason,days,factors,analysis)
				VALUES (?,?,?,?,?,?::jsonb,?::jsonb,?)
				ON CONFLICT (store_id,week_start) DO UPDATE SET
				    model=excluded.model,pct=excluded.pct,reason=excluded.reason,
				    days=excluded.days,factors=excluded.factors,analysis=excluded.analysis
				""",
				storeId,
				Date.valueOf(LocalDate.parse(f.path("week_start").asString())),
				f.path("model").asString(),
				f.path("pct").asInt(),
				f.path("reason").asString(),
				mapper.writeValueAsString(f.path("days")),
				mapper.writeValueAsString(f.path("factors")),
				analysis);
	}

	private void verifyCounts(long storeId) {
		JsonNode counts = manifest.path("counts");
		for (Map.Entry<String, String> entry : COUNT_QUERIES.entrySet()) {
			String table = entry.getKey();
			if (!counts.hasNonNull(table)) {
				continue;
			}
			long expected = counts.path(table).asLong();
			Long actual = jdbc.queryForObject(entry.getValue(), Long.class, storeId);
			if (actual == null || actual != expected) {
				log.warn("시연 템플릿 건수 불일치 — storeId={}, table={}, expected={}, actual={}",
						storeId, table, expected, actual);
				throw new PreparationFailure("DEMO_COUNT_MISMATCH");
			}
		}
	}

	private void verifyDateRange(long storeId) {
		String expectedStart = manifest.path("start").asString();
		String expectedEnd = manifest.path("end").asString();
		Map<String, Object> range = jdbc.queryForMap(
				"SELECT min(sold_date)::text AS min_d, max(sold_date)::text AS max_d FROM pos_sale WHERE store_id=?",
				storeId);
		if (!expectedStart.equals(range.get("min_d")) || !expectedEnd.equals(range.get("max_d"))) {
			log.warn("시연 템플릿 판매일 범위 불일치 — storeId={}, expected={}..{}, actual={}..{}",
					storeId, expectedStart, expectedEnd, range.get("min_d"), range.get("max_d"));
			throw new PreparationFailure("DEMO_DATE_MISMATCH");
		}
	}

	private String loadVerifiedSql(String path, String expectedSha256) throws IOException {
		String sql = readUtf8(path).replace("\r\n", "\n");
		String actualSha256 = sha256(sql.getBytes(StandardCharsets.UTF_8));
		if (!actualSha256.equalsIgnoreCase(expectedSha256)) {
			throw new IllegalStateException("시연 템플릿 체크섬 불일치: " + path);
		}
		return sql;
	}

	private String readUtf8(String path) throws IOException {
		return new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static boolean jsonEquals(JsonNode a, JsonNode b) {
		if (a.isNumber() && b.isNumber()) {
			return a.decimalValue().compareTo(b.decimalValue()) == 0;
		}
		if (a.isArray() && b.isArray()) {
			if (a.size() != b.size()) return false;
			for (int i = 0; i < a.size(); i++) {
				if (!jsonEquals(a.get(i), b.get(i))) return false;
			}
			return true;
		}
		if (a.isObject() && b.isObject()) {
			if (a.size() != b.size()) return false;
			for (String key : a.propertyNames()) {
				if (!b.has(key) || !jsonEquals(a.get(key), b.get(key))) return false;
			}
			return true;
		}
		return a.equals(b);
	}
}
