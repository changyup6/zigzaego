package com.zigzaego.api.onboarding.ai;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;

/**
 * 범용 재료표({@code onboarding/ingredient_catalog.csv}, ai {@code seed/} 원본의 복사본). 재료 분류는 표가 먼저다 —
 * 표에 있으면 표 값, 없으면 LLM 분류 결과를 쓴다. 이름 찾기 규칙은 ai {@code catalog.py} 와 같다:
 * 공백을 모두 빼고 소문자로 바꾼 이름 · 별칭이 같으면 그 항목(먼저 나온 항목이 이긴다).
 */
public final class IngredientCatalog {

	private static final String RESOURCE = "onboarding/ingredient_catalog.csv";

	/**
	 * 표 한 줄. 값은 CSV 그대로(kind BOM · ONE_TO_ONE, unit G · ML · EA).
	 *
	 * @param shelfLifeDays 개봉 전 업소 보관 기준 유통기한(일, 추정). 칸이 비었으면 null — 보관 방식 표 값을 쓴다
	 */
	public record Item(String name, List<String> aliases, String kind, String unit, String storageType,
			double perServing, double confidence, Integer shelfLifeDays) {
	}

	private final List<Item> items;
	private final Map<String, Item> index = new HashMap<>();

	private IngredientCatalog(List<Item> items) {
		this.items = List.copyOf(items);
		for (Item item : this.items) {
			index.putIfAbsent(normalize(item.name()), item);
			for (String alias : item.aliases()) {
				index.putIfAbsent(normalize(alias), item);
			}
		}
	}

	public static IngredientCatalog loadDefault() {
		try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
			return read(in);
		} catch (IOException e) {
			throw new UncheckedIOException("재료표를 읽지 못했다: " + RESOURCE, e);
		}
	}

	static IngredientCatalog read(InputStream in) throws IOException {
		List<Item> items = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String header = reader.readLine();
			if (header == null) {
				return new IngredientCatalog(items);
			}
			List<String> columns = Arrays.asList(stripBom(header).split(",", -1));
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				String[] cells = line.split(",", -1);
				items.add(new Item(
						cell(cells, columns, "name"),
						Arrays.stream(cell(cells, columns, "aliases").split(";")).map(String::strip)
								.filter(a -> !a.isEmpty()).toList(),
						cell(cells, columns, "kind"),
						cell(cells, columns, "unit"),
						cell(cells, columns, "storage_type"),
						Double.parseDouble(cell(cells, columns, "per_serving")),
						Double.parseDouble(cell(cells, columns, "confidence")),
						integerOrNull(cell(cells, columns, "shelf_life_days"))));
			}
		}
		return new IngredientCatalog(items);
	}

	/** 이름 또는 별칭이 공백 · 대소문자를 빼고 같으면 그 항목, 없으면 null. */
	public Item lookup(String name) {
		if (name == null) {
			return null;
		}
		String key = normalize(name);
		return key.isEmpty() ? null : index.get(key);
	}

	public List<Item> items() {
		return items;
	}

	/** 비교용 이름: 공백을 모두 빼고 소문자로 (ai {@code catalog.normalize}). */
	public static String normalize(String name) {
		StringBuilder out = new StringBuilder(name.length());
		name.codePoints()
				.filter(cp -> !Character.isWhitespace(cp) && !Character.isSpaceChar(cp))
				.forEach(out::appendCodePoint);
		return out.toString().toLowerCase(Locale.ROOT);
	}

	private static String cell(String[] cells, List<String> columns, String column) {
		int i = columns.indexOf(column);
		return i < 0 || i >= cells.length ? "" : cells[i].strip();
	}

	private static Integer integerOrNull(String value) {
		return value.isEmpty() ? null : Integer.valueOf(value);
	}

	private static String stripBom(String header) {
		return header.startsWith("﻿") ? header.substring(1) : header;
	}
}
