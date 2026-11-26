"""경계: dry-run 과 정형화 저장 파일 · 어긋난 저장 파일 · 저장 모델 · 분당 429 · 오류 칸 길이."""

import json

import pytest
from receipt_fakes import ENV, NORMALIZE_DOC, RUN_SCRIPT, FakePost, chat_ok, load_script, write_catalog

from weglee_ai.llm import DailyQuotaError, chat
from weglee_ai.receipt import pipeline

PER_MINUTE = (429, '{"error": {"code": 429, "details": [{"violations": '
                   '[{"quotaId": "GenerateRequestsPerMinutePerProjectPerModel-FreeTier"}]}]}}')
RF = {"type": "json_schema", "json_schema": {"name": "normalize", "strict": True, "schema": {}}}


def _imgs(tmp_path):
    d = tmp_path / "imgs"
    d.mkdir()
    (d / "a.jpg").write_bytes(b"jpg")
    return d


def _main(tmp_path, post, extra=(), environ=ENV):
    run = load_script(RUN_SCRIPT)
    imgs = tmp_path / "imgs" if (tmp_path / "imgs").exists() else _imgs(tmp_path)
    return run.main(["--images", str(imgs), "--out", str(tmp_path / "out"), "--catalog", str(write_catalog(tmp_path)),
                     *extra], environ=environ, post=post, sleep=lambda s: None, env_file=tmp_path / "없음.env")


def _cache(tmp_path, obj, encoding="utf-8"):
    c = tmp_path / "out" / "_normalize" / "a.json"
    c.parent.mkdir(parents=True, exist_ok=True)
    c.write_text(obj if isinstance(obj, str) else json.dumps(obj, ensure_ascii=False), encoding=encoding)
    return c


def _names(post):
    return [c[2]["response_format"]["json_schema"]["name"] for c in post.calls]


GOOD = {"doc": NORMALIZE_DOC, "usage": {"prompt_tokens": 100, "completion_tokens": 20, "total_tokens": 120},
        "model": ENV["GMS_MODEL"],
        "key": pipeline.norm_cache_key(pipeline.load_prompts()["normalize"], image_bytes=b"jpg")}  # _imgs 의 사진


# ---- dry-run 은 저장 파일을 건드리지 않는다 ----

def test_dry_run_cache_untouched_then_real_run_match_only(tmp_path):
    c = _cache(tmp_path, GOOD)
    before = c.read_text(encoding="utf-8")
    assert _main(tmp_path, FakePost(), ["--dry-run"]) == 0
    assert c.read_text(encoding="utf-8") == before  # 읽어 쓰지도, 지우지도 않았다
    dry = json.loads((tmp_path / "out" / "a.json").read_text(encoding="utf-8"))
    assert dry["dry_run"] is True and "normalize_cached" not in dry
    p = FakePost()
    assert _main(tmp_path, p) == 0
    assert _names(p) == ["match"]  # 실제 실행은 저장 파일로 매칭만(호출 1)
    assert not c.exists()


# ---- 어긋난 저장 파일은 지우고 다시 정형화 ----

@pytest.mark.parametrize("obj", [
    {"doc": {"items": []}, "usage": {}, "model": ENV["GMS_MODEL"]},                                  # supplier 없음
    {"doc": {"supplier": None, "date": None, "items": [{"unit": "kg"}]}, "usage": {},
     "model": ENV["GMS_MODEL"]},                                                                      # name 없음
    {"doc": NORMALIZE_DOC, "usage": {"total_tokens": "x"}, "model": ENV["GMS_MODEL"]},               # usage 값 문자열
    {"doc": NORMALIZE_DOC, "usage": "x", "model": ENV["GMS_MODEL"]},                                  # usage 가 사전 아님
    '{"doc": {"items": [',                                                                            # 깨진 JSON
])
def test_bad_cache_is_dropped_and_renormalized(tmp_path, obj):
    _cache(tmp_path, obj)
    p = FakePost()
    assert _main(tmp_path, p) == 0
    assert _names(p) == ["normalize", "match"]
    doc = json.loads((tmp_path / "out" / "a.json").read_text(encoding="utf-8"))
    assert doc["supplier"] == "가짜마트" and "normalize_cached" not in doc
    assert not (tmp_path / "out" / "_normalize" / "a.json").exists()


def test_bad_cache_bom_file_still_read(tmp_path):
    _cache(tmp_path, GOOD, encoding="utf-8-sig")
    p = FakePost()
    assert _main(tmp_path, p) == 0
    assert _names(p) == ["match"]


# ---- 저장 모델 ----

def test_cache_model_mismatch_renormalizes(tmp_path):
    _cache(tmp_path, dict(GOOD, model="model-A"))
    p = FakePost()
    assert _main(tmp_path, p) == 0
    assert _names(p) == ["normalize", "match"]
    doc = json.loads((tmp_path / "out" / "a.json").read_text(encoding="utf-8"))
    assert doc["normalize_model"] == doc["match_model"] == ENV["GMS_MODEL"]


def test_cache_model_recorded_and_split_in_output(tmp_path):
    _cache(tmp_path, dict(GOOD, model=ENV["GMS_MODEL"]))
    p = FakePost()
    assert _main(tmp_path, p) == 0
    doc = json.loads((tmp_path / "out" / "a.json").read_text(encoding="utf-8"))
    assert doc["normalize_cached"] is True
    assert doc["normalize_model"] == ENV["GMS_MODEL"] and doc["match_model"] == ENV["GMS_MODEL"]


def test_cache_model_written_when_match_fails(tmp_path):
    p = FakePost(match=[chat_ok({"items": [{"line": 9, "ingredient": None, "is_new_periodic": False, "pack": None,
                                            "confidence": 0.5, "reason": ""}]})])
    assert _main(tmp_path, p) == 1
    saved = json.loads((tmp_path / "out" / "_normalize" / "a.json").read_text(encoding="utf-8"))
    assert saved["model"] == ENV["GMS_MODEL"] and saved["doc"]["supplier"] == "가짜마트"


# ---- 분당 429 는 하루 한도가 아니다 ----

def test_per_minute_429_retried_not_daily_quota():
    seq = [PER_MINUTE, PER_MINUTE, chat_ok({"a": 1})]
    calls, waits = [], []

    def post(url, headers, data):
        calls.append(1)
        return seq[len(calls) - 1]
    content, _ = chat([], RF, base_url="https://g", key="k", model="m", post=post, sleep=waits.append)
    assert json.loads(content) == {"a": 1} and len(calls) == 3 and waits == [30, 30]  # 분당 한도는 30초


def test_per_minute_429_in_folder_run_does_not_stop(tmp_path):
    p = FakePost(normalize=[PER_MINUTE, PER_MINUTE, chat_ok(NORMALIZE_DOC)])
    assert _main(tmp_path, p) == 0
    assert _names(p) == ["normalize", "normalize", "normalize", "match"]


def test_per_minute_429_exhausted_is_api_error_not_daily():
    with pytest.raises(Exception) as e:
        chat([], RF, base_url="https://g", key="k", model="m", post=lambda *a: PER_MINUTE, sleep=lambda s: None)
    assert not isinstance(e.value, DailyQuotaError)


# ---- _run.csv 오류 칸 길이 ----

def test_per_minute_error_cell_keeps_long_body(tmp_path):
    body = '{"error": {"code": 400, "pad": "' + "x" * 600 + '", "quotaId": "TailMarker"}}'
    p = FakePost(normalize=[(400, body)])
    assert _main(tmp_path, p) == 1
    log = (tmp_path / "out" / "_run.csv").read_text(encoding="utf-8-sig")
    assert "TailMarker" in log
