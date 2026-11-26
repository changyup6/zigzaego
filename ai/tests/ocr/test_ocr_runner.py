import json

import pytest
from ocr_fakes import ENV, ITEMS_JSON, SCRIPT, FakePost, _chat_ok, _images, _load_script

from weglee_ai.ocr import runner

# ---- 환경 변수·이어 하기 (실행기) ----


@pytest.mark.parametrize("method,missing", [
    ("llm_only", ["GMS_KEY", "GMS_BASE_URL", "GMS_MODEL"]),
    ("clova_llm", ["GMS_KEY", "GMS_BASE_URL", "GMS_MODEL", "CLOVA_OCR_URL", "CLOVA_OCR_SECRET"]),
])
def test_env_missing_exits_2_without_api_calls(tmp_path, capsys, method, missing):
    run = _load_script()
    post = FakePost(gms=[_chat_ok()], clova=[(200, "{}")])
    out = tmp_path / "out"
    code = run.main(["--images", str(_images(tmp_path)), "--out", str(out), "--method", method],
                    environ={}, post=post, env_file=tmp_path / "없음.env")
    assert code == 2
    assert post.calls == []
    assert not list(out.rglob("*.json"))
    printed = capsys.readouterr()
    for name in missing:
        assert name in printed.out + printed.err


def test_env_file_read_but_existing_env_wins(tmp_path):
    f = tmp_path / ".env"
    f.write_text('# 주석\nGMS_KEY="from-file"\nexport GMS_MODEL=file-model\n\nGMS_BASE_URL = https://file\n', encoding="utf-8")
    env = runner.load_env({"GMS_KEY": "from-env"}, f)
    assert env["GMS_KEY"] == "from-env"
    assert env["GMS_MODEL"] == "file-model"
    assert env["GMS_BASE_URL"] == "https://file"
    assert runner.missing_env("llm_only", env) == []
    assert runner.missing_env("clova_llm", env) == ["CLOVA_OCR_URL", "CLOVA_OCR_SECRET"]
    assert runner.load_env({"A": "1"}, tmp_path / "없음.env") == {"A": "1"}


def test_resume_skips_existing_output_and_keeps_subfolders(tmp_path):
    imgs = _images(tmp_path)
    out = tmp_path / "out"
    (out / "a").mkdir(parents=True)
    (out / "a" / "synth_001.json").write_text('{"items": []}', encoding="utf-8")
    post = FakePost(gms=[_chat_ok()])
    stats = runner.run_folder(imgs, out, "llm_only", ENV, post=post, sleep=lambda s: None)
    assert len(post.calls) == 1  # b/synth_001 만
    assert (stats["skipped"], stats["done"], stats["failed"]) == (1, 1, 0)
    assert json.loads((out / "a" / "synth_001.json").read_text(encoding="utf-8")) == {"items": []}
    assert json.loads((out / "b" / "synth_001.json").read_text(encoding="utf-8"))["items"][0]["name"] == "베이컨 1kg"
    rows = (out / "_run.csv").read_text(encoding="utf-8-sig").splitlines()
    assert len(rows) == 2 and "b/synth_001" in rows[1]
    # 다시 돌리면 아무것도 부르지 않는다
    post2 = FakePost(gms=[_chat_ok()])
    assert runner.run_folder(imgs, out, "llm_only", ENV, post=post2, sleep=lambda s: None)["skipped"] == 2
    assert post2.calls == []


def test_resume_failed_image_is_logged_not_written(tmp_path):
    imgs = _images(tmp_path)
    out = tmp_path / "out"
    stats = runner.run_folder(imgs, out, "llm_only", ENV, post=FakePost(gms=[(401, "unauthorized")]), sleep=lambda s: None)
    assert (stats["failed"], stats["done"]) == (2, 0)
    assert not list(out.rglob("*.json"))  # 다음 실행에서 다시 시도된다
    assert "401" in (out / "_run.csv").read_text(encoding="utf-8-sig")


