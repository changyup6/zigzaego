import pytest

from weglee_ai.onboarding.catalog import load_catalog, lookup


@pytest.mark.parametrize(
    "name, unit",
    [
        ("새우", "EA"),
        ("고추장", "G"),
        ("우유", "ML"),
        ("계란", "EA"),
        ("파스타면", "G"),
        ("대파", "G"),
        ("식용유", "ML"),
        ("치즈", "G"),
        ("베이컨", "G"),
        ("콜라", "EA"),
    ],
)
def test_unit_examples(name, unit):
    item = lookup(name)
    assert item is not None, name
    assert item.unit == unit


def test_alias_matches_ignoring_space_and_case():
    assert lookup("소불고기").name == "불고기"
    assert lookup(" 파스타 면 ").name == "파스타면"
    assert lookup("달걀").name == "계란"
    assert lookup("Coca-Cola").name == "콜라"


@pytest.mark.parametrize("name", ["없는재료", "", "   ", "트러플 오일"])
def test_unknown_is_none(name):
    assert lookup(name) is None


def test_catalog_size_and_values():
    items = load_catalog().items
    assert len(items) >= 50
    for it in items:
        assert it.kind in {"BOM", "ONE_TO_ONE"}
        assert it.unit in {"G", "ML", "EA"}
        assert it.storage_type in {"FROZEN", "AMBIENT", "CHILLED", "FRESH"}
        assert it.per_serving > 0
        assert 0 <= it.confidence <= 1
        if it.kind == "ONE_TO_ONE":
            assert it.unit == "EA" and it.per_serving == 1


def test_names_and_aliases_do_not_collide():
    seen = {}
    for it in load_catalog().items:
        for key in [it.name, *it.aliases]:
            norm = "".join(key.split()).lower()
            assert norm not in seen or seen[norm] == it.name, (key, seen.get(norm), it.name)
            seen[norm] = it.name


# ---- [경계] 유통기한 (-k shelf_life 로 고른다) ----


def test_shelf_life_every_catalog_row_is_integer_at_least_2():
    import csv

    from weglee_ai.onboarding.catalog import DEFAULT_PATH

    with DEFAULT_PATH.open(encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(f))
    assert rows and "shelf_life_days" in rows[0]
    for r in rows:
        raw = (r["shelf_life_days"] or "").strip()
        assert raw.isdigit() and int(raw) >= 2, (r["name"], raw)
    for it in load_catalog().items:
        assert isinstance(it.shelf_life_days, int) and it.shelf_life_days >= 2, it.name


def test_shelf_life_resolve_carries_value_from_catalog_and_classify():
    from weglee_ai.onboarding.catalog import resolve

    classified = [
        {"name": "처음 보는 소스", "kind": "BOM", "unit": "ML", "storageType": "CHILLED",
         "perServing": 30, "confidence": 0.4, "shelfLifeDays": 14},
    ]
    infos = resolve(["계란", "처음 보는 소스"], classified)
    assert infos["계란"].shelf_life_days == lookup("계란").shelf_life_days >= 1
    assert infos["처음 보는 소스"].shelf_life_days == 14
    assert isinstance(infos["처음 보는 소스"].shelf_life_days, int)


@pytest.mark.parametrize("name", ["없는재료", "트러플 오일"])
def test_shelf_life_unknown_name_lookup_none(name):
    assert lookup(name) is None


@pytest.mark.parametrize("bad", ["missing", None, 0, -5, True, 1, "14", 3.5])
def test_shelf_life_bad_llm_value_becomes_none_not_error(bad):
    """빈칸·null·2 미만·정수 아님은 죽지 않고 None (= 보관 방식 표 후보값). 1 은 발주 상한을 0 이하로 만든다."""
    from weglee_ai.onboarding.catalog import resolve

    item = {"name": "처음 보는 소스", "kind": "BOM", "unit": "ML", "storageType": "CHILLED",
            "perServing": 30, "confidence": 0.4}
    if bad != "missing":
        item["shelfLifeDays"] = bad
    infos = resolve(["처음 보는 소스"], [item])
    assert infos["처음 보는 소스"].shelf_life_days is None
    assert infos["처음 보는 소스"].per_serving == 30


