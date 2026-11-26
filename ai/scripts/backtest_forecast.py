"""예측 백테스팅 비교표 (지라 S15P31A308-57, 평가 지표 정의 4절). 결과 md 는 outputs/ 에 쓴다 (커밋하지 않는다).

uv run python scripts/backtest_forecast.py                       # 감 · 요일 평균, 샘플 데이터
uv sync --group batch && uv run python scripts/backtest_forecast.py --timesfm   # TimesFM 까지
uv run python scripts/backtest_forecast.py --data data/processed --timesfm      # 위글리 배달 이력

--data 폴더의 pos_sales.csv (sold_date · menu_name · qty · channel) 를 읽는다.
"""

import argparse
import sys
import time
from pathlib import Path

import pandas as pd

from weglee_ai.backtest import (
    ALL_CHANNELS,
    EVAL_WEEKS,
    LastWeekPredictor,
    backtest,
    eval_period,
    score,
    to_markdown,
    verdict,
)
from weglee_ai.baseline import DowAvgPredictor

AI_DIR = Path(__file__).resolve().parents[1]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", default=str(AI_DIR / "data" / "sample"), help="pos_sales.csv 가 있는 폴더")
    parser.add_argument("--weeks", type=int, default=EVAL_WEEKS, help=f"평가 기간 주 (기본 {EVAL_WEEKS})")
    parser.add_argument("--timesfm", action="store_true", help="TimesFM 2.5 도 돌린다 (uv sync --group batch 필요)")
    parser.add_argument("--out", default=str(AI_DIR / "outputs" / "backtest_forecast.md"))
    args = parser.parse_args()
    if args.weeks < 1:
        parser.error("--weeks 는 1 이상")  # 0 이하면 평가 기간이 비어 빈 표가 나온다 (종료 코드 2)

    sales_path = Path(args.data) / "pos_sales.csv"
    if not sales_path.exists():
        print(f"{sales_path} 없음 — 샘플이면 먼저 uv run python scripts/make_sample_data.py", file=sys.stderr)
        return 1
    sales = pd.read_csv(sales_path, parse_dates=["sold_date"])

    predictors = [LastWeekPredictor(), DowAvgPredictor()]
    if args.timesfm:
        try:
            import timesfm  # noqa: F401 — batch 그룹이 없으면 여기서 바로 알린다

            from weglee_ai.batch.predictors import TimesFmPredictor
        except ImportError:
            print("timesfm 이 없다 — uv sync --group batch 후 다시", file=sys.stderr)
            return 1
        predictors.append(TimesFmPredictor())

    started = time.perf_counter()
    rows = backtest(sales, predictors, eval_weeks=args.weeks)
    elapsed = time.perf_counter() - started
    table = score(rows)
    first, last = eval_period(sales, args.weeks)
    slide = table[(table["구간"] == "h=1") & (table["채널"] == ALL_CHANNELS)][["모델", "WAPE", "Bias", "행"]]

    md = "\n".join([
        "# 예측 백테스팅 비교표",
        "",
        f"- 데이터: `{sales_path}`",
        f"- 평가 기간: {first} ~ {last} ({args.weeks}주), 기준일마다 그날까지의 데이터로 h = 1~7 예측 (rolling origin)",
        f"- 모델: {', '.join(p.name for p in predictors)}. 소요 {elapsed:.1f}초",
        "",
        f"**결론**: {verdict(rows)}",
        "",
        "## 발표용 (채널 합계 · h = 1)",
        "",
        to_markdown(slide),
        "",
        "## 전체 (모델 × 채널 × 구간)",
        "",
        to_markdown(table),
        "",
    ])
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(md, encoding="utf-8")
    print(md)
    print(f"out: {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
