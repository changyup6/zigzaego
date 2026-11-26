"""바깥에서 만든 정형화 결과(--normalized-dir)로 정형화 호출을 건너뛰고 매칭만 부른다 (가짜 post). 지라 S15P31A308-110."""

import csv
import json

from receipt_fakes import ENV, NORMALIZE_DOC, RUN_SCRIPT, FakePost, chat_ok, load_script, make_images, write_catalog

EXT_DOC = {"supplier": "바깥마트", "date": "2026-10-26", "items": [
    {"name": "대파 1단", "unit": "단", "qty": 1, "price": 2500, "amount": 2500},
    {"name": "냉동새우 1kg", "unit": "팩", "qty": 2, "price": 14000, "amount": 28000},
]}


def _run(tmp_path, ext_docs, argv_extra=(), post=None, n=2):
    run = load_script(RUN_SCRIPT)
    imgs = make_images(tmp_path, n)
    out = tmp_path / "out"
    ext = tmp_path / "claude_norm"
    ext.mkdir()
    for name, doc in ext_docs.items():
        (ext / name).write_text(doc if isinstance(doc, str) else json.dumps(doc, ensure_ascii=False), encoding="utf-8")
    post = post or FakePost()
    argv = ["--images", str(imgs), "--out", str(out), "--catalog", str(write_catalog(tmp_path)),
            "--normalized-dir", str(ext), *argv_extra]
    code = run.main(argv, environ=ENV, post=post, sleep=lambda s: None, env_file=tmp_path / "없음.env")
    return code, out, post


def _names(post):
    return [c[2]["response_format"]["json_schema"]["name"] for c in post.calls]


def test_external_norm_skips_normalize_call_and_marks_source(tmp_path, capsys):
    code, out, post = _run(tmp_path, {"r_01.json": EXT_DOC})
    assert code == 0
    # r_01 은 매칭만, r_02 는 바깥 결과가 없어 평소대로 정형화 + 매칭
    assert _names(post) == ["match", "normalize", "match"]
    assert "바깥마트" not in json.dumps(post.calls[1][2], ensure_ascii=False)
    m_user = post.calls[0][2]["messages"][1]["content"]
    assert "냉동새우 1kg" in m_user
    d1 = json.loads((out / "r_01.json").read_text(encoding="utf-8"))
    assert d1["supplier"] == "바깥마트" and d1["date"] == "2026-10-26"
    assert [it["qty"] for it in d1["items"]] == [1, 2]
    assert d1["normalize_source"] == "claude-vision"
    assert d1["normalize_model"] == "claude-vision"
    assert d1["match_model"] == ENV["GMS_MODEL"]
    assert d1["calls"] == 1
    assert "normalize_cached" not in d1
    d2 = json.loads((out / "r_02.json").read_text(encoding="utf-8"))
    assert d2["supplier"] == NORMALIZE_DOC["supplier"] and "normalize_source" not in d2
    assert d2["normalize_model"] == ENV["GMS_MODEL"]
    printed = capsys.readouterr().out
    assert "claude-vision" in printed and "r_01" in printed


def test_external_norm_invalid_schema_fails_image_without_any_call(tmp_path):
    """[경계] 스키마에 어긋난 바깥 정형화(모르는 칸)는 그 장 실패 · 정형화로 되돌아가 API 를 부르지 않는다."""
    bad = dict(EXT_DOC, total=30500)
    code, out, post = _run(tmp_path, {"r_01.json": bad}, n=1)
    assert code == 1
    assert post.calls == []
    assert not (out / "r_01.json").exists()
    rows = list(csv.reader((out / "_run.csv").open(encoding="utf-8-sig")))
    assert rows[1][0] == "r_01.jpg" and rows[1][2] == "0" and "claude_norm" in rows[1][6]


def test_external_norm_broken_json_fails_image(tmp_path):
    """[경계] JSON 이 아닌 바깥 파일."""
    code, out, post = _run(tmp_path, {"r_01.json": "{깨짐"}, n=1)
    assert code == 1 and post.calls == []
    assert not (out / "r_01.json").exists()


def test_external_norm_zero_items_makes_no_call(tmp_path):
    """[경계] 품목 0줄이면 매칭도 부르지 않는다."""
    code, out, post = _run(tmp_path, {"r_01.json": dict(EXT_DOC, items=[])}, n=1)
    assert code == 0 and post.calls == []
    d = json.loads((out / "r_01.json").read_text(encoding="utf-8"))
    assert d["calls"] == 0 and d["items"] == [] and d["normalize_source"] == "claude-vision"


