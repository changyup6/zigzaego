package com.zigzaego.api.push.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 5분마다 마감 시각이 지난 가게에 "오늘 재고 확인" 웹 푸시 (S15P31A308-248). 주기는 {@code app.push.closing-cron}. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClosingPushScheduler {

	private final ClosingPushService closingPushService;

	@Scheduled(cron = "${app.push.closing-cron}", zone = "${app.push.closing-zone}")
	public void run() {
		ClosingPushService.Result result = closingPushService.runOnce();
		if (result.sentStores() > 0) {
			log.info("마감 알림 배치 — 가게={}, 보냄={}, 지운 구독={}", result.sentStores(), result.notifications(),
					result.removedSubscriptions());
		}
	}
}
