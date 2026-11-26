from weglee_ai.onboarding.catalog import IngredientInfo
from weglee_ai.onboarding.questions import build_questions, make_options, question_limit


def info(name, conf=0.5, unit="G", per=100.0, kind="BOM"):
    return IngredientInfo(name=name, kind=kind, unit=unit, storage_type="CHILLED", per_serving=per, confidence=conf)


def comp(name, role="PROTEIN", mult=1):
    return {"name": name, "role": role, "mult": mult}


def menu(name, *components):
    return {"menu": name, "kind": "MAIN", "duplicateOf": None, "components": list(components)}


def test_sauce_one_to_one_and_confident_get_no_question():
    extraction = {
        "menus": [
            menu("a", comp("면", "BASE"), comp("크림 소스", "SAUCE"), comp("음료", "DRINK"), comp("소금", "OTHER")),
        ]
    }
    infos = {
        "면": info("면", 0.5),
        "크림 소스": info("크림 소스", 0.3, "ML"),
        "음료": info("음료", 0.2, "EA", 1, "ONE_TO_ONE"),
        "소금": info("소금", 0.7, per=2),
    }
    assert [q.ingredient for q in build_questions(extraction, infos)] == ["면"]


def test_one_question_per_ingredient_across_menus():
    extraction = {"menus": [menu("a", comp("닭"), comp("면", "BASE")), menu("b", comp("닭", mult=2))]}
    qs = build_questions(extraction, {"닭": info("닭", 0.4), "면": info("면", 0.6)})
    assert [q.ingredient for q in qs] == ["닭", "면"]  # 확신 낮은 순
    assert qs[0].menus == ("a", "b")
    assert qs[0].text == "닭 1인분"


def test_options_rounded_by_unit_and_distinct():
    assert make_options(100, "G") == (80, 100, 120)
    assert make_options(80, "ML") == (60, 80, 100)
    assert make_options(5, "EA") == (4, 5, 6)
    assert make_options(25, "G") == (20, 25, 30)  # 10 이상 50 미만 → 간격 5
    assert make_options(1, "EA") == (0.5, 1, 1.5)  # 3 미만 EA → 간격 0.5, 0.8 → 1 겹침 → 가운데 - 간격
    assert make_options(2, "G") == (1, 2, 3)  # 10 미만 → 간격 1
    for value, unit in [(100, "G"), (25, "G"), (1, "EA"), (2, "G"), (3, "EA"), (7, "ML"), (14, "G"), (0.3, "EA")]:
        opts = make_options(value, unit)
        assert len(set(opts)) == 3 and min(opts) > 0
        assert opts[0] < opts[1] < opts[2]


def test_middle_option_is_initial_value_rounded():
    assert make_options(5, "ML") == (4, 5, 6)
    assert make_options(14, "G") == (10, 15, 20)
    assert make_options(1, "EA") == (0.5, 1, 1.5)
    assert make_options(180, "G") == (140, 180, 220)


def test_options_whole_values_are_ints():
    assert all(type(o) is int for o in make_options(180, "G"))
    assert [type(o) for o in make_options(1, "EA")] == [float, int, float]


def test_limit_30_menus_10_and_45_menus_15():
    assert question_limit(8) == 10
    assert question_limit(30) == 10
    assert question_limit(31) == 10  # floor(310 / 30) — 평가 지표 정의 §2.2 "이하"
    assert question_limit(44) == 14
    assert question_limit(45) == 15


def _many(n_menus, n_ingredients):
    menus = [menu(f"m{i}", comp(f"i{i % n_ingredients}")) for i in range(n_menus)]
    menus[0]["components"] += [comp(f"i{j}") for j in range(n_ingredients)]
    infos = {f"i{j}": info(f"i{j}", conf=round(0.01 * j, 2)) for j in range(n_ingredients)}
    return {"menus": menus}, infos


def test_cap_applies_lowest_confidence_first():
    extraction, infos = _many(30, 40)
    qs = build_questions(extraction, infos)
    assert len(qs) == 10
    assert [q.ingredient for q in qs] == [f"i{j}" for j in range(10)]
    extraction, infos = _many(45, 40)
    assert len(build_questions(extraction, infos)) == 15


def test_small_value_middle_is_one_step_options_step_multiples():
    """가운데가 한 간격뿐이면 (간격, 2×, 3×) — Java QuestionRule 과 같은 고정값."""
    assert make_options(1, "G") == (1, 2, 3)
    assert make_options(0.3, "G") == (1, 2, 3)
    assert make_options(0.3, "EA") == (0.5, 1, 1.5)
