package com.zigzaego.api.receiving.service;

import com.zigzaego.api.account.entity.Store;
import com.zigzaego.api.account.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 00:10 도착 예정일이 지난(전날까지) 발주 줄 자동 입고 배치 (S15P31A308-203). 시각 · 시간대는 {@code app.receiving.auto.cron} · {@code .zone}.
 * 가게마다 "오늘" 은 그 가게 시간대 기준이다({@link AutoReceiveService#receiveDue}).
 * 가게마다 따로 돌리고, 한 가게가 실패해도 다음 가게는 계속한다 — 실패는 로그로만 남기고 다음 날 다시 시도한다.
 * 이미 입고된 줄은 다시 처리하지 않으므로 여러 번 돌아도 안전하다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutoReceiveScheduler {

	private final StoreRepository storeRepository;
	private final AutoReceiveService autoReceiveService;

	@Scheduled(cron = "${app.receiving.auto.cron}", zone = "${app.receiving.auto.zone}")
	public void runDaily() {
		for (Store store : storeRepository.findAll()) {
			try {
				AutoReceiveService.Result result = autoReceiveService.receiveDue(store.getId());
				log.info("자동 입고 배치 완료 — storeId={}, today={}, received={}, skipped={}, failed={}",
						store.getId(), result.today(), result.received(), result.skipped(), result.failed());
			} catch (RuntimeException e) {
				log.error("자동 입고 배치 실패 — storeId={}", store.getId(), e);
			}
		}
	}
}
