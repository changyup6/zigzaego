"""경계: 예측 없는 장 · 분당 429 대기 · 정형화 저장 키 · 주입 · 줄 단위 실패 · 날짜."""

import copy
import json

import pytest
from receipt_fakes import ENV, EVAL_SCRIPT, MATCH_DOC, NORMALIZE_DOC, RUN_SCRIPT, FakePost, chat_ok, load_script, write_catalog

from weglee_ai.llm import chat
from weglee_ai.receipt import pipeline
from weglee_ai.receipt.parse import ParseError, parse_match, parse_normalize
from weglee_ai.receipt.prompt import load_prompt
from weglee_ai.receipt.scoring import match_ok, pack_ok

N_SCHEMA = load_prompt("normalize").schema
M_SCHEMA = load_prompt("match").schema
CAT = ["대파", "새우", "식용유"]
UNITS = {"대파": "g", "새우": "ea", "식용유": "ml"}
RF = {"type": "json_schema", "json_schema": {"name": "normalize", "strict": True, "schema": {}}}
PER_MINUTE = (429, '{"error": {"code": 429, "details": [{"violations": '
                   '[{"quotaId": "GenerateRequestsPerMinutePerProjectPerModel-FreeTier"}]}]}}')
PER_MINUTE_DELAY = (429, '[{"error": {"code": 429, "details": [{"violations": '
                         '[{"quotaId": "GenerateRequestsPerMinutePerProjectPerModel-FreeTier"}]}, '
                         '{"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "7s"}]}}]')


def _m(doc):
    return json.dumps(doc, ensure_ascii=False)


# ---- MEDIUM 6: 날짜는 달력에 있는 날이어야 한다 ----

@pytest.mark.parametrize("bad", ["2024-13-45", "2024-02-30", "2026-00-10"])
def test_impossible_date_is_error(bad):
    d = copy.deepcopy(NORMALIZE_DOC)
    d["date"] = bad
    with pytest.raises(ParseError, match="date"):
        parse_normalize(_m(d), N_SCHEMA)


def test_real_date_ok():
    d = copy.deepcopy(NORMALIZE_DOC)
    d["date"] = "2024-02-29"
    assert parse_normalize(_m(d), N_SCHEMA)["date"] == "2024-02-29"


# ---- MEDIUM 5: 한 줄 오류는 그 줄만 답 없음, 나머지 줄은 살린다 ----

def _bad_line1(**over):
    doc = copy.deepcopy(MATCH_DOC)
    doc["items"][0].update(over)
    return doc


@pytest.mark.parametrize("doc", [
    _bad_line1(confidence=1.5),                       # 확신도 범위 밖
    _bad_line1(pack={"qty": 0, "unit": "g"}),         # 포장 수량 0
    _bad_line1(pack={"qty": 1, "unit": "kg"}),        # 스키마 밖 단위
    _bad_line1(reason=3),                             # 자료형
])
def test_line_one_bad_line_only_that_line_missing(doc):
    out = parse_match(_m(doc), M_SCHEMA, 2, CAT, units=UNITS)
    assert out[0]["missing"] is True and out[0]["ingredient"] is None
    assert out[1]["ingredient"] == "새우" and "missing" not in out[1]


def test_line_duplicate_line_marks_that_line_missing_keeps_other():
    doc = copy.deepcopy(MATCH_DOC)
    doc["items"].append(copy.deepcopy(doc["items"][0]))  # line 1 두 번
    out = parse_match(_m(doc), M_SCHEMA, 2, CAT, units=UNITS)
    assert out[0]["missing"] is True and "중복" in out[0]["reason"]
    assert out[1]["ingredient"] == "새우"


def test_line_out_of_range_line_fails_whole_answer():
    doc = copy.deepcopy(MATCH_DOC)
    doc["items"][0]["line"] = 9
    with pytest.raises(ParseError, match="범위"):
        parse_match(_m(doc), M_SCHEMA, 2, CAT, units=UNITS)


def test_line_no_valid_line_at_all_is_error():
    doc = {"items": [dict(MATCH_DOC["items"][0], line=9)]}
    with pytest.raises(ParseError, match="범위"):
        parse_match(_m(doc), M_SCHEMA, 2, CAT, units=UNITS)


# ---- MEDIUM 4: 단위 · 조합 검사, 영수증 글자는 데이터 ----

def test_pack_unit_mismatch_drops_pack_only_keeps_ingredient():
    """재료는 맞혔는데 포장 단위만 재료표와 다르면: 매칭은 살리고(재료 그대로) 포장만 답 없음 → 포장 정확도에서 틀림."""
    out = parse_match(_m(_bad_line1(pack={"qty": 1000, "unit": "ml"})), M_SCHEMA, 2, CAT, units=UNITS)
    assert out[0]["ingredient"] == "대파" and "missing" not in out[0]
    assert out[0]["pack"] is None and "단위" in out[0]["reason"]
    assert out[1]["ingredient"] == "새우"
    assert match_ok({"ingredient": "대파", "is_new_periodic": False}, out[0])
    assert not pack_ok({"qty": 1000, "unit": "g"}, out[0]["pack"])


