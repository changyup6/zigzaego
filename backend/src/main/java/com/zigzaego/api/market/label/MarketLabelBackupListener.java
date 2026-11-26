package com.zigzaego.api.market.label;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.listener.ChunkListener;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 라벨 배치의 CSV 백업 시점 (S15P31A308-229, ADR-0014).
 * <ul>
 *   <li>chunk 마다: Spring Batch 의 {@code afterChunk} 는 chunk 트랜잭션 **안**에서 불리므로, 커밋 뒤에 돌도록 동기화({@code afterCommit})를 건다.
 *       커밋이 실패하면 백업도 하지 않는다 — 파일에는 언제나 커밋된 라벨만 있다</li>
 *   <li>단계 끝(완료 · 실패): latest 를 한 번 더 쓰고 시각이 붙은 사본을 남긴다</li>
 * </ul>
 * 백업 실패는 WARN 만 남기고 배치를 멈추지 않는다 — 라벨은 이미 DB 에 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketLabelBackupListener implements ChunkListener<String, String>, StepExecutionListener {

	private final MarketLabelBackup backup;

	@Override
	public void afterChunk(Chunk<String> chunk) {
		if (!backup.enabled()) {
			return;
		}
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					exportQuietly();
				}
			});
		} else {
			exportQuietly();
		}
	}

	@Override
	public ExitStatus afterStep(StepExecution stepExecution) {
		if (backup.enabled()) {
			try {
				backup.exportLatest();
				backup.snapshot();
			} catch (RuntimeException e) {
				log.warn("라벨 백업 사본 실패 (라벨은 DB 에 있다): {}", e.getMessage());
			}
		}
		return null;  // exit status 는 writer 가 정한다
	}

	private void exportQuietly() {
		try {
			backup.exportLatest();
		} catch (RuntimeException e) {
			log.warn("라벨 백업 실패 (라벨은 DB 에 있다): {}", e.getMessage());
		}
	}
}
