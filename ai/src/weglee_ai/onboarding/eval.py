"""레시피 자동 세팅 채점 — docs/plans/평가_지표_정의.md §2.1 ~ 2.3 그대로.

- §2.1 재료 구성 F1: (메뉴, 재료) 쌍의 micro F1. 정답 별칭으로 재료 이름도 메뉴 이름도 맞춘다
  (aliases = 재료, menu_aliases = 메뉴). 별칭이 두 이름에 걸리거나 다른 실제 이름과 같으면 ValueError.
  정답에 없는 메뉴는 양쪽에서 빼고 개수만 센다. 분모가 0이면 0.
  메뉴별 F1 도 같이. 정답 줄이 전부 excluded 인 메뉴도 정답에 있는 메뉴다.
  excluded 쌍(소스 원재료 · 주기형 품목)은 정답 · 예측 양쪽에서 빼고 센다 (MAPE 도 같다).
- §2.2 질문 수: 메뉴 30개 이하 10개, 넘으면 10 × 메뉴 수 / 30 이하.
- §2.3 1인분 MAPE: 양쪽에 다 있는 쌍만, 1:1 제외, 단위가 다르면 오차 100%.
  변환표(UNITS)에 없는 단위(큰술 등)도 단위 불일치로 보고 100% 로 센다 (죽지 않는다).
  정답이 0인 쌍은 MAPE 에서 빼고 가중 오차 Σ|예측 − 정답| ÷ Σ정답 에만 넣는다. 대상이 없으면 None(측정 불가).
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping
from dataclasses import dataclass, field
from typing import NamedTuple

from .catalog import normalize
from .questions import question_limit

# 표준 변환만 (설계 §3). 단 · 망 같은 포장 단위는 여기서 다루지 않는다.
UNITS = {
    "g": ("G", 1), "그램": ("G", 1), "kg": ("G", 1000), "킬로": ("G", 1000),
    "ml": ("ML", 1), "밀리리터": ("ML", 1), "l": ("ML", 1000), "리터": ("ML", 1000),
    "ea": ("EA", 1), "개": ("EA", 1), "마리": ("EA", 1), "알": ("EA", 1), "장": ("EA", 1),
}


class Serving(NamedTuple):
    qty: float
    unit: str
    kind: str = "BOM"


def to_base(qty: float, unit: str) -> tuple[float, str] | None:
    """기준 단위로 바꾼다. 변환표에 없는 단위면 None."""
    found = UNITS.get(normalize(unit))
    if found is None:
        return None
    base, factor = found
    return qty * factor, base


Pair = tuple[str, str]
Aliases = Mapping[str, Iterable[str]] | None


def _alias_map(aliases: Mapping[str, Iterable[str]] | None, real_names: Iterable[str] = ()) -> dict[str, str]:
    """정규화 별칭 → 정규화 이름. real_names 는 정답에 실제로 나온 이름(별칭 표에 없어도 이름으로 친다).

    한 별칭이 서로 다른 두 이름에 걸리거나, 별칭이 다른 이름의 실제 이름과 같으면 두 이름을 담아 ValueError.
    조용히 덮어쓰면 정답 줄 순서에 따라 결과가 바뀐다. 제 이름을 별칭으로 적거나 같은 별칭을 되풀이하는 것은 괜찮다.
    """
    aliases = aliases or {}
    label = {normalize(n): n for n in real_names}
    label.update({normalize(c): c for c in aliases})
    out = {k: k for k in label}
    owner: dict[str, str] = {}
    for canonical, names in aliases.items():
        c = normalize(canonical)
        for n in names:
            a = normalize(n)
            if a == c:
                continue
            if a in label:
                raise ValueError(f"채점 별칭 충돌: '{canonical}' 의 별칭 '{n}' 이 다른 이름 '{label[a]}' 과 같다")
            if a in owner and normalize(owner[a]) != c:
                raise ValueError(f"채점 별칭 충돌: 별칭 '{n}' 이 '{owner[a]}' 과 '{canonical}' 두 이름에 걸린다")
            owner[a] = canonical
            out[a] = c
    return out


def _maps(aliases: Aliases, menu_aliases: Aliases, pairs: Iterable[Pair]) -> tuple[dict[str, str], dict[str, str]]:
    pairs = list(pairs)
    return _alias_map(aliases, (i for _, i in pairs)), _alias_map(menu_aliases, (m for m, _ in pairs))


def _key(pair: Pair, amap: dict[str, str], mmap: dict[str, str] | None = None) -> Pair:
    menu, ing = pair
    m = normalize(menu)
    return (mmap or {}).get(m, m), amap.get(normalize(ing), normalize(ing))



def _prf(tp: int, fp: int, fn: int) -> tuple[float, float, float]:
    p = tp / (tp + fp) if tp + fp else 0.0
    r = tp / (tp + fn) if tp + fn else 0.0
    f = 2 * p * r / (p + r) if p + r else 0.0
    return p, r, f


@dataclass
class F1Result:
    precision: float
    recall: float
    f1: float
    tp: int
    fp: int
    fn: int
    dropped_menus: int
    per_menu: dict[str, float] = field(default_factory=dict)


def menu_f1(
    pred_pairs: Iterable[Pair],
    true_pairs: Iterable[Pair],
    aliases: Aliases = None,
    *,
    menu_aliases: Aliases = None,
    excluded: Iterable[Pair] = (),
) -> F1Result:
    true_pairs, excluded = list(true_pairs), list(excluded)
    amap, mmap = _maps(aliases, menu_aliases, true_pairs + excluded)
    skip = {_key(p, amap, mmap) for p in excluded}
    # 정답에 있는 메뉴는 excluded 줄까지 포함해 정한다 — 줄이 전부 제외된 메뉴도 남은 예측은 FP 로 센다
    menu_label = {mmap.get(normalize(m), normalize(m)): m for m, _ in true_pairs + excluded}
    true_list = [p for p in true_pairs if _key(p, amap, mmap) not in skip]
    T = {_key(p, amap, mmap) for p in true_list}
    raw_pred = {_key(p, amap, mmap) for p in pred_pairs} - skip
    dropped = {m for m, _ in raw_pred if m not in menu_label}
    P = {p for p in raw_pred if p[0] in menu_label}

    tp, fp, fn = len(P & T), len(P - T), len(T - P)
    precision, recall, f1 = _prf(tp, fp, fn)

    per_menu = {}
    for m, label in menu_label.items():
        pm = {p for p in P if p[0] == m}
        tm = {p for p in T if p[0] == m}
        per_menu[label] = _prf(len(pm & tm), len(pm - tm), len(tm - pm))[2]
    return F1Result(precision, recall, f1, tp, fp, fn, len(dropped), per_menu)


@dataclass
class MapeResult:
    mape: float | None
    weighted: float | None
    n_pairs: int
    n_unit_mismatch: int
    unknown_units: tuple[str, ...] = ()


def serving_mape(
    pred: Mapping[Pair, Serving],
    true: Mapping[Pair, Serving],
    aliases: Aliases = None,
    *,
    menu_aliases: Aliases = None,
    excluded: Iterable[Pair] = (),
) -> MapeResult:
    excluded = list(excluded)
    amap, mmap = _maps(aliases, menu_aliases, list(true) + excluded)
    skip = {_key(p, amap, mmap) for p in excluded}
    P = {_key(k, amap, mmap): Serving(*v) for k, v in pred.items()}
    errors: list[float] = []
    abs_sum = true_sum = 0.0
    n_pairs = mismatch = 0
    unknown: set[str] = set()
    for k, t in true.items():
        t = Serving(*t)
        key = _key(k, amap, mmap)
        if key in skip:
            continue
        p = P.get(key)
        if p is None or "ONE_TO_ONE" in (p.kind, t.kind):
            continue
        n_pairs += 1
        tb, pb = to_base(t.qty, t.unit), to_base(p.qty, p.unit)
        unknown.update(u for u, b in ((t.unit, tb), (p.unit, pb)) if b is None)
        # 정답 단위를 모르면 기준 단위로 못 바꾸니 적힌 수 그대로 가중 오차 분모에 넣는다
        tq, tu = tb if tb is not None else (t.qty, None)
        pq, pu = pb if pb is not None else (p.qty, None)
        true_sum += tq
        if tu is None or pu is None or pu != tu:
            mismatch += 1
            abs_sum += tq
            if tq > 0:
                errors.append(1.0)
            continue
        abs_sum += abs(pq - tq)
        if tq > 0:
            errors.append(abs(pq - tq) / tq)
    return MapeResult(
        mape=sum(errors) / len(errors) if errors else None,
        weighted=abs_sum / true_sum if true_sum > 0 else None,
        n_pairs=n_pairs,
        n_unit_mismatch=mismatch,
        unknown_units=tuple(sorted(unknown)),
    )


def question_count_ok(n_questions: int, n_menus: int) -> bool:
    return n_questions <= question_limit(n_menus)