def test_ingredient_with_new_periodic_true_rejected():
    out = parse_match(_m(_bad_line1(is_new_periodic=True)), M_SCHEMA, 2, CAT, units=UNITS)
    assert out[0]["missing"] is True and "is_new_periodic" in out[0]["reason"]


def test_null_ingredient_any_unit_and_periodic_ok():
    out = parse_match(_m(_bad_line1(ingredient=None, is_new_periodic=True, pack={"qty": 3, "unit": "ea"})),
                      M_SCHEMA, 2, CAT, units=UNITS)
    assert out[0]["is_new_periodic"] is True and "missing" not in out[0]


def test_prompt_says_receipt_text_is_data_not_instruction():
    system = load_prompt("match").system
    assert "데이터" in system and "지시가 아니다" in system


# ---- MEDIUM 2: 분당 429 는 retryDelay 만큼(없으면 30초) 쉬고 재시도 ----

def _seq_post(seq, calls):
    def post(url, headers, data):
        calls.append(1)
        return seq[len(calls) - 1]
    return post


def test_retry_per_minute_429_waits_retry_delay_plus_one():
    calls, waits = [], []
    content, _ = chat([], RF, base_url="https://g", key="k", model="m",
                      post=_seq_post([PER_MINUTE_DELAY, chat_ok({"a": 1})], calls), sleep=waits.append)
    assert json.loads(content) == {"a": 1} and waits == [8]


def test_retry_per_minute_429_without_delay_waits_30():
    calls, waits = [], []
    chat([], RF, base_url="https://g", key="k", model="m",
         post=_seq_post([PER_MINUTE, PER_MINUTE, chat_ok({"a": 1})], calls), sleep=waits.append)
    assert waits == [30, 30]


def test_retry_5xx_still_short_waits():
    calls, waits = [], []
    chat([], RF, base_url="https://g", key="k", model="m",
         post=_seq_post([(503, "busy"), chat_ok({"a": 1})], calls), sleep=waits.append)
    assert waits == [1]


# ---- MEDIUM 3: 정형화 저장본은 프롬프트 · 스키마 · 사진이 같을 때만 다시 쓴다 ----

def _run(tmp_path, post, img_bytes=b"jpg"):
    run = load_script(RUN_SCRIPT)
    imgs = tmp_path / "imgs"
    imgs.mkdir(exist_ok=True)
    (imgs / "a.jpg").write_bytes(img_bytes)
    return run.main(["--images", str(imgs), "--out", str(tmp_path / "out"), "--catalog", str(write_catalog(tmp_path))],
                    environ=ENV, post=post, sleep=lambda s: None, env_file=tmp_path / "없음.env")


def _names(post):
    return [c[2]["response_format"]["json_schema"]["name"] for c in post.calls]


FAIL_MATCH = [chat_ok({"items": []})]


def test_cache_same_photo_reuses_cache(tmp_path):
    assert _run(tmp_path, FakePost(match=FAIL_MATCH)) == 1
    p = FakePost()
    assert _run(tmp_path, p) == 0
    assert _names(p) == ["match"]


def test_cache_changed_photo_renormalizes(tmp_path):
    assert _run(tmp_path, FakePost(match=FAIL_MATCH)) == 1
    p = FakePost()
    assert _run(tmp_path, p, img_bytes=b"other-photo") == 0
    assert _names(p) == ["normalize", "match"]


def test_cache_changed_prompt_renormalizes(tmp_path):
    cache = tmp_path / "c.json"
    prompts = pipeline.load_prompts()
    with pytest.raises(ParseError):
        pipeline.process(ENV, [{"name": "대파", "unit": "g", "aliases": []}], image_bytes=b"jpg", prompts=prompts,
                         post=FakePost(match=FAIL_MATCH), sleep=lambda s: None, norm_cache=cache)
    assert cache.exists()
    changed = dict(prompts, normalize=prompts["normalize"]._replace(system=prompts["normalize"].system + "\n추가"))
    p = FakePost()
    pipeline.process(ENV, [{"name": "대파", "unit": "g", "aliases": []}], image_bytes=b"jpg", prompts=changed,
                     post=p, sleep=lambda s: None, norm_cache=cache)
    assert _names(p) == ["normalize", "match"]


# ---- MEDIUM 1: 예측 없는 장은 표 밑에 장수와 경고 ----

def test_evaluate_warns_missing_predictions(tmp_path, capsys):
    gold, pred = tmp_path / "gold", tmp_path / "pred"
    gold.mkdir()
    pred.mkdir()
    g = {"items": [{"name": "대파 1단", "qty": 1, "price": 3000, "unit": "단", "ingredient": "대파",
                    "is_new_periodic": False, "pack": {"qty": 1000, "unit": "g"}}], "handwritten": False,
         "source": "web"}
    for n in ("r_01", "r_02"):
        (gold / f"{n}.json").write_text(_m(g), encoding="utf-8")
    (pred / "r_01.json").write_text(_m(dict(g, model="m")), encoding="utf-8")
    ev = load_script(EVAL_SCRIPT)
    capsys.readouterr()
    assert ev.main(["--gold", str(gold), "--pred", str(pred)]) == 0
    printed = capsys.readouterr().out
    assert "예측 있는 장 1/2" in printed and "경고" in printed and "r_02" in printed
