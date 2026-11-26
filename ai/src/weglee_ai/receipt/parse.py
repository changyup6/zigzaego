"""LLM 답 → 검증한 JSON. 지라 S15P31A308-110.

strict 스키마를 걸어도 모델 · 프록시에 따라 어긋난 답이 올 수 있어, 받은 쪽에서 스키마 칸 · 자료형을 다시 본다.
정형화 답이 어긋나면 ParseError (그 장은 실패로 남기고 다시 돌릴 수 있게 출력 파일을 쓰지 않는다).
매칭 답은 줄 하나가 어긋나면 그 줄만 답 없음으로 둔다.
"""

import json
import math
import re
from datetime import date

from weglee_ai.llm import ParseError, extract_json

DATE = re.compile(r"\d{4}-\d{2}-\d{2}")
# 매칭 답에 빠진 줄. missing=True 는 채점에서 틀림으로 센다(정답이 null·false 여도).
NO_ANSWER = {"ingredient": None, "is_new_periodic": False, "pack": None, "confidence": 0, "reason": "응답에 없음",
             "missing": True}


def _type_ok(v, t: str) -> bool:
    if t == "null":
        return v is None
    if t == "boolean":
        return isinstance(v, bool)
    if t == "integer":
        return isinstance(v, int) and not isinstance(v, bool)
    if t == "number":
        return isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v)
    if t == "string":
        return isinstance(v, str)
    if t == "array":
        return isinstance(v, list)
    if t == "object":
        return isinstance(v, dict)
    return False


def validate(v, schema: dict, path: str = "$") -> None:
    """스키마의 type · anyOf · enum · properties · required · additionalProperties · items 만 본다."""
    if "anyOf" in schema:
        errs = []
        for sub in schema["anyOf"]:
            try:
                validate(v, sub, path)
                return
            except ParseError as e:
                errs.append(str(e))
        raise ParseError(f"{path}: 어느 형식에도 맞지 않는다 ({'; '.join(errs)})")
    types = schema.get("type")
    types = types if isinstance(types, list) else [types]
    if not any(_type_ok(v, t) for t in types):
        raise ParseError(f"{path}: 자료형이 {'/'.join(map(str, types))} 가 아니다 ({json.dumps(v, ensure_ascii=False)[:40]})")
    if "enum" in schema and v not in schema["enum"]:
        raise ParseError(f"{path}: {v!r} 는 {schema['enum']} 중 하나가 아니다")
    if isinstance(v, dict) and "properties" in schema:
        props = schema["properties"]
        for k in schema.get("required") or []:
            if k not in v:
                raise ParseError(f"{path}.{k}: 필수 칸이 없다")
        if schema.get("additionalProperties") is False:
            extra = sorted(set(v) - set(props))
            if extra:
                raise ParseError(f"{path}.{extra[0]}: 모르는 칸")
        for k, sub in props.items():
            if k in v:
                validate(v[k], sub, f"{path}.{k}")
    if isinstance(v, list) and "items" in schema:
        for n, it in enumerate(v):
            validate(it, schema["items"], f"{path}[{n}]")


def parse_normalize(text: str, schema: dict) -> dict:
    """정형화 답 → {"supplier", "date", "items"}. 날짜는 YYYY-MM-DD 또는 null."""
    doc = extract_json(text)
    validate(doc, schema)
    d = doc["date"]
    if d is not None:
        if not DATE.fullmatch(d):
            raise ParseError(f"$.date: YYYY-MM-DD 가 아니다 ({d!r})")
        try:
            date.fromisoformat(d)
        except ValueError as e:
            raise ParseError(f"$.date: 달력에 없는 날이다 ({d!r})") from e
    return doc


def _line_problem(it: dict, item_schema: dict, names: set) -> str | None:
    """줄 하나의 답이 스키마 · 규칙에 맞으면 None, 아니면 이유. 재료표에 없는 이름은 여기서 보지 않는다(null 로 바꾼다)."""
    try:
        validate(it, item_schema, f"line {it['line']}")
    except ParseError as e:
        return str(e)
    line = it["line"]
    if not 0 <= it["confidence"] <= 1:
        return f"line {line}: confidence {it['confidence']} 가 0~1 밖이다"
    pack = it["pack"]
    if pack is not None and not pack["qty"] > 0:
        return f"line {line}: pack 수량이 양수가 아니다 ({pack['qty']})"
    ing = it["ingredient"]
    if ing is not None and ing in names:
        if it["is_new_periodic"]:
            return f"line {line}: ingredient {ing!r} 가 있는데 is_new_periodic 이 true 다"
    return None


