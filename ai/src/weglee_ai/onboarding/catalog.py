"""범용 재료표 (ai/seed/ingredient_catalog.csv) 읽기와 이름 찾기.

재료 분류는 표가 먼저다. 표에 있으면 표 값, 없으면 LLM 분류 결과를 쓴다 (`resolve`).
"""

from __future__ import annotations

import csv
import math
from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

DEFAULT_PATH = Path(__file__).resolve().parents[3] / "seed" / "ingredient_catalog.csv"


def normalize(name: str) -> str:
    """비교용 이름: 공백을 모두 빼고 소문자로."""
    return "".join(name.split()).lower()


@dataclass(frozen=True)
class CatalogItem:
    name: str
    aliases: tuple[str, ...]
    kind: str
    unit: str
    storage_type: str
    per_serving: float
    confidence: float
    shelf_life_days: int  # 개봉 전 업소 보관 기준 유통기한(일), 추정


@dataclass(frozen=True)
class IngredientInfo:
    """질문 · 채점에 쓰는 재료 한 개의 분류 결과 (표에서 왔든 LLM 에서 왔든 같은 모양)."""

    name: str
    kind: str
    unit: str
    storage_type: str
    per_serving: float
    confidence: float
    # 개봉 전 업소 보관 기준 유통기한(일). None 이면 보관 방식 표(101)의 후보값을 쓴다
    shelf_life_days: int | None = None
    # LLM 분류 출력이 허용 값 밖이면 그 사유("perServing: None" 등). None 이면 정상.
    # 무효인 재료는 질문을 만들지 않고, 채점에서 MAPE 에서 빼되 F1 에는 남긴다
    invalid_reason: str | None = None


KINDS = ("BOM", "ONE_TO_ONE")
UNITS = ("G", "ML", "EA")
STORAGE_TYPES = ("FROZEN", "AMBIENT", "CHILLED", "FRESH")


class Catalog:
    def __init__(self, items: Iterable[CatalogItem]):
        self.items: tuple[CatalogItem, ...] = tuple(items)
        self._index: dict[str, CatalogItem] = {}
        for item in self.items:
            for key in (item.name, *item.aliases):
                norm = normalize(key)
                prev = self._index.get(norm)
                if prev is not None and prev is not item:
                    raise ValueError(
                        f"재료표 이름 충돌: '{key}' 가 '{prev.name}' 행과 '{item.name}' 행에 같이 걸린다"
                    )
                self._index[norm] = item

    def lookup(self, name: str) -> CatalogItem | None:
        key = normalize(name)
        return self._index.get(key) if key else None


def load_catalog(path: str | Path | None = None) -> Catalog:
    p = Path(path) if path else DEFAULT_PATH
    if p == DEFAULT_PATH:
        return _default_catalog()
    return _read(p)


def _read(path: Path) -> Catalog:
    with path.open(encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(f))
    return Catalog(
        CatalogItem(
            name=r["name"].strip(),
            aliases=tuple(a.strip() for a in r["aliases"].split(";") if a.strip()),
            kind=r["kind"].strip(),
            unit=r["unit"].strip(),
            storage_type=r["storage_type"].strip(),
            per_serving=float(r["per_serving"]),
            confidence=float(r["confidence"]),
            shelf_life_days=int(r["shelf_life_days"]),
        )
        for r in rows
    )


@lru_cache(maxsize=1)
def _default_catalog() -> Catalog:
    return _read(DEFAULT_PATH)


def lookup(name: str, catalog: Catalog | None = None) -> CatalogItem | None:
    """이름 또는 별칭이 공백 · 대소문자를 빼고 같으면 그 항목, 없으면 None."""
    return (catalog or load_catalog()).lookup(name)


def unknown_names(names: Iterable[str], catalog: Catalog | None = None) -> list[str]:
    """표에 없어 LLM 분류로 보내야 할 이름 (입력 순서, 중복 제거)."""
    cat = catalog or load_catalog()
    seen, out = set(), []
    for n in names:
        if n not in seen and cat.lookup(n) is None:
            seen.add(n)
            out.append(n)
    return out


def resolve(
    names: Iterable[str], classified: Iterable[Mapping], catalog: Catalog | None = None
) -> dict[str, IngredientInfo]:
    """재료 이름 → 분류 결과. 표가 먼저, 없으면 LLM 분류 출력(items)에서 찾는다.

    둘 다 없으면 KeyError — 분류 단계가 그 재료를 빠뜨린 것이다.
    LLM 출력이 허용 값 밖이면(perServing 없음 · null · 숫자 아님 · 0 이하, confidence 없음 · 숫자 아님 ·
    0~1 밖, kind · unit · storageType 이 enum 밖) 죽지 않고 invalid_reason 에 사유를 적어 돌려준다.
    """
    cat = catalog or load_catalog()
    llm = {normalize(i["name"]): i for i in classified}
    out: dict[str, IngredientInfo] = {}
    for name in names:
        if name in out:
            continue
        hit = cat.lookup(name)
        if hit is not None:
            out[name] = IngredientInfo(
                name,
                hit.kind,
                hit.unit,
                hit.storage_type,
                hit.per_serving,
                hit.confidence,
                hit.shelf_life_days,
            )
            continue
        item = llm.get(normalize(name))
        if item is None:
            raise KeyError(f"분류 결과 없음: {name}")
        reasons = _invalid_reasons(item)
        per, conf = item.get("perServing"), item.get("confidence")
        out[name] = IngredientInfo(
            name,
            str(item.get("kind")),
            str(item.get("unit")),
            str(item.get("storageType")),
            float(per) if _is_number(per) else 0.0,
            float(conf) if _is_number(conf) else 0.0,
            _shelf_life(item.get("shelfLifeDays")),
            "; ".join(reasons) or None,
        )
    return out


def _is_number(value) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def _invalid_reasons(item: Mapping) -> list[str]:
    """LLM 분류 한 줄이 스키마(ingredient_classify.schema.json) 허용 값 밖인 칸들."""
    reasons = []
    per = item.get("perServing")
    if not _is_number(per) or per <= 0:
        reasons.append(f"perServing: {per!r}")
    conf = item.get("confidence")
    if not _is_number(conf) or not 0 <= conf <= 1:
        reasons.append(f"confidence: {conf!r}")
    for key, allowed in (("kind", KINDS), ("unit", UNITS), ("storageType", STORAGE_TYPES)):
        if item.get(key) not in allowed:
            reasons.append(f"{key}: {item.get(key)!r}")
    return reasons


def _shelf_life(value) -> int | None:
    """LLM 이 낸 유통기한. 없거나·정수가 아니거나·2 미만이면 None (= 보관 방식 표의 후보값).

    1 이하는 발주 상한(하루 소비 × (유통기한 − 1) − 재고, DECISIONS #28)을 0 이하로 만들어 발주가 막힌다.
    """
    if isinstance(value, bool) or not isinstance(value, int) or value < 2:
        return None
    return value
