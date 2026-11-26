package com.zigzaego.api.market.importer;

import com.zigzaego.api.market.importer.MarketProductImportService.LabelImportResult;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 운영자용 식자재몰 시세 · 라벨 적재 (S15P31A308-232 · 229). {@code import-market-products} 프로필로 띄울 때만 돈다.
 * 웹 서버 없이 라벨 CSV(있으면) → 시세 CSV(있으면) → 시세를 넣었으면 상품명 라벨 배치(이벤트, ADR-0014) 순으로 돌고 끝난다. 둘 다 없으면 멈춘다. 실행법은 {@code backend/README.md}.
 */
@Slf4j
@Component
@Profile("import-market-products")
@EnableConfigurationProperties(MarketProductImportProperties.class)
@RequiredArgsConstructor
public class MarketProductImportRunner implements ApplicationRunner {

	private final MarketProductImportProperties properties;
	private final MarketProductImportService service;
	private final ApplicationEventPublisher events;

	@Override
	public void run(ApplicationArguments args) {
		Path products = properties.filePath();
		Path labels = properties.labelsFilePath();
		if (products == null && labels == null) {
			throw new IllegalArgumentException("MARKET_PRODUCTS_FILE(시세 CSV) 또는 MARKET_LABELS_FILE(라벨 CSV) 중 하나는 있어야 합니다");
		}
		if (labels != null) {
			log.info("상품명 라벨 적재 시작 — {}", labels);
			LabelImportResult r = service.importLabels(labels);
			log.info("상품명 라벨 적재 끝 — {}줄 (표에 없는 품목군 {}줄)", r.rows(), r.unknownGroupCodes());
		}
		if (products != null) {
			log.info("식자재몰 시세 적재 시작 — {}", products);
			int rows = service.replaceAll(products);
			log.info("식자재몰 시세 적재 끝 — {}줄. 재료별 최저가 · 일별 추이가 이 수집일을 본다.", rows);
			// 적재 트랜잭션이 끝난 뒤 — 상품명 라벨 배치(MarketProductLabelJobTrigger)가 이 이벤트로 돈다 (ADR-0014)
			events.publishEvent(new MarketProductsImportedEvent(products, rows));
		}
	}
}
