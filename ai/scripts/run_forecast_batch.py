"""야간 예측 배치 실행 (지라 S15P31A308-105). 서버에서는 CronJob 이 매일 03:00 에 부른다 (S15P31A308-91).

uv sync --group batch
DB_URL=jdbc:postgresql://localhost:5432/zigzaego DB_USERNAME=... DB_PASSWORD=... \\
    uv run python scripts/run_forecast_batch.py [--today 2026-10-16] [--models timesfm,dow_avg]

종료 코드: 모든 가게 성공 0, 실패한 가게가 하나라도 있으면 1 (나머지 가게는 끝까지 돈다).
"""

import argparse
import logging
import os
import sys
import time
from datetime import date, datetime
from zoneinfo import ZoneInfo

from weglee_ai.batch.db import DbConfig, PostgresRepository
from weglee_ai.batch.predictors import DowAvgPredictor, TimesFmPredictor
from weglee_ai.batch.run import run_batch

PREDICTORS = {"timesfm": TimesFmPredictor, "dow_avg": DowAvgPredictor}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--today", type=date.fromisoformat, help="기준일 (기본: 가게 시간대의 오늘)")
    parser.add_argument("--models", default="timesfm,dow_avg", help="쉼표 구분. 기본 timesfm,dow_avg")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, stream=sys.stdout, format="%(asctime)s %(levelname)s %(message)s")
    names = [name.strip() for name in args.models.split(",")]
    unknown = [n for n in names if n not in PREDICTORS]
    if unknown:
        parser.error(f"--models 에 모르는 이름 {unknown}. 가능: {', '.join(PREDICTORS)}")
    predictors = [PREDICTORS[name]() for name in names]

    def today_of(store):
        return args.today or datetime.now(ZoneInfo(store.timezone)).date()

    started = time.monotonic()
    repo = PostgresRepository.connect(DbConfig.from_env(os.environ))
    try:
        summary = run_batch(repo, predictors, today_of)
    finally:
        repo.close()
    logging.info(
        "끝: 가게 성공 %d · 실패 %d %s · 저장 %d행 · 예측기 실패 %s · %.1f초",
        summary.stores_ok, len(summary.stores_failed), summary.stores_failed, summary.rows,
        summary.model_failures, time.monotonic() - started,
    )
    return 1 if summary.stores_failed else 0


if __name__ == "__main__":
    sys.exit(main())
