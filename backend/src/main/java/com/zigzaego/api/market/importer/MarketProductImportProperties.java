package com.zigzaego.api.market.importer;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 시세 · 라벨 적재 값 ({@code application-import-market-products.yaml}). 환경 변수로 받는다 — {@code backend/.env.example}.
 *
 * @param file 크롤러가 내보낸 데이터셋 CSV 경로 (MARKET_PRODUCTS_FILE). 비우면 시세는 넣지 않는다
 * @param labelsFile LLM 배치가 만든 라벨 CSV 경로 (MARKET_LABELS_FILE). 비우면 라벨은 넣지 않는다
 */
@ConfigurationProperties(prefix = "app.market-products")
public record MarketProductImportProperties(String file, String labelsFile) {

	/** 시세 CSV 경로. 비어 있으면 null. */
	Path filePath() {
		return file == null || file.isBlank() ? null : Path.of(file.strip());
	}

	/** 라벨 CSV 경로. 비어 있으면 null. */
	Path labelsFilePath() {
		return labelsFile == null || labelsFile.isBlank() ? null : Path.of(labelsFile.strip());
	}
}
