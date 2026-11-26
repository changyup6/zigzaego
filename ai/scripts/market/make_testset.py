"""검색 방식 비교 시험지 초안: uv run python scripts/market/make_testset.py --parquet <products.parquet> [--per-site 6]   (S15P31A308-228)

검색어마다 사이트별로 **후보(상품명에 검색어 · 이명이 낱말로 든 상품) 중 비교 단위에서 가장 싼 N개**를 뽑아 CSV 로 쓴다.
사이트별 1위는 이 N개 안에서 정해지므로, 이 줄들에만 정답(truth: 1 = 그 품목의 원물 · 손질품, 0 = 아님)을 달면 네 방식의
정밀도 · 재현율을 같은 잣대로 잴 수 있다. truth 칸은 비워서 내고 사람이 채운다(초안은 규칙 판정을 rule 칸에 참고로 적는다).
비교 단위 = 검색어의 품목군 기준 단위(없으면 후보가 가장 많은 단위). 같은 사이트 · 상품번호는 가장 싼 점포 하나.
"""
from __future__ import annotations

import argparse
import csv
import sys
from pathlib import Path

import duckdb

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "src"))

from weglee_ai.market.taxonomy import DATASET_UNIT, Taxonomy, word_re  # noqa: E402

QUERIES = ("계란 액란 두부 순두부 대파 쪽파 양파 마늘 다진마늘 감자 오이 무 사과 토마토 삼겹살 목살 닭가슴살 새우 오징어 고등어 "
           "김 우유 버터 간장 식용유 참기름 고춧가루 설탕 소금 밀가루").split()
COLUMNS = ["query", "group", "site", "rank", "product_name", "spec", "price", "unit", "unit_price_100", "rule", "truth", "note"]
UNIT_BACK = {v: k for k, v in DATASET_UNIT.items()}


def candidate_regex(taxonomy: Taxonomy, query: str) -> str:
    g = taxonomy.group_for_name(query)
    alts = g.aliases if g else (query,)
    return "(" + "|".join(word_re(a) for a in alts) + ")"


def rows_for(con: duckdb.DuckDBPyConnection, parquet: str, taxonomy: Taxonomy, query: str, per_site: int, rule) -> list[dict]:
    g = taxonomy.group_for_name(query)
    regex = candidate_regex(taxonomy, query)
    con.execute("CREATE OR REPLACE TEMP TABLE c AS SELECT * FROM read_parquet(?) WHERE 단위가격_100 > 0 AND regexp_matches(lower(상품명), ?)",
                [parquet, regex])
    unit = UNIT_BACK.get(g.base_unit) if g else None
    if unit is None:
        unit = con.execute("SELECT 단위 FROM c GROUP BY 1 ORDER BY count(*) DESC LIMIT 1").fetchone()
        unit = unit[0] if unit else "g"
    rows = con.execute(f"""
        WITH d AS (SELECT * FROM c WHERE 단위 = ? QUALIFY row_number() OVER (PARTITION BY 사이트, 상품번호 ORDER BY 단위가격_100, 판매가) = 1),
             r AS (SELECT *, row_number() OVER (PARTITION BY 사이트 ORDER BY 단위가격_100, 판매가, 상품번호) AS rk FROM d)
        SELECT 사이트, rk, 상품명, 규격, 판매가, 단위, 단위가격_100 FROM r WHERE rk <= {int(per_site)} ORDER BY 사이트, rk""", [unit]).fetchall()
    return [{"query": query, "group": g.code if g else "", "site": s, "rank": rk, "product_name": n, "spec": sp or "", "price": p,
             "unit": u, "unit_price_100": up, "rule": int(rule(n, query)) if rule else "", "truth": "", "note": ""}
            for s, rk, n, sp, p, u, up in rows]


def load_rule(crawler_src: Path):
    """크롤러 match.is_exact (있을 때만). 없으면 None — rule 칸을 비운다."""
    if not (crawler_src / "wiggly" / "prices" / "match.py").is_file():
        return None
    sys.path.insert(0, str(crawler_src))
    from wiggly.prices.match import is_exact  # noqa: E402
    return is_exact


def main(argv=None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--parquet", required=True, type=Path)
    ap.add_argument("--out", type=Path, default=ROOT / "data" / "market" / "testset.csv")
    ap.add_argument("--per-site", type=int, default=6)
    ap.add_argument("--queries", default=",".join(QUERIES))
    ap.add_argument("--crawler-src", type=Path, default=ROOT.parent / "foodspring-crawler" / "src")
    a = ap.parse_args(argv)
    taxonomy = Taxonomy.load()
    rule = load_rule(a.crawler_src)
    con = duckdb.connect()
    out: list[dict] = []
    for q in [x.strip() for x in a.queries.split(",") if x.strip()]:
        rows = rows_for(con, a.parquet.as_posix(), taxonomy, q, a.per_site, rule)
        out.extend(rows)
        print(f"{q}: 사이트 {len({r['site'] for r in rows})} · 줄 {len(rows)}")
    a.out.parent.mkdir(parents=True, exist_ok=True)
    with open(a.out, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=COLUMNS)
        w.writeheader()
        w.writerows(out)
    print(f"{len(out)}줄 → {a.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
