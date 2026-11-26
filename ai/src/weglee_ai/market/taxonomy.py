"""식자재몰 상품 ↔ 품목군 기준표 (S15P31A308-228 · 229).

`ai/seed/market_item_group.csv` 한 장이 정본이다. 열 뜻과 품목군을 나누는 기준은
`docs/plans/2026-11-03_식자재몰_품목군_분류_기준.md` 에 있다.
- 품목군(item group): 사장님이 발주할 때 서로 바꿔 쓸 수 있고 같은 기준 단위로 값을 비교할 수 있는 상품 묶음(계란 ≠ 액란, 두부 ≠ 순두부)
- 기준 단위(base_unit): 그 품목군의 값을 비교하는 단위(G · ML · EA). 다른 단위로 적힌 상품은 환산값(ea_weight_g · density)이 있을 때만 바꾼다
- 별칭(aliases): 재료 이름 → 품목군 매핑과 LLM 분류 대상(후보) 거르기에만 쓴다. 상품이 품목군에 드는지는 LLM 라벨이 정한다
네트워크 · DB 없이 도는 순수 함수만 둔다.
"""
from __future__ import annotations

import csv
import re
from dataclasses import dataclass
from pathlib import Path

SEED = Path(__file__).resolve().parents[3] / "seed" / "market_item_group.csv"
UNITS = ("G", "ML", "EA")
# 데이터셋(크롤러) 단위 글자 → 기준 단위 이름
DATASET_UNIT = {"g": "G", "ml": "ML", "개": "EA"}
COLUMNS = ("code", "name", "parent", "category", "comparable", "base_unit", "ea_weight_g", "density_g_per_ml", "aliases", "note")


@dataclass(frozen=True)
class ItemGroup:
    code: str
    name: str
    parent: str | None
    category: str
    comparable: bool  # False 면 부위 품목군의 부모(돼지고기 · 소고기 · 닭고기 · 치즈) — 최저가 · 추이를 내지 않는다
    base_unit: str  # G · ML · EA
    ea_weight_g: float | None  # 1개 무게(g). 개 ↔ g 환산. 없으면 환산하지 않는다
    density: float | None  # g / ml. g ↔ ml 환산. 없으면 환산하지 않는다
    aliases: tuple[str, ...]  # 이름 포함
    note: str


def normalize(text: str) -> str:
    """재료 이름 · 별칭 비교용: 소문자, 공백 · 구분 기호 제거(다진 마늘 == 다진마늘)."""
    return re.sub(r"[\s\-_/·,]+", "", (text or "").lower())


def word_re(term: str) -> str:
    """상품명에서 별칭을 찾는 정규식. 별칭 바로 뒤에 한글이 붙으면 다른 낱말이다(대파함박스테이크 · 양파링). 앞은 괜찮다(흙대파).
    크롤러 match.py 의 word_re 와 같은 뜻 — 후보를 거르는 기준이 양쪽에서 같아야 비교표가 맞다."""
    return re.escape(term.lower()) + "([^가-힣]|$)"


def _float(v: str) -> float | None:
    v = (v or "").strip()
    return float(v) if v else None


