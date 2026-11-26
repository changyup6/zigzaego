import json

import pytest

from weglee_ai.market import classify as c
from weglee_ai.market.taxonomy import Taxonomy
from weglee_ai.receipt.prompt import strict_schema_problems


@pytest.fixture(scope="module")
def tax():
    return Taxonomy.load()


@pytest.fixture(scope="module")
def prompt():
    return c.load_prompt()


def test_prompt_and_schema_load(prompt):
    assert "{{groups}}" in prompt.system and prompt.placeholders() == {"products"}
    assert prompt.schema["properties"]["items"]["items"]["required"] == [
        "name", "item_group_code", "form", "qty", "qty_unit", "confidence", "reason"]
    assert c.response_format(prompt)["json_schema"]["strict"] is True
    assert strict_schema_problems(prompt.schema) == []


def test_build_messages_lists_products_and_groups(prompt, tax):
    msgs = c.build_messages(prompt, ["계란 30구", "계란찜 1kg"], tax)
    assert msgs[0]["role"] == "system" and "EGG | 계란 |" in msgs[0]["content"] and "{{groups}}" not in msgs[0]["content"]
    assert msgs[1]["content"].endswith("1. 계란 30구\n2. 계란찜 1kg")


def _item(name, code=None, form="UNKNOWN", qty=None, unit=None, conf=0.9, reason="r"):
    return {"name": name, "item_group_code": code, "form": form, "qty": qty, "qty_unit": unit, "confidence": conf, "reason": reason}


def test_parse_labels_validates_and_normalizes(tax):
    names = ["계란 30구", "계란찜 1kg", "에그 트레이", "이상한것", "진간장 1.8L", "무언가 원물"]
    content = json.dumps({"items": [
        _item("계란 30구", "EGG", "RAW", 30, "EA", 0.95),
        _item("계란찜 1kg", "EGG", "PROCESSED", 1000, "G"),  # 가공품인데 코드 → 코드 지움
        _item("에그 트레이", None, "NONFOOD"),
        _item("이상한것", "NOPE_CODE", "RAW"),  # 표에 없는 코드 → UNKNOWN
        _item("진간장 1.8L", "soy_sauce", "raw", 1800, "ml", 1.7),  # 소문자 · 확신 1 초과
        _item("무언가 원물", None, "RAW"),  # 원물이라면서 품목군 없음 → UNKNOWN
    ]})
    labels = c.parse_labels(content, names, tax)
    assert [(x.item_group_code, x.form) for x in labels] == [
        ("EGG", "RAW"), (None, "PROCESSED"), (None, "NONFOOD"), (None, "UNKNOWN"), ("SOY_SAUCE", "RAW"), (None, "UNKNOWN")]
    assert labels[0].qty == 30 and labels[0].qty_unit == "EA"
    assert labels[4].qty == 1800 and labels[4].qty_unit == "ML" and labels[4].confidence == 1.0
    assert labels[1].qty_unit == "G" and labels[1].label_version == c.LABEL_VERSION


@pytest.mark.parametrize("content", [
    "not json",
    json.dumps({"items": [_item("a")]}),  # 항목 수 다름
    json.dumps({"items": [_item("a"), _item("x")]}),  # 이름 다름
    json.dumps({"items": [_item("a"), _item("b", form="COOKED")]}),  # form 밖
])
def test_parse_labels_rejects_bad_shape(content, tax):
    with pytest.raises(ValueError):
        c.parse_labels(content, ["a", "b"], tax)


def test_classify_splits_broken_batch_and_counts_usage(tax, prompt):
    calls = []

    def chat(messages, fmt):
        names = c._LINE.findall(messages[-1]["content"])
        calls.append(len(names))
        if "깨짐" in names and len(names) > 1:
            return json.dumps({"items": []}), {"prompt_tokens": 10, "completion_tokens": 1}
        if names == ["깨짐"]:
            return "garbage", {"prompt_tokens": 1, "completion_tokens": 1}
        items = [_item(n, "EGG", "RAW") for n in names]
        return json.dumps({"items": items}, ensure_ascii=False), {"prompt_tokens": 10, "completion_tokens": 5}

    out = list(c.classify(["계란 a", "계란 b", "깨짐", "계란 d"], chat, tax, prompt, batch_size=4))
    assert len(out) == 1
    labels, usage = out[0]
    assert [x.product_name for x in labels] == ["계란 a", "계란 b", "깨짐", "계란 d"]
    assert labels[2].form == "UNKNOWN" and labels[2].reason.startswith("응답 오류")
    assert labels[0].item_group_code == "EGG" and labels[3].item_group_code == "EGG"
    assert usage["requests"] == 5 and usage["retries"] == 3 and usage["prompt_tokens"] == 10 + 10 + 10 + 1 + 10
    assert calls == [4, 2, 2, 1, 1]


def test_fake_chat_round_trip(tax, prompt):
    labels, usage = next(c.classify(["흙대파 1단", "실링용기 뚜껑"], c.fake_chat(tax), tax, prompt))
    assert labels[0].item_group_code == "SCALLION" and labels[0].form == "RAW" and labels[0].reason == "dry-run"
    assert labels[1].item_group_code is None and labels[1].form == "UNKNOWN"
    assert usage["requests"] == 1 and usage["prompt_tokens"] > 0


def test_batches():
    assert list(c.batches(["a", "b", "c"], 2)) == [["a", "b"], ["c"]]
    assert list(c.batches([], 2)) == []


def test_merge_labels_replaces_by_name_and_keeps_the_rest():
    old = {"a": {"product_name": "a", "label_version": "v1", "form": "RAW"}, "b": {"product_name": "b", "label_version": "v1", "form": "RAW"}}
    new = [c.Label("a", "EGG", "RAW", None, None, 0.9, "다시 분류")]
    out = c.merge_labels(old, new)
    assert out is old and out["a"]["label_version"] == c.LABEL_VERSION and out["a"]["item_group_code"] == "EGG"
    assert out["b"]["label_version"] == "v1"  # 아직 다시 분류하지 못한 이름의 이전 라벨은 남는다
