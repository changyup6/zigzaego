package com.zigzaego.api.market.importer;

import java.nio.file.Path;

/**
 * 크롤러 데이터셋(시세 CSV)이 {@code market_product} 에 들어갔다 (S15P31A308-232 · 229).
 * {@code MarketProductImportRunner} 가 적재 트랜잭션이 끝난 뒤 발행하고, 상품명 라벨 배치가 이 이벤트로 기동한다 (ADR-0014).
 * 스케줄이 아니라 "새 수집분이 들어왔을 때" 만 LLM 을 부르려는 것이다.
 *
 * @param file 넣은 CSV
 * @param rows 넣은 줄 수
 */
public record MarketProductsImportedEvent(Path file, int rows) {
}