class Taxonomy:
    def __init__(self, groups: list[ItemGroup]):
        self.groups: dict[str, ItemGroup] = {}
        self._alias: dict[str, list[str]] = {}  # normalize(별칭) → 품목군 코드들(겹치면 여러 개 → 매핑하지 않는다)
        self._finder: list[tuple[str, str, re.Pattern]] = []  # (코드, 별칭 소문자, 정규식)
        for g in groups:
            if g.code in self.groups:
                raise ValueError(f"품목군 코드가 겹친다: {g.code}")
            if g.base_unit not in UNITS:
                raise ValueError(f"{g.code}: 기준 단위는 {UNITS} 중 하나여야 한다: {g.base_unit}")
            self.groups[g.code] = g
            for a in g.aliases:
                self._alias.setdefault(normalize(a), [])
                if g.code not in self._alias[normalize(a)]:
                    self._alias[normalize(a)].append(g.code)
                self._finder.append((g.code, a.lower(), re.compile(word_re(a))))
        for g in groups:
            if g.parent and g.parent not in self.groups:
                raise ValueError(f"{g.code}: 부모 품목군이 없다: {g.parent}")

    @classmethod
    def load(cls, path: Path | str = SEED) -> Taxonomy:
        with open(path, encoding="utf-8", newline="") as f:
            reader = csv.DictReader(f)
            missing = [c for c in COLUMNS if c not in (reader.fieldnames or [])]
            if missing:
                raise ValueError(f"{path}: 열이 없다: {missing}")
            groups = []
            for r in reader:
                code = r["code"].strip()
                if not code:
                    continue
                aliases = [a.strip() for a in r["aliases"].split(";") if a.strip()]
                name = r["name"].strip()
                if name not in aliases:
                    aliases.insert(0, name)
                groups.append(ItemGroup(
                    code=code, name=name, parent=r["parent"].strip() or None, category=r["category"].strip(),
                    comparable=r["comparable"].strip().upper() != "N", base_unit=r["base_unit"].strip().upper(),
                    ea_weight_g=_float(r["ea_weight_g"]), density=_float(r["density_g_per_ml"]),
                    aliases=tuple(dict.fromkeys(aliases)), note=r["note"].strip()))
        return cls(groups)

    def get(self, code: str) -> ItemGroup | None:
        return self.groups.get(code)

    def comparable(self) -> list[ItemGroup]:
        return [g for g in self.groups.values() if g.comparable]

    def group_for_name(self, ingredient_name: str) -> ItemGroup | None:
        """재료 이름(사장님이 적은 것) → 품목군. 이름 · 별칭과 정확히 같을 때만(공백 · 대소문자 무시).
        여러 품목군이 같은 별칭을 쓰면(등심) 매핑하지 않는다 — 부르는 쪽이 이름 검색으로 넘어간다."""
        codes = self._alias.get(normalize(ingredient_name), [])
        return self.groups[codes[0]] if len(codes) == 1 else None

    def candidate_codes(self, product_name: str) -> list[str]:
        """상품명에 별칭이 낱말로 든 품목군들(LLM 분류 대상 거르기). 상품이 그 품목군이라는 뜻은 아니다 — 계란찜도 EGG 후보다."""
        low = product_name.lower()
        out: list[str] = []
        for code, alias, pat in self._finder:
            if code not in out and alias in low and pat.search(low):
                out.append(code)
        return out

    def to_base(self, code: str, qty: float | None, unit: str | None) -> float | None:
        """(총량, 단위) → 기준 단위 양. 단위는 G · ML · EA 또는 데이터셋 글자(g · ml · 개). 환산값이 없으면 None."""
        g = self.groups.get(code)
        if g is None or qty is None or qty <= 0 or not unit:
            return None
        unit = DATASET_UNIT.get(unit, unit.upper())
        if unit not in UNITS:
            return None
        if unit == g.base_unit:
            return float(qty)
        grams = {"G": lambda q: q, "EA": lambda q: q * g.ea_weight_g if g.ea_weight_g else None,
                 "ML": lambda q: q * g.density if g.density else None}[unit](float(qty))
        if grams is None:
            return None
        if g.base_unit == "G":
            return grams
        if g.base_unit == "EA":
            return grams / g.ea_weight_g if g.ea_weight_g else None
        return grams / g.density if g.density else None

    def base_unit_price(self, code: str, price: float, qty: float | None, unit: str | None) -> float | None:
        """판매가 → 원 / 기준 단위 1 (결정 #57 과 같은 뜻: 원/g · 원/ml · 원/개)."""
        base = self.to_base(code, qty, unit)
        return None if not base else price / base

    def prompt_table(self) -> str:
        """LLM 에 주는 품목군 목록 한 줄씩: `CODE | 이름 | 별칭 … | 메모`. 부모 품목군은 '부모' 라고 적어 고르지 않게 한다."""
        lines = []
        for g in self.groups.values():
            tail = " | 부위 품목군의 부모 — 부위를 모를 때만" if not g.comparable else ""
            note = f" | {g.note}" if g.note else ""
            lines.append(f"{g.code} | {g.name} | {' · '.join(a for a in g.aliases if a != g.name)}{note}{tail}")
        return "\n".join(lines)
