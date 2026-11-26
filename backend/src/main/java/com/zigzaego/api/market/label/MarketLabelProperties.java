package com.zigzaego.api.market.label;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 식자재몰 상품명 라벨 배치 설정 ({@code app.market-label}, S15P31A308-229 · ADR-0014). 값 설명은 {@code application.yaml}.
 *
 * @param version   라벨 버전 = 프롬프트 · 품목군 표 · 모델 버전. 다른 버전 라벨이 붙은 상품명은 다시 분류한다
 * @param batchSize LLM 한 요청에 넣는 상품명 수 = chunk 크기
 * @param limit     한 번 실행에 분류할 상품명 상한. 0 이면 전부
 * @param threads   동시에 보내는 LLM 요청 수. chunk 하나 = {@code threads × batchSize} 개 상품명을 {@code threads} 개 요청으로 나란히 보내고 한 번에 커밋
 * @param backupDir 라벨 표 CSV 백업 폴더. chunk 커밋마다 {@code labels_latest.csv}, 단계 끝에 시각 붙은 사본. 비우면 백업하지 않는다
 */
@ConfigurationProperties(prefix = "app.market-label")
public record MarketLabelProperties(String version, int batchSize, long limit, int threads, String backupDir) {

	public static final String DEFAULT_VERSION = "v2";
	public static final int DEFAULT_BATCH_SIZE = 40;

	public MarketLabelProperties {
		version = version == null || version.isBlank() ? DEFAULT_VERSION : version.strip();
		batchSize = batchSize <= 0 ? DEFAULT_BATCH_SIZE : batchSize;
		limit = Math.max(limit, 0);
		threads = threads <= 0 ? 1 : threads;
		backupDir = backupDir == null ? "" : backupDir.strip();
	}

	/** 백업 폴더. 비어 있으면 null. */
	public Path backupDirPath() {
		return backupDir.isEmpty() ? null : Path.of(backupDir);
	}

	/** Spring Batch chunk 크기 = 한 커밋에 넣는 상품명 수. */
	public int chunkSize() {
		return batchSize * threads;
	}
}
