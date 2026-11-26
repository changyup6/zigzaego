import json

import pytest
from receipt_fakes import write_catalog

from weglee_ai.receipt.catalog import catalog_json, load_catalog
from weglee_ai.receipt.prompt import PROMPTS_DIR, load_prompt, split_sections, strict_schema_problems

NAMES = ("normalize", "match")


def test_prompt_match_keeps_noodle_head_noun_rules():
    # 사용자 규칙(10/20): 면 제품은 상품명 뒷말(종류어)로 재료를 고른다 — 규칙 줄이 지워지면 잡는다
    s = load_prompt("match").system
    for phrase in ("뒷말 규칙", "`라면 사리`", "이름에 냉면이 들어 있으면 냉면", "`중화면`", "쌀국수는 쌀면", "컵라면", "145g*5 → 5 ea",
                   "칼국수 · 쌀국수 · 메밀국수 · 막국수 · 쫄면은 각자 다른 종류", 
                   "그냥 국수(옛날국수 · 잔치국수)만 `소면`", "별칭 '국수' 와 겹쳐도 소면이 아니다"):
        assert phrase in s, phrase
    assert "145g*5 → 725 g" not in s and "봉지 인스턴트" not in s


@pytest.mark.parametrize("name", NAMES)
def test_prompt_loads_system_and_user(name):
    p = load_prompt(name)
    assert p.name == name
    assert p.system and p.user_template
    assert "## system" not in p.system and "## user" not in p.user_template
    assert p.system == p.system.strip() and p.user_template == p.user_template.strip()


@pytest.mark.parametrize("name", NAMES)
def test_prompt_schema_is_strict(name):
    p = load_prompt(name)
    assert strict_schema_problems(p.schema) == []
    rf = p.response_format()
    assert rf == {"type": "json_schema", "json_schema": {"name": name, "strict": True, "schema": p.schema}}


def test_prompt_placeholders_all_filled(tmp_path):
    cat = load_catalog(write_catalog(tmp_path))
    n = load_prompt("normalize")
    m = load_prompt("match")
    assert "{{" not in n.user(ocr_text="대파 1단 3,000")
    filled = m.user(catalog_json=catalog_json(cat), lines_json=json.dumps([{"line": 1, "name": "대파"}]))
    assert "{{" not in filled and "대파" in filled


def test_prompt_unknown_placeholder_raises():
    with pytest.raises(KeyError):
        load_prompt("normalize").user(없는자리="x")


def test_prompt_missing_placeholder_value_raises():
    with pytest.raises(ValueError):
        load_prompt("match").user(catalog_json="[]")  # lines_json 빠짐


def test_prompt_split_crlf_and_order():
    sys_, user = split_sections("# 제목\r\n설명\r\n\r\n## system\r\n  지시  \r\n\r\n## user\r\n틀 {{x}}\r\n")
    assert sys_ == "지시" and user == "틀 {{x}}"
    with pytest.raises(ValueError):
        split_sections("## user\n틀\n## system\n지시\n")
    with pytest.raises(ValueError):
        split_sections("## system\n지시만\n")
    # 제목이 줄 머리에 혼자 있어야 한다("## systemX" 는 제목이 아니다)
    with pytest.raises(ValueError):
        split_sections("## systemX\n지시\n## user\n틀\n")


def test_prompt_files_live_in_receipt_dir():
    for name in NAMES:
        assert (PROMPTS_DIR / f"{name}.md").is_file()
        assert (PROMPTS_DIR / f"{name}.schema.json").is_file()


def test_prompt_strict_problems_detects_loose_schema():
    loose = {"type": "object", "properties": {"a": {"type": "string"}, "b": {"type": "number"}},
             "required": ["a"]}
    probs = strict_schema_problems(loose)
    assert any("additionalProperties" in p for p in probs)
    assert any("required" in p for p in probs)


def test_prompt_catalog_loads_aliases_and_unit(tmp_path):
    cat = load_catalog(write_catalog(tmp_path))
    assert [c["name"] for c in cat] == ["대파", "새우", "식용유"]
    assert cat[1] == {"name": "새우", "unit": "ea", "aliases": ["칵테일새우", "흰다리새우", "깐새우", "생새우"]}
    assert json.loads(catalog_json(cat))[0]["unit"] == "g"


def test_prompt_match_all_nonfood_is_periodic():
    # 설계 4.1 비식품 = 주기형, 사용자 10/26 "비식품 전부 주기형": 영업용 · 개인 생활용품 · 주방 기구 · 가전 모두 true.
    # false(재료 아님)로 남는 것은 매칭 안 된 식품(개인 간식 · 과자 · 음료 · 술 · 완제품)뿐.
    s = load_prompt("match").system
    lines = s.splitlines()
    true_line = next(ln for ln in lines if ln.strip().startswith("- true:"))
    false_line = next(ln for ln in lines if ln.strip().startswith("- false:"))
    for w in ("세제", "수세미", "위생장갑", "봉투", "일회용 용기", "키친타월", "샴푸", "바디워시", "칫솔", "생리대",
              "방향제", "옷", "양말", "쟁반", "프라이팬", "가전", "비식품은 전부"):
        assert w in true_line, w
    for w in ("세제", "샴푸", "칫솔", "옷", "양말", "가전", "프라이팬", "쟁반", "생활용품"):
        assert w not in false_line, w
    for w in ("과자", "아이스크림", "술", "식품"):
        assert w in false_line, w
    assert "설계 4.1" in s and "비식품 전부 주기형" in s
    assert "원문이 정하지 않은" not in s
