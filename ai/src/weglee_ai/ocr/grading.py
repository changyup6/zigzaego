"""OCR 줄 정확도 채점. 지라 S15P31A308-109.

문서 JSON(정답·예측 공통): {"items": [{"name", "qty", "price", ...}], "handwritten": bool(선택, 기본 false)}
줄 정확도 = 정답 줄 중 짝지어진 예측 줄의 품목명·수량·단가가 모두 같은 비율.
기준: develop 의 docs/plans/평가_지표_정의.md §3.1 — 품목명은 공백·괄호 기호·대소문자를 지우고(괄호 안 글자는 남김) 비교,
짝짓기는 품목명 유사도가 높은 쌍부터 1:1, 수량·단가는 쉼표·"원" 을 지운 숫자로 비교, 추가 줄 비율 = 추가 줄 ÷ 예측 줄.
"""

import json
import re
import unicodedata
from collections import Counter
from pathlib import Path

from weglee_ai.ocr.schema import _num

EXCLUDED_TYPES = ("chat", "tax_invoice_list", "ledger")  # chat 은 단가 없음, 나머지는 품목 줄 없음
NO_TYPE = "(type 없음)"


BRACKETS = re.compile(r"[\s()\[\]{}<>【】〔〕「」『』〈〉《》]")


def norm_name(s) -> str:
    """품목명 비교용: NFKC(전각 → 반각: （［ＯＬＩＶＥ → ([OLIVE) 뒤 공백·괄호 기호를 지우고 소문자로.
    괄호 안 글자는 남긴다(돈등심(국내산) → 돈등심국내산)."""
    return BRACKETS.sub("", unicodedata.normalize("NFKC", str(s or ""))).lower()


def name_similarity(a: str, b: str) -> float:
    """편집 거리 유사도 = 1 - 레벤슈타인 거리 ÷ 긴 쪽 길이 (§3.1 "품목명 글자 유사도(편집 거리)"). 둘 다 비었으면 1."""
    if a == b:
        return 1.0
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return 1 - prev[-1] / max(len(a), len(b))


def _num_eq(a, b) -> bool:
    """정답·예측 모두 schema._num 으로 정리(쉼표·"원" 제거)한 뒤 비교. 둘 다 비었으면 같다, 못 바꾸는 값("1 kg")은 다르다."""
    if a is None or b is None:
        return a is None and b is None
    x, y = _num(a), _num(b)
    if x is None or y is None:
        return False
    return abs(float(x) - float(y)) < 1e-6


def match_lines(gold: list[dict], pred: list[dict]) -> list[tuple[int, int | None]]:
    """정답 줄 ↔ 예측 줄 짝짓기: 정규화한 품목명의 편집 거리 유사도가 높은 쌍부터 1:1(욕심쟁이).
    유사도가 같을 때만(같은 이름 줄이 여럿) 수량·단가가 맞는 개수가 많은 쌍을 먼저 — §3.1 이 정하지 않은 동점 처리.
    유사도 0 인 쌍은 짝짓지 않는다. 반환: [(정답 i, 예측 j 또는 None)]"""
    cand = []
    for i, g in enumerate(gold):
        for j, p in enumerate(pred):
            s = name_similarity(norm_name(g.get("name")), norm_name(p.get("name")))
            if s > 0:
                nums = _num_eq(g.get("qty"), p.get("qty")) + _num_eq(g.get("price"), p.get("price"))
                cand.append((s, nums, i, j))
    cand.sort(key=lambda c: (-c[0], -c[1], c[2], c[3]))
    used_j, pairs = set(), {}
    for _, _, i, j in cand:
        if i in pairs or j in used_j:
            continue
        pairs[i] = j
        used_j.add(j)
    return [(i, pairs.get(i)) for i in range(len(gold))]


def score_doc(gold_doc: dict, pred_doc: dict) -> dict:
    """문서 한 장 → 개수(합산용)"""
    gold = gold_doc.get("items") or []
    pred = (pred_doc or {}).get("items") or []
    c = dict(n_gold=len(gold), n_pred=len(pred), line_ok=0, name_ok=0, qty_ok=0, price_ok=0)
    matched = 0
    for i, j in match_lines(gold, pred):
        if j is None:
            continue
        matched += 1
        g, p = gold[i], pred[j]
        nok = norm_name(g.get("name")) == norm_name(p.get("name"))
        qok = _num_eq(g.get("qty"), p.get("qty"))
        pok = _num_eq(g.get("price"), p.get("price"))
        c["name_ok"] += nok
        c["qty_ok"] += qok
        c["price_ok"] += pok
        c["line_ok"] += nok and qok and pok
    c["extra"] = len(pred) - matched
    return c


