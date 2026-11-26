"""식자재몰 상품 품목군 라벨 배치: uv run python scripts/market/classify_products.py --parquet <products.parquet> [옵션]   (S15P31A308-229)

데이터셋 Parquet 의 고유 상품명 중 품목군 별칭이 든 것(후보)만 LLM 에 보내 라벨을 붙이고 data/market/labels.parquet 에 모은다.
이미 같은 label_version 라벨이 있는 상품명은 보내지 않는다(증분). 사이트에는 요청하지 않는다.
  --groups EGG,SCALLION   이 품목군 후보만(228 비교 실험용 부분 실행)
  --limit N               후보 N개까지만
  --dry-run               LLM 없이 흐름 · 비용 추정만(fake_chat)
  --batch 40              한 요청에 넣는 상품명 수
환경 변수(또는 ai/.env): GMS_KEY · GMS_BASE_URL · GMS_MODEL. 결과: labels.parquet · labels.csv(적재용) · labels_run_<시각>.md(비용 · 시간 기록)
"""
from __future__ import annotations

import argparse
import os
import sys
import time
from datetime import datetime
from pathlib import Path

import duckdb

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "src"))

from weglee_ai.llm import DailyQuotaError  # noqa: E402
from weglee_ai.market import classify as cl  # noqa: E402
from weglee_ai.market.taxonomy import Taxonomy  # noqa: E402
from weglee_ai.ocr.http import ApiError  # noqa: E402
from weglee_ai.ocr.runner import load_env  # noqa: E402

GMS_VARS = ("GMS_KEY", "GMS_BASE_URL", "GMS_MODEL")
SAVE_EVERY = 25  # 묶음 몇 개마다 중간 저장


def distinct_names(parquet: Path) -> list[str]:
    rows = duckdb.sql(f"SELECT DISTINCT 상품명 FROM read_parquet('{parquet.as_posix()}') WHERE 상품명 IS NOT NULL ORDER BY 1").fetchall()
    return [r[0] for r in rows]


def load_labels(path: Path) -> list[dict]:
    if not path.is_file():
        return []
    cur = duckdb.sql(f"SELECT {', '.join(cl.LABEL_COLUMNS)} FROM read_parquet('{path.as_posix()}')")
    cols = [d[0] for d in cur.description]
    return [dict(zip(cols, r, strict=False)) for r in cur.fetchall()]


def save_labels(rows: list[dict], out_dir: Path) -> None:
    """labels.parquet · labels.csv 를 **임시 파일에 쓴 뒤 바꿔치기** — 쓰다가 실패해도 이전 파일은 그대로 남는다."""
    out_dir.mkdir(parents=True, exist_ok=True)
    con = duckdb.connect()
    con.execute("CREATE TABLE l (product_name VARCHAR, item_group_code VARCHAR, form VARCHAR, qty DOUBLE, qty_unit VARCHAR, "
                "confidence DOUBLE, reason VARCHAR, label_version VARCHAR)")
    if rows:
        con.executemany("INSERT INTO l VALUES (?, ?, ?, ?, ?, ?, ?, ?)", [[r[c] for c in cl.LABEL_COLUMNS] for r in rows])
    targets = {out_dir / "labels.parquet": "(FORMAT PARQUET)",
               out_dir / "labels.csv": "(FORMAT CSV, HEADER, DELIMITER ',', QUOTE '\"', ESCAPE '\"')"}
    tmps = {p: p.with_name(p.name + ".tmp") for p in targets}
    try:
        for p, fmt in targets.items():
            con.execute(f"COPY l TO '{tmps[p].as_posix()}' {fmt}")
        for p in targets:
            os.replace(tmps[p], p)
    finally:
        for t in tmps.values():
            t.unlink(missing_ok=True)


def save_labels_keeping_error(rows: list[dict], out_dir: Path) -> None:
    """finally 블록용: 저장이 실패해도 **이미 올라가는 중인 예외를 덮지 않는다**. 다른 예외가 없을 때만 저장 실패를 올린다."""
    in_flight = sys.exc_info()[1]
    try:
        save_labels(rows, out_dir)
    except Exception as e:  # noqa: BLE001 — 원래 오류를 먼저 보고한다
        if in_flight is None:
            raise
        print(f"라벨 저장 실패 (원래 오류를 먼저 보고): {e}", file=sys.stderr)


def select_targets(names: list[str], taxonomy: Taxonomy, done: set[str], groups: set[str] | None, limit: int | None) -> list[str]:
    out = []
    for n in names:
        if n in done:
            continue
        codes = taxonomy.candidate_codes(n)
        if not codes or (groups and not groups.intersection(codes)):
            continue
        out.append(n)
        if limit and len(out) >= limit:
            break
    return out


