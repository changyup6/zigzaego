"""응답 파싱·검증 경계 (완료 조건 [경계] 파싱)."""

import copy
import json

import pytest
from receipt_fakes import MATCH_DOC, NORMALIZE_DOC

from weglee_ai.llm import ParseError, extract_json
from weglee_ai.receipt.parse import parse_match, parse_normalize
from weglee_ai.receipt.prompt import load_prompt

N_SCHEMA = load_prompt("normalize").schema
M_SCHEMA = load_prompt("match").schema
CAT = ["대파", "새우", "식용유"]


def _m(**over):
    doc = copy.deepcopy(MATCH_DOC)
    doc["items"][0].update(over)
    return json.dumps(doc, ensure_ascii=False)


def test_parse_normalize_ok():
    doc = parse_normalize(json.dumps(NORMALIZE_DOC, ensure_ascii=False), N_SCHEMA)
    assert doc == NORMALIZE_DOC


def test_parse_normalize_code_block_and_prose():
    text = "결과입니다.\n```json\n" + json.dumps(NORMALIZE_DOC, ensure_ascii=False) + "\n```\n끝"
    assert parse_normalize(text, N_SCHEMA)["supplier"] == "가짜마트"


@pytest.mark.parametrize("text", ["", "   ", "null", "[]", "not json"])
def test_parse_boundary_empty_or_garbage_is_error(text):
    with pytest.raises(ParseError):
        parse_normalize(text, N_SCHEMA)


@pytest.mark.parametrize("mutate,where", [
    (lambda d: d.pop("supplier"), "supplier"),                      # 필수 칸 없음
    (lambda d: d.update(extra=1), "extra"),                         # 모르는 칸
    (lambda d: d["items"][0].update(qty="2"), "qty"),               # 자료형(문자열 숫자)
    (lambda d: d["items"][0].update(price=True), "price"),          # bool 은 숫자가 아니다
    (lambda d: d.update(items={}), "items"),                        # 목록 아님
    (lambda d: d["items"][0].pop("amount"), "amount"),
])
def test_parse_boundary_schema_violation_is_error(mutate, where):
    d = copy.deepcopy(NORMALIZE_DOC)
    mutate(d)
    with pytest.raises(ParseError) as e:
        parse_normalize(json.dumps(d, ensure_ascii=False), N_SCHEMA)
    assert where in str(e.value)


def test_parse_normalize_bad_date_is_error():
    d = copy.deepcopy(NORMALIZE_DOC)
    d["date"] = "19-11-24"
    with pytest.raises(ParseError):
        parse_normalize(json.dumps(d), N_SCHEMA)


def test_parse_normalize_zero_items_ok():
    doc = parse_normalize(json.dumps({"supplier": None, "date": None, "items": []}), N_SCHEMA)
    assert doc["items"] == []


def test_parse_match_ok_in_line_order():
    doc = copy.deepcopy(MATCH_DOC)
    doc["items"].reverse()
    out = parse_match(json.dumps(doc, ensure_ascii=False), M_SCHEMA, 2, CAT)
    assert [o["ingredient"] for o in out] == ["대파", "새우"]
    assert out[1]["pack"] == {"qty": 35, "unit": "ea"}


def test_parse_boundary_match_duplicate_line_is_error():
    doc = copy.deepcopy(MATCH_DOC)
    doc["items"][1]["line"] = 1
    with pytest.raises(ParseError, match="중복"):
        parse_match(json.dumps(doc, ensure_ascii=False), M_SCHEMA, 2, CAT)


# 범위 밖 line 은 줄이 밀렸을 수 있어 장 전체 오류(계약). 줄 중복 · confidence 등은 그 줄만 missing
@pytest.mark.parametrize("line", [0, 3, -1])
def test_parse_boundary_match_line_out_of_range_is_error(line):
    with pytest.raises(ParseError, match="범위"):
        parse_match(_m(line=line), M_SCHEMA, 2, CAT)


def test_parse_boundary_match_zero_based_lines_is_error():
    """모델이 줄 번호를 0부터 매기면 답이 한 칸씩 밀린 채 저장되면 안 된다."""
    items = [{"line": k, "ingredient": ing, "is_new_periodic": per, "pack": None, "confidence": 0.9, "reason": ""}
             for k, (ing, per) in enumerate([("대파", False), ("새우", False), ("식용유", False)])]
    with pytest.raises(ParseError, match="범위"):
        parse_match(json.dumps({"items": items}, ensure_ascii=False), M_SCHEMA, 3, CAT)


@pytest.mark.parametrize("conf", [-0.01, 1.01, 5])
def test_parse_boundary_match_confidence_out_of_range_line_missing(conf):
    out = parse_match(_m(confidence=conf), M_SCHEMA, 2, CAT)
    assert out[0]["missing"] is True and "confidence" in out[0]["reason"]


@pytest.mark.parametrize("conf", [0, 1, 0.5])
def test_parse_boundary_match_confidence_edges_ok(conf):
    assert parse_match(_m(confidence=conf), M_SCHEMA, 2, CAT)[0]["confidence"] == conf


@pytest.mark.parametrize("pack", [{"qty": 0, "unit": "g"}, {"qty": -5, "unit": "g"}, {"qty": 1, "unit": "kg"},
                                  {"qty": 1}])
def test_parse_boundary_match_bad_pack_line_missing(pack):
    out = parse_match(_m(pack=pack), M_SCHEMA, 2, CAT)
    assert out[0]["missing"] is True and out[1]["ingredient"] == "새우"


def test_parse_match_missing_line_filled_as_no_answer():
    doc = copy.deepcopy(MATCH_DOC)
    doc["items"].pop()
    out = parse_match(json.dumps(doc, ensure_ascii=False), M_SCHEMA, 2, CAT)
    assert out[1]["ingredient"] is None and out[1]["is_new_periodic"] is False and out[1]["pack"] is None
    assert out[1]["confidence"] == 0 and "응답에 없음" in out[1]["reason"]


def test_parse_match_unknown_ingredient_becomes_null():
    out = parse_match(_m(ingredient="청경채"), M_SCHEMA, 2, CAT)
    assert out[0]["ingredient"] is None
    assert "재료표에 없는 이름" in out[0]["reason"]


def test_parse_match_empty_response_is_error():
    with pytest.raises(ParseError):
        parse_match("", M_SCHEMA, 2, CAT)


def test_parse_match_zero_lines_ok():
    assert parse_match(json.dumps({"items": []}), M_SCHEMA, 0, CAT) == []


def test_parse_extract_json_prefers_whole_then_block():
    assert extract_json('{"a": 1}') == {"a": 1}
    assert extract_json('앞 {"a": 2} 뒤') == {"a": 2}
