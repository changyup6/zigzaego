"""행사 효과표: 과거 행사 날 그 상권이 평소의 몇 배였나 → `python -m weglee_ai.seoulevent.model.event_effects`

미래 행사 일정에 곱할 '종류별 · 장소별 효과' 를 과거에서 배운다 (앞으로 열릴 행사를 보고 예측하는 모델의 1단계).
until 을 주면 그 날 이전 행사만 쓴다 — 예측 평가에서 미래 정보가 새지 않게.
출력: data/out/event_effects_kind.csv · event_effects_place.csv
"""

from datetime import date

import pandas as pd

from weglee_ai.seoulevent import config
from weglee_ai.seoulevent.build.district_day import SHORT_EVENT_DAYS

STRONG, SPIKE = 1.3, 2.0


def event_days(events: pd.DataFrame, district_day: pd.DataFrame, until: date | None = None) -> pd.DataFrame:
    """짧은 행사(7일 이하)의 날마다 그날 그 상권의 배수를 붙인다. 몇 달짜리 전시는 기준선에도 들어 있어 효과가 안 보여 뺀다."""
    ev = events[(events["source"] != "holiday") & events["trdar_cd"].notna()]
    ev = ev[[(e - s).days + 1 <= SHORT_EVENT_DAYS for s, e in zip(ev["start_date"], ev["end_date"])]]
    if until is not None:
        ev = ev[ev["start_date"] < until]
    rows = [(r.event_id, r.kind, r.place, r.trdar_cd, d.date())
            for r in ev.itertuples(index=False) for d in pd.date_range(r.start_date, r.end_date)]
    ed = pd.DataFrame(rows, columns=["event_id", "kind", "place", "trdar_cd", "date"])
    if until is not None:
        ed = ed[ed["date"] < until]
    return ed.merge(district_day[["trdar_cd", "date", "ratio"]], on=["trdar_cd", "date"]).dropna(subset=["ratio"])


def effect_table(ed: pd.DataFrame, by: str, min_days: int = 1) -> pd.DataFrame:
    g = ed.groupby(by)
    t = pd.DataFrame({
        "n_events": g["event_id"].nunique(),
        "n_days": g.size(),
        "ratio_median": g["ratio"].median(),
        "ratio_mean": g["ratio"].mean(),
        "share_13x": g["ratio"].apply(lambda s: (s >= STRONG).mean()),
        "share_2x": g["ratio"].apply(lambda s: (s >= SPIKE).mean()),
    }).reset_index()
    return t[t["n_days"] >= min_days].sort_values("ratio_median", ascending=False).reset_index(drop=True)


def main() -> None:
    out = config.load().data_dir / "out"
    events = pd.read_parquet(out / "events.parquet")
    day = pd.read_parquet(out / "district_day.parquet", columns=["trdar_cd", "date", "ratio"])
    ed = event_days(events, day)
    kind = effect_table(ed, "kind")
    place = effect_table(ed, "place", min_days=10)
    kind.to_csv(out / "event_effects_kind.csv", index=False, encoding="utf-8-sig")
    place.to_csv(out / "event_effects_place.csv", index=False, encoding="utf-8-sig")


if __name__ == "__main__":
    main()