def write_run_log(out_dir: Path, *, parquet: Path, model: str, targets: int, done: int, usage: dict, seconds: float,
                  stopped: str | None, forms: dict) -> Path:
    stamp = datetime.now().strftime("%Y%m%d_%H%M")
    p = out_dir / f"labels_run_{stamp}.md"
    lines = [f"# 품목군 라벨 배치 실행 기록 ({stamp})", "",
             f"- 입력: `{parquet.name}` · 모델: `{model}` · 라벨 버전: `{cl.LABEL_VERSION}`",
             f"- 대상 상품명 {targets:,}개 중 {done:,}개 라벨 · 걸린 시간 {seconds / 60:.1f}분" + (f" · **멈춤: {stopped}**" if stopped else ""),
             f"- 요청 {usage.get('requests', 0):,}번 (재시도 {usage.get('retries', 0)}) · 입력 토큰 {usage.get('prompt_tokens', 0):,} · "
             f"출력 토큰 {usage.get('completion_tokens', 0):,}",
             "- 형태별: " + " · ".join(f"{k} {v:,}" for k, v in sorted(forms.items())), ""]
    p.write_text("\n".join(lines), encoding="utf-8")
    return p


def main(argv=None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description="식자재몰 상품 품목군 라벨 배치")
    ap.add_argument("--parquet", required=True, type=Path)
    ap.add_argument("--out", type=Path, default=ROOT / "data" / "market")
    ap.add_argument("--groups", default="", help="쉼표로 나눈 품목군 코드. 이 품목군 후보만")
    ap.add_argument("--limit", type=int, default=None)
    ap.add_argument("--batch", type=int, default=cl.BATCH_SIZE)
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--env-file", type=Path, default=ROOT / ".env")
    a = ap.parse_args(argv)
    if not a.parquet.is_file():
        print(f"멈춤: Parquet 없음 — {a.parquet}")
        return 1
    if a.dry_run:
        a.out = a.out / "dry-run"  # 가짜 라벨이 진짜 labels.parquet 에 섞이지 않게

    taxonomy = Taxonomy.load()
    existing = load_labels(a.out / "labels.parquet")
    done = {r["product_name"] for r in existing if r["label_version"] == cl.LABEL_VERSION}
    groups = {g.strip().upper() for g in a.groups.split(",") if g.strip()} or None
    for g in groups or ():
        if taxonomy.get(g) is None:
            print(f"멈춤: 모르는 품목군 코드 {g}")
            return 1
    names = distinct_names(a.parquet)
    targets = select_targets(names, taxonomy, done, groups, a.limit)
    print(f"고유 상품명 {len(names):,} · 이미 라벨 {len(done):,} · 이번 대상 {len(targets):,} (묶음 {a.batch}개 → 요청 약 {-(-len(targets) // a.batch):,}번)")
    if not targets:
        return 0

    if a.dry_run:
        chat, model = cl.fake_chat(taxonomy), "dry-run"
    else:
        env = load_env(os.environ, a.env_file)
        missing = [k for k in GMS_VARS if not str(env.get(k) or "").strip()]
        if missing:
            print(f"멈춤: 환경 변수 없음 {missing} — ai/.env 또는 환경 변수에 적으세요 (--dry-run 은 키 없이 돕니다)")
            return 1
        model = env["GMS_MODEL"]
        chat = cl.chat_with_temperature_fallback(env["GMS_BASE_URL"], env["GMS_KEY"], model)  # temperature: 받는 모델은 0, 아니면 생략(자동)

    by_name = {r["product_name"]: r for r in existing}  # 기존 라벨은 그대로 두고, 새 라벨이 오면 이름 기준으로 교체
    total_usage: dict = {}
    forms: dict = {}
    n_done, stopped, t0 = 0, None, time.perf_counter()
    try:
        for i, (labels, usage) in enumerate(cl.classify(targets, chat, taxonomy, batch_size=a.batch), 1):
            cl.merge_labels(by_name, labels)
            for label in labels:
                forms[label.form] = forms.get(label.form, 0) + 1
            for k, v in usage.items():
                total_usage[k] = total_usage.get(k, 0) + v
            n_done += len(labels)
            if i % SAVE_EVERY == 0:
                save_labels(list(by_name.values()), a.out)
                print(f"  {n_done:,}/{len(targets):,} · 요청 {total_usage.get('requests', 0):,} · {(time.perf_counter() - t0) / 60:.1f}분")
    except DailyQuotaError as e:
        stopped = f"하루 한도 소진 — 내일 다시 돌리면 이어서 (상세: {str(e)[:80]})"
    except ApiError as e:
        stopped = f"API 오류 — {str(e)[:120]}"
    except KeyboardInterrupt:
        stopped = "사용자 중단"
    finally:
        save_labels_keeping_error(list(by_name.values()), a.out)  # 어떤 예외로 끝나도 그때까지의 라벨은 남긴다
    seconds = time.perf_counter() - t0
    log = write_run_log(a.out, parquet=a.parquet, model=model, targets=len(targets), done=n_done, usage=total_usage,
                        seconds=seconds, stopped=stopped, forms=forms)
    print(f"라벨 {n_done:,}개 ({seconds / 60:.1f}분, 요청 {total_usage.get('requests', 0):,} · 입력 {total_usage.get('prompt_tokens', 0):,} · "
          f"출력 {total_usage.get('completion_tokens', 0):,} 토큰) → {a.out / 'labels.parquet'} · 기록 {log.name}"
          + (f"\n멈춤: {stopped}" if stopped else ""))
    return 2 if stopped else 0


if __name__ == "__main__":
    raise SystemExit(main())
