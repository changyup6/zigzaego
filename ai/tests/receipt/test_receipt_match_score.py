"""매칭 채점 경계 (평가 지표 §3.2 + 계약 "해석 확정")."""

import pytest

from weglee_ai.receipt.scoring import match_ok, pack_ok, score_doc, summarize


def G(name, ing=None, periodic=False, pack=None, qty=1, price=1000):
    return {"name": name, "qty": qty, "price": price, "ingredient": ing, "is_new_periodic": periodic, "pack": pack}


def P(name, ing=None, periodic=False, pack=None, qty=1, price=1000):
    return {"name": name, "qty": qty, "price": price, "ingredient": ing, "is_new_periodic": periodic, "pack": pack,
            "confidence": 0.5, "reason": ""}


@pytest.mark.parametrize("gold,pred,ok", [
    (G("대파", "대파"), P("대파", "대파"), True),                         # 같은 재료
    (G("대파", "대파"), P("대파", "양파"), False),                        # 다른 재료
    (G("대파", "대파"), P("대파", None, True), False),                    # 재료인데 새 주기형으로
    (G("느타리", None, True), P("느타리", None, True), True),             # 새 주기형 맞힘
    (G("느타리", None, True), P("느타리", "양송이버섯", True), True),     # 새 주기형이면 플래그만 본다(§3.2)
    (G("느타리", None, True), P("느타리", None, False), False),           # 새 주기형 놓침
    (G("과자", None, False), P("과자", None, False), True),               # 재료 아님·주기형 아님 맞힘
    (G("과자", None, False), P("과자", None, True), False),               # 해석 확정: null·false 일 때만
    (G("과자", None, False), P("과자", "설탕", False), False),
])
def test_match_score_match_ok_cases(gold, pred, ok):
    assert match_ok(gold, pred) is ok


@pytest.mark.parametrize("gp,pp,ok", [
    ({"qty": 1000, "unit": "g"}, {"qty": 1000, "unit": "g"}, True),
    ({"qty": 1000, "unit": "g"}, {"qty": 1100, "unit": "g"}, True),     # +10% 경계 안
    ({"qty": 1000, "unit": "g"}, {"qty": 900, "unit": "g"}, True),      # -10% 경계 안
    ({"qty": 1000, "unit": "g"}, {"qty": 1101, "unit": "g"}, False),
    ({"qty": 1000, "unit": "g"}, {"qty": 899, "unit": "g"}, False),
    ({"qty": 35, "unit": "ea"}, {"qty": 1000, "unit": "g"}, False),     # 단위 다름
    ({"qty": 1000, "unit": "g"}, None, False),
])
def test_match_score_pack_ok_cases(gp, pp, ok):
    assert pack_ok(gp, pp) is ok


def test_match_score_unmatched_lines_outside_denominator():
    gold = {"items": [G("대파 1단", "대파"), G("냉동새우 1kg", "새우")]}
    pred = {"items": [P("대파 1단", "대파")]}  # 새우 줄을 OCR 이 놓침
    c = score_doc(gold, pred)
    assert c["match_n"] == 1 and c["match_ok"] == 1   # 놓친 줄은 분모 밖
    assert c["n_gold"] == 2


def test_match_score_pack_denominator_only_gold_pack():
    gold = {"items": [G("대파 1단", "대파", pack={"qty": 1000, "unit": "g"}), G("과자", None)]}
    pred = {"items": [P("대파 1단", "대파", pack={"qty": 1000, "unit": "g"}),
                      P("과자", None, pack={"qty": 100, "unit": "g"})]}
    c = score_doc(gold, pred)
    assert c["pack_n"] == 1 and c["pack_ok"] == 1


def test_match_score_summary_ratios_and_extra():
    gold = {"items": [G("대파 1단", "대파"), G("느타리버섯", None, True)]}
    pred = {"items": [P("대파 1단", "대파"), P("느타리버섯", None, False), P("합계", None)],
            "usage": {"prompt_tokens": 400, "completion_tokens": 70, "total_tokens": 470}, "calls": 2,
            "seconds": 3.0}
    s = summarize([(gold, pred)])
    assert s["match_acc"] == 0.5
    assert s["extra"] == 1 and s["extra_rate"] == pytest.approx(1 / 3)
    assert s["tokens_per_call"] == 235 and s["seconds_per_doc"] == 3.0


def test_match_score_boundary_zero_items_gives_none():
    s = summarize([({"items": []}, {"items": []})])
    assert s["match_acc"] is None and s["line_acc"] is None and s["pack_acc"] is None
    assert s["extra_rate"] is None and s["tokens_per_call"] is None


def test_match_score_boundary_missing_pred_doc_counts_zero_lines():
    s = summarize([({"items": [G("대파", "대파")]}, None)])
    assert s["n_gold"] == 1 and s["match_n"] == 0 and s["match_acc"] is None and s["line_acc"] == 0
