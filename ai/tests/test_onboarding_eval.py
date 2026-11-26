import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

from weglee_ai.onboarding.eval import Serving, menu_f1, question_count_ok, serving_mape

AI = Path(__file__).resolve().parents[1]
SCORE = AI / "scripts" / "onboarding" / "score.py"

TRUE = [("A", "면"), ("A", "닭"), ("A", "치즈"), ("B", "면"), ("B", "소고기")]
PRED = [("A", "면"), ("A", "닭"), ("A", "베이컨"), ("B", "면"), ("B", "소불고기"), ("B", "양파"), ("C", "x")]
ALIASES = {"소고기": ["소불고기"]}


def test_micro_f1_hand_calc():
    r = menu_f1(PRED, TRUE, ALIASES)
    # C 는 정답에 없어 빠짐. TP 4 (면A 닭A 면B 소고기B) · FP 2 (베이컨 양파) · FN 1 (치즈)
    assert (r.tp, r.fp, r.fn) == (4, 2, 1)
    assert r.precision == pytest.approx(4 / 6)
    assert r.recall == pytest.approx(4 / 5)
    assert r.f1 == pytest.approx(8 / 11)
    assert r.dropped_menus == 1


def test_per_menu_f1_hand_calc():
    r = menu_f1(PRED, TRUE, ALIASES)
    assert r.per_menu["A"] == pytest.approx(2 / 3)
    assert r.per_menu["B"] == pytest.approx(4 / 5)
    assert set(r.per_menu) == {"A", "B"}


def test_alias_is_needed_and_zero_denominator_is_zero():
    assert menu_f1(PRED, TRUE, {}).tp == 3
    r = menu_f1([], TRUE, ALIASES)
    assert (r.precision, r.recall, r.f1) == (0, 0, 0)
    assert r.per_menu["A"] == 0


def test_mape_unit_mismatch_100_and_one_to_one_excluded():
    pred = {
        ("A", "면"): Serving(100, "G"),
        ("A", "닭"): Serving(5, "EA"),
        ("B", "콜라"): Serving(1, "EA", "ONE_TO_ONE"),
        ("B", "소불고기"): Serving(150, "G"),
        ("B", "양파"): Serving(30, "G"),
    }
    true = {
        ("A", "면"): Serving(80, "g"),
        ("A", "닭"): Serving(120, "g"),
        ("B", "콜라"): Serving(2, "개"),
        ("B", "소고기"): Serving(0.2, "kg"),
    }
    r = serving_mape(pred, true, ALIASES)
    # 0.25 · 1.0(개↔g) · 0.25 → 평균 0.5,  가중 (20 + 120 + 50) / (80 + 120 + 200)
    assert r.n_pairs == 3
    assert r.n_unit_mismatch == 1
    assert r.mape == pytest.approx(0.5)
    assert r.weighted == pytest.approx(190 / 400)


def test_mape_zero_truth_only_in_weighted_and_empty_is_none():
    pred = {("A", "소금"): Serving(10, "G"), ("A", "면"): Serving(90, "G")}
    true = {("A", "소금"): Serving(0, "G"), ("A", "면"): Serving(100, "G")}
    r = serving_mape(pred, true)
    assert r.mape == pytest.approx(0.1)
    assert r.weighted == pytest.approx(20 / 100)
    empty = serving_mape({}, true)
    assert empty.mape is None and empty.weighted is None and empty.n_pairs == 0


def test_question_count_ok():
    assert question_count_ok(10, 30)
    assert not question_count_ok(11, 30)
    assert question_count_ok(15, 45)
    assert not question_count_ok(16, 45)
    assert question_count_ok(10, 31)
    assert not question_count_ok(11, 31)
    assert question_count_ok(14, 44)
    assert not question_count_ok(15, 44)


def test_mape_unknown_unit_counts_as_mismatch():
    pred = {("A", "면"): Serving(100, "G"), ("A", "소금"): Serving(5, "G")}
    true = {("A", "면"): Serving(1, "큰술"), ("A", "소금"): Serving(4, "g")}
    r = serving_mape(pred, true)
    # 큰술 은 변환표에 없다 → 단위 불일치 100%,  소금 0.25 → 평균 0.625
    assert r.n_pairs == 2
    assert r.n_unit_mismatch == 1
    assert r.mape == pytest.approx(0.625)
    assert r.unknown_units == ("큰술",)


