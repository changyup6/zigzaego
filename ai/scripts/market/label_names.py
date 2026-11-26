"""상품명 목록 파일(CSV 의 product_name 열)만 라벨링: 시험지(testset.csv) 상품명으로 228 비교표의 LLM 칸을 채운다.
후보 거르기(classify_products.select_targets)를 하지 않고 파일의 상품명을 전부 보낸다 — 시험지 줄은 이미 후보 regex 로 만든 것이다(기준 문서 9절 예외).
uv run python scripts/market/label_names.py --names <csv> --env-file <.env> [--out data/market]
"""
from __future__ import annotations

import argparse
import csv
import os
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "src"))
sys.path.insert(0, str(ROOT / "scripts" / "market"))
from classify_products import load_labels, save_labels, save_labels_keeping_error, write_run_log  # noqa: E402

from weglee_ai.llm import DailyQuotaError  # noqa: E402
from weglee_ai.market import classify as cl  # noqa: E402
from weglee_ai.market.taxonomy import Taxonomy  # noqa: E402
from weglee_ai.ocr.http import ApiError  # noqa: E402
from weglee_ai.ocr.runner import load_env  # noqa: E402


def main(argv=None) -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--names", required=True, type=Path)
    ap.add_argument("--env-file", required=True, type=Path)
    ap.add_argument("--out", type=Path, default=ROOT / "data" / "market")
    ap.add_argument("--batch", type=int, default=40)
    a = ap.parse_args(argv)
    taxonomy = Taxonomy.load()
    with open(a.names, encoding="utf-8-sig", newline="") as f:
        names = list(dict.fromkeys(r["product_name"] for r in csv.DictReader(f)))
    existing = load_labels(a.out / "labels.parquet")
    done = {r["product_name"] for r in existing if r["label_version"] == cl.LABEL_VERSION}
    targets = [n for n in names if n not in done]
    print(f"상품명 {len(names)} · 이미 라벨 {len(names) - len(targets)} · 이번 대상 {len(targets)}")
    env = load_env(dict(os.environ), a.env_file)
    model = env["GMS_MODEL"]
    chat = cl.chat_with_temperature_fallback(env["GMS_BASE_URL"], env["GMS_KEY"], model)  # temperature: 받는 모델은 0, 아니면 생략(자동)
    by_name = {r["product_name"]: r for r in existing}  # 기존 라벨은 그대로, 새 라벨이 오면 이름 기준 교체
    usage, forms, n, stopped, t0 = {}, {}, 0, None, time.perf_counter()
    try:
        for labels, u in cl.classify(targets, chat, taxonomy, batch_size=a.batch):
            cl.merge_labels(by_name, labels)
            n += len(labels)
            for label in labels:
                forms[label.form] = forms.get(label.form, 0) + 1
            for k, v in u.items():
                usage[k] = usage.get(k, 0) + v
            save_labels(list(by_name.values()), a.out)
            print(f"  {n}/{len(targets)} · {(time.perf_counter() - t0) / 60:.1f}분", flush=True)
    except DailyQuotaError as e:
        stopped = f"하루 한도 소진 {str(e)[:80]}"
    except ApiError as e:
        stopped = f"API 오류 {str(e)[:300]}"
    finally:
        save_labels_keeping_error(list(by_name.values()), a.out)  # 어떤 예외로 끝나도 그때까지의 라벨은 남긴다
    log = write_run_log(a.out, parquet=a.names, model=model, targets=len(targets), done=n, usage=usage,
                        seconds=time.perf_counter() - t0, stopped=stopped, forms=forms)
    print(f"라벨 {n}개 · 요청 {usage.get('requests', 0)} · 입력 {usage.get('prompt_tokens', 0):,} · 출력 {usage.get('completion_tokens', 0):,} · {log.name}"
          + (f"\n멈춤: {stopped}" if stopped else ""))
    return 2 if stopped else 0

if __name__ == "__main__":
    raise SystemExit(main())
