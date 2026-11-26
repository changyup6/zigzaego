"""경계: limit 이어 하기 · 빠진 매칭 · 하루 한도 · 빈 user · 단위 대소문자."""

import copy
import json

import pytest
from receipt_fakes import ENV, EVAL_SCRIPT, MATCH_DOC, RUN_SCRIPT, FakePost, chat_ok, load_script, write_catalog

from weglee_ai.receipt.parse import ParseError, parse_match
from weglee_ai.receipt.prompt import load_prompt, split_sections
from weglee_ai.receipt.scoring import match_ok, pack_ok, summarize

M_SCHEMA = load_prompt("match").schema
CAT = ["대파", "새우", "식용유"]
PERDAY = (429, '[{"error": {"code": 429, "details": [{"violations": '
               '[{"quotaId": "GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]}]}}]')


def _imgs(tmp_path, n):
    d = tmp_path / "imgs"
    d.mkdir()
    for k in range(n):
        (d / f"r{k}.jpg").write_bytes(b"jpg" + bytes([k]))
    return d


def _main(tmp_path, imgs, post, extra=()):
    run = load_script(RUN_SCRIPT)
    return run.main(["--images", str(imgs), "--out", str(tmp_path / "out"), "--catalog", str(write_catalog(tmp_path)),
                     *extra], environ=ENV, post=post, sleep=lambda s: None, env_file=tmp_path / "없음.env")


# ---- --limit = 이번 실행에서 새로 시도할 장 수 ----

def test_limit_resume_counts_only_new_images(tmp_path):
    imgs = _imgs(tmp_path, 6)
    p1 = FakePost()
    assert _main(tmp_path, imgs, p1, ["--limit", "3"]) == 0
    assert len(p1.calls) == 6
    p2 = FakePost()
    assert _main(tmp_path, imgs, p2, ["--limit", "3"]) == 0
    assert len(p2.calls) == 6  # 끝난 3장은 건너뛰고 남은 3장
    assert sorted(f.name for f in (tmp_path / "out").glob("*.json")) == [f"r{k}.json" for k in range(6)]
    p3 = FakePost()
    assert _main(tmp_path, imgs, p3, ["--limit", "3"]) == 0
    assert p3.calls == []


# ---- 빠진 매칭 ----

def test_missing_all_items_with_lines_is_parse_error():
    with pytest.raises(ParseError, match="비었다"):
        parse_match('{"items": []}', M_SCHEMA, 3, CAT)


def test_missing_line_marked_and_scored_wrong():
    doc = copy.deepcopy(MATCH_DOC)
    doc["items"].pop()
    out = parse_match(json.dumps(doc, ensure_ascii=False), M_SCHEMA, 2, CAT)
    assert out[1]["missing"] is True and "missing" not in out[0]
    gold = {"name": "과자", "ingredient": None, "is_new_periodic": False, "pack": None}
    assert match_ok(gold, out[1]) is False  # 정답이 null·false 여도 빠진 줄은 틀림


def test_missing_counted_in_summary_and_table(tmp_path, capsys):
    gold = {"items": [{"name": "대파 1단", "qty": 1, "price": 1, "ingredient": "대파", "is_new_periodic": False,
                       "pack": None},
                      {"name": "과자", "qty": 1, "price": 1, "ingredient": None, "is_new_periodic": False,
                       "pack": None}]}
    pred = {"items": [{"name": "대파 1단", "qty": 1, "price": 1, "ingredient": "대파", "is_new_periodic": False,
                       "pack": None},
                      {"name": "과자", "qty": 1, "price": 1, "ingredient": None, "is_new_periodic": False,
                       "pack": None, "missing": True}]}
    s = summarize([(gold, pred)])
    assert s["match_missing"] == 1 and s["match_ok"] == 1 and s["match_acc"] == 0.5
    g, p = tmp_path / "g", tmp_path / "p"
    g.mkdir()
    p.mkdir()
    (g / "a.json").write_text(json.dumps(gold, ensure_ascii=False), encoding="utf-8")
    (p / "a.json").write_text(json.dumps(pred, ensure_ascii=False), encoding="utf-8")
    assert load_script(EVAL_SCRIPT).main(["--gold", str(g), "--pred", str(p)]) == 0
    out = capsys.readouterr().out
    header, _, row = out.splitlines()[:3]
    cols = [c.strip() for c in header.split("|")]
    assert "매칭 빠진 줄" in cols
    assert [c.strip() for c in row.split("|")][cols.index("매칭 빠진 줄")] == "1"


