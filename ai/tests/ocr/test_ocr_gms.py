import pytest
from ocr_fakes import ITEMS_JSON, FakePost, _chat_ok

from weglee_ai.ocr import gms, schema
from weglee_ai.ocr.http import ApiError

# ---- 요청 모양 ----


def test_gms_request_shape_bearer_and_json_schema():
    post = FakePost(gms=[_chat_ok()])
    out = gms.gms_chat([{"role": "user", "content": "x"}], base_url="https://gms.example/v1/", key="k1",
                       model="m1", post=post)
    assert out == ITEMS_JSON
    url, headers, body = post.calls[0]
    assert url == "https://gms.example/v1/chat/completions"
    assert headers["Authorization"] == "Bearer k1"
    assert body["model"] == "m1"
    rf = body["response_format"]
    assert rf["type"] == "json_schema"
    assert rf["json_schema"]["schema"] == schema.ITEMS_SCHEMA


# ---- 재시도 ----

def test_retry_429_twice_then_success_on_third_call():
    post = FakePost(gms=[(429, "busy"), (429, "busy"), _chat_ok()])
    waits = []
    out = gms.gms_chat([{"role": "user", "content": "x"}], base_url="https://gms.x", key="k", model="m",
                       post=post, sleep=waits.append)
    assert out == ITEMS_JSON
    assert len(post.calls) == 3
    assert waits == [1, 2]


def test_retry_5xx_and_connection_error_then_gives_up_after_three_retries():
    post = FakePost(gms=[(503, "down"), OSError("conn reset"), (500, "x"), (502, "x")])
    waits = []
    with pytest.raises(ApiError):
        gms.gms_chat([], base_url="https://gms.x", key="k", model="m", post=post, sleep=waits.append)
    assert len(post.calls) == 4
    assert waits == [1, 2, 4]


def test_retry_none_for_400():
    post = FakePost(gms=[(400, "bad request"), _chat_ok()])
    waits = []
    with pytest.raises(ApiError) as e:
        gms.gms_chat([], base_url="https://gms.x", key="k", model="m", post=post, sleep=waits.append)
    assert len(post.calls) == 1 and waits == []
    assert "400" in str(e.value)


# ---- 경계 보강 ----

@pytest.mark.parametrize("body", ['{"choices": []}', "[]", "null", '{"choices": [{"message": {}}]}',
                                  '{"choices": [{"message": {"content": null}}]}', "not json", '{"error": "x"}'])
def test_retry_gms_200_bad_shape_raises_api_error(body):
    post = FakePost(gms=[(200, body)])
    with pytest.raises(ApiError):
        gms.gms_chat([], base_url="https://gms.x", key="k", model="m", post=post, sleep=lambda s: None)
    assert len(post.calls) == 1


# ---- 경계 보강 ----

def test_retry_timeout_is_not_retried():
    import urllib.error

    for err in (TimeoutError("timed out"), TimeoutError("timed out"), urllib.error.URLError(TimeoutError("t"))):
        post = FakePost(gms=[err, _chat_ok()])
        waits = []
        with pytest.raises(ApiError) as e:
            gms.gms_chat([], base_url="https://gms.x", key="k", model="m", post=post, sleep=waits.append)
        assert len(post.calls) == 1 and waits == [], err
        assert "시간 초과" in str(e.value)


def test_retry_connection_refused_still_retried():
    post = FakePost(gms=[ConnectionRefusedError("refused"), _chat_ok()])
    waits = []
    assert gms.gms_chat([], base_url="https://gms.x", key="k", model="m", post=post, sleep=waits.append) == ITEMS_JSON
    assert len(post.calls) == 2 and waits == [1]


def test_gms_error_body_is_cut_to_200_chars():
    post = FakePost(gms=[(400, "x" * 1500)])
    with pytest.raises(ApiError) as ei:
        gms.gms_chat([{"role": "user", "content": "x"}], base_url="https://gms.x", key="k", model="m", post=post)
    assert str(ei.value) == "GMS 400: " + "x" * 200
