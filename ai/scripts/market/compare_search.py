"""검색 방식 비교: uv run python scripts/market/compare_search.py --parquet <products.parquet> [--testset data/market/testset.csv]
[--labels data/market/labels.parquet]   (S15P31A308-228)

시험지(make_testset.py 로 만들고 truth 를 채운 CSV)의 검색어 × 사이트마다, 네 방식이 고른 사이트별 1위가 맞는지 잰다.
- rule   : 지금 규칙(크롤러 match.is_exact = 백엔드 ItemMatcher). --crawler-src 가 없으면 뺀다
- prefix : 검색어(이명)가 상품명의 앞말인가 (괄호 · ★ · [] 를 뗀 뒤)
- morph  : 형태소 검색 엔진 대용 — Kiwi 형태소 중 검색어(이명)가 독립 형태소로 있는가 (Elasticsearch nori 와 같은 원리)
- llm    : LLM 사전 배치 라벨(labels.parquet)의 품목군 코드가 검색어 품목군과 같고 form 이 RAW · PREPARED 인가. 라벨 파일이 없으면 뺀다
- vector : 선택. --vector-model(sentence-transformers 모델 이름)을 주면 검색어 · 상품명 임베딩 코사인 ≥ --vector-threshold
측정: 정밀도(보여 준 1위가 그 품목) · 재현율(진짜 최저가 상품을 1위로) · 칸 정확도(210 기준: 1위가 맞거나 없을 때 '없음') · 검색 1번 시간.
결과는 마크다운으로 찍고 --out 에 저장한다. 사이트에는 요청하지 않는다.
"""
from __future__ import annotations

import argparse
import csv
import re
import statistics
import sys
import time
from collections import defaultdict
from pathlib import Path

import duckdb

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "src"))

from weglee_ai.market.classify import MIN_CONFIDENCE  # noqa: E402
from weglee_ai.market.taxonomy import Taxonomy, word_re  # noqa: E402

LEAD = re.compile(r"^(\s*(\[[^\]]*\]|\([^)]*\)|★[^★]*★|[^\w가-힣]+))*\s*")


def load_rule(crawler_src: Path):
    if not (crawler_src / "wiggly" / "prices" / "match.py").is_file():
        return None
    sys.path.insert(0, str(crawler_src))
    from wiggly.prices.match import is_exact  # noqa: E402
    return is_exact


def aliases_for(taxonomy: Taxonomy, query: str) -> tuple[str, ...]:
    g = taxonomy.group_for_name(query)
    return g.aliases if g else (query,)


# ---- 방식 (상품명, 검색어) → 정확한가 ----

def prefix_method(taxonomy: Taxonomy):
    def f(name: str, query: str) -> bool:
        head = LEAD.sub("", name.lower())
        return any(head.startswith(a.lower()) for a in aliases_for(taxonomy, query))
    return f


def morph_method(taxonomy: Taxonomy):
    from kiwipiepy import Kiwi
    kiwi = Kiwi()
    cache: dict[str, set[str]] = {}

    def f(name: str, query: str) -> bool:
        if name not in cache:
            cache[name] = {t.form for t in kiwi.tokenize(name)}
        return any(a in cache[name] for a in aliases_for(taxonomy, query))
    return f


def llm_method(taxonomy: Taxonomy, labels: dict[str, tuple[str | None, str, float]]):
    def f(name: str, query: str) -> bool:
        g = taxonomy.group_for_name(query)
        lab = labels.get(name)
        if g is None or lab is None:
            return False
        code, form, conf = lab
        return code == g.code and form in ("RAW", "PREPARED") and conf >= MIN_CONFIDENCE
    return f


def vector_method(model_name: str, threshold: float):
    from sentence_transformers import SentenceTransformer
    model = SentenceTransformer(model_name)
    cache: dict[str, list[float]] = {}

    def emb(text: str):
        if text not in cache:
            cache[text] = model.encode(text, normalize_embeddings=True)
        return cache[text]

    def f(name: str, query: str) -> bool:
        return float(emb(name) @ emb(query)) >= threshold
    return f


