"""226 모델 특징: 달력 · 공휴일 · 작년 같은 시기 급증. 모든 특징은 364일 이상 과거 또는 달력만 본다(시험 기간 누수 없음)."""

import numpy as np
import pandas as pd

from weglee_ai.seoulevent.build.spikes import year_artifact

LAST_YEAR = 364  # 같은 요일
WINDOW = 3  # 작년 같은 날 앞뒤 3일 (행사 날짜는 해마다 조금씩 움직인다)
RATIO_CLIP = (0.05, 20.0)
FEATURES = ["weekday", "month", "is_holiday", "holiday_before", "holiday_after",
            "ly_spike", "ly_max_ratio", "ly_city_spikes", "ly_ratio", "log_base", "short_event_count", "short_festival_count", "max_event_seats", "big_event_count",
            "max_event_popularity",  # 그날 공연의 시작 전 서울 예매 순위 (51 - 순위, 순위 밖 0)
            "avg_ta", "sum_rn", "rainy", "semester_week"]  # 날씨는 그날 실측 — 운영에서는 예보값으로 바꿔 넣는다
RAINY_MM = 1.0
SEMESTER_STARTS = ((3, 2), (9, 1))  # 대학 개강 — 대학가 상권은 방학 동안 낮았던 기준선 대비 개강 첫 몇 주가 2배로 잡힌다 (한양대역 급증 135일 중 116일이 3 · 9월 평일)
SEMESTER_WEEKS = 4


def build_features(day: pd.DataFrame, spikes: pd.DataFrame, holidays: pd.DataFrame) -> pd.DataFrame:
    f = day.copy()
    ts = pd.to_datetime(f["date"])
    f["weekday"] = ts.dt.day_of_week
    f["month"] = ts.dt.month
    week = np.zeros(len(f), dtype=int)
    for m, d in SEMESTER_STARTS:
        days = (ts - pd.to_datetime(dict(year=ts.dt.year, month=m, day=d))).dt.days.to_numpy()
        on = (days >= 0) & (days < 7 * SEMESTER_WEEKS) & (ts.dt.day_of_week.to_numpy() < 5)
        week = np.where(on, days // 7 + 1, week)
    f["semester_week"] = week

    hol = set(pd.to_datetime(holidays.loc[holidays["is_holiday"].astype(bool), "date"]))
    f["is_holiday"] = ts.isin(hol).astype(int)
    f["holiday_before"] = (ts + pd.Timedelta(days=1)).isin(hol).astype(int)
    f["holiday_after"] = (ts - pd.Timedelta(days=1)).isin(hol).astype(int)

    sp_ratio = spikes.assign(date=pd.to_datetime(spikes["date"])).groupby(["trdar_cd", "date"])["ratio"].max()
    city = spikes.assign(date=pd.to_datetime(spikes["date"])).groupby("date").size()
    own, cnt = [], []
    for k in range(-WINDOW, WINDOW + 1):
        past = ts - pd.Timedelta(days=LAST_YEAR - k)
        own.append(sp_ratio.reindex(pd.MultiIndex.from_arrays([f["trdar_cd"], past])).to_numpy(dtype=float))
        cnt.append(city.reindex(past).fillna(0).to_numpy(dtype=float))
    own, cnt = np.vstack(own), np.vstack(cnt)
    has_history = (ts - pd.Timedelta(days=LAST_YEAR + WINDOW)) >= ts.min()
    with np.errstate(all="ignore"):
        f["ly_max_ratio"] = np.where(np.isnan(own).all(axis=0), np.nan, np.nanmax(np.nan_to_num(own, nan=-np.inf), axis=0))
    f["ly_spike"] = (~np.isnan(own)).any(axis=0).astype(float)
    f["ly_city_spikes"] = cnt.max(axis=0)
    f.loc[~has_history.to_numpy(), ["ly_spike", "ly_max_ratio", "ly_city_spikes"]] = np.nan

    ratio_by_day = f.assign(_d=ts).set_index(["trdar_cd", "_d"])["ratio"]
    f["ly_ratio"] = ratio_by_day.reindex(
        pd.MultiIndex.from_arrays([f["trdar_cd"], ts - pd.Timedelta(days=LAST_YEAR)])).to_numpy(dtype=float)

    f["rainy"] = (f["sum_rn"] >= RAINY_MM).astype(float).where(f["sum_rn"].notna())
    f["log_base"] = np.log(f["riders_base"])
    f["y"] = np.log(f["ratio"].clip(*RATIO_CLIP))
    # 급증 판정용 ratio: 1년 기준선 대비 착시(방학 뒤 개강 등)는 2배 바로 아래로 — 크기 회귀 목표(y)는 그대로 둔다
    f["ratio"] = f["ratio"].where(~year_artifact(f), np.minimum(f["ratio"], 1.99))
    return f