def test_resume_parse_failure_is_written_as_empty_items(tmp_path):
    imgs = _images(tmp_path, subs=("a",))
    out = tmp_path / "out"
    stats = runner.run_folder(imgs, out, "llm_only", ENV, post=FakePost(gms=[_chat_ok("표를 못 찾았습니다")]),
                              sleep=lambda s: None)
    doc = json.loads((out / "a" / "synth_001.json").read_text(encoding="utf-8"))
    assert doc["items"] == [] and doc["error"]
    assert stats["done"] == 1 and stats["failed"] == 0


def test_resume_stops_after_five_consecutive_failures(tmp_path):
    imgs = _images(tmp_path, n_per_sub=4)  # 8장
    out = tmp_path / "out"
    post = FakePost(gms=[(401, "unauthorized")])
    stats = runner.run_folder(imgs, out, "llm_only", ENV, post=post, sleep=lambda s: None)
    assert len(post.calls) == 5  # 6번째 장은 부르지 않는다
    assert stats["stopped"] is True and stats["failed"] == 5


def test_resume_consecutive_failure_count_resets_on_success(tmp_path):
    imgs = _images(tmp_path, n_per_sub=4)  # 8장
    seq = [(401, "x")] * 4 + [_chat_ok()] + [(401, "x")] * 2 + [_chat_ok()]
    stats = runner.run_folder(imgs, tmp_path / "out", "llm_only", ENV, post=FakePost(gms=seq), sleep=lambda s: None)
    assert stats["stopped"] is False and (stats["failed"], stats["done"]) == (6, 2)


def test_resume_script_exit_1_when_stopped(tmp_path):
    run = _load_script()
    imgs = _images(tmp_path, n_per_sub=4)
    post = FakePost(gms=[(401, "unauthorized")])
    code = run.main(["--images", str(imgs), "--out", str(tmp_path / "out"), "--method", "llm_only"],
                    environ=dict(ENV), post=post, env_file=tmp_path / "없음.env")
    assert code == 1 and len(post.calls) == 5


def test_resume_clova_waits_one_second_between_images(tmp_path):
    imgs = _images(tmp_path)
    clova = (200, json.dumps({"images": [{"fields": [{"inferText": "x", "lineBreak": True}]}]}))
    post = FakePost(gms=[_chat_ok()], clova=[clova])
    waits = []
    runner.run_folder(imgs, tmp_path / "out", "clova_llm", ENV, post=post, sleep=waits.append, clock=lambda: 100.0)
    assert waits == [1.0]  # 두 번째 장 앞에서만 1초


def test_resume_limit_and_dry_run_writes_without_network(tmp_path):
    imgs = _images(tmp_path)
    run = _load_script()
    out = tmp_path / "out"
    assert run.main(["--images", str(imgs), "--out", str(out), "--method", "llm_only", "--dry-run", "--limit", "1"],
                    environ={}, env_file=tmp_path / "없음.env") == 0
    assert [p.relative_to(out).as_posix() for p in out.rglob("*.json")] == ["a/synth_001.json"]
    code = run.main(["--images", str(imgs), "--out", str(out), "--method", "clova_llm", "--dry-run"],
                    environ={}, env_file=tmp_path / "없음.env")
    assert code == 0
    files = sorted(p.relative_to(out).as_posix() for p in out.rglob("*.json"))
    assert files == ["a/synth_001.json", "b/synth_001.json"]
    assert len(json.loads((out / "b" / "synth_001.json").read_text(encoding="utf-8"))["items"]) == 1


# ---- 경계 보강 ----

def test_resume_gms_bad_shape_is_logged_failure_not_crash(tmp_path):
    imgs = _images(tmp_path)
    out = tmp_path / "out"
    stats = runner.run_folder(imgs, out, "llm_only", ENV, post=FakePost(gms=[(200, '{"choices": []}')]),
                              sleep=lambda s: None)
    assert (stats["failed"], stats["done"]) == (2, 0)
    assert not list(out.rglob("*.json"))
    assert len((out / "_run.csv").read_text(encoding="utf-8-sig").splitlines()) == 3


def test_resume_unexpected_exception_is_failure_and_counts_toward_stop(tmp_path):
    imgs = _images(tmp_path, n_per_sub=4)

    def post(url, headers, data):
        raise RuntimeError("예상 못 한 오류")

    stats = runner.run_folder(imgs, tmp_path / "out", "llm_only", ENV, post=post, sleep=lambda s: None)
    assert stats["failed"] == 5 and stats["stopped"] is True
    assert "RuntimeError" in (tmp_path / "out" / "_run.csv").read_text(encoding="utf-8-sig")


