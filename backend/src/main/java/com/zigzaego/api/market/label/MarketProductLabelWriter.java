package com.zigzaego.api.market.label;

import com.zigzaego.api.market.catalog.MarketItemGroupCatalog;
import com.zigzaego.api.market.catalog.MarketItemGroupCatalog.Snapshot;
import com.zigzaego.api.market.label.MarketProductLabelClassifier.Usage;
import com.zigzaego.api.market.label.MarketProductLabelJdbcRepository.LabelRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemWriter;

/**
 * 라벨 배치의 쓰기 쪽 (S15P31A308-229). chunk(상품명 {@code batch-size × threads}개) 하나를 {@code batch-size}개씩 잘라 LLM 요청
 * {@code threads}개를 나란히 보내고, 전부 받으면 {@code market_product_label} 에 upsert 한다. 한 요청에 약 30초라 순차로는 후보 10만 개가
 * 하루를 넘겨 나란히 보낸다 (GMS 분당 한도 30,000 요청에 한참 못 미친다).
 * chunk 마다 커밋되므로 중간에 멈춰도 그때까지의 라벨은 남고, 다시 돌리면 남은 이름만 간다.
 * 단계가 끝나면 비용(요청 · 토큰)과 형태별 건수를 {@code BATCH_STEP_EXECUTION} 의 exit message · 실행 컨텍스트에 남긴다(229 완료 조건 "1회 비용 · 시간 기록").
 */
@Slf4j
public class MarketProductLabelWriter implements ItemWriter<String>, StepExecutionListener {

	public static final String CONTEXT_REQUESTS = "llmRequests";
	public static final String CONTEXT_RETRIES = "llmRetries";
	public static final String CONTEXT_PROMPT_TOKENS = "llmPromptTokens";
	public static final String CONTEXT_COMPLETION_TOKENS = "llmCompletionTokens";
	public static final String CONTEXT_LABELED = "labeled";
	public static final String CONTEXT_FORMS = "forms";

	private final MarketProductLabelClassifier classifier;
	private final MarketProductLabelJdbcRepository labelRepository;
	private final MarketItemGroupCatalog catalog;
	private final String version;
	private final int batchSize;
	private final int threads;

	private Snapshot groups;
	private ExecutorService executor;
	private Usage usage = new Usage();
	private long labeled;
	private final Map<String, Long> forms = new TreeMap<>();

	public MarketProductLabelWriter(MarketProductLabelClassifier classifier, MarketProductLabelJdbcRepository labelRepository,
			MarketItemGroupCatalog catalog, String version, int batchSize, int threads) {
		this.classifier = classifier;
		this.labelRepository = labelRepository;
		this.catalog = catalog;
		this.version = version;
		this.batchSize = Math.max(batchSize, 1);
		this.threads = Math.max(threads, 1);
	}

	@Override
	public void beforeStep(StepExecution stepExecution) {
		groups = catalog.load();
		usage = new Usage();
		labeled = 0;
		forms.clear();
		if (threads > 1) {
			AtomicInteger seq = new AtomicInteger();
			executor = Executors.newFixedThreadPool(threads, r -> {
				Thread t = new Thread(r, "market-label-" + seq.incrementAndGet());
				t.setDaemon(true);
				return t;
			});
		}
	}

	@Override
	public void write(Chunk<? extends String> chunk) {
		if (groups == null) {
			groups = catalog.load();
		}
		List<String> names = new ArrayList<>(chunk.getItems());
		List<LabelRow> rows = classifyParallel(names);
		labelRepository.upsertAll(rows);
		labeled += rows.size();
		for (LabelRow row : rows) {
			forms.merge(row.form(), 1L, Long::sum);
		}
		log.info("상품명 라벨 {}개 저장 (누적 {} · {})", rows.size(), labeled, usage.summary());
	}

	/** {@code batchSize}개씩 잘라 요청하고 모은다. 한 요청이 전송 실패면 chunk 전체가 실패한다(커밋 안 됨) — 다른 요청의 라벨은 다음 실행이 다시 받는다. */
	private List<LabelRow> classifyParallel(List<String> names) {
		List<List<String>> parts = new ArrayList<>();
		for (int i = 0; i < names.size(); i += batchSize) {
			parts.add(names.subList(i, Math.min(i + batchSize, names.size())));
		}
		List<LabelRow> rows = new ArrayList<>(names.size());
		if (executor == null || parts.size() == 1) {
			for (List<String> part : parts) {
				rows.addAll(classifier.classify(part, groups, version, usage));
			}
			return rows;
		}
		List<Future<List<LabelRow>>> futures = new ArrayList<>(parts.size());
		for (List<String> part : parts) {
			futures.add(executor.submit(() -> classifier.classify(part, groups, version, usage)));
		}
		for (Future<List<LabelRow>> future : futures) {
			try {
				rows.addAll(future.get());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("상품명 라벨 요청이 중단됐다", e);
			} catch (ExecutionException e) {
				if (e.getCause() instanceof RuntimeException cause) {
					throw cause;
				}
				throw new IllegalStateException("상품명 라벨 요청 실패", e.getCause());
			}
		}
		return rows;
	}

	@Override
	public ExitStatus afterStep(StepExecution stepExecution) {
		if (executor != null) {
			executor.shutdownNow();
			executor = null;
		}
		ExecutionContext context = stepExecution.getExecutionContext();
		context.putLong(CONTEXT_REQUESTS, usage.getRequests());
		context.putLong(CONTEXT_RETRIES, usage.getRetries());
		context.putLong(CONTEXT_PROMPT_TOKENS, usage.getPromptTokens());
		context.putLong(CONTEXT_COMPLETION_TOKENS, usage.getCompletionTokens());
		context.putLong(CONTEXT_LABELED, labeled);
		String formSummary = forms.entrySet().stream().map(e -> e.getKey() + " " + e.getValue())
				.collect(Collectors.joining(" · "));
		context.putString(CONTEXT_FORMS, formSummary);
		String summary = "라벨 %d · %s · 형태별 %s".formatted(labeled, usage.summary(), formSummary.isEmpty() ? "없음" : formSummary);
		log.info("상품명 라벨 배치 단계 끝 ({}) — {}", stepExecution.getStatus(), summary);
		return new ExitStatus(stepExecution.getExitStatus().getExitCode(), summary);
	}
}
