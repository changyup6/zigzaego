"""예측 vs 실제 채점표 (지라 S15P31A308-78). 배치와 같은 DB 환경변수(DB_URL · DB_USERNAME · DB_PASSWORD)를 쓴다.

uv sync --group batch
uv run python scripts/forecast_accuracy.py --store 1 [--days 7 | --start 2026-10-01 --end 2026-10-14]

모델 × 채널 WAPE · Bias 와 채널 합산을 표준 출력으로 낸다. 결과는 저장하지 않는다 (DECISIONS #51).
"""

import argparse
import os
from datetime import date, timedelta

from weglee_ai.accuracy import AccuracyRepository, join_actual, score
from weglee_ai.batch.db import DbConfig


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--store", type=int, required=True)
    parser.add_argument("--days", type=int, default=7, help="어제까지 며칠 (기본 7). --start 를 주면 무시")
    parser.add_argument("--start", type=date.fromisoformat)
    parser.add_argument("--end", type=date.fromisoformat)
    args = parser.parse_args()
    end = args.end or date.today() - timedelta(days=1)
    start = args.start or end - timedelta(days=args.days - 1)
    if args.days < 1 or start > end:
        parser.error("--days 는 1 이상, --start 는 --end 보다 늦을 수 없다")

    repo = AccuracyRepository.connect(DbConfig.from_env(os.environ))
    try:
        fc, sales = repo.inputs(args.store, start, end)
    finally:
        repo.close()
    if fc.empty or sales.empty:
        print(f"가게 {args.store} {start} ~ {end}: 예측 또는 판매가 없다")
        return
    joined = join_actual(fc, sales)
    if joined.empty:
        print(f"가게 {args.store} {start} ~ {end}: 영업일에 해당하는 예측이 없다 (전부 휴무 · 판매 기록 밖)")
        return
    print(f"가게 {args.store} · {start} ~ {end}")
    print(score(joined).to_string(index=False, float_format=lambda v: f"{v:.3f}"))


if __name__ == "__main__":
    main()
