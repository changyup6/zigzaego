package com.zigzaego.api.market.importer;

import com.zigzaego.api.market.catalog.MarketItemGroup;
import com.zigzaego.api.market.catalog.MarketItemGroupJdbcRepository;
import com.zigzaego.api.market.label.MarketProductLabelJdbcRepository;
import com.zigzaego.api.market.label.MarketProductLabelJdbcRepository.LabelRow;
import com.zigzaego.api.market.repository.MarketProductJdbcRepository;
import com.zigzaego.api.market.repository.MarketProductJdbcRepository.MarketProductRow;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 크롤러 통합 데이터셋 CSV → {@code market_product}, 라벨 CSV → {@code market_product_label} 적재 (S15P31A308-232 · 229, ADR-0012).
 * <ul>
 *   <li>시세: 파일에 든 **수집일의 줄만** 지우고 다시 넣는다(한 트랜잭션). 다른 날짜의 줄은 남아 일별 추이가 된다. 실패하면 이전 상태 그대로</li>
 *   <li>라벨: 상품명 기준 upsert. 표에 없는 품목군 코드는 UNKNOWN 으로 바꿔 넣고 센다(기준표보다 새 CSV 가 왔을 때)</li>
 * </ul>
 * 데이터셋 CSV 는 크롤러 {@code scripts/export_market_csv.py}, 라벨 CSV 는 {@code ai/scripts/market/classify_products.py} 가 만든다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketProductImportService {

	/** 데이터셋 열 이름 → 뜻. 이 열이 하나라도 없으면 적재하지 않는다. */
	static final List<String> REQUIRED_COLUMNS = List.of(
			"사이트", "점포지역", "판매자", "상품번호", "상품명", "브랜드", "규격", "판매가", "원래가격", "총량", "단위",
			"단위가격_100", "카테고리", "URL", "수집일", "묶음번호");

	static final List<String> LABEL_COLUMNS = List.of(
			"product_name", "item_group_code", "form", "qty", "qty_unit", "confidence", "reason", "label_version");

	static final Set<String> FORMS = Set.of("RAW", "PREPARED", "PROCESSED", "NONFOOD", "UNKNOWN");

	static final int BATCH_SIZE = 2000;

	private final MarketProductJdbcRepository repository;
	private final MarketProductLabelJdbcRepository labelRepository;
	private final MarketItemGroupJdbcRepository groupRepository;

	/** 라벨 적재 결과. */
	public record LabelImportResult(int rows, int unknownGroupCodes) {
	}

	/**
	 * 시세 CSV 적재. 첫 번째 훑기로 수집일을 모으고, 그 날짜의 줄을 지운 뒤 두 번째 훑기로 넣는다.
	 *
	 * @return 넣은 줄 수
	 * @throws IllegalArgumentException 파일이 없거나 필수 열이 빠졌거나 값이 형식에 안 맞으면 (줄 번호 포함)
	 */
	@Transactional
	public int replaceAll(Path file) {
		if (!Files.isRegularFile(file)) {
			throw new IllegalArgumentException("데이터셋 CSV 가 없습니다: " + file);
		}
		Set<LocalDate> dates = collectDates(file);
		int deleted = repository.deleteByCollectedOn(dates);
		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			CsvRows rows = new CsvRows(reader);
			Map<String, Integer> columns = header(rows.readRow(), REQUIRED_COLUMNS);
			List<MarketProductRow> batch = new ArrayList<>(BATCH_SIZE);
			int total = 0;
			int line = 1;
			List<String> row;
			while ((row = rows.readRow()) != null) {
				line++;
				batch.add(toRow(row, columns, line));
				if (batch.size() == BATCH_SIZE) {
					total += flush(batch);
				}
			}
			total += flush(batch);
			log.info("식자재몰 시세 적재 완료 — {}줄 (수집일 {}, 같은 날짜 {}줄 교체), 파일 {}", total, dates, deleted, file.getFileName());
			return total;
		} catch (IOException e) {
			throw new UncheckedIOException("데이터셋 CSV 를 읽지 못했습니다: " + file, e);
		}
	}

	/** 라벨 CSV upsert. */
	@Transactional
	public LabelImportResult importLabels(Path file) {
		if (!Files.isRegularFile(file)) {
			throw new IllegalArgumentException("라벨 CSV 가 없습니다: " + file);
		}
		Set<String> codes = groupRepository.findAll().stream().map(MarketItemGroup::code).collect(Collectors.toSet());
		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			CsvRows rows = new CsvRows(reader);
			Map<String, Integer> columns = header(rows.readRow(), LABEL_COLUMNS);
			List<LabelRow> batch = new ArrayList<>(BATCH_SIZE);
			int total = 0;
			int unknown = 0;
			int line = 1;
			List<String> row;
			while ((row = rows.readRow()) != null) {
				line++;
				LabelRow label = toLabel(row, columns, line);
				if (label.itemGroupCode() != null && !codes.contains(label.itemGroupCode())) {
					unknown++;
					label = new LabelRow(label.productName(), null, "UNKNOWN", label.qty(), label.qtyUnit(), label.confidence(),
							"표에 없는 품목군 " + label.itemGroupCode(), label.labelVersion());
				}
				batch.add(label);
				if (batch.size() == BATCH_SIZE) {
					labelRepository.upsertAll(batch);
					total += batch.size();
					batch.clear();
				}
			}
			if (!batch.isEmpty()) {
				labelRepository.upsertAll(batch);
				total += batch.size();
			}
			log.info("상품명 라벨 적재 완료 — {}줄 (표에 없는 품목군 코드 {}줄은 UNKNOWN), 파일 {}", total, unknown, file.getFileName());
			return new LabelImportResult(total, unknown);
		} catch (IOException e) {
			throw new UncheckedIOException("라벨 CSV 를 읽지 못했습니다: " + file, e);
		}
	}

	private static Set<LocalDate> collectDates(Path file) {
		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			CsvRows rows = new CsvRows(reader);
			Map<String, Integer> columns = header(rows.readRow(), REQUIRED_COLUMNS);
			Set<LocalDate> dates = new TreeSet<>();
			List<String> row;
			int line = 1;
			while ((row = rows.readRow()) != null) {
				line++;
				try {
					dates.add(LocalDate.parse(required(text(row, columns, "수집일"), "수집일", line)));
				} catch (java.time.format.DateTimeParseException e) {
					throw new IllegalArgumentException(line + "번째 줄의 수집일이 형식에 안 맞습니다: " + e.getMessage(), e);
				}
			}
			return dates;
		} catch (IOException e) {
			throw new UncheckedIOException("데이터셋 CSV 를 읽지 못했습니다: " + file, e);
		}
	}

	private int flush(List<MarketProductRow> batch) {
		if (batch.isEmpty()) {
			return 0;
		}
		int n = batch.size();
		repository.insertAll(batch);
		batch.clear();
		return n;
	}

	private static Map<String, Integer> header(List<String> header, List<String> requiredColumns) {
		if (header == null) {
			throw new IllegalArgumentException("CSV 가 비어 있습니다");
		}
		Map<String, Integer> columns = new HashMap<>();
		for (int i = 0; i < header.size(); i++) {
			columns.put(header.get(i).strip().replace("﻿", ""), i);
		}
		List<String> missing = requiredColumns.stream().filter(c -> !columns.containsKey(c)).toList();
		if (!missing.isEmpty()) {
			throw new IllegalArgumentException("CSV 에 열이 없습니다: " + missing);
		}
		return columns;
	}

	private static MarketProductRow toRow(List<String> row, Map<String, Integer> columns, int line) {
		try {
			return new MarketProductRow(
					required(text(row, columns, "사이트"), "사이트", line),
					text(row, columns, "점포지역"),
					text(row, columns, "판매자"),
					required(text(row, columns, "상품번호"), "상품번호", line),
					required(text(row, columns, "상품명"), "상품명", line),
					text(row, columns, "브랜드"),
					text(row, columns, "규격"),
					Long.parseLong(required(text(row, columns, "판매가"), "판매가", line)),
					longOrNull(text(row, columns, "원래가격")),
					decimalOrNull(text(row, columns, "총량")),
					text(row, columns, "단위"),
					decimalOrNull(text(row, columns, "단위가격_100")),
					text(row, columns, "카테고리"),
					required(text(row, columns, "URL"), "URL", line),
					LocalDate.parse(required(text(row, columns, "수집일"), "수집일", line)),
					longOrNull(text(row, columns, "묶음번호")));
		} catch (NumberFormatException | java.time.format.DateTimeParseException e) {
			throw new IllegalArgumentException(line + "번째 줄의 값이 형식에 안 맞습니다: " + e.getMessage(), e);
		}
	}

	private static LabelRow toLabel(List<String> row, Map<String, Integer> columns, int line) {
		try {
			String form = required(text(row, columns, "form"), "form", line).toUpperCase();
			if (!FORMS.contains(form)) {
				throw new IllegalArgumentException(line + "번째 줄의 form 이 이상합니다: " + form);
			}
			String code = text(row, columns, "item_group_code");
			if (code != null && !"RAW".equals(form) && !"PREPARED".equals(form)) {
				code = null;  // 가공품 · 비식품에는 코드가 없다 (DB CHECK 와 같은 규칙)
			}
			String unit = text(row, columns, "qty_unit");
			BigDecimal qty = decimalOrNull(text(row, columns, "qty"));
			if (unit != null && !Set.of("G", "ML", "EA").contains(unit.toUpperCase())) {
				unit = null;
				qty = null;
			}
			BigDecimal confidence = decimalOrNull(text(row, columns, "confidence"));
			return new LabelRow(required(text(row, columns, "product_name"), "product_name", line), code, form, qty,
					unit == null ? null : unit.toUpperCase(), confidence == null ? BigDecimal.ZERO : confidence.min(BigDecimal.ONE).max(BigDecimal.ZERO),
					text(row, columns, "reason"), required(text(row, columns, "label_version"), "label_version", line));
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(line + "번째 줄의 값이 형식에 안 맞습니다: " + e.getMessage(), e);
		}
	}

	/** 빈 칸은 null. 데이터셋은 없는 값을 빈 문자열로 내보낸다. */
	private static String text(List<String> row, Map<String, Integer> columns, String column) {
		int i = columns.get(column);
		if (i >= row.size()) {
			return null;
		}
		String v = row.get(i).strip();
		return v.isEmpty() ? null : v;
	}

	private static String required(String value, String column, int line) {
		if (value == null) {
			throw new IllegalArgumentException(line + "번째 줄의 " + column + " 이(가) 비었습니다");
		}
		return value;
	}

	private static Long longOrNull(String v) {
		return v == null ? null : new BigDecimal(v).longValueExact();
	}

	private static BigDecimal decimalOrNull(String v) {
		return v == null ? null : new BigDecimal(v);
	}
}
