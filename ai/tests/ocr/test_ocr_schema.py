from ocr_fakes import ITEMS_JSON

from weglee_ai.ocr import schema

# ---- 파싱 ----


def test_parse_broken_json_gives_empty_items_with_error():
    doc = schema.parse_items('{"items": [{"name": "베이컨"')
    assert doc["items"] == [] and doc["error"]


def test_parse_missing_items_key():
    doc = schema.parse_items('{"rows": []}')
    assert doc["items"] == [] and "items" in doc["error"]


def test_parse_strips_code_fence():
    doc = schema.parse_items("```json\n" + ITEMS_JSON + "\n```")
    assert "error" not in doc
    assert doc["items"] == [{"name": "베이컨 1kg", "unit": "팩", "qty": 3, "price": 11500, "amount": 34500}]


def test_parse_number_strings():
    doc = schema.parse_items('{"items": [{"name": "베이컨", "qty": "3", "price": "11,500", "amount": "34,500원", "unit": null}]}')
    it = doc["items"][0]
    assert (it["qty"], it["price"], it["amount"], it["unit"]) == (3, 11500, 34500, None)
    it2 = schema.parse_items('{"items": [{"name": "a", "qty": "1.5", "price": "없음"}]}')["items"][0]
    assert it2["qty"] == 1.5 and it2["price"] is None


# ---- 경계 보강 ----

def test_parse_nan_and_infinity_are_none():
    for v in ('"NaN"', '"Infinity"', '"-Infinity"', "NaN", "Infinity"):
        it = schema.parse_items('{"items": [{"name": "a", "qty": ' + v + ', "price": 1}]}')["items"][0]
        assert it["qty"] is None, v
    assert schema.parse_items('{"items": [{"name": "a", "qty": "1e3"}]}')["items"][0]["qty"] == 1000


def test_parse_won_sign_removed_but_unit_suffix_is_none():
    for v, want in (('"₩11,500"', 11500), ('"￦ 7,000"', 7000), ('"1 kg"', None), ('"3개"', None)):
        it = schema.parse_items('{"items": [{"name": "a", "price": ' + v + '}]}')["items"][0]
        assert it["price"] == want, v


# ---- 경계 보강 ----

def test_parse_code_block_with_text_around():
    doc = schema.parse_items('다음은 결과입니다.\n```json\n' + ITEMS_JSON + '\n```\n확인 바랍니다.')
    assert "error" not in doc and doc["items"][0]["name"] == "베이컨 1kg"


def test_parse_braces_with_text_around():
    doc = schema.parse_items("결과: " + ITEMS_JSON + " 이상입니다.")
    assert "error" not in doc and doc["items"][0]["qty"] == 3


def test_parse_still_errors_without_json():
    for s in ("표를 못 찾았습니다", "결과: {이건 JSON 아님}", "```\n아님\n```"):
        doc = schema.parse_items(s)
        assert doc["items"] == [] and doc["error"], s


def test_parse_code_block_present_ignores_braces_outside_it():
    # 코드 블록이 있으면 그 안만 본다(그 밖의 {…} 후보는 시도하지 않는다) — 리팩터 전 동작
    out = schema.parse_items('```\nnot json\n```\n{"items":[{"name":"a","qty":1}]}')
    assert out["items"] == []
    assert out["error"].startswith("JSON 파싱 실패: ")


def test_parse_empty_response_error_message_is_json_parse_failure():
    assert schema.parse_items("")["error"].startswith("JSON 파싱 실패: ")