def _run_score(*args):
    env = {**os.environ, "PYTHONPATH": str(AI / "src"), "PYTHONIOENCODING": "utf-8"}
    return subprocess.run(
        [sys.executable, str(SCORE), *args], capture_output=True, text=True, encoding="utf-8", env=env
    )


def test_score_missing_truth_exits_2(tmp_path):
    result = tmp_path / "result.json"
    result.write_text(json.dumps({"menus": []}), encoding="utf-8")
    missing = tmp_path / "없음.csv"
    p = _run_score(str(result), str(missing))
    assert p.returncode == 2
    assert f"정답 파일 없음: {missing}" in p.stdout + p.stderr


def test_score_missing_result_exits_2(tmp_path):
    truth = tmp_path / "truth.csv"
    truth.write_text("menu,ingredient,qty,unit,aliases\n", encoding="utf-8")
    missing = tmp_path / "없음.json"
    p = _run_score(str(missing), str(truth))
    assert p.returncode == 2
    assert f"결과 파일 없음: {missing}" in p.stdout + p.stderr


def test_score_unknown_unit_warns_not_crash(tmp_path):
    truth = tmp_path / "truth.csv"
    truth.write_text(
        "menu,ingredient,qty,unit,aliases\n치킨 알리오 파스타볼,파스타면,1,큰술,\n", encoding="utf-8"
    )
    demo = AI / "prompts" / "onboarding" / "examples" / "demo_extraction.json"
    p = _run_score(str(demo), str(truth))
    assert p.returncode == 0, p.stdout + p.stderr
    assert "단위 불일치 1개" in p.stdout
    warn = [line for line in p.stdout.splitlines() if "모르는 단위" in line]
    assert len(warn) == 1 and "큰술" in warn[0]


def test_score_end_to_end_on_demo(tmp_path):
    examples = AI / "prompts" / "onboarding" / "examples"
    result = {
        "menus": json.loads((examples / "demo_extraction.json").read_text(encoding="utf-8"))["menus"],
        "items": json.loads((examples / "demo_classify.json").read_text(encoding="utf-8"))["items"],
    }
    rp = tmp_path / "result.json"
    rp.write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
    truth = tmp_path / "truth.csv"
    truth.write_text(
        "menu,ingredient,qty,unit,aliases\n"
        "더블 불고기 파스타볼,파스타면,200,g,\n"
        "더블 불고기 파스타볼,소고기,220,g,불고기\n",
        encoding="utf-8",
    )
    p = _run_score(str(rp), str(truth))
    assert p.returncode == 0, p.stdout + p.stderr
    assert "F1" in p.stdout and "MAPE" in p.stdout and "질문" in p.stdout
    assert "정답에 없는 메뉴 7개" in p.stdout


# ---- [경계] 메뉴 별칭 · 제외 열 · 무효 분류 ----


def test_menu_alias_matches_menu_name_in_f1():
    true = [("불고기 피자", "도우"), ("불고기 피자", "소고기")]
    pred = [("소불고기 피자", "도우"), ("소불고기 피자", "소고기")]
    assert menu_f1(pred, true).tp == 0
    r = menu_f1(pred, true, menu_aliases={"불고기 피자": ["소불고기 피자"]})
    assert (r.tp, r.fp, r.fn, r.dropped_menus) == (2, 0, 0, 0)
    assert r.per_menu == {"불고기 피자": 1.0}


def test_menu_alias_matches_menu_name_in_mape():
    pred = {("소불고기 피자", "도우"): Serving(200, "G")}
    true = {("불고기 피자", "도우"): Serving(250, "g")}
    r = serving_mape(pred, true, menu_aliases={"불고기 피자": ["소불고기 피자"]})
    assert r.n_pairs == 1 and r.mape == pytest.approx(0.2)


def test_excluded_pairs_leave_both_f1_and_mape():
    true = [("A", "면"), ("A", "마늘")]
    pred = [("A", "면"), ("A", "마늘"), ("A", "올리브유")]
    excluded = [("A", "마늘"), ("A", "올리브유")]
    r = menu_f1(pred, true, excluded=excluded)
    assert (r.tp, r.fp, r.fn) == (1, 0, 0)
    ps = {("A", "면"): Serving(90, "G"), ("A", "마늘"): Serving(1, "G")}
    ts = {("A", "면"): Serving(100, "G"), ("A", "마늘"): Serving(50, "G")}
    m = serving_mape(ps, ts, excluded=excluded)
    assert m.n_pairs == 1 and m.mape == pytest.approx(0.1)


def _menus_result(components, items):
    return {
        "menus": [{"menu": "가 메뉴", "kind": "MAIN", "duplicateOf": None, "components": components}],
        "items": items,
    }