# ---- 하루 한도 ----

class SeqPost(FakePost):
    """n 번째 호출부터 하루 한도 429."""

    def __init__(self, quota_from=None):
        super().__init__()
        self.quota_from = quota_from

    def __call__(self, url, headers, data):
        if self.quota_from is not None and len(self.calls) + 1 >= self.quota_from:
            self.calls.append((url, headers, json.loads(data.decode("utf-8"))))
            return PERDAY
        return super().__call__(url, headers, data)


def test_daily_quota_stops_folder_and_resumes_match_only(tmp_path, capsys):
    imgs = _imgs(tmp_path, 4)
    p1 = SeqPost(quota_from=4)  # 1장째 2번 + 2장째 정형화 1번 성공, 4번째(2장째 매칭)에서 하루 한도
    assert _main(tmp_path, imgs, p1) == 1
    assert len(p1.calls) == 4  # 한도 뒤 호출 0
    assert "하루 한도" in capsys.readouterr().out
    out = tmp_path / "out"
    assert (out / "r0.json").exists() and not (out / "r1.json").exists()
    assert (out / "_normalize" / "r1.json").exists()  # 정형화는 먼저 저장됐다
    p2 = SeqPost()
    assert _main(tmp_path, imgs, p2) == 0
    names = [c[2]["response_format"]["json_schema"]["name"] for c in p2.calls]
    assert names == ["match", "normalize", "match", "normalize", "match"]  # r1 은 매칭만
    doc = json.loads((out / "r1.json").read_text(encoding="utf-8"))
    assert doc["calls"] == 2 and doc["usage"]["total_tokens"] == 470  # 저장한 정형화 토큰 + 매칭 토큰
    assert sorted(f.name for f in out.glob("*.json")) == ["r0.json", "r1.json", "r2.json", "r3.json"]


def test_daily_quota_on_first_normalize_stops_with_zero_more_calls(tmp_path):
    p = SeqPost(quota_from=1)
    assert _main(tmp_path, _imgs(tmp_path, 3), p) == 1
    assert len(p.calls) == 1


def test_daily_quota_existing_outputs_still_done(tmp_path):
    """이전 형식 출력(정형화 저장 파일 없음)도 완료로 인정한다(이어 하기 호환)."""
    imgs = _imgs(tmp_path, 2)
    out = tmp_path / "out"
    out.mkdir()
    (out / "r0.json").write_text(json.dumps({"supplier": None, "date": None, "items": [], "usage": {}, "calls": 1}),
                                 encoding="utf-8")
    p = FakePost()
    assert _main(tmp_path, imgs, p) == 0
    assert len(p.calls) == 2  # r1 만


# ---- 로더 · 단위 ----

def test_empty_user_section_is_error():
    with pytest.raises(ValueError, match="user"):
        split_sections("## system\n지시\n## user\n   \n")


def test_unit_case_pack_compare_ignores_case():
    assert pack_ok({"qty": 1000, "unit": "G"}, {"qty": 1000, "unit": "g"}) is True
    assert pack_ok({"qty": 35, "unit": "ea"}, {"qty": 35, "unit": "EA"}) is True
    assert pack_ok({"qty": 1000, "unit": "G"}, {"qty": 1000, "unit": "ml"}) is False


def test_missing_items_empty_fails_image_then_retry_ok(tmp_path):
    imgs = _imgs(tmp_path, 1)
    p1 = FakePost(match=[chat_ok({"items": []})])
    assert _main(tmp_path, imgs, p1) == 1
    assert not (tmp_path / "out" / "r0.json").exists()
    p2 = FakePost()
    assert _main(tmp_path, imgs, p2) == 0
    assert [c[2]["response_format"]["json_schema"]["name"] for c in p2.calls] == ["match"]
