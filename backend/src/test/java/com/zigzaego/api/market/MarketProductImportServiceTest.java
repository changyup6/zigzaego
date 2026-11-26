package com.zigzaego.api.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zigzaego.api.TestcontainersConfiguration;
import com.zigzaego.api.market.importer.MarketProductImportService;
import com.zigzaego.api.market.repository.MarketProductJdbcRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** 데이터셋 CSV → market_product 수집일 단위 교체 적재, 라벨 CSV → market_product_label upsert (S15P31A308-232 · 229, ADR-0012). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class MarketProductImportServiceTest {

	private static final String HEADER =
			"사이트,점포지역,판매자,상품번호,상품명,브랜드,규격,판매가,원래가격,총량,단위,단위가격_100,카테고리,URL,수집일,묶음번호\n";

	@Autowired
	private MarketProductImportService service;

	@Autowired
	private MarketProductJdbcRepository repository;

	@Autowired
	private JdbcTemplate jdbc;

	@TempDir
	Path dir;

	@Test
	void 따옴표_쉼표_빈칸을_읽고_같은_수집일만_교체한다() throws IOException {
		jdbc.update("DELETE FROM market_product");
		Path csv = write("v1.csv", HEADER
				+ "식봄,서울 중구,CJ프레시웨이,538328,\"프레시원 계란(세척 특란 60g*30입) 1.8kg\",,1.8kg / 1EA,7800,9000,1800.0,g,433.3,816,https://www.foodspring.co.kr/goods/detail/538328,2026-10-26,\n"
				+ "식자재왕몰,상일점,식자재왕 상일점,53780,\"지킴이계란(특란), \"\"특가\"\"\",,1.8kg (60gx30),6990,,1800.0,g,388.3,축산/난류,https://www.ewangmart.com/goods/detail.do?gno=53780,2026-10-27,12\n"
				+ "마켓컬리,전국,,1001400868,[자연애찬] 쫄깃한 계란 (20개입),,,18200,18200,,,,정육·가공육·달걀,https://www.kurly.com/goods/1001400868,2026-10-27,\n");

		assertThat(service.replaceAll(csv)).isEqualTo(3);
		assertThat(repository.count()).isEqualTo(3);

		Map<String, Object> ewang = jdbc.queryForMap("SELECT * FROM market_product WHERE site = '식자재왕몰'");
		assertThat(ewang.get("product_name")).isEqualTo("지킴이계란(특란), \"특가\"");
		assertThat(ewang.get("original_price")).isNull();
		assertThat(ewang.get("group_no")).isEqualTo(12L);
		assertThat((BigDecimal) ewang.get("unit_price_100")).isEqualByComparingTo("388.3");
		assertThat(ewang.get("collected_on").toString()).isEqualTo("2026-10-27");
		Map<String, Object> kurly = jdbc.queryForMap("SELECT * FROM market_product WHERE site = '마켓컬리'");
		assertThat(kurly.get("seller")).isNull();
		assertThat(kurly.get("spec")).isNull();
		assertThat(kurly.get("total_qty")).isNull();
		assertThat(kurly.get("unit")).isNull();
		assertThat(kurly.get("unit_price_100")).isNull();
		assertThat(kurly.get("original_price")).isEqualTo(18200L);

		Path csv2 = write("v2.csv", HEADER
				+ "﻿농협몰,,,9039570447,[푸른들] 구운계란 대란 30구,,,11900,,30.0,개,396.7,축산,https://www.nonghyupmall.com/x,2026-10-28,\n"
				.replace("﻿", ""));
		// 다른 수집일(10-28)은 쌓인다 — 일별 추이의 재료
		assertThat(service.replaceAll(csv2)).isEqualTo(1);
		assertThat(repository.count()).isEqualTo(4);
		// 같은 수집일(10-27)을 다시 넣으면 그 날짜 줄만 바뀐다
		Path csv3 = write("v3.csv", HEADER
				+ "식자재왕몰,상일점,식자재왕 상일점,53780,지킴이계란(특란),,1.8kg (60gx30),7990,,1800.0,g,443.9,축산/난류,https://www.ewangmart.com/goods/detail.do?gno=53780,2026-10-27,12\n");
		assertThat(service.replaceAll(csv3)).isEqualTo(1);
		assertThat(repository.count()).isEqualTo(3);
		assertThat(jdbc.queryForObject("SELECT price FROM market_product WHERE site = '식자재왕몰'", Long.class)).isEqualTo(7990L);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM market_product WHERE collected_on = '2026-10-26'", Long.class)).isEqualTo(1L);
	}

	@Test
	void 라벨_CSV_는_상품명_기준으로_덮어쓰고_모르는_품목군은_UNKNOWN() throws IOException {
		jdbc.update("DELETE FROM market_product_label");
		String header = "product_name,item_group_code,form,qty,qty_unit,confidence,reason,label_version\n";
		Path labels = write("labels.csv", header
				+ "\"계란(중란/30알/국내산)판\",EGG,RAW,30,EA,0.95,특란 30구 원물,v1\n"
				+ "계란찜 1kg,EGG,PROCESSED,1000,G,0.9,조리됨,v1\n"  // 가공품인데 코드 → 코드 지움
				+ "이상한 상품,NOPE_CODE,RAW,,,0.8,,v1\n");  // 표에 없는 코드 → UNKNOWN
		assertThat(service.importLabels(labels).unknownGroupCodes()).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM market_product_label", Long.class)).isEqualTo(3L);
		Map<String, Object> egg = jdbc.queryForMap("SELECT * FROM market_product_label WHERE product_name = '계란(중란/30알/국내산)판'");
		assertThat(egg.get("item_group_code")).isEqualTo("EGG");
		assertThat((BigDecimal) egg.get("qty")).isEqualByComparingTo("30");
		assertThat(jdbc.queryForMap("SELECT * FROM market_product_label WHERE product_name = '계란찜 1kg'").get("item_group_code")).isNull();
		Map<String, Object> odd = jdbc.queryForMap("SELECT * FROM market_product_label WHERE product_name = '이상한 상품'");
		assertThat(odd.get("item_group_code")).isNull();
		assertThat(odd.get("form")).isEqualTo("UNKNOWN");

		// 같은 상품명이 다시 오면 덮어쓴다
		service.importLabels(write("labels2.csv", header + "\"계란(중란/30알/국내산)판\",EGG,PREPARED,30,EA,0.7,다시 분류,v2\n"));
		Map<String, Object> again = jdbc.queryForMap("SELECT * FROM market_product_label WHERE product_name = '계란(중란/30알/국내산)판'");
		assertThat(again.get("form")).isEqualTo("PREPARED");
		assertThat(again.get("label_version")).isEqualTo("v2");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM market_product_label", Long.class)).isEqualTo(3L);
	}

	@Test
	void 라벨_CSV_에_열이_빠지면_실패() throws IOException {
		Path bad = write("badlabels.csv", "product_name,form\n계란,RAW\n");
		assertThatThrownBy(() -> service.importLabels(bad)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("label_version");
	}

	@Test
	void BOM_과_CRLF_도_읽는다() throws IOException {
		Path csv = write("bom.csv", "﻿" + HEADER.replace("\n", "\r\n")
				+ "식봄,서울 중구,,1,계란 30구,,,6000,,30,개,200,816,https://x,2026-10-26,\r\n");
		assertThat(service.replaceAll(csv)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT product_name FROM market_product", String.class)).isEqualTo("계란 30구");
	}

	@Test
	void 필수_열이_빠지면_넣지_않고_실패() throws IOException {
		jdbc.update("DELETE FROM market_product");
		service.replaceAll(write("ok.csv", HEADER + "식봄,,,1,계란,,,6000,,,,,,https://x,2026-10-26,\n"));
		Path bad = write("bad.csv", "사이트,상품명,판매가\n식봄,계란,6000\n");
		assertThatThrownBy(() -> service.replaceAll(bad))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("상품번호");
		assertThat(repository.count()).as("실패한 적재는 이전 스냅샷을 남긴다").isEqualTo(1);
	}

	@Test
	void 값이_형식에_안_맞으면_줄_번호와_함께_실패() throws IOException {
		Path bad = write("num.csv", HEADER + "식봄,,,1,계란,,,6000,,,,,,https://x,2026-10-26,\n식봄,,,2,계란,,,비싸요,,,,,,https://x,2026-10-26,\n");
		assertThatThrownBy(() -> service.replaceAll(bad))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("3번째 줄");
	}

	@Test
	void 파일이_없으면_실패() {
		assertThatThrownBy(() -> service.replaceAll(dir.resolve("없음.csv")))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private Path write(String name, String content) throws IOException {
		Path file = dir.resolve(name);
		Files.writeString(file, content, StandardCharsets.UTF_8);
		return file;
	}
}
