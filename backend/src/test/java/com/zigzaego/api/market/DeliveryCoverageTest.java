package com.zigzaego.api.market;

import static org.assertj.core.api.Assertions.assertThat;

import com.zigzaego.api.market.delivery.DeliveryCoverage;
import com.zigzaego.api.market.delivery.DeliveryCoverage.Area;
import com.zigzaego.api.market.delivery.DeliveryCoverage.Status;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 가게 시군구 → 판매처(점포) 배달 판정 규칙 (S15P31A308-230, ADR-0011). */
class DeliveryCoverageTest {

	private static final List<Area> AREAS = List.of(
			new Area("식봄", null, "서울 중구", true),
			new Area("마켓컬리", null, null, null),
			new Area("식자재왕몰", "상일점", "서울 강동구", null),
			new Area("식자재왕몰", "성남점", "경기 성남시", false));

	@Test
	void 시군구가_맞는_사이트는_AVAILABLE_새벽배송_값_포함() {
		DeliveryCoverage c = DeliveryCoverage.of(AREAS, "서울 중구");
		assertThat(c.check("식봄", "서울 중구").status()).isEqualTo(Status.AVAILABLE);
		assertThat(c.check("식봄", "서울 중구").dawnDelivery()).isTrue();
		assertThat(c.check("마켓컬리", "전국").status()).isEqualTo(Status.AVAILABLE);
		assertThat(c.check("마켓컬리", "전국").dawnDelivery()).isNull();
	}

	@Test
	void 시군구가_다르면_BLOCKED_점포는_점포별로() {
		DeliveryCoverage c = DeliveryCoverage.of(AREAS, "경기 성남시");
		assertThat(c.check("식봄", "서울 중구").status()).isEqualTo(Status.BLOCKED);
		assertThat(c.check("식자재왕몰", "성남점").status()).isEqualTo(Status.AVAILABLE);
		assertThat(c.check("식자재왕몰", "성남점").dawnDelivery()).isFalse();
		assertThat(c.check("식자재왕몰", "상일점").status()).isEqualTo(Status.BLOCKED);
		assertThat(c.check("식자재왕몰", "천호점").status()).isEqualTo(Status.BLOCKED);  // 줄 없는 점포도 사이트는 아는 것
	}

	@Test
	void 배송_지역_줄이_없는_사이트는_UNKNOWN() {
		DeliveryCoverage c = DeliveryCoverage.of(AREAS, "서울 중구");
		assertThat(c.check("농협몰", null).status()).isEqualTo(Status.UNKNOWN);
		assertThat(c.check("농협몰", null).allowed()).isTrue();
	}

	@Test
	void 가게_시군구가_없으면_전부_UNKNOWN() {
		for (String none : new String[] {null, "", "   "}) {
			DeliveryCoverage c = DeliveryCoverage.of(AREAS, none);
			assertThat(c.storeSigungu()).isNull();
			assertThat(c.check("식봄", "서울 중구").status()).isEqualTo(Status.UNKNOWN);
			assertThat(c.check("농협몰", null).status()).isEqualTo(Status.UNKNOWN);
		}
	}

	@Test
	void 공백은_정리해서_비교한다() {
		DeliveryCoverage c = DeliveryCoverage.of(AREAS, "  서울   중구 ");
		assertThat(c.storeSigungu()).isEqualTo("서울 중구");
		assertThat(c.check("식봄", " 서울  중구").status()).isEqualTo(Status.AVAILABLE);
	}
}
