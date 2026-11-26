package com.zigzaego.api.market.label;

import com.zigzaego.api.market.label.MarketProductLabelJdbcRepository.LabelRow;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 라벨 표 {@code market_product_label} 전체를 CSV 로 내보낸다 (S15P31A308-229, ADR-0014).
 * 열은 {@code MARKET_LABELS_FILE} 적재 형식({@code MarketProductImportService.LABEL_COLUMNS})과 같아 그 파일을 그대로 다시 넣을 수 있다
 * (DB 를 새로 만들었을 때 LLM 을 다시 부르지 않게). 임시 파일에 쓴 뒤 바꿔치기하므로 쓰다가 죽어도 이전 백업은 그대로 남는다.
 * <ul>
 *   <li>{@link #exportLatest()}: {@code <dir>/labels_latest.csv} — chunk 가 커밋될 때마다 (Ctrl-C 로 멈춰도 마지막 커밋까지 들어 있다)</li>
 *   <li>{@link #snapshot()}: {@code <dir>/labels_<yyyyMMdd_HHmm>.csv} — 단계가 끝날 때(완료 · 실패) latest 의 사본</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketLabelBackup {

	public static final String LATEST = "labels_latest.csv";
	static final String HEADER = "product_name,item_group_code,form,qty,qty_unit,confidence,reason,label_version";
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm");

	private final MarketProductLabelJdbcRepository repository;
	private final MarketLabelProperties properties;
	private final Clock clock;

	public boolean enabled() {
		return properties.backupDirPath() != null;
	}

	/** 라벨 표 전체 → {@code labels_latest.csv}. 백업 폴더가 비어 있으면 아무것도 하지 않고 null. */
	public Path exportLatest() {
		Path dir = properties.backupDirPath();
		if (dir == null) {
			return null;
		}
		Path target = dir.resolve(LATEST);
		List<LabelRow> rows = repository.findAll();
		try {
			Files.createDirectories(dir);
			Path tmp = dir.resolve(LATEST + ".tmp");
			try (BufferedWriter out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				out.write(HEADER);
				out.newLine();
				for (LabelRow r : rows) {
					out.write(line(r));
					out.newLine();
				}
			}
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			throw new UncheckedIOException("라벨 백업 CSV 를 쓰지 못했다: " + target, e);
		}
		log.info("라벨 백업 — {}줄 → {}", rows.size(), target);
		return target;
	}

	/** {@code labels_latest.csv} 를 시각이 붙은 이름으로 복사한다. latest 가 없으면 먼저 만든다. */
	public Path snapshot() {
		Path dir = properties.backupDirPath();
		if (dir == null) {
			return null;
		}
		Path latest = dir.resolve(LATEST);
		if (!Files.isRegularFile(latest)) {
			latest = exportLatest();
		}
		Path target = dir.resolve("labels_" + LocalDateTime.now(clock).format(STAMP) + ".csv");
		try {
			Files.copy(latest, target, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			throw new UncheckedIOException("라벨 백업 사본을 만들지 못했다: " + target, e);
		}
		log.info("라벨 백업 사본 — {}", target);
		return target;
	}

	/** RFC 4180 한 줄. 빈 값은 빈 칸 (적재 쪽 {@code text()} 가 빈 칸을 null 로 읽는다). */
	public static String line(LabelRow r) {
		return String.join(",", quote(r.productName()), quote(r.itemGroupCode()), quote(r.form()), number(r.qty()),
				quote(r.qtyUnit()), number(r.confidence()), quote(r.reason()), quote(r.labelVersion()));
	}

	static String quote(String v) {
		if (v == null) {
			return "";
		}
		if (v.indexOf(',') < 0 && v.indexOf('"') < 0 && v.indexOf('\n') < 0 && v.indexOf('\r') < 0) {
			return v;
		}
		return '"' + v.replace("\"", "\"\"") + '"';
	}

	private static String number(BigDecimal v) {
		return v == null ? "" : v.stripTrailingZeros().toPlainString();
	}
}
