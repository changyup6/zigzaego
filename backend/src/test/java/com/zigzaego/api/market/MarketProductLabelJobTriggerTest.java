package com.zigzaego.api.market;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.zigzaego.api.llm.GmsProperties;
import com.zigzaego.api.market.importer.MarketProductsImportedEvent;
import com.zigzaego.api.market.label.MarketLabelProperties;
import com.zigzaego.api.market.label.MarketProductLabelJobTrigger;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.launch.JobOperator;

/** GMS 설정이 없으면 라벨 배치를 시작하지 않는다 — 시세 적재는 라벨 없이도 쓸 수 있다 (S15P31A308-229). */
class MarketProductLabelJobTriggerTest {

	@Test
	void GMS_설정이_비어_있으면_Job_을_돌리지_않는다() {
		JobOperator operator = mock(JobOperator.class);
		GmsProperties unconfigured = new GmsProperties("", "", "", Duration.ofSeconds(1), List.of());
		MarketProductLabelJobTrigger trigger = new MarketProductLabelJobTrigger(operator, mock(Job.class), unconfigured,
				new MarketLabelProperties("v2", 40, 0, 1, ""), Clock.systemUTC());

		trigger.onImported(new MarketProductsImportedEvent(Path.of("x.csv"), 1));

		verifyNoInteractions(operator);
	}
}
