"""범용 재료표(develop `ai/seed/ingredient_catalog.csv`) 읽기. 지라 S15P31A308-110.

재료표는 저장소(이 가지)에 복사하지 않는다 — 경로를 인자로 받는다.
"""

import csv
import json
from pathlib import Path

UNITS = {"G": "g", "ML": "ml", "EA": "ea"}

# dry-run 에서 --catalog 가 없을 때만 쓰는 작은 재료표(흐름 확인용)
DRY_CATALOG = [
    {"name": "대파", "unit": "g", "aliases": ["쪽파", "실파"]},
    {"name": "계란", "unit": "ea", "aliases": ["달걀"]},
]


def load_catalog(path) -> list[dict]:
    """CSV(name,aliases,...,unit,...) → [{"name", "unit"(g·ml·ea), "aliases": [...]}]. 파일이 없으면 FileNotFoundError."""
    rows = []
    with Path(path).open(encoding="utf-8-sig", newline="") as fh:
        for r in csv.DictReader(fh):
            name = (r.get("name") or "").strip()
            if not name:
                continue
            unit = UNITS.get((r.get("unit") or "").strip().upper())
            if unit is None:
                raise ValueError(f"재료표 단위가 G·ML·EA 가 아니다: {name} {r.get('unit')!r}")
            aliases = [a.strip() for a in (r.get("aliases") or "").split(";") if a.strip()]
            rows.append({"name": name, "unit": unit, "aliases": aliases})
    if not rows:
        raise ValueError(f"재료표가 비었다: {path}")
    return rows


def catalog_json(rows: list[dict]) -> str:
    """프롬프트에 넣을 재료표 JSON (한 줄에 재료 하나)."""
    return "[\n" + ",\n".join(json.dumps(r, ensure_ascii=False) for r in rows) + "\n]"
