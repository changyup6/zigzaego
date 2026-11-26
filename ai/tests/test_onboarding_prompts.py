"""온보딩 프롬프트 · 스키마 · 데모 출력 검사.

스키마는 OpenAI json_schema strict 에 넣을 수 있는 부분집합만 쓴다.
jsonschema 의존성을 늘리지 않으려고 그 부분집합만 검사하는 작은 검사기를 여기 둔다.
"""

import json
from pathlib import Path

import pytest

from weglee_ai.onboarding.catalog import load_catalog

AI = Path(__file__).resolve().parents[1]
PROMPTS = AI / "prompts" / "onboarding"
EXAMPLES = PROMPTS / "examples"

STRICT_KEYWORDS = {"type", "properties", "required", "additionalProperties", "items", "enum", "description"}
STRICT_TYPES = {"object", "array", "string", "number", "integer", "boolean", "null"}


def _load(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def _strict_problems(schema: dict, where: str = "$") -> list[str]:
    """strict 부분집합을 벗어난 곳을 모은다."""
    problems = []
    extra = set(schema) - STRICT_KEYWORDS
    if extra:
        problems.append(f"{where}: 허용 안 된 키 {sorted(extra)}")
    types = schema.get("type")
    types = types if isinstance(types, list) else [types]
    if not types or any(t not in STRICT_TYPES for t in types):
        problems.append(f"{where}: type {schema.get('type')!r}")
    if "object" in types:
        props = schema.get("properties", {})
        if schema.get("additionalProperties") is not False:
            problems.append(f"{where}: additionalProperties 가 false 가 아님")
        if sorted(schema.get("required", [])) != sorted(props):
            problems.append(f"{where}: required 가 properties 전부가 아님")
        for key, sub in props.items():
            problems += _strict_problems(sub, f"{where}.{key}")
    if "array" in types:
        if "items" not in schema:
            problems.append(f"{where}: items 없음")
        else:
            problems += _strict_problems(schema["items"], f"{where}[]")
    return problems


_PY_TYPES = {
    "object": lambda v: isinstance(v, dict),
    "array": lambda v: isinstance(v, list),
    "string": lambda v: isinstance(v, str),
    "number": lambda v: isinstance(v, (int, float)) and not isinstance(v, bool),
    "integer": lambda v: isinstance(v, int) and not isinstance(v, bool),
    "boolean": lambda v: isinstance(v, bool),
    "null": lambda v: v is None,
}


def _validate(instance, schema: dict, where: str = "$") -> list[str]:
    """strict 부분집합 스키마로 값을 검사한다."""
    types = schema["type"] if isinstance(schema["type"], list) else [schema["type"]]
    if not any(_PY_TYPES[t](instance) for t in types):
        return [f"{where}: {instance!r} 는 {types} 가 아님"]
    if "enum" in schema and instance not in schema["enum"]:
        return [f"{where}: {instance!r} 는 enum {schema['enum']} 밖"]
    problems = []
    if isinstance(instance, dict):
        props = schema["properties"]
        missing = set(schema["required"]) - set(instance)
        extra = set(instance) - set(props)
        if missing:
            problems.append(f"{where}: 빠진 칸 {sorted(missing)}")
        if extra:
            problems.append(f"{where}: 없는 칸 {sorted(extra)}")
        for key in set(instance) & set(props):
            problems += _validate(instance[key], props[key], f"{where}.{key}")
    if isinstance(instance, list):
        for i, item in enumerate(instance):
            problems += _validate(item, schema["items"], f"{where}[{i}]")
    return problems


def _sections(md: str) -> dict[str, str]:
    """'## system' · '## user' 제목 아래 본문을 나눈다 (백엔드도 같은 규칙으로 읽는다)."""
    out, current = {}, None
    for line in md.splitlines():
        if line.startswith("## "):
            current = line[3:].strip()
            out[current] = ""
        elif current:
            out[current] += line + "\n"
    return {k: v.strip() for k, v in out.items()}


@pytest.fixture(scope="module")
def extraction():
    return _load(EXAMPLES / "demo_extraction.json")


@pytest.fixture(scope="module")
def menus_by_name(extraction):
    return {m["menu"]: m for m in extraction["menus"]}


@pytest.mark.parametrize("name", ["menu_extraction", "ingredient_classify"])
def test_schema_uses_strict_subset_only(name):
    schema = _load(PROMPTS / f"{name}.schema.json")
    assert schema["type"] == "object"
    assert _strict_problems(schema) == []


def test_strict_checker_catches_loose_schema():
    loose = {"$schema": "x", "type": "object", "properties": {"a": {"type": "string"}}, "required": []}
    assert len(_strict_problems(loose)) == 3


@pytest.mark.parametrize(
    "output, schema",
    [("demo_extraction.json", "menu_extraction"), ("demo_classify.json", "ingredient_classify")],
)
def test_demo_output_passes_schema(output, schema):
    assert _validate(_load(EXAMPLES / output), _load(PROMPTS / f"{schema}.schema.json")) == []


def test_validator_catches_bad_output():
    schema = _load(PROMPTS / "menu_extraction.schema.json")
    bad = {"menus": [{"menu": "x", "kind": "COMBO", "duplicateOf": None, "components": []}]}
    assert _validate(bad, schema) != []


def test_every_demo_menu_present_with_same_name(extraction):
    inputs = [m["name"] for m in _load(EXAMPLES / "demo_menus.json")["menus"]]
    assert len(inputs) == 8
    assert [m["menu"] for m in extraction["menus"]] == inputs


def test_demo_mult_positive_and_classify_values_in_range():
    for menu in _load(EXAMPLES / "demo_extraction.json")["menus"]:
        assert menu["components"], menu["menu"]
        assert all(c["mult"] > 0 for c in menu["components"])
    for item in _load(EXAMPLES / "demo_classify.json")["items"]:
        assert item["perServing"] > 0
        assert 0 <= item["confidence"] <= 1


def test_demo_classify_covers_exactly_uncatalogued(extraction):
    catalog = load_catalog()
    names = {c["name"] for m in extraction["menus"] for c in m["components"]}
    unknown = {n for n in names if catalog.lookup(n) is None}
    classified = [i["name"] for i in _load(EXAMPLES / "demo_classify.json")["items"]]
    assert unknown and sorted(classified) == sorted(unknown)


@pytest.mark.parametrize(
    "name, placeholder",
    [("menu_extraction", "{{menus_json}}"), ("ingredient_classify", "{{ingredients_json}}")],
)
def test_prompt_has_system_and_user_with_placeholder(name, placeholder):
    sections = _sections((PROMPTS / f"{name}.md").read_text(encoding="utf-8"))
    assert sections.get("system") and sections.get("user")
    assert placeholder in sections["user"]
    assert placeholder not in sections["system"]


@pytest.mark.parametrize("name", ["menu_extraction", "ingredient_classify"])
def test_prompt_has_no_store_or_ingredient_hardcoding(name):
    text = (PROMPTS / f"{name}.md").read_text(encoding="utf-8").replace(" ", "")
    demo_menus = [m["name"] for m in _load(EXAMPLES / "demo_menus.json")["menus"]]
    words = {"위글리", "파스타볼", *demo_menus}
    for item in load_catalog().items:
        words |= {item.name, *item.aliases}
    words = {w.replace(" ", "") for w in words}
    found = sorted(w for w in words if len(w) >= 2 and w in text)
    assert found == []


# ---- [경계] 조합 규칙 (-k "double or sauce or set_menu" 로 이 셋만 고른다) ----


def test_double_menu_protein_mult_2(menus_by_name):
    doubles = [m for name, m in menus_by_name.items() if "더블" in name]
    assert doubles
    for menu in doubles:
        proteins = [c for c in menu["components"] if c["role"] == "PROTEIN"]
        assert proteins and all(c["mult"] == 2 for c in proteins)
        assert all(c["mult"] == 1 for c in menu["components"] if c["role"] in {"BASE", "SAUCE"})


SAUCE_RAW = {"마늘", "올리브유", "생크림", "우유", "계란", "토마토", "고추장", "들기름", "버터", "고춧가루", "간장", "설탕"}


def test_sauce_single_line_without_raw_materials(menus_by_name):
    with_sauce = 0
    for menu in menus_by_name.values():
        sauces = [c for c in menu["components"] if c["role"] == "SAUCE"]
        assert len(sauces) <= 1, menu["menu"]
        with_sauce += bool(sauces)
        assert all(c["name"].endswith("소스") and c["mult"] == 1 for c in sauces)
        names = {c["name"] for c in menu["components"]}
        assert not names & SAUCE_RAW, (menu["menu"], names & SAUCE_RAW)
    assert with_sauce >= 5


def test_set_menu_lists_component_ingredients(menus_by_name):
    sets = [m for m in menus_by_name.values() if m["kind"] == "SET"]
    assert sets
    singles = [m for m in menus_by_name.values() if m["kind"] == "MAIN"]
    for menu in sets:
        names = {c["name"] for c in menu["components"]}
        assert not names & set(menus_by_name), "세트가 메뉴 이름을 재료로 적었다"
        assert {"BASE", "SIDE", "DRINK"} <= {c["role"] for c in menu["components"]}
        # 세트의 각 재료는 같은 목록의 단품 메뉴에서 온 것이거나(BASE · SIDE) 음료다
        for c in menu["components"]:
            if c["role"] != "DRINK":
                assert any(c["name"] in {s["name"] for s in m["components"]} for m in singles), c["name"]


# ---- [경계] 유통기한 (-k shelf_life 로 고른다) ----


def test_shelf_life_classify_schema_requires_integer_field():
    item = _load(PROMPTS / "ingredient_classify.schema.json")["properties"]["items"]["items"]
    assert "shelfLifeDays" in item["required"]
    assert item["properties"]["shelfLifeDays"]["type"] == "integer"
    assert _validate(1.5, item["properties"]["shelfLifeDays"]) != []


def test_shelf_life_demo_classify_passes_schema_with_values():
    schema = _load(PROMPTS / "ingredient_classify.schema.json")
    demo = _load(EXAMPLES / "demo_classify.json")
    assert _validate(demo, schema) == []
    for item in demo["items"]:
        days = item["shelfLifeDays"]
        assert isinstance(days, int) and not isinstance(days, bool) and days >= 1, item["name"]