def test_score_menu_alias_and_excluded_columns(tmp_path):
    result = {
        "menus": [{"menu": "소불고기 피자", "kind": "MAIN", "duplicateOf": None,
                   "components": [{"name": "계란", "role": "PROTEIN", "mult": 1},
                                  {"name": "마늘", "role": "VEGETABLE", "mult": 1}]}],
        "items": [],
    }
    rp = tmp_path / "result.json"
    rp.write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
    truth = tmp_path / "truth.csv"
    truth.write_text(
        "menu,ingredient,qty,unit,aliases,menu_aliases,excluded\n"
        "불고기 피자,계란,1,개,,소불고기 피자,\n"
        "불고기 피자,마늘,5,g,,소불고기 피자,1\n"
        "불고기 피자,양파,30,g,,소불고기 피자,true\n",
        encoding="utf-8",
    )
    p = _run_score(str(rp), str(truth))
    assert p.returncode == 0, p.stdout + p.stderr
    assert "TP 1 FP 0 FN 0" in p.stdout
    assert "정답에 없는 메뉴 0개" in p.stdout
    assert "쌍 1개" in p.stdout


@pytest.mark.parametrize(
    "patch",
    [{"perServing": 0}, {"perServing": -1}, {"perServing": None}, {"perServing": "x"}, {"unit": "KG"}],
)
def test_score_invalid_classify_item_reported_kept_in_f1_out_of_mape(tmp_path, patch):
    item = {"name": "처음 보는 소스", "kind": "BOM", "unit": "ML", "storageType": "CHILLED",
            "perServing": 30, "confidence": 0.4, "shelfLifeDays": 14, **patch}
    result = _menus_result(
        [{"name": "처음 보는 소스", "role": "SAUCE", "mult": 1}, {"name": "계란", "role": "PROTEIN", "mult": 1}],
        [item],
    )
    rp = tmp_path / "result.json"
    rp.write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
    truth = tmp_path / "truth.csv"
    truth.write_text("menu,ingredient,qty,unit,aliases\n가 메뉴,처음 보는 소스,30,ml,\n가 메뉴,계란,1,개,\n",
                     encoding="utf-8")
    p = _run_score(str(rp), str(truth))
    assert p.returncode == 0, p.stdout + p.stderr
    assert "TP 2 FP 0 FN 0" in p.stdout
    assert "쌍 1개" in p.stdout
    line = [x for x in p.stdout.splitlines() if "분류 결과 무효" in x]
    assert line and "처음 보는 소스" in "\n".join(line)


# ---- [경계] 채점 별칭 충돌 · 전부 제외된 메뉴 ----


def _two_pizza_truth(tmp_path, rows):
    truth = tmp_path / "truth.csv"
    truth.write_text("menu,ingredient,qty,unit,aliases,menu_aliases,excluded\n" + "".join(rows), encoding="utf-8")
    result = {
        "menus": [
            {"menu": "불고기 피자", "kind": "MAIN", "duplicateOf": None,
             "components": [{"name": "계란", "role": "PROTEIN", "mult": 1}]},
            {"menu": "소불고기 피자", "kind": "MAIN", "duplicateOf": None,
             "components": [{"name": "양파", "role": "VEGETABLE", "mult": 1}]},
        ],
        "items": [],
    }
    rp = tmp_path / "result.json"
    rp.write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
    return _run_score(str(rp), str(truth))


_PIZZA_ROWS = ["소불고기 피자,계란,1,개,,소불고기 피자,\n", "불고기 피자,계란,1,개,,소불고기 피자,\n"]


@pytest.mark.parametrize("rows", [_PIZZA_ROWS, _PIZZA_ROWS[::-1]], ids=["order1", "order2"])
def test_collision_menu_alias_equal_to_other_menu_name_exits_2_in_any_order(tmp_path, rows):
    p = _two_pizza_truth(tmp_path, rows)
    assert p.returncode == 2, p.stdout + p.stderr
    assert "Traceback" not in p.stdout + p.stderr
    assert "불고기 피자" in p.stdout and "소불고기 피자" in p.stdout
    assert "재료 구성 F1" not in p.stdout


def test_collision_menu_alias_equal_to_other_menu_name_raises():
    true = [("소불고기 피자", "계란"), ("불고기 피자", "계란")]
    with pytest.raises(ValueError) as e:
        menu_f1([], true, menu_aliases={"불고기 피자": ["소불고기 피자"]})
    assert "불고기 피자" in str(e.value) and "소불고기 피자" in str(e.value)


