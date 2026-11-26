"""실행기·채점 스크립트·chat 재시도 (가짜 post)."""

import json

import pytest
from receipt_fakes import ENV, EVAL_SCRIPT, RUN_SCRIPT, USAGE_M, USAGE_N, FakePost, chat_ok, load_script, make_images, write_catalog

from weglee_ai.llm import chat
from weglee_ai.ocr.http import ApiError

RF = {"type": "json_schema", "json_schema": {"name": "normalize", "strict": True, "schema": {}}}


def _run(tmp_path, argv_extra=(), environ=None, post=None, n=2):
    run = load_script(RUN_SCRIPT)
    imgs = make_images(tmp_path, n)
    out = tmp_path / "out"
    post = post or FakePost()
    argv = ["--images", str(imgs), "--out", str(out), *argv_extra]
    code = run.main(argv, environ=ENV if environ is None else environ, post=post, sleep=lambda s: None,
                    env_file=tmp_path / "없음.env")
    return code, out, post


# ---- 재료표 없음 [경계] ----

def test_catalog_missing_real_run_exits_2_without_calls(tmp_path, capsys):
    code, out, post = _run(tmp_path, ["--catalog", str(tmp_path / "없는재료표.csv")])
    assert code == 2
    assert post.calls == []
    assert not list(out.rglob("*.json")) if out.exists() else True
    assert "재료표" in capsys.readouterr().err


def test_catalog_missing_arg_real_run_exits_2(tmp_path):
    code, _, post = _run(tmp_path, [])
    assert code == 2 and post.calls == []


def test_catalog_missing_dry_run_uses_builtin_with_warning(tmp_path, capsys):
    code, out, _ = _run(tmp_path, ["--dry-run"])
    assert code == 0
    assert len(list(out.glob("*.json"))) == 2
    assert "경고" in capsys.readouterr().err


# ---- 실제 흐름(가짜 post) ----

def test_run_two_calls_per_image_and_merged_output(tmp_path):
    code, out, post = _run(tmp_path, ["--catalog", str(write_catalog(tmp_path))])
    assert code == 0
    assert len(post.calls) == 4  # 장마다 normalize + match
    names = [c[2]["response_format"]["json_schema"]["name"] for c in post.calls]
    assert names == ["normalize", "match", "normalize", "match"]
    # 사진은 data: URL 로, 재료표는 match 사용자 글에 들어간다
    n_user = post.calls[0][2]["messages"][1]["content"]
    assert any(part.get("type") == "image_url" for part in n_user)
    m_user = post.calls[1][2]["messages"][1]["content"]
    assert "칵테일새우" in m_user and "냉동새우 1kg" in m_user
    doc = json.loads((out / "r_01.json").read_text(encoding="utf-8"))
    assert doc["supplier"] == "가짜마트" and doc["calls"] == 2
    assert doc["items"][1]["ingredient"] == "새우" and doc["items"][1]["pack"] == {"qty": 35, "unit": "ea"}
    assert doc["items"][0]["name"] == "대파 1단" and doc["items"][0]["qty"] == 2
    assert doc["usage"] == {k: USAGE_N[k] + USAGE_M[k] for k in USAGE_N}
    assert "dry_run" not in doc


def test_run_resume_skips_done(tmp_path):
    cat = str(write_catalog(tmp_path))
    run = load_script(RUN_SCRIPT)
    imgs = make_images(tmp_path, 2)
    out = tmp_path / "out"
    p1 = FakePost()
    assert run.main(["--images", str(imgs), "--out", str(out), "--catalog", cat], environ=ENV, post=p1,
                    sleep=lambda s: None) == 0
    p2 = FakePost()
    assert run.main(["--images", str(imgs), "--out", str(out), "--catalog", cat], environ=ENV, post=p2,
                    sleep=lambda s: None) == 0
    assert p2.calls == []


def test_run_zero_normalize_items_skips_match_call(tmp_path):
    post = FakePost(normalize=[chat_ok({"supplier": None, "date": None, "items": []})])
    code, out, post = _run(tmp_path, ["--catalog", str(write_catalog(tmp_path))], post=post, n=1)
    assert code == 0
    assert len(post.calls) == 1
    assert json.loads((out / "r_01.json").read_text(encoding="utf-8"))["items"] == []


def test_run_parse_error_fails_image_without_output(tmp_path):
    post = FakePost(match=[chat_ok({"items": [{"line": 9, "ingredient": None, "is_new_periodic": False, "pack": None,
                                               "confidence": 0.5, "reason": ""}]})])
    code, out, _ = _run(tmp_path, ["--catalog", str(write_catalog(tmp_path))], post=post, n=1)
    assert code == 1
    assert not (out / "r_01.json").exists()
    rows = (out / "_run.csv").read_text(encoding="utf-8-sig").splitlines()
    assert rows[1].split(",")[2] == "0" and "범위" in rows[1]


