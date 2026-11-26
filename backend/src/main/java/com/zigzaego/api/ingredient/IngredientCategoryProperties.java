package com.zigzaego.api.ingredient;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 재료 카테고리 자동 분류 설정 ({@code app.ingredient-category}, S15P31A308-274). 값 설명은 {@code application.yaml}.
 *
 * @param llmTimeout LLM 한 요청을 기다리는 상한. 넘기면 그 묶음은 전부 기타 — 재료 생성 요청이 LLM 때문에 길어지지 않게
 * @param batchSize  LLM 한 요청에 넣는 재료 이름 수
 * @param backupDir  분류 결과 CSV 백업 폴더. 비우면 백업하지도, 되살리지도 않는다
 */
@ConfigurationProperties(prefix = "app.ingredient-category")
public record IngredientCategoryProperties(Duration llmTimeout, int batchSize, String backupDir) {

	public static final Duration DEFAULT_LLM_TIMEOUT = Duration.ofSeconds(20);
	public static final int DEFAULT_BATCH_SIZE = 40;

	public IngredientCategoryProperties {
		llmTimeout = llmTimeout == null || llmTimeout.isZero() || llmTimeout.isNegative() ? DEFAULT_LLM_TIMEOUT : llmTimeout;
		batchSize = batchSize <= 0 ? DEFAULT_BATCH_SIZE : batchSize;
		backupDir = backupDir == null ? "" : backupDir.strip();
	}

	/** 백업 폴더. 비어 있으면 null. */
	public Path backupDirPath() {
		return backupDir.isEmpty() ? null : Path.of(backupDir);
	}
}
