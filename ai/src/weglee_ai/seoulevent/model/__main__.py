"""`python -m weglee_ai.seoulevent.model` → data/out/model_eval.csv · model_pred_top3.csv

학습 ~2024-12-31, 시험 2025-01-01~. 고른 상권(docs/district-candidates.md)의 시험 기간 예측 상위 날짜와 근거를 낸다.
model_eval.csv 는 3차 기본 모델(FEATURES, 실측 날씨 포함) 기록이다. 대표 성능은 `python -m weglee_ai.seoulevent.model.experiment` 의
experiment_spike.csv (운영 조건 · 날씨 제외) — docs/model-226.md 맨 위 '대표값'.
"""

from datetime import date

import pandas as pd

from weglee_ai.seoulevent import config, holidays_kr
from weglee_ai.seoulevent.build.spikes import WEEKDAYS
from weglee_ai.seoulevent.model import train
from weglee_ai.seoulevent.model.features import build_features

TEST_START = date(2025, 1, 1)
CHOSEN = ["월드컵경기장역(월드컵경기장)", "국회의사당역(국회의사당)", "여의도역(여의도)", "잠실 관광특구"]


def main() -> None:
    cfg = config.load()
    out = cfg.data_dir / "out"
    day = pd.read_parquet(out / "district_day.parquet")
    spikes = pd.read_csv(out / "spikes.csv", encoding="utf-8-sig", dtype={"trdar_cd": str}, parse_dates=["date"])
    spikes["date"] = spikes["date"].dt.date
    years = range(min(day["date"]).year, max(day["date"]).year + 1)
    holidays = holidays_kr.load(cfg.data_dir / "raw", years)

    feat = build_features(day, spikes, holidays)
    tr, te = train.split(feat, TEST_START)
    model = train.fit(tr)
    ev = train.evaluate(model, te)
    ev.to_csv(out / "model_eval.csv", encoding="utf-8-sig")

    names = pd.read_parquet(cfg.data_dir / "raw" / "district_areas" / "areas.parquet").set_index("TRDAR_CD_NM")["TRDAR_CD"]
    rows = []
    for nm in CHOSEN:
        cd = names.get(nm)
        g = te[te["trdar_cd"] == cd]
        if g.empty:  # 지하철 반경 밖 상권은 일 단위 표에 없다
            continue
        g = g.assign(pred=train.predict_ratio(model, g)).nlargest(5, "pred")
        for r in g.itertuples(index=False):
            s = pd.Series(r._asdict())
            rows.append({"상권": nm, "날짜": r.date, "요일": WEEKDAYS[r.weekday], "예측 배수": round(r.pred, 2),
                         "실제 배수": round(r.ratio, 2), "근거": train.explain(s)})
    pred = pd.DataFrame(rows)
    pred.to_csv(out / "model_pred_top3.csv", index=False, encoding="utf-8-sig")


if __name__ == "__main__":
    main()
