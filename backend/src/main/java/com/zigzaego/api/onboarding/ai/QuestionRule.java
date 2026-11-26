package com.zigzaego.api.onboarding.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 레시피 확인 질문 규칙. ai {@code src/weglee_ai/onboarding/questions.py} 를 그대로 옮긴 것이다 — 규칙을 바꾸려면 두 곳을 같이 바꾼다.
 *
 * <ul>
 *   <li>대상: 분류가 BOM 이고, 소스(SAUCE)가 아닌 역할로 쓰이고, 확신도가 0.7 미만인 재료</li>
 *   <li>재료당 질문 1개 (여러 메뉴에 걸려도 1개)</li>
 *   <li>보기 3개: 가운데 = 초기값을 간격으로 반올림(최소 한 간격), 양옆 = 초기값 × 0.8 · × 1.2 를 같은 간격으로 반올림.
 *       간격 G · ML: 50 이상 10, 10 이상 5, 그 아래 1 / EA: 3 이상 1, 그 아래 0.5</li>
 *   <li>상한: 메뉴 30개 이하 10개, 넘으면 floor(10 × 메뉴 수 / 30). 확신 낮은 순(같으면 걸린 메뉴가 많은 순, 그다음 이름 순)</li>
 * </ul>
 */
public final class QuestionRule {

	public static final double CONFIDENCE_THRESHOLD = 0.7;
	private static final double[] FACTORS = {0.8, 1.0, 1.2};

	private QuestionRule() {
	}

	/** 구성 추출의 구성요소 한 줄. */
	public record Component(String name, String role, double mult) {
	}

	/** 메뉴 하나의 구성요소. */
	public record MenuComponents(String menu, List<Component> components) {
	}

	/** 재료 분류 결과(표 또는 LLM). unit 은 G · ML · EA. */
	public record Info(String kind, String unit, double perServing, double confidence) {
	}

	/** 질문 하나. unit 은 G · ML · EA, options 는 낮은 순 3개(딱 떨어지면 Long, 아니면 Double). */
	public record Question(String ingredient, String text, String unit, List<Number> options, Number defaultValue,
			double confidence, List<String> menus) {
	}

	public static int questionLimit(int menuCount) {
		return menuCount <= 30 ? 10 : (10 * menuCount) / 30;
	}

	static double stepFor(double value, String unit) {
		if ("EA".equals(unit)) {
			return value >= 3 ? 1 : 0.5;
		}
		if (value >= 50) {
			return 10;
		}
		return value >= 10 ? 5 : 1;
	}

	private static double round(double value, double step) {
		return Math.floor(value / step + 0.5) * step;
	}

	/** ai {@code _clean}: 소수 여섯째 자리로 반올림하고, 딱 떨어지면 정수. */
	private static Number clean(double value) {
		BigDecimal rounded = BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_EVEN);
		double d = rounded.doubleValue();
		return d == Math.rint(d) ? (Number) (long) d : (Number) d;
	}

	public static List<Number> makeOptions(double value, String unit) {
		double step = stepFor(value, unit);
		double mid = Math.max(round(value * FACTORS[1], step), step);
		double lo = round(value * FACTORS[0], step);
		double hi = round(value * FACTORS[2], step);
		if (hi <= mid) {
			hi = mid + step;
		}
		if (lo >= mid) {
			lo = mid - step;
		}
		if (lo <= 0) {
			lo = Math.max(round(mid / 2, step), step);
			if (lo >= mid) {
				lo = step;
				mid = 2 * step;
				hi = 3 * step;
			}
		}
		return List.of(clean(lo), clean(mid), clean(hi));
	}

	/** 구성 추출(메뉴별 구성요소) + 재료별 분류 결과 → 질문 목록 (상한까지). infos 에 없는 재료가 있으면 IllegalArgumentException. */
	public static List<Question> build(List<MenuComponents> menus, Map<String, Info> infos) {
		Map<String, List<String>> menusOf = new LinkedHashMap<>();
		for (MenuComponents menu : menus) {
			for (Component c : menu.components()) {
				if ("SAUCE".equals(c.role())) {
					continue;
				}
				List<String> names = menusOf.computeIfAbsent(c.name(), k -> new ArrayList<>());
				if (!names.contains(menu.menu())) {
					names.add(menu.menu());
				}
			}
		}

		List<Question> candidates = new ArrayList<>();
		for (Map.Entry<String, List<String>> entry : menusOf.entrySet()) {
			String name = entry.getKey();
			Info info = infos.get(name);
			if (info == null) {
				throw new IllegalArgumentException("분류 결과 없음: " + name);
			}
			if (!"BOM".equals(info.kind()) || info.confidence() >= CONFIDENCE_THRESHOLD) {
				continue;
			}
			List<Number> options = makeOptions(info.perServing(), info.unit());
			candidates.add(new Question(name, name + " 1인분", info.unit(), options, options.get(1), info.confidence(),
					List.copyOf(entry.getValue())));
		}
		candidates.sort(Comparator.comparingDouble(Question::confidence)
				.thenComparing(q -> -q.menus().size())
				.thenComparing(Question::ingredient));
		return List.copyOf(candidates.subList(0, Math.min(candidates.size(), questionLimit(menus.size()))));
	}
}
