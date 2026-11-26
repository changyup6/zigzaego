package com.zigzaego.api.purchaseorder;

import static org.assertj.core.api.Assertions.assertThat;

import com.zigzaego.api.ingredient.entity.IngredientUnit;
import com.zigzaego.api.purchaseorder.service.OrderMessageRenderer;
import com.zigzaego.api.purchaseorder.service.OrderMessageRenderer.Line;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 주문 메시지 렌더링 (3주 계획 4.7절): 기본 템플릿 · 거래처 템플릿 · 포장 단위 표기. */
class OrderMessageRendererTest {

	private static final LocalDate THU = LocalDate.of(2026, 10, 15);

	@Test
	void 기본_템플릿은_가게_이름_배송일_품목을_채운다() {
		String text = OrderMessageRenderer.render(null, "위글리", THU, List.of(
				new Line("대파 1단", new BigDecimal("2.000"), "단", new BigDecimal("1000"), IngredientUnit.G),
				new Line("양파 15kg", new BigDecimal("1.000"), "망", new BigDecimal("15000"), IngredientUnit.G)));

		assertThat(text).isEqualTo("""
				안녕하세요, 위글리입니다.
				10/15(목) 배송으로 아래 부탁드립니다.
				- 대파 1단 2단
				- 양파 15kg 1망
				감사합니다.""");
	}

	@Test
	void 거래처_템플릿의_자리표시자를_바꾼다_영문도_같다() {
		List<Line> lines = List.of(new Line("등심", new BigDecimal("3.000"), "팩", new BigDecimal("500"), IngredientUnit.G));

		assertThat(OrderMessageRenderer.render("{가게} 주문: {품목} ({배송일})", "한빛식당", THU, lines))
				.isEqualTo("한빛식당 주문: - 등심 3팩 (10/15(목))");
		assertThat(OrderMessageRenderer.render("{store} order {arrival_date}\n{items}", "한빛식당", THU, lines))
				.isEqualTo("한빛식당 order 10/15(목)\n- 등심 3팩");
	}

	@Test
	void 템플릿에_품목_자리가_없으면_끝에_붙인다() {
		String text = OrderMessageRenderer.render("안녕하세요 부탁드립니다  \n", "가게", THU,
				List.of(new Line("새우", new BigDecimal("4.000"), "박스", new BigDecimal("20"), IngredientUnit.EA)));

		assertThat(text).isEqualTo("안녕하세요 부탁드립니다\n- 새우 4박스");
	}

	@Test
	void 가게_이름에_자리표시자_글자가_있어도_다시_바뀌지_않고_품목_자리가_둘이면_둘_다_채운다() {
		List<Line> lines = List.of(new Line("등심", new BigDecimal("3.000"), "팩", new BigDecimal("500"), IngredientUnit.G));

		assertThat(OrderMessageRenderer.render("{가게}: {품목}", "{품목}가게", THU, lines)).isEqualTo("{품목}가게: - 등심 3팩");
		assertThat(OrderMessageRenderer.render("{품목}|{items}", "가게", THU, lines)).isEqualTo("- 등심 3팩|- 등심 3팩");
	}

	@Test
	void 단위_표기_포장_이름_없으면_팩_환산을_모르면_포장_이름이_있어도_기준_단위() {
		assertThat(new Line("대파", BigDecimal.ONE, null, new BigDecimal("1000"), IngredientUnit.G).displayUnit()).isEqualTo("팩");
		assertThat(new Line("대파", BigDecimal.ONE, " ", new BigDecimal("1000"), IngredientUnit.G).displayUnit()).isEqualTo("팩");
		assertThat(new Line("대파", BigDecimal.ONE, null, null, IngredientUnit.G).displayUnit()).isEqualTo("g");
		// 환산을 모르면 수량이 기준 단위(1,600)라 "박스"를 붙이면 "1,600박스"가 된다
		assertThat(new Line("대파", new BigDecimal("1600"), "박스", null, IngredientUnit.G).displayUnit()).isEqualTo("g");
		assertThat(new Line("우유", BigDecimal.ONE, null, null, IngredientUnit.ML).displayUnit()).isEqualTo("ml");
		assertThat(new Line("계란", BigDecimal.ONE, null, null, IngredientUnit.EA).displayUnit()).isEqualTo("개");
	}

	@Test
	void 수량은_천_단위_쉼표_소수는_셋째_자리까지_0은_뗀다() {
		String text = OrderMessageRenderer.render("{품목}", "가게", THU, List.of(
				new Line("대파", new BigDecimal("1600.000"), null, null, IngredientUnit.G),
				new Line("소스", new BigDecimal("12.345"), null, null, IngredientUnit.ML),
				new Line("계란", new BigDecimal("30.000"), "판", new BigDecimal("30"), IngredientUnit.EA)));

		assertThat(text).isEqualTo("- 대파 1,600g\n- 소스 12.345ml\n- 계란 30판");
	}
}