def test_collision_one_alias_on_two_menus_raises():
    with pytest.raises(ValueError) as e:
        menu_f1([("피자", "치즈")], [("A", "치즈"), ("B", "치즈")], menu_aliases={"A": ["피자"], "B": ["피자"]})
    assert "A" in str(e.value) and "B" in str(e.value)


def test_collision_ingredient_alias_raises_in_f1_and_mape():
    with pytest.raises(ValueError):
        menu_f1([], [("가", "대파"), ("가", "쪽파")], {"대파": ["파"], "쪽파": ["파"]})
    with pytest.raises(ValueError):
        menu_f1([], [("가", "대파"), ("가", "양파")], {"대파": ["양 파"]})
    with pytest.raises(ValueError):
        serving_mape({}, {("가", "대파"): Serving(1, "G")}, {"대파": ["파"], "쪽파": ["파"]})


def test_collision_self_alias_and_repeated_alias_are_fine():
    true = [("불고기 피자", "대파")]
    pred = [("소불고기 피자", "파")]
    r = menu_f1(
        pred, true, {"대파": ["대파", "파", "파"]},
        menu_aliases={"불고기 피자": ["불고기 피자", "소불고기 피자", "소불고기 피자"]},
    )
    assert (r.tp, r.fp, r.fn) == (1, 0, 0)


def test_excluded_all_rows_menu_still_in_truth_and_other_pred_is_fp():
    true = [("N", "계란")]
    pred = [("M", "마늘"), ("M", "양파"), ("N", "계란")]
    r = menu_f1(pred, true, excluded=[("M", "마늘")])
    assert (r.tp, r.fp, r.fn, r.dropped_menus) == (1, 1, 0, 0)


def test_score_excluded_all_rows_menu_counts_fp(tmp_path):
    result = {
        "menus": [
            {"menu": "M", "kind": "MAIN", "duplicateOf": None,
             "components": [{"name": "마늘", "role": "VEGETABLE", "mult": 1},
                            {"name": "양파", "role": "VEGETABLE", "mult": 1}]},
            {"menu": "N", "kind": "MAIN", "duplicateOf": None,
             "components": [{"name": "계란", "role": "PROTEIN", "mult": 1}]},
        ],
        "items": [],
    }
    rp = tmp_path / "result.json"
    rp.write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
    truth = tmp_path / "truth.csv"
    truth.write_text("menu,ingredient,qty,unit,aliases,menu_aliases,excluded\nM,마늘,5,g,,,1\nN,계란,1,개,,,\n",
                     encoding="utf-8")
    p = _run_score(str(rp), str(truth))
    assert p.returncode == 0, p.stdout + p.stderr
    assert "TP 1 FP 1 FN 0" in p.stdout
    assert "정답에 없는 메뉴 0개" in p.stdout


@pytest.mark.parametrize(
    "patch",
    [{"confidence": "missing"}, {"confidence": 1.5}, {"confidence": -0.1},
     {"kind": "BULK"}, {"storageType": "WARM"}, {"perServing": True}],
    ids=["confidence-missing", "confidence-over", "confidence-under", "kind", "storageType", "perServing-true"],
)
def test_score_invalid_classify_other_branches(tmp_path, patch):
    item = {"name": "처음 보는 소스", "kind": "BOM", "unit": "ML", "storageType": "CHILLED",
            "perServing": 30, "confidence": 0.4, "shelfLifeDays": 14}
    for k, v in patch.items():
        if v == "missing":
            item.pop(k)
        else:
            item[k] = v
    result = _menus_result(
        [{"name": "처음 보는 소스", "role": "SAUCE", "mult": 1}, {"name": "계란", "role": "PROTEIN", "mult": 1}],
        [item],
    )
    rp = tmp_path / "result.json"
    rp.write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
    truth = tmp_path / "truth.csv"
    truth.write_text("menu,ingredient,qty,unit,aliases\n가 메뉴,처음 보는 소스,30,ml,\n가 메뉴,계란,1,개,\n",
                     encoding="utf-8")
    p = _run_score(str(rp), str(truth))
    assert p.returncode == 0, p.stdout + p.stderr
    assert "TP 2 FP 0 FN 0" in p.stdout
    assert "쌍 1개" in p.stdout
    line = [x for x in p.stdout.splitlines() if "분류 결과 무효" in x]
    assert line and "처음 보는 소스" in line[0] and next(iter(patch)) in line[0]
