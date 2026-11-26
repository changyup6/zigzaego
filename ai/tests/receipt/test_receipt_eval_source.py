"""채점표에 정형화 출처(gemini · claude-vision)와 출처별 줄 · 매칭 · 포장 정확도를 낸다. 지라 S15P31A308-110."""

import json

from receipt_fakes import EVAL_SCRIPT, load_script

GOLD_ITEM = {"name": "대파 1단", "qty": 1, "price": 3000, "unit": "단", "ingredient": "대파", "is_new_periodic": False,
             "pack": {"qty": 1000, "unit": "g"}}


def _pred(ingredient, source=None, cached=False):
    doc = {"supplier": None, "date": None, "model": "gemini-2.5-flash", "calls": 1,
           "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}, "seconds": 1.0,
           "items": [{"name": "대파 1단", "unit": "단", "qty": 1, "price": 3000, "amount": 3000,
                      "ingredient": ingredient, "is_new_periodic": False, "pack": {"qty": 1000, "unit": "g"},
                      "confidence": 0.9, "reason": ""}]}
    if source:
        doc["normalize_source"] = doc["normalize_model"] = source
    if cached:
        doc["normalize_cached"] = True
    return doc


def _write(tmp_path, preds):
    gold, pred = tmp_path / "gold", tmp_path / "pred"
    gold.mkdir()
    pred.mkdir()
    for name, doc in preds.items():
        (gold / f"{name}.json").write_text(json.dumps({"items": [GOLD_ITEM], "source": "web"}, ensure_ascii=False),
                                           encoding="utf-8")
        if doc is not None:
            (pred / f"{name}.json").write_text(json.dumps(doc, ensure_ascii=False), encoding="utf-8")
    return gold, pred


def _rows(out):
    return {ln.split("|")[1].strip(): [c.strip() for c in ln.split("|")[2:-1]]
            for ln in out.splitlines() if ln.startswith("| ")}


def test_eval_prints_normalize_source_counts_and_per_source_rows(tmp_path, capsys):
    # gemini 직접 1장(맞음) · gemini 저장본 1장(맞음) · claude-vision 2장(하나 틀림)
    gold, pred = _write(tmp_path, {"a": _pred("대파"), "b": _pred("대파", cached=True),
                                   "c": _pred("대파", "claude-vision"), "d": _pred(None, "claude-vision")})
    assert load_script(EVAL_SCRIPT).main(["--gold", str(gold), "--pred", str(pred)]) == 0
    out = capsys.readouterr().out
    assert "정형화 출처 (pred): gemini 2장 · claude-vision 2장" in out
    assert "모델 (pred): gemini-2.5-flash" in out  # 매칭 모델은 그대로
    rows = _rows(out)
    # 출처별 표: 장수 · 정답 줄 · 줄 정확도 · 매칭 대상 · 매칭 정확도 · 포장 대상 · 포장 맞음
    assert rows["전체"][:5] == ["4", "4", "100.0%", "4", "75.0%"]
    assert rows["gemini"][:5] == ["2", "2", "100.0%", "2", "100.0%"]
    assert rows["claude-vision"][:5] == ["2", "2", "100.0%", "2", "50.0%"]


def test_eval_source_only_gemini_and_missing_pred(tmp_path, capsys):
    """[경계] claude-vision 0장 · 예측 없는 장은 출처 '예측 없음' 으로 센다."""
    gold, pred = _write(tmp_path, {"a": _pred("대파"), "b": None})
    assert load_script(EVAL_SCRIPT).main(["--gold", str(gold), "--pred", str(pred)]) == 0
    out = capsys.readouterr().out
    assert "정형화 출처 (pred): gemini 1장 · 예측 없음 1장" in out
    assert "0장" not in out  # 없는 출처는 0장으로 찍지 않는다(10/26)
    rows = _rows(out)
    assert rows["gemini"][0] == "1" and rows["예측 없음"][0] == "1"
    assert "claude-vision" not in rows


def test_eval_source_gemini_reuse_separate_and_no_zero_gemini(tmp_path, capsys):
    """10/26 2회차: gemini-reuse(1회차 Gemini 정형화 재사용)는 따로 센다. 없는 gemini 는 '0장' 으로 찍지 않는다."""
    gold, pred = _write(tmp_path, {"a": _pred("대파", "gemini-reuse"), "b": _pred(None, "claude-vision")})
    assert load_script(EVAL_SCRIPT).main(["--gold", str(gold), "--pred", str(pred)]) == 0
    out = capsys.readouterr().out
    assert "정형화 출처 (pred): claude-vision 1장 · gemini-reuse 1장" in out
    assert "gemini 0장" not in out
    rows = _rows(out)
    assert rows["gemini-reuse"][:5] == ["1", "1", "100.0%", "1", "100.0%"]
    assert "gemini" not in rows
