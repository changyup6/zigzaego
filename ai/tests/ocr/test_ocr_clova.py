import base64
import json

import pytest
from ocr_fakes import ENV, FakePost, _chat_ok, _images

from weglee_ai.ocr import clova, methods, runner
from weglee_ai.ocr.http import ApiError

# ---- 요청 모양 ----


def test_clova_request_shape():
    post = FakePost(clova=[(200, json.dumps({"images": [{"fields": [{"inferText": "x", "lineBreak": True}]}]}))])
    clova.clova_general(b"\xff\xd8jpg", "jpg", url=ENV["CLOVA_OCR_URL"], secret="s3cret", post=post)
    url, headers, body = post.calls[0]
    assert url == ENV["CLOVA_OCR_URL"]
    assert headers["X-OCR-SECRET"] == "s3cret"
    assert body["version"] == "V2"
    assert body["enableTableDetection"] is True
    assert body["requestId"] and isinstance(body["timestamp"], int)
    img = body["images"][0]
    assert img["format"] == "jpg" and img["name"]
    assert base64.b64decode(img["data"]) == b"\xff\xd8jpg"


def _cell(r, c, text):
    return {"rowIndex": r, "columnIndex": c, "rowSpan": 1, "columnSpan": 1,
            "cellTextLines": [{"cellWords": [{"inferText": w} for w in text.split()]}]}


def test_clova_table_cells_become_tab_rows():
    resp = {"images": [{
        "fields": [{"inferText": "거래명세서", "lineBreak": True},
                   {"inferText": "미래냉동식품", "lineBreak": True}],
        "tables": [{"cells": [
            _cell(1, 1, "3"), _cell(0, 0, "품목"), _cell(0, 1, "수량"), _cell(1, 0, "베이컨 1kg"),
            _cell(0, 2, "단가"), _cell(1, 2, "11,500"),
        ]}],
    }]}
    post = FakePost(clova=[(200, json.dumps(resp, ensure_ascii=False))])
    lines = clova.clova_general(b"x", "jpg", url=ENV["CLOVA_OCR_URL"], secret="s", post=post).splitlines()
    assert "거래명세서" in lines and "미래냉동식품" in lines
    assert "품목\t수량\t단가" in lines
    assert "베이컨 1kg\t3\t11,500" in lines


def test_clova_fields_inside_table_box_not_repeated():
    box = {"vertices": [{"x": 0, "y": 100}, {"x": 500, "y": 100}, {"x": 500, "y": 300}, {"x": 0, "y": 300}]}

    def field(text, x, y, br):
        return {"inferText": text, "lineBreak": br,
                "boundingPoly": {"vertices": [{"x": x, "y": y}, {"x": x + 20, "y": y},
                                              {"x": x + 20, "y": y + 10}, {"x": x, "y": y + 10}]}}

    resp = {"images": [{
        "fields": [field("거래명세서", 10, 10, True), field("베이컨", 10, 150, True), field("합계", 10, 400, True)],
        "tables": [{"boundingPoly": box, "cells": [_cell(0, 0, "베이컨")]}],
    }]}
    post = FakePost(clova=[(200, json.dumps(resp, ensure_ascii=False))])
    lines = clova.clova_general(b"x", "jpg", url=ENV["CLOVA_OCR_URL"], secret="s", post=post).splitlines()
    assert lines.count("베이컨") == 1
    assert "거래명세서" in lines and "합계" in lines


def test_clova_fields_join_by_linebreak():
    resp = {"images": [{"fields": [
        {"inferText": "생크림", "lineBreak": False}, {"inferText": "1L", "lineBreak": False},
        {"inferText": "28", "lineBreak": True}, {"inferText": "합계", "lineBreak": True},
    ]}]}
    post = FakePost(clova=[(200, json.dumps(resp, ensure_ascii=False))])
    assert clova.clova_general(b"x", "jpg", url="https://clova.x", secret="s", post=post).splitlines() == ["생크림 1L 28", "합계"]


def test_clova_error_status_raises():
    post = FakePost(clova=[(401, "bad secret")])
    with pytest.raises(ApiError):
        clova.clova_general(b"x", "jpg", url="https://clova.x", secret="s", post=post)


# ---- 경계 보강 ----

@pytest.mark.parametrize("result", ["FAILURE", "ERROR"])
def test_clova_infer_result_not_success_raises_and_skips_gms(tmp_path, result):
    resp = {"images": [{"inferResult": result, "message": "Image download failed", "fields": []}]}
    post = FakePost(gms=[_chat_ok()], clova=[(200, json.dumps(resp))])
    with pytest.raises(ApiError) as e:
        methods.method_a(b"img", "jpg", ENV, post=post)
    assert result in str(e.value)
    assert [c[0] for c in post.calls] == [ENV["CLOVA_OCR_URL"]]  # GMS 는 부르지 않는다
    # 실행기에서는 그 장 실패
    stats = runner.run_folder(_images(tmp_path, subs=("a",)), tmp_path / "out", "clova_llm", ENV,
                              post=FakePost(gms=[_chat_ok()], clova=[(200, json.dumps(resp))]), sleep=lambda s: None)
    assert (stats["failed"], stats["done"]) == (1, 0)


def test_clova_infer_result_success_or_missing_is_ok():
    for img in ({"inferResult": "SUCCESS", "fields": [{"inferText": "a", "lineBreak": True}]},
                {"fields": [{"inferText": "a", "lineBreak": True}]}):
        assert clova.clova_text({"images": [img]}) == "a"


def test_clova_table_span_cells_fill_grid():
    def cell(r, c, t, rs=1, cs=1):
        return dict(_cell(r, c, t), rowSpan=rs, columnSpan=cs)

    resp = {"images": [{"inferResult": "SUCCESS", "fields": [], "tables": [{"cells": [
        cell(0, 0, "날짜"), cell(0, 1, "품목"), cell(0, 2, "수량"), cell(0, 3, "단가"),
        cell(1, 0, "10-01", rs=2), cell(1, 1, "베이컨"), cell(1, 2, "3"), cell(1, 3, "11,500"),
        cell(2, 1, "생크림"), cell(2, 2, "2"), cell(2, 3, "5,900"),
        cell(3, 0, "합계", cs=3), cell(3, 3, "46,300"),
    ]}]}]}
    assert clova.clova_text(resp).splitlines() == [
        "날짜\t품목\t수량\t단가",
        "10-01\t베이컨\t3\t11,500",
        "\t생크림\t2\t5,900",
        "합계\t\t\t46,300",
    ]


# ---- 경계 보강 ----

def test_retry_clova_timeout_is_failure_not_retried():
    post = FakePost(clova=[TimeoutError("timed out")])
    with pytest.raises(ApiError):
        clova.clova_general(b"x", "jpg", url="https://clova.x", secret="s", post=post)
    assert len(post.calls) == 1


@pytest.mark.parametrize("resp", [{"images": []}, {"images": [{"inferResult": "SUCCESS", "fields": []}]}, {},
                                  {"images": [{"fields": [{"inferText": " ", "lineBreak": True}]}]}])
def test_clova_empty_text_raises_and_skips_gms(resp):
    post = FakePost(gms=[_chat_ok()], clova=[(200, json.dumps(resp))])
    with pytest.raises(ApiError) as e:
        methods.method_a(b"img", "jpg", ENV, post=post)
    assert "CLOVA 글자 없음" in str(e.value)
    assert [c[0] for c in post.calls] == [ENV["CLOVA_OCR_URL"]]
