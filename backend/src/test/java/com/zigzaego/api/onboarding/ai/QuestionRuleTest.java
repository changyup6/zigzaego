package com.zigzaego.api.onboarding.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.zigzaego.api.onboarding.ai.QuestionRule.Component;
import com.zigzaego.api.onboarding.ai.QuestionRule.Info;
import com.zigzaego.api.onboarding.ai.QuestionRule.MenuComponents;
import com.zigzaego.api.onboarding.ai.QuestionRule.Question;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** ai {@code questions.py} · {@code tests/test_onboarding_questions.py} 와 같은 답을 내는지 본다. */
class QuestionRuleTest {

	private static Info info(double confidence, String unit, double perServing, String kind) {
		return new Info(kind, unit, perServing, confidence);
	}

	private static Info info(double confidence) {
		return info(confidence, "G", 100, "BOM");
	}

	private static Component comp(String name, String role, double mult) {
		return new Component(name, role, mult);
	}

	private static Component comp(String name) {
		return comp(name, "PROTEIN", 1);
	}

	private static MenuComponents menu(String name, Component... components) {
		return new MenuComponents(name, List.of(components));
	}

	private static List<Double> options(double value, String unit) {
		return QuestionRule.makeOptions(value, unit).stream().map(Number::doubleValue).toList();
	}

	@Test
	void 가운데_보기는_초기값을_간격으로_반올림한다() {
		assertThat(options(5, "ML")).containsExactly(4.0, 5.0, 6.0);
		assertThat(options(14, "G")).containsExactly(10.0, 15.0, 20.0);
		assertThat(options(1, "EA")).containsExactly(0.5, 1.0, 1.5);
		assertThat(options(180, "G")).containsExactly(140.0, 180.0, 220.0);
		assertThat(options(9.99, "G")).containsExactly(8.0, 10.0, 12.0);
		assertThat(options(12.4, "G")).containsExactly(5.0, 10.0, 15.0);
	}

	@Test
	void 보기는_단위별_간격으로_반올림하고_서로_다르다() {
		assertThat(options(100, "G")).containsExactly(80.0, 100.0, 120.0);
		assertThat(options(80, "ML")).containsExactly(60.0, 80.0, 100.0);
		assertThat(options(5, "EA")).containsExactly(4.0, 5.0, 6.0);
		assertThat(options(25, "G")).containsExactly(20.0, 25.0, 30.0);
		assertThat(options(2, "G")).containsExactly(1.0, 2.0, 3.0);
		double[] values = {100, 25, 1, 2, 3, 7, 14, 0.3};
		String[] units = {"G", "G", "EA", "G", "EA", "ML", "G", "EA"};
		for (int i = 0; i < values.length; i++) {
			List<Double> opts = options(values[i], units[i]);
			assertThat(opts.get(0)).as("%s %s", values[i], units[i]).isPositive().isLessThan(opts.get(1));
			assertThat(opts.get(1)).isLessThan(opts.get(2));
		}
	}

	@Test
	void 딱_떨어지는_보기는_정수() {
		assertThat(QuestionRule.makeOptions(180, "G")).allSatisfy(o -> assertThat(o).isInstanceOf(Long.class));
		List<Number> one = QuestionRule.makeOptions(1, "EA");
		assertThat(one.get(0)).isInstanceOf(Double.class);
		assertThat(one.get(1)).isInstanceOf(Long.class);
	}

	@Test
	void 상한은_30개_이하_10개_넘으면_floor_10n_30() {
		assertThat(QuestionRule.questionLimit(8)).isEqualTo(10);
		assertThat(QuestionRule.questionLimit(30)).isEqualTo(10);
		assertThat(QuestionRule.questionLimit(31)).isEqualTo(10);
		assertThat(QuestionRule.questionLimit(44)).isEqualTo(14);
		assertThat(QuestionRule.questionLimit(45)).isEqualTo(15);
	}

	@Test
	void 소스_1대1_확신_높은_재료는_묻지_않는다() {
		List<MenuComponents> menus = List.of(menu("a", comp("면", "BASE", 1), comp("크림 소스", "SAUCE", 1),
				comp("음료", "DRINK", 1), comp("소금", "OTHER", 1)));
		Map<String, Info> infos = Map.of(
				"면", info(0.5),
				"크림 소스", info(0.3, "ML", 100, "BOM"),
				"음료", info(0.2, "EA", 1, "ONE_TO_ONE"),
				"소금", info(0.7, "G", 2, "BOM"));

		assertThat(QuestionRule.build(menus, infos)).extracting(Question::ingredient).containsExactly("면");
	}

	@Test
	void 재료당_질문_하나_확신_낮은_순() {
		List<MenuComponents> menus = List.of(menu("a", comp("닭"), comp("면", "BASE", 1)),
				menu("b", comp("닭", "PROTEIN", 2)));
		List<Question> questions = QuestionRule.build(menus, Map.of("닭", info(0.4), "면", info(0.6)));

		assertThat(questions).extracting(Question::ingredient).containsExactly("닭", "면");
		assertThat(questions.get(0).menus()).containsExactly("a", "b");
		assertThat(questions.get(0).text()).isEqualTo("닭 1인분");
	}

	@Test
	void 메뉴_31개면_상한_10개로_확신_낮은_순으로_자른다() {
		Map<String, Info> infos = new LinkedHashMap<>();
		for (int j = 0; j < 40; j++) {
			infos.put("i" + j, info(j / 100.0));
		}
		List<MenuComponents> menus = new ArrayList<>();
		for (int i = 0; i < 31; i++) {
			List<Component> components = new ArrayList<>(List.of(comp("i" + (i % 40))));
			if (i == 0) {
				for (int j = 0; j < 40; j++) {
					components.add(comp("i" + j));
				}
			}
			menus.add(new MenuComponents("m" + i, components));
		}

		List<Question> questions = QuestionRule.build(menus, infos);

		assertThat(questions).extracting(Question::ingredient)
				.containsExactly("i0", "i1", "i2", "i3", "i4", "i5", "i6", "i7", "i8", "i9");
	}
}
