package com.zigzaego.api.market.label;

import com.zigzaego.api.market.catalog.MarketItemGroupCatalog;
import com.zigzaego.api.market.catalog.MarketItemGroupCatalog.Snapshot;
import java.util.Iterator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamReader;

/**
 * 라벨 배치의 읽기 쪽 (S15P31A308-229, 기준 문서 9절). 단계가 열릴 때 한 번:
 * <ol>
 *   <li>{@code market_product} 의 고유 상품명 중 현재 {@code label_version} 라벨이 없는 것(증분)을 읽고</li>
 *   <li>품목군 별칭이 낱말로 든 것(후보)만 남긴다 — 후보가 아니면 LLM 에 보내지 않고 라벨도 남기지 않는다(규칙 폴백, 기준 문서 10절)</li>
 *   <li>{@code limit} 이 있으면 앞에서 그만큼만</li>
 * </ol>
 * 페이지 조회 대신 목록을 메모리에 올린다 — 고유 상품명 40만 개는 몇 MB 이고, 라벨이 붙는 대로 조회 결과가 바뀌어 페이지가 밀리는 문제를 피한다.
 * 다시 시작하면 이미 라벨이 붙은 이름은 조회에서 빠지므로 읽은 위치를 따로 저장하지 않는다.
 */
@Slf4j
public class MarketLabelCandidateReader implements ItemStreamReader<String> {

	public static final String CONTEXT_UNLABELED = "unlabeledNames";
	public static final String CONTEXT_CANDIDATES = "candidates";

	private final MarketProductLabelJdbcRepository labelRepository;
	private final MarketItemGroupCatalog catalog;
	private final String version;
	private final long limit;

	private Iterator<String> iterator;

	public MarketLabelCandidateReader(MarketProductLabelJdbcRepository labelRepository, MarketItemGroupCatalog catalog,
			String version, long limit) {
		this.labelRepository = labelRepository;
		this.catalog = catalog;
		this.version = version;
		this.limit = limit;
	}

	@Override
	public void open(ExecutionContext executionContext) {
		Snapshot groups = catalog.load();
		List<String> unlabeled = labelRepository.findUnlabeledNames(version);
		List<String> targets = unlabeled.stream().filter(groups::isCandidate)
				.limit(limit > 0 ? limit : Long.MAX_VALUE).toList();
		executionContext.putLong(CONTEXT_UNLABELED, unlabeled.size());
		executionContext.putLong(CONTEXT_CANDIDATES, targets.size());
		log.info("상품명 라벨 배치 대상 — 라벨 없는 고유 상품명 {}개 중 후보 {}개 (라벨 버전 {}, 상한 {})", unlabeled.size(),
				targets.size(), version, limit > 0 ? limit : "없음");
		iterator = targets.iterator();
	}

	@Override
	public String read() {
		return iterator != null && iterator.hasNext() ? iterator.next() : null;
	}

	@Override
	public void close() {
		iterator = null;
	}
}