def load_labels(path: Path) -> dict[str, tuple[str | None, str, float]]:
    rows = duckdb.sql(f"SELECT product_name, item_group_code, form, confidence FROM read_parquet('{path.as_posix()}')").fetchall()
    return {n: (c, f, float(conf or 0)) for n, c, f, conf in rows}


# ---- 평가 ----

def evaluate(rows: list[dict], methods: dict) -> tuple[dict, dict]:
    """→ ({방식: 지표}, {방식: [새어 나온 사례]}). 칸 = (검색어, 사이트). 줄은 rank 순(단가 싼 순)."""
    cells: dict[tuple[str, str], list[dict]] = defaultdict(list)
    for r in rows:
        cells[(r["query"], r["site"])].append(r)
    for v in cells.values():
        v.sort(key=lambda r: int(r["rank"]))
    stats = {m: {"cells": 0, "shown": 0, "shown_ok": 0, "positives": 0, "best_hit": 0, "ok": 0, "unlabeled": 0} for m in methods}
    leaks: dict[str, list[dict]] = {m: [] for m in methods}
    for (q, site), lines in cells.items():
        if any(r["truth"] == "" for r in lines):
            for m in methods:
                stats[m]["unlabeled"] += 1
            continue
        best = next((r for r in lines if r["truth"] == "1"), None)
        for m, f in methods.items():
            s = stats[m]
            s["cells"] += 1
            pick = next((r for r in lines if f(r["product_name"], q)), None)
            if best is not None:
                s["positives"] += 1
            if pick is None:
                s["ok"] += best is None
                continue
            s["shown"] += 1
            if pick["truth"] == "1":
                s["shown_ok"] += 1
                s["ok"] += 1
                s["best_hit"] += pick is best
            else:
                leaks[m].append({"query": q, "site": site, "product_name": pick["product_name"], "note": pick.get("note", "")})
    return stats, leaks


def timing(parquet: str, queries: list[str], taxonomy: Taxonomy, methods: dict, labels_path: Path | None, runs: int = 3) -> dict[str, float]:
    """검색 1번 시간(초, 검색어 평균): 후보 regex → 방식 판정 → 사이트별 1위. llm 은 라벨 표 조인."""
    con = duckdb.connect()
    if labels_path and labels_path.is_file():
        con.execute(f"CREATE TABLE labels AS SELECT * FROM read_parquet('{labels_path.as_posix()}')")
    out: dict[str, list[float]] = defaultdict(list)
    for q in queries:
        regex = "(" + "|".join(word_re(a) for a in aliases_for(taxonomy, q)) + ")"
        g = taxonomy.group_for_name(q)
        for m, f in methods.items():
            for _ in range(runs):
                t0 = time.perf_counter()
                if m == "llm" and g is not None:
                    con.execute("""SELECT p.사이트, min(p.단위가격_100) FROM read_parquet(?) p JOIN labels l ON l.product_name = p.상품명
                                   WHERE l.item_group_code = ? AND l.form IN ('RAW', 'PREPARED') AND l.confidence >= ? AND p.단위가격_100 > 0
                                   GROUP BY 1""", [parquet, g.code, MIN_CONFIDENCE]).fetchall()
                else:
                    cand = con.execute("SELECT 사이트, 상품명, 단위가격_100 FROM read_parquet(?) WHERE 단위가격_100 > 0 AND regexp_matches(lower(상품명), ?)",
                                       [parquet, regex]).fetchall()
                    best: dict[str, float] = {}
                    for site, name, up in sorted(cand, key=lambda r: r[2]):
                        if site not in best and f(name, q):
                            best[site] = up
                out[m].append(time.perf_counter() - t0)
    return {m: statistics.mean(v) for m, v in out.items()}