def test_resume_cp949_console_failure_message_does_not_stop_run(tmp_path, monkeypatch):
    # 실패 문구에 cp949 로 못 쓰는 "—" 가 섞여도 그 장만 실패로 남기고 다음 장으로 간다
    import io
    import sys

    out = io.TextIOWrapper(io.BytesIO(), encoding="cp949")
    monkeypatch.setattr(sys, "stdout", out)
    imgs = _images(tmp_path, n_per_sub=1)

    def post(url, headers, data):
        raise RuntimeError("HTTP 400: bad — retry")

    stats = runner.run_folder(imgs, tmp_path / "out", "llm_only", ENV, post=post, sleep=lambda s: None)
    out.flush()
    assert stats["failed"] == stats["total"] >= 2 and stats["stopped"] is False
    assert "bad ? retry" in out.buffer.getvalue().decode("cp949")


def test_resume_keyboard_interrupt_passes_through(tmp_path):
    imgs = _images(tmp_path)

    def post(url, headers, data):
        raise KeyboardInterrupt

    with pytest.raises(KeyboardInterrupt):
        runner.run_folder(imgs, tmp_path / "out", "llm_only", ENV, post=post, sleep=lambda s: None)


def test_env_file_inline_comments_and_quotes(tmp_path):
    f = tmp_path / ".env"
    f.write_text('GMS_KEY=abc123 # 팀 공용 키\nGMS_MODEL="gpt-4o" # 모델\nGMS_BASE_URL=\'https://g#x\'\r\n'
                 '  # 들여쓴 주석\nCLOVA_OCR_SECRET=a=b==\nCLOVA_OCR_URL=https://c/x#frag\n', encoding="utf-8")
    env = runner.load_env({}, f)
    assert env["GMS_KEY"] == "abc123"
    assert env["GMS_MODEL"] == "gpt-4o"
    assert env["GMS_BASE_URL"] == "https://g#x"
    assert env["CLOVA_OCR_SECRET"] == "a=b=="
    assert env["CLOVA_OCR_URL"] == "https://c/x#frag"  # 앞에 공백 없는 # 은 값의 일부


def test_env_images_dir_missing_exits_2(tmp_path, capsys):
    run = _load_script()
    post = FakePost(gms=[_chat_ok()])
    for extra in ([], ["--dry-run"]):
        code = run.main(["--images", str(tmp_path / "없는폴더"), "--out", str(tmp_path / "out"),
                         "--method", "llm_only", *extra], environ=dict(ENV), post=post, env_file=tmp_path / "x.env")
        assert code == 2
    assert post.calls == []
    assert "없는폴더" in capsys.readouterr().err


def test_env_images_dir_without_images_exits_2(tmp_path, capsys):
    run = _load_script()
    (tmp_path / "빈폴더" / "a").mkdir(parents=True)
    (tmp_path / "빈폴더" / "a" / "note.txt").write_text("x")
    post = FakePost(gms=[_chat_ok()])
    code = run.main(["--images", str(tmp_path / "빈폴더"), "--out", str(tmp_path / "out"), "--method", "llm_only"],
                    environ=dict(ENV), post=post, env_file=tmp_path / "x.env")
    assert code == 2 and post.calls == []
    assert "0장" in capsys.readouterr().err


def test_env_blank_environment_value_falls_back_to_env_file(tmp_path):
    f = tmp_path / ".env"
    f.write_text("GMS_KEY=from-file\nGMS_MODEL=file-model\nGMS_BASE_URL=   \n", encoding="utf-8")
    env = runner.load_env({"GMS_KEY": "", "GMS_MODEL": "   ", "GMS_BASE_URL": " "}, f)
    assert env["GMS_KEY"] == "from-file"
    assert env["GMS_MODEL"] == "file-model"
    assert runner.missing_env("llm_only", env) == ["GMS_BASE_URL"]  # 둘 다 비면 없음


