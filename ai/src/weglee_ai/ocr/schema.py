"""OCR 출력 형식(두 방식 공통)·지시문·LLM 답 파싱. 지라 S15P31A308-109."""

import json
import math
import re

from weglee_ai.llm import ParseError, extract_json

# 출력 형식(두 방식 공통)
ITEMS_SCHEMA = {
    "type": "object",
    "properties": {
        "items": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "name": {"type": "string"},
                    "unit": {"type": ["string", "null"]},
                    "qty": {"type": ["number", "null"]},
                    "price": {"type": ["number", "null"]},
                    "amount": {"type": ["number", "null"]},
                },
                "required": ["name", "unit", "qty", "price", "amount"],
                "additionalProperties": False,
            },
        },
    },
    "required": ["items"],
    "additionalProperties": False,
}
RESPONSE_FORMAT = {"type": "json_schema", "json_schema": {"name": "purchase_items", "strict": True, "schema": ITEMS_SCHEMA}}

PROMPT = ("사진/글자에서 매입 품목 줄만 뽑아라. 품목명은 보이는 그대로, 수량·단가는 숫자만, 없으면 null, "
          "합계·세금 줄은 빼라.")


def _num(v):
    """숫자 또는 숫자 문자열("11,500", "34,500원", "₩11,500") → 숫자. 단위가 붙었거나("1 kg") 못 바꾸면 None."""
    if v is None or isinstance(v, bool):
        return None
    if isinstance(v, int):
        return v
    if isinstance(v, float):
        return v if math.isfinite(v) else None
    s = re.sub(r"[,\s원₩￦]", "", str(v))
    try:
        f = float(s)
    except ValueError:
        return None
    if not math.isfinite(f):  # NaN·Infinity
        return None
    return int(f) if f.is_integer() else f


def parse_items(text: str) -> dict:
    """LLM 답 → {"items": [...]}. 깨졌으면 {"items": [], "error": 사유} (채점에서 0줄).

    JSON 꺼내기는 weglee_ai.llm.extract_json 이 한다.
    """
    if not (text or "").strip():  # 예전 문구: 빈 문자열도 json.loads 오류로 "JSON 파싱 실패"
        try:
            json.loads("")
        except json.JSONDecodeError as e:
            return {"items": [], "error": f"JSON 파싱 실패: {e}"}
    try:
        doc = extract_json(text, braces_with_fence=False)
    except ParseError as e:
        return {"items": [], "error": str(e)}
    if not isinstance(doc, dict) or not isinstance(doc.get("items"), list):
        return {"items": [], "error": "items 목록이 없다"}
    items = []
    for it in doc["items"]:
        if not isinstance(it, dict):
            continue
        unit = it.get("unit")
        items.append({
            "name": str(it.get("name") or ""),
            "unit": None if unit in (None, "") else str(unit),
            "qty": _num(it.get("qty")),
            "price": _num(it.get("price")),
            "amount": _num(it.get("amount")),
        })
    return {"items": items}