def _pack_unit_problem(r: dict, units: dict | None) -> str | None:
    """재료표 재료의 pack.unit 이 그 재료 단위와 다르면 이유. 이때는 pack 만 버리고 재료 답은 살린다(매칭 정확도 정의)."""
    ing, pack = r["ingredient"], r["pack"]
    want = (units or {}).get(ing) if ing is not None else None
    if pack is not None and want and pack["unit"] != want:
        return f"pack 단위 {pack['unit']!r} 가 재료표 {ing} 의 단위 {want!r} 와 달라 포장 버림"
    return None


def parse_match(text: str, schema: dict, n_lines: int, catalog_names, *, units: dict | None = None) -> list[dict]:
    """매칭 답 → 입력 줄 순서대로 n_lines 개의 {"ingredient", "is_new_periodic", "pack", "confidence", "reason"}.

    답 전체가 JSON 이 아니거나 {"items": [...]} 모양이 아니면, 범위 밖(또는 정수 아닌) line 이 하나라도 있으면
    (0부터 매겨 답이 밀렸을 수 있다), 또는 입력이 1줄 이상인데 쓸 수 있는 줄 답이 하나도 없으면 ParseError.
    줄 하나의 오류(스키마 · confidence 0~1 밖 · pack 수량 0 이하 · line 중복 · ingredient 가 있는데 is_new_periodic true)는
    그 줄만 NO_ANSWER(missing=True, reason 에 이유)로 두고 나머지 줄은 살린다.
    units 를 주면 재료표 단위와 다른 pack.unit 은 그 줄의 pack 만 null 로 버리고 재료 답은 살린다(reason 앞에 적는다).
    답에 빠진 줄도 NO_ANSWER(채점에서 틀림).
    재료표에 없는 이름은 null 로 바꾸고 reason 앞에 적는다(채점에서는 null 제안으로 센다).
    """
    doc = extract_json(text)
    if not isinstance(doc, dict) or set(doc) != {"items"} or not isinstance(doc["items"], list):
        raise ParseError('$: 매칭 답이 {"items": [...]} 모양이 아니다')
    if n_lines and not doc["items"]:
        raise ParseError(f"입력 {n_lines}줄인데 매칭 답 items 가 비었다")
    item_schema = schema["properties"]["items"]["items"]
    names = set(catalog_names)
    out: list[dict | None] = [None] * n_lines
    seen: set[int] = set()
    problems = []
    for it in doc["items"]:
        line = it.get("line") if isinstance(it, dict) else None
        if not isinstance(line, int) or isinstance(line, bool) or not 1 <= line <= n_lines:
            raise ParseError(f"line {line!r} 이 범위(1~{n_lines}) 밖이다")
    for it in doc["items"]:
        line = it["line"]
        if line in seen:
            out[line - 1] = dict(NO_ANSWER, reason=f"답 검증 실패: line {line} 중복")
            problems.append(f"line {line} 중복")
            continue
        seen.add(line)
        why = _line_problem(it, item_schema, names)
        if why:
            out[line - 1] = dict(NO_ANSWER, reason=f"답 검증 실패: {why}")
            problems.append(why)
            continue
        r = {k: it[k] for k in ("ingredient", "is_new_periodic", "pack", "confidence", "reason")}
        if r["ingredient"] is not None and r["ingredient"] not in names:
            r["reason"] = f"재료표에 없는 이름 {r['ingredient']!r} → null; {r['reason']}"
            r["ingredient"] = None
        unit_why = _pack_unit_problem(r, units)
        if unit_why:
            r["reason"] = f"{unit_why}; {r['reason']}"
            r["pack"] = None
        out[line - 1] = r
    if n_lines and all(r is None or r.get("missing") for r in out):
        raise ParseError(f"쓸 수 있는 매칭 줄 답이 없다 ({'; '.join(problems)[:300]})")
    return [dict(NO_ANSWER) if r is None else r for r in out]
