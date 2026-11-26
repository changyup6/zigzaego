"""품목군 기준표 CSV → 백엔드 마이그레이션 INSERT 문: uv run python scripts/market/make_item_group_sql.py [--table market_item_group]   (S15P31A308-229)

`ai/seed/market_item_group.csv` 가 정본이고 서비스 DB 의 `market_item_group` 표는 그 복사본이다. 표가 바뀌면 이 출력으로
**새 Flyway 마이그레이션**을 만든다(기존 마이그레이션 수정 금지). 출력은 표준 출력 — 파일로 받아서 V<번호>__…sql 에 붙인다.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "src"))

from weglee_ai.market.taxonomy import SEED, Taxonomy  # noqa: E402


def q(v) -> str:
    if v is None or v == "":
        return "NULL"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (int, float)):
        return repr(v)
    return "'" + str(v).replace("'", "''") + "'"


def render(taxonomy: Taxonomy, table: str) -> str:
    rows = []
    for g in taxonomy.groups.values():  # 부모가 먼저 오도록 CSV 순서를 지킨다(부모 코드 FK)
        rows.append("    (" + ", ".join([q(g.code), q(g.name), q(g.parent), q(g.category), q(g.comparable), q(g.base_unit),
                                          q(g.ea_weight_g), q(g.density), q(";".join(g.aliases)), q(g.note)]) + ")")
    head = (f"INSERT INTO {table} (code, name, parent_code, category, comparable, base_unit, ea_weight_g, density_g_per_ml, aliases, note) VALUES\n")
    return head + ",\n".join(rows) + ";\n"


def main(argv=None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", type=Path, default=SEED)
    ap.add_argument("--table", default="market_item_group")
    a = ap.parse_args(argv)
    taxonomy = Taxonomy.load(a.csv)
    # 부모가 자식보다 뒤에 있으면 FK 가 깨진다 — 여기서 미리 확인
    seen: set[str] = set()
    for g in taxonomy.groups.values():
        if g.parent and g.parent not in seen:
            print(f"멈춤: {g.code} 의 부모 {g.parent} 가 CSV 에서 뒤에 있다", file=sys.stderr)
            return 1
        seen.add(g.code)
    sys.stdout.write(render(taxonomy, a.table))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