def test_env_blank_everywhere_exits_2_without_api_calls(tmp_path, capsys):
    run = _load_script()
    f = tmp_path / ".env"
    f.write_text("GMS_KEY=\n", encoding="utf-8")
    post = FakePost(gms=[_chat_ok()])
    code = run.main(["--images", str(_images(tmp_path)), "--out", str(tmp_path / "out"), "--method", "llm_only"],
                    environ={"GMS_KEY": " ", "GMS_BASE_URL": "https://g", "GMS_MODEL": "m"}, post=post, env_file=f)
    assert code == 2 and post.calls == []
    assert "GMS_KEY" in capsys.readouterr().err


# ---- 경계 보강 ----

@pytest.mark.parametrize("broken", ["", '{"items": [{"na', '{"rows": []}', '{"items": {}}', "null"])
def test_resume_reprocesses_empty_or_partial_output(tmp_path, broken):
    imgs = _images(tmp_path)
    out = tmp_path / "out"
    (out / "a").mkdir(parents=True)
    (out / "a" / "synth_001.json").write_text(broken, encoding="utf-8")
    post = FakePost(gms=[_chat_ok()])
    stats = runner.run_folder(imgs, out, "llm_only", ENV, post=post, sleep=lambda s: None)
    assert len(post.calls) == 2 and stats["skipped"] == 0 and stats["done"] == 2
    assert json.loads((out / "a" / "synth_001.json").read_text(encoding="utf-8"))["items"][0]["name"] == "베이컨 1kg"


def test_resume_interrupted_write_leaves_no_partial_output(tmp_path, monkeypatch):
    imgs = _images(tmp_path, subs=("a",))
    out = tmp_path / "out"

    def boom(src, dst):
        raise KeyboardInterrupt

    monkeypatch.setattr(runner.os, "replace", boom)
    with pytest.raises(KeyboardInterrupt):
        runner.run_folder(imgs, out, "llm_only", ENV, post=FakePost(gms=[_chat_ok()]), sleep=lambda s: None)
    assert not (out / "a" / "synth_001.json").exists()
    monkeypatch.undo()
    post = FakePost(gms=[_chat_ok()])
    stats = runner.run_folder(imgs, out, "llm_only", ENV, post=post, sleep=lambda s: None)
    assert stats["done"] == 1 and len(post.calls) == 1
    assert [p.name for p in out.rglob("*.json")] == ["synth_001.json"]


@pytest.mark.parametrize("limit", ["0", "-1"])
def test_env_limit_must_be_positive(tmp_path, limit):
    run = _load_script()
    post = FakePost(gms=[_chat_ok()])
    with pytest.raises(SystemExit) as e:
        run.main(["--images", str(_images(tmp_path)), "--out", str(tmp_path / "out"), "--method", "llm_only",
                  "--limit", limit], environ=dict(ENV), post=post, env_file=tmp_path / "x.env")
    assert e.value.code == 2 and post.calls == []


# ---- 경계 보강 ----

def _read(p):
    return json.loads(p.read_text(encoding="utf-8"))


def test_resume_dry_run_output_is_marked_and_real_run_reprocesses(tmp_path):
    imgs = _images(tmp_path)
    out = tmp_path / "out"
    run = _load_script()
    assert run.main(["--images", str(imgs), "--out", str(out), "--method", "llm_only", "--dry-run"],
                    environ={}, env_file=tmp_path / "x.env") == 0
    assert _read(out / "a" / "synth_001.json")["dry_run"] is True
    post = FakePost(gms=[_chat_ok()])
    code = run.main(["--images", str(imgs), "--out", str(out), "--method", "llm_only"],
                    environ=dict(ENV), post=post, env_file=tmp_path / "x.env")
    assert code == 0 and len(post.calls) == 2  # 가짜 결과는 건너뛰지 않는다
    for sub in ("a", "b"):
        doc = _read(out / sub / "synth_001.json")
        assert "dry_run" not in doc and doc["items"][0]["name"] == "베이컨 1kg"