def test_run_env_file_quotes_stripped_and_gemini_key_fallback(tmp_path):
    f = tmp_path / "gemini.env"
    f.write_bytes(b'GMS_KEY=\r\nGemini_KEY="abc-key"\r\nGMS_BASE_URL="https://g.example/v1"\r\nGMS_MODEL="gm"\r\n')
    run = load_script(RUN_SCRIPT)
    imgs = make_images(tmp_path, 1)
    post = FakePost()
    code = run.main(["--images", str(imgs), "--out", str(tmp_path / "out"), "--catalog", str(write_catalog(tmp_path)),
                     "--env-file", str(f)], environ={"GMS_MODEL": '"gm2"'}, post=post, sleep=lambda s: None)
    assert code == 0
    url, headers, body = post.calls[0]
    assert url == "https://g.example/v1/chat/completions"
    assert headers["Authorization"] == "Bearer abc-key"
    assert body["model"] == "gm2"  # 환경 변수로 넘긴 값의 따옴표도 벗긴다


def test_run_env_missing_exits_2(tmp_path):
    code, _, post = _run(tmp_path, ["--catalog", str(write_catalog(tmp_path))], environ={})
    assert code == 2 and post.calls == []


# ---- chat 재시도 ----

def test_chat_retries_503_then_ok_and_returns_usage():
    seq = [(503, "busy"), (503, "busy"), chat_ok({"a": 1}, {"prompt_tokens": 1, "completion_tokens": 2,
                                                             "total_tokens": 3})]
    calls, waits = [], []

    def post(url, headers, data):
        calls.append(url)
        return seq[len(calls) - 1]
    content, usage = chat([], RF, base_url="https://g/v1/", key="k", model="m", post=post, sleep=waits.append)
    assert json.loads(content) == {"a": 1} and usage["total_tokens"] == 3
    assert waits == [1, 2] and calls[0] == "https://g/v1/chat/completions"


def test_chat_daily_quota_429_not_retried():
    calls = []

    def post(url, headers, data):
        calls.append(1)
        return 429, '{"error": {"details": [{"quotaId": "GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]}}'
    with pytest.raises(ApiError, match="하루 한도"):
        chat([], RF, base_url="https://g", key="k", model="m", post=post, sleep=lambda s: None)
    assert calls == [1]


def test_chat_timeout_not_retried():
    calls = []

    def post(url, headers, data):
        calls.append(1)
        raise TimeoutError("t")
    with pytest.raises(ApiError, match="시간 초과"):
        chat([], RF, base_url="https://g", key="k", model="m", post=post, sleep=lambda s: None)
    assert calls == [1]


def test_chat_4xx_fails_fast_and_missing_usage_is_zero():
    with pytest.raises(ApiError):
        chat([], RF, base_url="https://g", key="k", model="m", post=lambda *a: (400, "bad"), sleep=lambda s: None)
    _, usage = chat([], RF, base_url="https://g", key="k", model="m", post=lambda *a: chat_ok({"a": 1}),
                    sleep=lambda s: None)
    assert usage == {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}


# ---- 채점 스크립트 ----

def test_evaluate_table_and_dry_run_warning(tmp_path, capsys):
    code, out, _ = _run(tmp_path, ["--dry-run", "--catalog", str(write_catalog(tmp_path))], n=1)
    assert code == 0
    gold = tmp_path / "gold"
    gold.mkdir()
    (gold / "r_01.json").write_text(json.dumps({"items": [
        {"name": "가짜 품목", "qty": 1, "price": 1000, "unit": "개", "ingredient": None, "is_new_periodic": False,
         "pack": None}], "handwritten": False, "source": "web"}, ensure_ascii=False), encoding="utf-8")
    ev = load_script(EVAL_SCRIPT)
    capsys.readouterr()
    assert ev.main(["--gold", str(gold), "--pred", str(out)]) == 0
    printed = capsys.readouterr().out
    assert "매칭 정확도" in printed and "줄 정확도" in printed and "dry-run" in printed


def test_evaluate_no_gold_exits_2(tmp_path):
    ev = load_script(EVAL_SCRIPT)
    (tmp_path / "gold").mkdir()
    with pytest.raises(SystemExit) as e:
        ev.main(["--gold", str(tmp_path / "gold"), "--pred", str(tmp_path)])
    assert e.value.code == 2
