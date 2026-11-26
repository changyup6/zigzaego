package com.zigzaego.api.market.importer;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * RFC 4180 CSV 읽기(따옴표 안의 쉼표 · 줄바꿈 · 겹따옴표 {@code ""}, CRLF, 맨 앞 BOM). 데이터셋 상품명에 쉼표가 1.7만 줄,
 * 따옴표가 24줄, 줄바꿈이 1줄 있어 {@code split(",")} 으로는 안 된다. 바깥 라이브러리를 더하지 않으려고 직접 둔다.
 */
final class CsvRows {

	private static final char BOM = '﻿';

	private final Reader reader;

	private int next;

	private boolean started;

	CsvRows(Reader reader) {
		this.reader = reader;
	}

	/** 다음 줄의 칸들. 더 없으면 null. 빈 줄은 건너뛴다. */
	List<String> readRow() throws IOException {
		if (!started) {
			started = true;
			next = reader.read();
			if (next == BOM) {
				next = reader.read();
			}
		}
		while (next == '\r' || next == '\n') {
			next = reader.read();
		}
		if (next == -1) {
			return null;
		}
		List<String> row = new ArrayList<>();
		StringBuilder cell = new StringBuilder();
		while (true) {
			if (next == '"') {
				next = reader.read();
				while (true) {
					if (next == -1) {
						throw new IOException("닫히지 않은 따옴표");
					}
					if (next == '"') {
						next = reader.read();
						if (next != '"') {
							break;  // 따옴표 끝
						}
					}
					cell.append((char) next);
					next = reader.read();
				}
			}
			while (next != -1 && next != ',' && next != '\r' && next != '\n') {
				cell.append((char) next);
				next = reader.read();
			}
			row.add(cell.toString());
			cell.setLength(0);
			if (next == ',') {
				next = reader.read();
				continue;
			}
			if (next == '\r') {
				next = reader.read();
			}
			if (next == '\n') {
				next = reader.read();
			}
			return row;
		}
	}
}
