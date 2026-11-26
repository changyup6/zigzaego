package com.zigzaego.api.ingredient.service;

import com.zigzaego.api.ingredient.IngredientCategoryProperties;
import com.zigzaego.api.ingredient.entity.CategorySource;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 재료 ↔ 카테고리 전체를 CSV 로 내보내고 되살린다 (S15P31A308-274). 229 의 라벨 백업({@code MarketLabelBackup})과 같은 생각 —
 * LLM 이 붙인 카테고리는 비용이 든 것이라 DB 를 새로 만들어도 다시 부르지 않게 파일에 남긴다.
 * <ul>
 *   <li>{@link #exportAfterCommit()}: 자동 분류 · 일괄 분류가 끝나면 {@code <dir>/ingredient_categories_latest.csv} 를 다시 쓴다.
 *       트랜잭션 안이면 커밋 뒤에 한 번만 쓴다(되돌려진 행이 파일에 남지 않게). 파일 오류는 로그만 — 재료 생성을 막지 않는다</li>
 *   <li><b>합쳐 쓴다</b>: 파일에 있던 줄 위에 DB 의 줄을 얹되, DB 가 '자동 분류 기타' 인 줄은 파일의 정해진 값을 덮지 않는다.
 *       DB 를 새로 만들어(재료가 전부 기타) 첫 재료가 생겨도 아직 되살리지 않은 줄이 사라지지 않는다. 지운 재료의 줄은 남는다(찾기에만 쓰니 무해)</li>
 *   <li>{@link #load()}: 자동 분류 1단계가 (가게 이름, 재료 이름)으로 찾는다. 같은 파일을 ai {@code seed_sql.py} 가 샘플 폴더에서 읽어
 *       seed.sql 에 카테고리까지 넣는다 — 열이 바뀌면 그쪽도 같이</li>
 * </ul>
 * 가게는 id 가 아니라 이름으로 적는다. DB 를 새로 만들면 id 가 바뀌지만 이름은 시드 · 가입이 그대로 쓴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngredientCategoryBackup {

	public static final String LATEST = "ingredient_categories_latest.csv";
	static final String HEADER = "store_name,ingredient_name,category_code,category_name,category_source";
	/** 한 트랜잭션에 내보내기를 한 번만 등록하기 위한 표시. */
	private static final Object REGISTERED = new Object();

	/** 가게를 가리지 않는 전체 조회 — 서버 디스크 백업이라 ADR-0002 의 예외(시스템 작업). API 응답에는 쓰지 않는다. */
	private static final String SELECT_ALL = """
			SELECT s.name AS store_name, i.name AS ingredient_name, c.code, c.name AS category_name, i.category_source
			FROM ingredient i
			JOIN ingredient_category c ON c.id = i.category_id
			JOIN store s ON s.id = i.store_id
			ORDER BY s.id, i.id
			""";

	/**
	 * 파일 한 줄.
	 *
	 * @param categoryCode 기본 11종이면 코드, 사장님이 만든 카테고리면 null
	 */
	public record Row(String storeName, String ingredientName, String categoryCode, String categoryName,
			CategorySource source) {

		static String key(String storeName, String ingredientName) {
			return storeName + "\u0000" + ingredientName;
		}

		/** 자동 분류가 못 정한 기타. 파일의 정해진 값을 덮지 않고, 되살리지도 않는다. */
		boolean undecided() {
			return "OTHER".equals(categoryCode) && source == CategorySource.AUTO;
		}
	}

	private final JdbcTemplate jdbc;
	private final IngredientCategoryProperties properties;

	public boolean enabled() {
		return properties.backupDirPath() != null;
	}

	/** 트랜잭션 안이면 커밋 뒤에, 아니면 지금 {@link #exportLatest()}. 실패는 로그만. */
	public void exportAfterCommit() {
		if (!enabled()) {
			return;
		}
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			if (TransactionSynchronizationManager.hasResource(REGISTERED)) {
				return;  // 같은 트랜잭션(영수증 확정의 새 재료 여러 개)에서는 한 번만
			}
			TransactionSynchronizationManager.bindResource(REGISTERED, Boolean.TRUE);
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					exportQuietly();
				}

				@Override
				public void afterCompletion(int status) {
					TransactionSynchronizationManager.unbindResourceIfPossible(REGISTERED);  // 스레드가 다음 트랜잭션에 재사용된다
				}
			});
		} else {
			exportQuietly();
		}
	}

	private void exportQuietly() {
		try {
			exportLatest();
		} catch (RuntimeException e) {
			log.warn("재료 카테고리 백업 실패: {}", e.getMessage());
		}
	}

	/**
	 * 재료 ↔ 카테고리 전체를 파일의 줄과 합쳐 {@code ingredient_categories_latest.csv} 로. 백업 폴더가 비어 있으면 null.
	 * 동시에 두 요청이 쓰지 않게 직렬화하고, 임시 파일에 쓴 뒤 바꿔치기한다.
	 */
	public synchronized Path exportLatest() {
		Path dir = properties.backupDirPath();
		if (dir == null) {
			return null;
		}
		List<Row> current = jdbc.query(SELECT_ALL, (rs, i) -> new Row(rs.getString("store_name"),
				rs.getString("ingredient_name"), rs.getString("code"), rs.getString("category_name"),
				CategorySource.valueOf(rs.getString("category_source"))));
		Map<String, Row> merged = new TreeMap<>(load());
		for (Row row : current) {
			String key = Row.key(row.storeName(), row.ingredientName());
			Row kept = merged.get(key);
			if (kept != null && row.undecided() && !kept.undecided()) {
				continue;  // DB 가 새로 만든 '자동 분류 기타' 는 파일이 아는 값을 덮지 않는다
			}
			merged.put(key, row);
		}
		Path target = dir.resolve(LATEST);
		try {
			Files.createDirectories(dir);
			Path tmp = Files.createTempFile(dir, LATEST, ".tmp");
			try (BufferedWriter out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				out.write(HEADER);
				out.write('\n');
				for (Row row : merged.values()) {
					out.write(String.join(",", cell(row.storeName()), cell(row.ingredientName()), cell(row.categoryCode()),
							cell(row.categoryName()), row.source().name()));
					out.write('\n');
				}
			}
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			throw new IllegalStateException("재료 카테고리 백업 CSV 를 쓰지 못했다: " + target, e);
		}
		log.info("재료 카테고리 백업 — DB {}줄 · 파일 {}줄 → {}", current.size(), merged.size(), target);
		return target;
	}

	/** 파일 전체를 (가게 이름, 재료 이름) → 줄로. 파일이 없거나 읽지 못하면 빈 맵. */
	public Map<String, Row> load() {
		Path dir = properties.backupDirPath();
		if (dir == null) {
			return Map.of();
		}
		Path file = dir.resolve(LATEST);
		if (!Files.isRegularFile(file)) {
			return Map.of();
		}
		Map<String, Row> rows = new HashMap<>();
		try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String header = in.readLine();
			if (header == null || !header.strip().equals(HEADER)) {
				log.warn("재료 카테고리 백업 파일의 열이 다르다, 쓰지 않는다: {}", file);
				return Map.of();
			}
			String line;
			while ((line = in.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				List<String> cells = split(line);
				if (cells.size() != 5) {
					continue;
				}
				CategorySource source;
				try {
					source = CategorySource.valueOf(cells.get(4));
				} catch (IllegalArgumentException e) {
					continue;
				}
				Row row = new Row(cells.get(0), cells.get(1), cells.get(2).isEmpty() ? null : cells.get(2), cells.get(3),
						source);
				rows.put(Row.key(row.storeName(), row.ingredientName()), row);
			}
		} catch (IOException e) {
			log.warn("재료 카테고리 백업 파일을 읽지 못했다: {} ({})", file, e.getMessage());
			return Map.of();
		}
		return rows;
	}

	public static Optional<Row> find(Map<String, Row> rows, String storeName, String ingredientName) {
		return Optional.ofNullable(rows.get(Row.key(storeName, ingredientName)));
	}

	/** 쉼표 · 따옴표 · 줄바꿈이 있으면 큰따옴표로 감싼다 (안의 따옴표는 두 번). */
	static String cell(String value) {
		if (value == null) {
			return "";
		}
		if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
			return value;
		}
		return "\"" + value.replace("\"", "\"\"") + "\"";
	}

	/** {@link #cell} 의 반대. 한 줄 안의 값만 (값 안 줄바꿈은 쓰지 않는다). */
	static List<String> split(String line) {
		List<String> out = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < line.length(); i++) {
			char ch = line.charAt(i);
			if (quoted) {
				if (ch == '"') {
					if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
						cur.append('"');
						i++;
					} else {
						quoted = false;
					}
				} else {
					cur.append(ch);
				}
			} else if (ch == '"') {
				quoted = true;
			} else if (ch == ',') {
				out.add(cur.toString());
				cur.setLength(0);
			} else {
				cur.append(ch);
			}
		}
		out.add(cur.toString());
		return out;
	}
}