def report(stats: dict, leaks: dict, times: dict, n_queries: int, notes: dict[str, str]) -> str:
    lines = [f"검색어 {n_queries}개 · 칸 = 검색어 × 사이트. 정밀도 = 보여 준 1위가 그 품목인 비율, 재현율 = 그 사이트의 진짜 최저가 상품을 1위로 보여 준 비율, "
             "칸 정확도 = 1위가 맞거나(품목 없는 사이트는) '없음'으로 뺀 비율.", "",
             "| 방식 | 칸 정확도 | 정밀도 | 재현율 | 보여 준 칸 | 검색 1번 | 준비 · 비용 |", "|---|---|---|---|---|---|---|"]
    for m, s in stats.items():
        prec = s["shown_ok"] / s["shown"] if s["shown"] else 0
        rec = s["best_hit"] / s["positives"] if s["positives"] else 0
        acc = s["ok"] / s["cells"] if s["cells"] else 0
        lines.append(f"| {m} | {s['ok']}/{s['cells']} ({acc:.0%}) | {prec:.0%} | {rec:.0%} | {s['shown']} | {times.get(m, 0):.2f}초 | {notes.get(m, '')} |")
    for m, items in leaks.items():
        if items:
            lines += ["", f"### {m} — 1위로 새어 나온 사례 {len(items)}건", "", "| 검색어 | 사이트 | 상품명 | 메모 |", "|---|---|---|---|"]
            lines += [f"| {x['query']} | {x['site']} | {x['product_name'][:50]} | {x['note']} |" for x in items]
    return "\n".join(lines)


def main(argv=None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--parquet", required=True, type=Path)
    ap.add_argument("--testset", type=Path, default=ROOT / "data" / "market" / "testset.csv")
    ap.add_argument("--labels", type=Path, default=ROOT / "data" / "market" / "labels.parquet")
    ap.add_argument("--crawler-src", type=Path, default=ROOT.parent / "foodspring-crawler" / "src")
    ap.add_argument("--vector-model", default="", help="예: intfloat/multilingual-e5-small (sentence-transformers 필요)")
    ap.add_argument("--vector-threshold", type=float, default=0.85)
    ap.add_argument("--runs", type=int, default=3)
    ap.add_argument("--out", type=Path, default=None)
    a = ap.parse_args(argv)
    taxonomy = Taxonomy.load()
    with open(a.testset, encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))
    queries = list(dict.fromkeys(r["query"] for r in rows))

    methods: dict = {}
    notes: dict[str, str] = {}
    rule = load_rule(a.crawler_src)
    if rule:
        methods["rule"] = rule
        notes["rule"] = "없음. 규칙 목록을 손으로 늘려야 함"
    methods["prefix"] = prefix_method(taxonomy)
    notes["prefix"] = "없음"
    methods["morph"] = morph_method(taxonomy)
    notes["morph"] = "ES 라면 새 인프라 + 매주 색인. 여기선 Kiwi 로 원리만 측정"
    if a.labels.is_file():
        methods["llm"] = llm_method(taxonomy, load_labels(a.labels))
        notes["llm"] = "배치 1회(labels_run_*.md 의 토큰 · 시간). 검색 시 0"
    if a.vector_model:
        methods["vector"] = vector_method(a.vector_model, a.vector_threshold)
        notes["vector"] = f"{a.vector_model} 임베딩 전체 1회 + 벡터 색인"

    stats, leaks = evaluate(rows, methods)
    times = timing(a.parquet.as_posix(), queries, taxonomy, methods, a.labels if "llm" in methods else None, a.runs)
    unlabeled = next(iter(stats.values()))["unlabeled"] if stats else 0
    md = report(stats, leaks, times, len(queries), notes)
    if unlabeled:
        md += f"\n\n정답(truth)이 비어 있어 뺀 칸: {unlabeled}"
    print(md)
    if a.out:
        a.out.write_text(md + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
