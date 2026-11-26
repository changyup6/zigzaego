"""합성 데이터 기준 예측 백테스트 (지라 S15P31A308-165). 로직은 src/weglee_ai/synthetic_backtest.py.

uv sync --group batch      # TimesFM 을 쓸 때만
uv run python scripts/backtest_synthetic.py [--seed 15] [--days 730] [--holdout-days 56] [--models timesfm,dow_avg]

결과: 표준 출력에 요약 표(markdown), --out(기본 outputs/synthetic_backtest/) 에 CSV 와 report.md. 커밋하지 않는다.
TimesFM 을 못 불러오면 그 예측기만 빠지고 나머지로 결과를 낸다(실패 이름을 찍는다).
"""

import argparse
import logging
import sys
import time
from pathlib import Path

import pandas as pd

from weglee_ai.batch.predictors import DowAvgPredictor, TimesFmPredictor
from weglee_ai.sample_data import DAYS, END_DATE, generate
from weglee_ai.synthetic_backtest import ALL_CHANNELS, HOLDOUT_DAYS, BacktestResult, run_backtest

PREDICTORS = {"timesfm": TimesFmPredictor, "dow_avg": DowAvgPredictor}
OUT = Path(__file__).resolve().parents[1] / "outputs" / "synthetic_backtest"


def md_table(df: pd.DataFrame) -> str:
    def cell(v):
        if isinstance(v, float):
            return f"{v:.3f}" if abs(v) < 100 else f"{v:,.0f}"
        return str(v)

    lines = ["| " + " | ".join(df.columns) + " |", "|" + "---|" * len(df.columns)]
    lines += ["| " + " | ".join(cell(v) for v in row) + " |" for row in df.itertuples(index=False)]
    return "\n".join(lines)


def report(r: BacktestResult, args, seconds: float) -> str:
    p = r.period
    wide_h = r.by_horizon.pivot(index="horizon", columns="model", values="wape").reset_index()
    parts = [
        f"# 합성 데이터 백테스트 (seed {args.seed}, {args.days}일, 끝 {args.end_date})",
        f"학습 구간 {p.train_start} ~ {p.train_end} · 홀드아웃 {p.holdout_start} ~ {p.holdout_end} · "
        f"실패 예측기 {r.failed or '없음'} · {seconds:.1f}초",
        "## 모델 × 채널 (전 지평)", md_table(r.summary),
        "## 지평별 WAPE", md_table(wide_h),
        "## 지평별 WAPE · Bias", md_table(r.by_horizon),
        "## 날 종류별", md_table(r.by_event),
        "## 재료 (BOM 환산, 채널 합)", md_table(r.ingredients),
        "## 메뉴 × 채널", md_table(r.menu_channel),
    ]
    return "\n\n".join(parts) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--seed", type=int, default=15)
    parser.add_argument("--days", type=int, default=DAYS)
    parser.add_argument("--end-date", default=END_DATE)
    parser.add_argument("--holdout-days", type=int, default=HOLDOUT_DAYS)
    parser.add_argument("--models", default="timesfm,dow_avg", help="쉼표 구분. 기본 timesfm,dow_avg")
    parser.add_argument("--out", type=Path, default=OUT)
    args = parser.parse_args()

    logging.basicConfig(level=logging.WARNING, stream=sys.stdout, format="%(levelname)s %(name)s %(message)s")
    names = [n.strip() for n in args.models.split(",")]
    unknown = [n for n in names if n not in PREDICTORS]
    if unknown:
        parser.error(f"--models 에 모르는 이름 {unknown}. 가능: {', '.join(PREDICTORS)}")

    started = time.monotonic()
    data = generate(end_date=args.end_date, days=args.days, seed=args.seed)
    result = run_backtest(data, [PREDICTORS[n]() for n in names], holdout_days=args.holdout_days)
    seconds = time.monotonic() - started

    args.out.mkdir(parents=True, exist_ok=True)
    result.forecasts.to_csv(args.out / "forecasts.csv", index=False)
    for name in ("summary", "menu_channel", "by_horizon", "by_event", "ingredients"):
        getattr(result, name).to_csv(args.out / f"{name}.csv", index=False)
    text = report(result, args, seconds)
    (args.out / "report.md").write_text(text, encoding="utf-8")
    sys.stdout.reconfigure(encoding="utf-8")
    print(text)
    print(f"저장: {args.out}")
    total = result.summary[result.summary["channel"] == ALL_CHANNELS]
    return 1 if total.empty else 0


if __name__ == "__main__":
    sys.exit(main())
