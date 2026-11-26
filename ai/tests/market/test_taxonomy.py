import pytest

from weglee_ai.market.taxonomy import SEED, ItemGroup, Taxonomy, normalize, word_re


@pytest.fixture(scope="module")
def tax():
    return Taxonomy.load(SEED)


def test_seed_loads_and_is_consistent(tax):
    assert len(tax.groups) > 100
    assert all(g.base_unit in ("G", "ML", "EA") for g in tax.groups.values())
    for g in tax.groups.values():
        assert g.name in g.aliases
        if g.parent:
            assert g.parent in tax.groups and not tax.groups[g.parent].comparable
    # 환산값은 기준 단위와 짝이 맞아야 한다: 개 ↔ g 는 ea_weight_g, ml ↔ g 는 density
    egg = tax.get("EGG")
    assert egg.base_unit == "EA" and egg.ea_weight_g == 60
    assert tax.get("SOY_SAUCE").density == 1.2 and tax.get("SOY_SAUCE").base_unit == "ML"


def test_ingredient_name_maps_to_one_group(tax):
    assert tax.group_for_name("계란").code == "EGG"
    assert tax.group_for_name("달걀").code == "EGG"
    assert tax.group_for_name("난액").code == "EGG_LIQUID"
    assert tax.group_for_name("다진 마늘").code == "GARLIC_MINCED"
    assert tax.group_for_name("돼지 목살").code == "PORK_SHOULDER"
    assert tax.group_for_name("없는재료") is None


def test_ambiguous_alias_is_not_mapped():
    groups = [
        ItemGroup("A", "에이", None, "x", True, "G", None, None, ("에이", "등심"), ""),
        ItemGroup("B", "비", None, "x", True, "G", None, None, ("비", "등심"), ""),
    ]
    tax = Taxonomy(groups)
    assert tax.group_for_name("등심") is None
    assert tax.group_for_name("에이").code == "A"


def test_candidates_are_only_a_prefilter(tax):
    assert "EGG" in tax.candidate_codes("새우 계란 볶음밥 250g")  # 가공품도 후보 — 판정은 LLM 몫
    assert tax.candidate_codes("계란찜 1kg") == []  # 별칭 뒤에 한글이 붙은 낱말(계란찜 · 계란말이)은 검색 후보와 같은 기준으로 뺀다
    assert "EGG" in tax.candidate_codes("[KF365] 무항생제 달걀 L(대란) 20구")
    assert "SCALLION" not in tax.candidate_codes("대파함박스테이크 1kg")  # 별칭 뒤에 한글이 붙으면 다른 낱말
    assert "SCALLION" in tax.candidate_codes("흙대파 1단")
    assert tax.candidate_codes("실링용기 뚜껑 100개") == []


def test_unit_conversion(tax):
    assert tax.to_base("EGG", 30, "개") == 30
    assert tax.to_base("EGG", 1800, "g") == 30  # 1.8kg (60g x 30)
    assert tax.to_base("SOY_SAUCE", 1.9 * 1000, "g") == pytest.approx(1583.33, abs=0.01)
    assert tax.to_base("SOY_SAUCE", 1800, "ml") == 1800
    assert tax.to_base("TOFU", 2, "개") is None  # 1모 무게를 정하지 않았다 → 환산 없음
    assert tax.to_base("SCALLION", 1, "EA") is None
    assert tax.to_base("MILK", 1000, "ML") == 1000
    assert tax.to_base("EGG", None, "개") is None and tax.to_base("EGG", 0, "개") is None
    assert tax.to_base("NOPE", 1, "g") is None


def test_base_unit_price(tax):
    assert tax.base_unit_price("EGG", 5690, 30, "개") == pytest.approx(189.67, abs=0.01)
    assert tax.base_unit_price("EGG", 5400, 1800, "g") == 180.0
    assert tax.base_unit_price("TOFU", 1500, 1, "개") is None


def test_helpers():
    assert normalize(" 다진 마늘 ") == "다진마늘"
    assert word_re("대파") == "대파([^가-힣]|$)"


def test_prompt_table_marks_parents(tax):
    table = tax.prompt_table()
    assert "EGG | 계란 |" in table
    assert "PORK | 돼지고기 |" in table and "부모" in table.split("PORK | 돼지고기")[1].split("\n")[0]