def test_resume_dry_run_skips_real_results(tmp_path):
    imgs = _images(tmp_path)
    out = tmp_path / "out"
    runner.run_folder(imgs, out, "llm_only", ENV, post=FakePost(gms=[_chat_ok()]), sleep=lambda s: None)
    before = (out / "a" / "synth_001.json").read_text(encoding="utf-8")
    calls = []

    def post(url, headers, data):
        calls.append(url)
        return runner.dry_run_post(url, headers, data)

    stats = runner.run_folder(imgs, out, "llm_only", runner.DRY_ENV, post=post, clova_interval=0, dry_run=True)
    assert stats["skipped"] == 2 and calls == []
    assert (out / "a" / "synth_001.json").read_text(encoding="utf-8") == before


def test_resume_real_run_does_not_redo_real_results(tmp_path):
    imgs = _images(tmp_path)
    out = tmp_path / "out"
    runner.run_folder(imgs, out, "llm_only", ENV, post=FakePost(gms=[_chat_ok()]), sleep=lambda s: None)
    post = FakePost(gms=[_chat_ok()])
    assert runner.run_folder(imgs, out, "llm_only", ENV, post=post, sleep=lambda s: None)["skipped"] == 2
    assert post.calls == []


def _eval(gold, pred):
    import os
    import subprocess
    import sys

    script = SCRIPT.parent / "evaluate.py"
    env = dict(os.environ, PYTHONIOENCODING="utf-8")
    return subprocess.run([sys.executable, str(script), "--gold", str(gold), "--pred", str(pred)],
                          capture_output=True, text=True, encoding="utf-8", env=env)


def test_resume_eval_warns_when_pred_has_dry_run(tmp_path):
    gold, pred = tmp_path / "gold", tmp_path / "pred"
    for sub in ("a", "b"):
        (gold / sub).mkdir(parents=True)
        (gold / sub / "synth_001.json").write_text(json.dumps({"type": "statement", "items": [
            {"name": "베이컨 1kg", "qty": 3, "price": 11500}]}, ensure_ascii=False), encoding="utf-8")
        (pred / sub).mkdir(parents=True)
    (pred / "a" / "synth_001.json").write_text(json.dumps(dict(runner.DRY_ITEMS, dry_run=True)), encoding="utf-8")
    (pred / "b" / "synth_001.json").write_text(ITEMS_JSON, encoding="utf-8")
    r = _eval(gold, pred)
    assert r.returncode == 0, r.stderr
    assert "경고: dry-run 가짜 결과 1장 포함 - 실제 성적 아님" in r.stdout
    (pred / "a" / "synth_001.json").write_text(ITEMS_JSON, encoding="utf-8")
    r = _eval(gold, pred)
    assert r.returncode == 0 and "dry-run" not in r.stdout


def test_resume_write_failure_is_logged_counted_and_continues(tmp_path, monkeypatch):
    imgs = _images(tmp_path, n_per_sub=4)  # 8장
    out = tmp_path / "out"

    def deny(src, dst):
        raise PermissionError("쓰기 거부")

    monkeypatch.setattr(runner.os, "replace", deny)
    post = FakePost(gms=[_chat_ok()])
    stats = runner.run_folder(imgs, out, "llm_only", ENV, post=post, sleep=lambda s: None)
    assert stats["failed"] == 5 and stats["stopped"] is True and len(post.calls) == 5
    assert not list(out.rglob("*.json")) and not list(out.rglob("*.tmp"))  # 임시 파일도 정리
    log = (out / "_run.csv").read_text(encoding="utf-8-sig")
    assert "PermissionError" in log and len(log.splitlines()) == 6


def test_resume_write_failure_then_success_continues(tmp_path, monkeypatch):
    imgs = _images(tmp_path)
    out = tmp_path / "out"
    real = runner.os.replace
    state = {"n": 0}

    def flaky(src, dst):
        state["n"] += 1
        if state["n"] == 1:
            raise PermissionError("잠김")
        return real(src, dst)

    monkeypatch.setattr(runner.os, "replace", flaky)
    stats = runner.run_folder(imgs, out, "llm_only", ENV, post=FakePost(gms=[_chat_ok()]), sleep=lambda s: None)
    assert (stats["failed"], stats["done"]) == (1, 1)
    assert [p.relative_to(out).as_posix() for p in out.rglob("*.json")] == ["b/synth_001.json"]