def test_shelf_life_missing_does_not_drop_ingredient_from_score(tmp_path):
    """유통기한 칸이 없는 옛 결과 파일도 채점에서 재료를 빼지 않는다."""
    import json
    import subprocess
    import sys
    from pathlib import Path

    result = {
        "menus": [{"menu": "가 메뉴", "kind": "MAIN", "duplicateOf": None,
                   "components": [{"name": "처음 보는 소스", "role": "SAUCE", "mult": 1},
                                  {"name": "계란", "role": "PROTEIN", "mult": 1}]}],
        "items": [{"name": "처음 보는 소스", "kind": "BOM", "unit": "ML", "storageType": "CHILLED",
                   "perServing": 30, "confidence": 0.4}],
    }
    rp = tmp_path / "result.json"
    rp.write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
    tp = tmp_path / "truth.csv"
    tp.write_text("menu,ingredient,qty,unit,aliases\n가 메뉴,처음 보는 소스,30,ml,\n가 메뉴,계란,1,개,\n", encoding="utf-8")
    script = Path(__file__).resolve().parents[1] / "scripts" / "onboarding" / "score.py"
    out = subprocess.run([sys.executable, str(script), str(rp), str(tp)], capture_output=True,
                         text=True, encoding="utf-8", env={**__import__("os").environ, "PYTHONIOENCODING": "utf-8"})
    assert out.returncode == 0, out.stderr
    assert "분류 결과가 없는 재료" not in out.stdout
    assert "쌍 2개" in out.stdout


# ---- [경계] 분류 출력 무효 · 별칭 충돌 ----

_GOOD = {"name": "처음 보는 소스", "kind": "BOM", "unit": "ML", "storageType": "CHILLED",
         "perServing": 30, "confidence": 0.4, "shelfLifeDays": 14}


@pytest.mark.parametrize(
    "patch",
    [
        {"perServing": 0}, {"perServing": -1}, {"perServing": None}, {"perServing": "x"},
        {"perServing": True}, {"perServing": "missing"},
        {"unit": "KG"}, {"kind": "BULK"}, {"storageType": "WARM"},
    ],
)
def test_invalid_classify_item_marked_not_raised(patch):
    from weglee_ai.onboarding.catalog import resolve

    item = dict(_GOOD)
    for k, v in patch.items():
        if v == "missing":
            item.pop(k)
        else:
            item[k] = v
    info = resolve(["처음 보는 소스"], [item])["처음 보는 소스"]
    assert info.invalid_reason
    assert next(iter(patch)) in info.invalid_reason


def test_invalid_reason_none_for_valid_and_catalog_items():
    from weglee_ai.onboarding.catalog import resolve

    infos = resolve(["계란", "처음 보는 소스"], [_GOOD])
    assert infos["계란"].invalid_reason is None
    assert infos["처음 보는 소스"].invalid_reason is None


def test_invalid_item_skipped_by_questions():
    from weglee_ai.onboarding.catalog import resolve
    from weglee_ai.onboarding.questions import build_questions

    bad = {**_GOOD, "name": "트러플 오일", "perServing": None, "confidence": 0.1}
    infos = resolve(["트러플 오일"], [bad])
    ext = {"menus": [{"menu": "가", "kind": "MAIN", "duplicateOf": None,
                      "components": [{"name": "트러플 오일", "role": "OTHER", "mult": 1}]}]}
    assert build_questions(ext, infos) == []


_HEADER = "name,aliases,kind,unit,storage_type,per_serving,confidence,shelf_life_days\n"


def test_collision_alias_on_two_rows_raises(tmp_path):
    p = tmp_path / "cat.csv"
    p.write_text(_HEADER + "대파,파,BOM,G,FRESH,10,0.8,7\n쪽파,파,BOM,G,FRESH,5,0.8,7\n", encoding="utf-8")
    with pytest.raises(ValueError) as e:
        load_catalog(p)
    assert "대파" in str(e.value) and "쪽파" in str(e.value)


def test_collision_name_vs_alias_ignoring_space_raises(tmp_path):
    p = tmp_path / "cat.csv"
    p.write_text(_HEADER + "양파,,BOM,G,FRESH,30,0.8,14\n적양파,양 파,BOM,G,FRESH,30,0.8,14\n", encoding="utf-8")
    with pytest.raises(ValueError) as e:
        load_catalog(p)
    assert "양파" in str(e.value) and "적양파" in str(e.value)


def test_collision_same_row_repeat_is_fine_and_real_catalog_loads(tmp_path):
    p = tmp_path / "cat.csv"
    p.write_text(_HEADER + "대파,대 파;파,BOM,G,FRESH,10,0.8,7\n", encoding="utf-8")
    assert load_catalog(p).lookup("파").name == "대파"
    assert len(load_catalog().items) >= 50