def test_external_norm_not_written_to_gemini_cache_on_match_failure(tmp_path):
    """매칭이 실패해도 바깥 정형화를 _normalize 저장본(모델 이름 붙는 곳)에 쓰지 않는다."""
    post = FakePost(match=[chat_ok("JSON 아님")])
    code, out, post = _run(tmp_path, {"r_01.json": EXT_DOC}, post=post, n=1)
    assert code == 1
    assert _names(post) == ["match"]
    assert not (out / "_normalize" / "r_01.json").exists()


def test_external_norm_dir_missing_exits_2(tmp_path):
    """[경계] 없는 폴더를 주면 API 를 부르지 않고 2."""
    run = load_script(RUN_SCRIPT)
    imgs = make_images(tmp_path, 1)
    post = FakePost()
    code = run.main(["--images", str(imgs), "--out", str(tmp_path / "out"), "--catalog", str(write_catalog(tmp_path)),
                     "--normalized-dir", str(tmp_path / "없는폴더")], environ=ENV, post=post, sleep=lambda s: None,
                    env_file=tmp_path / "없음.env")
    assert code == 2 and post.calls == []


def test_external_norm_dry_run_uses_external_lines(tmp_path):
    code, out, post = _run(tmp_path, {"r_01.json": EXT_DOC}, ["--dry-run"], n=1)
    assert code == 0
    d = json.loads((out / "r_01.json").read_text(encoding="utf-8"))
    assert d["dry_run"] is True and d["normalize_source"] == "claude-vision"
    assert [it["name"] for it in d["items"]] == ["대파 1단", "냉동새우 1kg"]
    assert d["calls"] == 1


def _run_dirs(tmp_path, dirs, argv_extra=(), n=2):
    """dirs = [(폴더 이름, {파일: 문서}, _source.txt 내용 또는 None)] — --normalized-dir 를 폴더마다 한 번씩 준다."""
    run = load_script(RUN_SCRIPT)
    imgs = make_images(tmp_path, n)
    out = tmp_path / "out"
    argv = ["--images", str(imgs), "--out", str(out), "--catalog", str(write_catalog(tmp_path)), *argv_extra]
    for dname, docs, source in dirs:
        d = tmp_path / dname
        d.mkdir()
        for name, doc in docs.items():
            (d / name).write_text(json.dumps(doc, ensure_ascii=False), encoding="utf-8")
        if source is not None:
            (d / "_source.txt").write_text(source, encoding="utf-8")
        argv += ["--normalized-dir", str(d)]
    post = FakePost()
    code = run.main(argv, environ=ENV, post=post, sleep=lambda s: None, env_file=tmp_path / "없음.env")
    return code, out, post


def test_external_norm_two_dirs_source_name_per_dir(tmp_path, capsys):
    """폴더마다 _source.txt 의 출처 이름(없으면 claude-vision). 두 장 모두 매칭만 부른다(10/26 Gemini 정형화 재사용)."""
    code, out, post = _run_dirs(tmp_path, [("claude_norm", {"r_01.json": EXT_DOC}, None),
                                           ("gemini_norm", {"r_02.json": EXT_DOC}, "gemini-reuse\ngemini-2.5-flash\n")])
    assert code == 0
    assert _names(post) == ["match", "match"]
    d1 = json.loads((out / "r_01.json").read_text(encoding="utf-8"))
    d2 = json.loads((out / "r_02.json").read_text(encoding="utf-8"))
    assert d1["normalize_source"] == "claude-vision" and d1["normalize_model"] == "claude-vision"
    assert d2["normalize_source"] == "gemini-reuse" and d2["normalize_model"] == "gemini-2.5-flash"  # 둘째 줄 = 정형화 모델
    assert d1["calls"] == d2["calls"] == 1
    printed = capsys.readouterr().out
    assert "claude-vision 1장" in printed and "gemini-reuse 1장" in printed


def test_external_norm_same_image_in_two_dirs_exits_2(tmp_path):
    """[경계] 같은 장이 두 폴더에 있으면 어느 정형화를 쓸지 모르니 API 를 부르지 않고 2."""
    code, out, post = _run_dirs(tmp_path, [("a", {"r_01.json": EXT_DOC}, None),
                                           ("b", {"r_01.json": EXT_DOC}, "gemini-reuse")], n=1)
    assert code == 2 and post.calls == []
    assert not (out / "r_01.json").exists()


def test_external_norm_blank_source_file_exits_2(tmp_path):
    """[경계] _source.txt 가 있는데 비었으면 출처를 알 수 없어 2."""
    code, out, post = _run_dirs(tmp_path, [("a", {"r_01.json": EXT_DOC}, "  \n")], n=1)
    assert code == 2 and post.calls == []
