"""매칭 정확도 · 포장 변환 채점 (평가 지표 §3.2). 지라 S15P31A308-110.

대상 = 109 `grading.match_lines` 로 정답 줄과 짝지어진 예측 줄 (OCR 이 놓친 줄은 분모 밖).
맞음 (계약 "해석 확정" 10/19):
- 정답이 새 주기형(is_new_periodic=true) → 예측 is_new_periodic 이 참이면 맞음 (§3.2)
- 정답 재료가 있음 → 예측 재료가 같으면 맞음
- 정답 재료 null · 주기형 아님(과자 · 술 등) → 예측도 재료 null · 주기형 아님일 때만 맞음
포장 맞음 = 정답 pack 이 있는 짝지어진 줄만 분모, 단위가 같고 수량이 정답의 ±10% 안.
"""

from weglee_ai.ocr import grading

PACK_TOL = 0.10


def match_ok(gold: dict, pred: dict) -> bool:
    if pred.get("missing"):  # 매칭 답에서 빠진 줄은 틀림(정답이 null·false 여도)
        return False
    if gold.get("is_new_periodic"):
        return bool(pred.get("is_new_periodic"))
    g_ing = gold.get("ingredient")
    if g_ing is not None:
        return pred.get("ingredient") == g_ing
    return pred.get("ingredient") is None and not pred.get("is_new_periodic")


def pack_ok(gold_pack, pred_pack, tol: float = PACK_TOL) -> bool:
    if not gold_pack or not pred_pack:
        return False
    if str(gold_pack.get("unit") or "").lower() != str(pred_pack.get("unit") or "").lower():
        return False
    g, p = float(gold_pack["qty"]), float(pred_pack["qty"])
    return abs(p - g) <= tol * g + 1e-9


def score_doc(gold_doc: dict, pred_doc: dict | None) -> dict:
    """한 장 → 개수(합산용). 줄 정확도 개수는 109 채점기 그대로."""
    pred_doc = pred_doc or {"items": []}
    c = grading.score_doc(gold_doc, pred_doc)
    gold, pred = gold_doc.get("items") or [], pred_doc.get("items") or []
    c.update(match_n=0, match_ok=0, match_missing=0, pack_n=0, pack_ok=0)
    for i, j in grading.match_lines(gold, pred):
        if j is None:
            continue
        g, p = gold[i], pred[j]
        c["match_n"] += 1
        c["match_missing"] += bool(p.get("missing"))
        c["match_ok"] += match_ok(g, p)
        if g.get("pack"):
            c["pack_n"] += 1
            c["pack_ok"] += pack_ok(g["pack"], p.get("pack"))
    usage = pred_doc.get("usage") or {}
    c["tokens"] = int(usage.get("total_tokens") or 0)
    c["calls"] = int(pred_doc.get("calls") or 0)
    c["seconds"] = float(pred_doc.get("seconds") or 0)
    c["timed_docs"] = 1 if pred_doc.get("seconds") is not None else 0
    return c


def _ratio(ok, n):
    return ok / n if n else None


def summarize(pairs) -> dict:
    """[(정답 문서, 예측 문서 또는 None)] → 합계 + 비율. 분모가 0 이면 비율 None (표에는 "-")."""
    keys = ("n_gold", "n_pred", "line_ok", "extra", "match_n", "match_ok", "match_missing", "pack_n", "pack_ok", "tokens", "calls",
            "seconds", "timed_docs")
    t = dict.fromkeys(keys, 0)
    t["docs"] = 0
    for gold, pred in pairs:
        c = score_doc(gold, pred)
        t["docs"] += 1
        for k in keys:
            t[k] += c[k]
    return dict(
        t,
        line_acc=_ratio(t["line_ok"], t["n_gold"]),
        match_acc=_ratio(t["match_ok"], t["match_n"]),
        pack_acc=_ratio(t["pack_ok"], t["pack_n"]),
        extra_rate=_ratio(t["extra"], t["n_pred"]),
        tokens_per_call=_ratio(t["tokens"], t["calls"]),
        seconds_per_doc=_ratio(t["seconds"], t["timed_docs"]),
    )
