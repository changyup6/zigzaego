"""레시피 확인 질문 규칙 — 백엔드가 같은 규칙을 Java 로 구현할 기준 구현.

- 대상: 분류가 BOM 이고, 소스(SAUCE)가 아닌 역할로 쓰이고, 확신도가 0.7 미만인 재료
- 재료당 질문 1개 (여러 메뉴에 걸려도 1개, 2.2 질문 수 정의)
- 보기 3개: 가운데 = 초기값을 간격으로 반올림(최소 한 간격), 양옆 = 초기값 × 0.8 · × 1.2 를 같은 간격으로 반올림.
  간격은 초기값 크기로 정한다 — G · ML: 50 이상 10, 10 이상 5, 그 아래 1 / EA: 3 이상 1, 그 아래 0.5.
  양옆이 가운데와 겹치면 가운데 ± 간격, 아래 보기가 0 이하면 가운데의 절반(간격 반올림, 최소 한 간격)
  (예: 5ml → 4 · 5 · 6, 14g → 10 · 15 · 20, 1개 → 0.5 · 1 · 1.5, 180g → 140 · 180 · 220)
- LLM 분류 출력이 무효(invalid_reason 있음)인 재료는 질문하지 않는다
- 상한: 메뉴 30개 이하 10개, 넘으면 floor(10 × 메뉴 수 / 30) (평가 지표 정의 §2.2 "이하"). 확신 낮은 순으로 자른다
  (같으면 걸린 메뉴가 많은 순, 그다음 이름 순)
"""

from __future__ import annotations

import math
from collections.abc import Mapping
from dataclasses import dataclass

from .catalog import IngredientInfo

CONFIDENCE_THRESHOLD = 0.7
FACTORS = (0.8, 1.0, 1.2)

Number = int | float


@dataclass(frozen=True)
class Question:
    ingredient: str
    text: str
    unit: str
    options: tuple[Number, Number, Number]
    default: Number
    confidence: float
    menus: tuple[str, ...]


def question_limit(n_menus: int) -> int:
    return 10 if n_menus <= 30 else (10 * n_menus) // 30


def step_for(value: float, unit: str) -> float:
    if unit == "EA":
        return 1 if value >= 3 else 0.5
    if value >= 50:
        return 10
    return 5 if value >= 10 else 1


def _round(value: float, step: float) -> float:
    return math.floor(value / step + 0.5) * step


def _clean(value: float) -> Number:
    value = round(value, 6)
    return int(value) if value == int(value) else value


def make_options(value: float, unit: str) -> tuple[Number, Number, Number]:
    """보기 3개 (아래 · 가운데 · 위). 백엔드 Java QuestionRule 과 같은 규칙이어야 한다.

    예외: 가운데가 한 간격뿐이면(아래 보기를 양수로 둘 자리가 없으면) (간격, 2×간격, 3×간격) 이다.
    그래서 G · ML 1.5 미만, EA 0.75 미만은 가운데 ≠ 초기값 (예: (1, G) → (1, 2, 3), (0.3, G) → (1, 2, 3)).
    """
    step = step_for(value, unit)
    mid = max(_round(value * FACTORS[1], step), step)
    lo = _round(value * FACTORS[0], step)
    hi = _round(value * FACTORS[2], step)
    if hi <= mid:
        hi = mid + step
    if lo >= mid:
        lo = mid - step
    if lo <= 0:
        lo = max(_round(mid / 2, step), step)
        if lo >= mid:  # 가운데가 한 간격뿐이면 그 아래 양수 보기가 없다 → 한 간격씩 올린다
            lo, mid, hi = step, 2 * step, 3 * step
    return _clean(lo), _clean(mid), _clean(hi)


def build_questions(extraction: Mapping, infos: Mapping[str, IngredientInfo]) -> list[Question]:
    """구성 추출 출력(menus) + 재료별 분류 결과 → 질문 목록 (상한까지)."""
    menus_of: dict[str, list[str]] = {}
    for menu in extraction["menus"]:
        for c in menu["components"]:
            if c["role"] == "SAUCE":
                continue
            names = menus_of.setdefault(c["name"], [])
            if menu["menu"] not in names:
                names.append(menu["menu"])

    candidates = []
    for name, menus in menus_of.items():
        info = infos[name]
        if info.invalid_reason is not None:
            continue
        if info.kind != "BOM" or info.confidence >= CONFIDENCE_THRESHOLD:
            continue
        options = make_options(info.per_serving, info.unit)
        candidates.append(
            Question(
                ingredient=name,
                text=f"{name} 1인분",
                unit=info.unit,
                options=options,
                default=options[1],
                confidence=info.confidence,
                menus=tuple(menus),
            )
        )
    candidates.sort(key=lambda q: (q.confidence, -len(q.menus), q.ingredient))
    return candidates[: question_limit(len(extraction["menus"]))]
