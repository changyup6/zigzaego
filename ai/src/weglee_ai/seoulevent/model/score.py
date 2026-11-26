"""보관된 예측표 채점: `python -m weglee_ai.seoulevent.model.score` → data/out/forecast_scores.csv (매주 weekly 가 실행)

data/out/forecast_archive/forecast{,_h3}_YYYYMMDD.csv (예측표를 만든 날 보관) 중 실제 승하차가 공개된 날짜만 v3 정답(spikes.csv)과 맞춰 본다.
지표: 높은 위험 · 행사장 참고 경보 정밀도 · 재현율, 날짜 단위(그날 높은 위험 이 있었나 vs 그날 행사장 밖 급증이 있었나), 예상 인원 오차(중앙값 %).
시간순 검증 숫자(개발용 진단)와 달리 실제 운영에서 낸 예측 그대로의 성적이다 — 표본이 쌓일 때까지 해석은 조심.
"""

import sys

import numpy as np
import pandas as pd

from weglee_ai.seoulevent import config
from weglee_ai.seoulevent.build.district_day import VENUE_PREFIX, with_venue_areas
from weglee_ai.seoulevent.model.experiment import ALERT_HIGH, ALERT_VENUE
from weglee_ai.seoulevent.model.forecast import forecast_dir


def _codes(fc: pd.DataFrame, names: pd.Series) -> pd.Series:
    """상권 코드 — 열이 없는 옛 예측표는 이름으로 찾고, 같은 이름이 여러 상권이면 빈 값 (채점에서 뺀다)."""
    if "상권 코드" in fc:
        return fc["상권 코드"].astype(str)
    uniq = names[~names.duplicated(keep=False)]
    return fc["상권"].map(pd.Series(uniq.index, index=uniq.to_numpy()))


def score_one(fc: pd.DataFrame, day: pd.DataFrame, spikes: pd.DataFrame, names: pd.Series) -> dict | None:
    fc = fc.assign(trdar_cd=_codes(fc, names), date=pd.to_datetime(fc["날짜"]).dt.date).dropna(subset=["trdar_cd"])
    m = fc.merge(day[["trdar_cd", "date", "riders"]], on=["trdar_cd", "date"])  # 실제 승하차가 나온 날만
    if m.empty:
        return None
    sp = set(zip(spikes["trdar_cd"].astype(str), spikes["date"]))
    m["spike"] = [(t, d) in sp for t, d in zip(m["trdar_cd"], m["date"])]
    venue = m["trdar_cd"].str.startswith(VENUE_PREFIX)
    red, ven = m["경보"].eq(ALERT_HIGH), m["경보"].eq(ALERT_VENUE)
    o = m[~venue]
    days = o.groupby("date").agg(alert=("경보", lambda a: a.eq(ALERT_HIGH).any()), spike=("spike", "any"))
    err = ((m["예상 인원"] - m["riders"]).abs() / m["riders"]).replace(np.inf, np.nan)
    return {"날짜 범위": f"{min(m['date'])} ~ {max(m['date'])}", "채점 날수": m["date"].nunique(), "상권-일": len(m),
            "높은 위험 경보": int(red.sum()), "높은 위험 적중": int((red & m["spike"]).sum()), "행사장 밖 급증": int(o["spike"].sum()),
            "행사장 참고 경보": int(ven.sum()), "행사장 참고 적중": int((ven & m["spike"]).sum()), "행사장 급증": int(m.loc[venue, "spike"].sum()),
            "붐비는 날 경보": int(days["alert"].sum()), "붐비는 날 적중": int((days["alert"] & days["spike"]).sum()), "급증 있던 날": int(days["spike"].sum()),
            "예상 인원 오차 중앙값": float(err.median())}


def main(argv: list[str] | None = None) -> int:
    cfg = config.load()
    out = cfg.data_dir / "out"
    day = pd.read_parquet(out / "district_day.parquet", columns=["trdar_cd", "date", "riders"])
    spikes = pd.read_csv(out / "spikes.csv", encoding="utf-8-sig", dtype={"trdar_cd": str}, parse_dates=["date"])
    spikes["date"] = spikes["date"].dt.date
    names = with_venue_areas(pd.read_parquet(cfg.data_dir / "raw" / "district_areas" / "areas.parquet")).set_index("TRDAR_CD")["TRDAR_CD_NM"]
    rows = []
    fdir = forecast_dir(out)
    for p in sorted((fdir / "archive").glob("forecast*.csv")):
        r = score_one(pd.read_csv(p, encoding="utf-8-sig", dtype={"상권 코드": str}), day, spikes, names)
        rows.append({"예측표": p.name} | (r or {"날짜 범위": "실제 승하차 아직 없음"}))
    res = pd.DataFrame(rows)
    res.to_csv(fdir / "scores.csv", index=False, encoding="utf-8-sig")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