def _ratio(ok: int, n: int) -> float | None:
    return ok / n if n else None


def summarize(pairs_of_docs) -> dict:
    """[(정답 문서, 예측 문서)] → 합계 + 비율. 인쇄/손글씨는 정답 문서의 handwritten 으로 나눈다. 정답 줄이 0이면 비율은 None."""
    keys = ("n_gold", "n_pred", "line_ok", "name_ok", "qty_ok", "price_ok", "extra")
    tot = dict.fromkeys(keys, 0)
    tot["docs"] = 0
    by_kind = {False: [0, 0], True: [0, 0]}  # handwritten → [line_ok, n_gold]
    for gold_doc, pred_doc in pairs_of_docs:
        c = score_doc(gold_doc, pred_doc)
        tot["docs"] += 1
        for k in keys:
            tot[k] += c[k]
        kind = by_kind[bool(gold_doc.get("handwritten", False))]
        kind[0] += c["line_ok"]
        kind[1] += c["n_gold"]
    n = tot["n_gold"]
    return dict(
        tot,
        line_acc=_ratio(tot["line_ok"], n),
        name_acc=_ratio(tot["name_ok"], n),
        qty_acc=_ratio(tot["qty_ok"], n),
        price_acc=_ratio(tot["price_ok"], n),
        extra_rate=_ratio(tot["extra"], tot["n_pred"]),  # 추가 줄 ÷ 예측 줄. 예측 줄 0 이면 None
        printed_lines=by_kind[False][1],
        printed_acc=_ratio(*by_kind[False]),
        handwritten_lines=by_kind[True][1],
        handwritten_acc=_ratio(*by_kind[True]),
    )


# ---- 정답 정규화 · 폴더 짝짓기 ----

def _pos_item(row: dict) -> dict:
    it = {"name": row.get("item")}
    for k in ("qty", "price", "amount", "unit"):
        if k in row:
            it[k] = row[k]
    return it


def gold_items(doc: dict) -> tuple[list[dict], bool] | None:
    """정답 문서(종류별 형식) → (품목 줄, 손글씨 여부). 채점에서 뺄 종류(chat·tax_invoice_list·ledger 등)는 None."""
    t = doc.get("type")
    if t in ("statement", "statement_handwritten"):
        return list(doc.get("items") or []), t == "statement_handwritten"
    if t == "mall_orders":
        return [it for o in doc.get("orders") or [] for it in o.get("items") or []], False
    if t == "pos":
        return [_pos_item(r) for r in doc.get("rows") or []], False
    if t is None and isinstance(doc.get("items"), list):  # 위글리 실제 영수증 정답 형식
        return list(doc["items"]), bool(doc.get("handwritten", False))
    return None


def read_json(path: Path) -> dict:
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def load_gold(gold_dir) -> tuple[list[tuple[str, str, dict]], Counter]:
    """정답 폴더(하위 폴더까지) → ([(상대 경로, 종류, {"items", "handwritten"})], 제외 종류별 장수)"""
    gold_dir = Path(gold_dir)
    golds, excluded = [], Counter()
    files = sorted(gold_dir.rglob("*.json"), key=lambda f: f.relative_to(gold_dir).as_posix())
    for f in files:
        doc = read_json(f)
        t = doc.get("type") or NO_TYPE
        g = gold_items(doc)
        if g is None:
            excluded[t] += 1
            continue
        golds.append((f.relative_to(gold_dir).as_posix(), t, {"items": g[0], "handwritten": g[1]}))
    return golds, excluded


def pair_docs(golds, pred_dir) -> list[tuple[str, dict, dict]]:
    """정답 폴더 기준 상대 경로가 같은 예측끼리 짝짓는다. 예측이 없으면 0줄. 반환: [(종류, 정답, 예측)]"""
    pred_dir = Path(pred_dir)
    out = []
    for rel, t, gold in golds:
        pf = pred_dir / rel
        out.append((t, gold, read_json(pf) if pf.exists() else {"items": []}))
    return out


def count_dry_run(pred_dir) -> int:
    """예측 폴더(하위 폴더까지)에서 dry-run 가짜 결과("dry_run": true) 장수. 읽을 수 없는 파일은 세지 않는다."""
    n = 0
    for f in Path(pred_dir).rglob("*.json"):
        try:
            doc = read_json(f)
        except (ValueError, OSError):
            continue
        n += isinstance(doc, dict) and doc.get("dry_run") is True
    return n
