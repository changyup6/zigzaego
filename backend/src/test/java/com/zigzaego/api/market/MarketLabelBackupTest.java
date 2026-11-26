package com.zigzaego.api.market;

import static org.assertj.core.api.Assertions.assertThat;

import com.zigzaego.api.market.label.MarketLabelBackup;
import com.zigzaego.api.market.label.MarketProductLabelJdbcRepository.LabelRow;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** 라벨 백업 CSV 한 줄의 모양 — 쉼표 · 따옴표 · 줄바꿈이 든 상품명, 빈 값, 숫자 (S15P31A308-229). */
class MarketLabelBackupTest {

	@Test
	void 쉼표_따옴표_줄바꿈은_따옴표로_감싸고_빈_값은_빈_칸() {
		LabelRow full = new LabelRow("지킴이계란(특란), \"특가\"\n2판", "EGG", "RAW", new BigDecimal("60.000"), "EA",
				new BigDecimal("0.950"), "특란 30구 원물", "v2");
		assertThat(MarketLabelBackup.line(full))
				.isEqualTo("\"지킴이계란(특란), \"\"특가\"\"\n2판\",EGG,RAW,60,EA,0.95,특란 30구 원물,v2");

		LabelRow empty = new LabelRow("실속세트 1호", null, "UNKNOWN", null, null, BigDecimal.ZERO, null, "v2");
		assertThat(MarketLabelBackup.line(empty)).isEqualTo("실속세트 1호,,UNKNOWN,,,0,,v2");
	}
}
