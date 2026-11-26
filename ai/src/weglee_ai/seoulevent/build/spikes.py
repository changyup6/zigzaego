"""승하차 급증일: 공개 데이터가 없는 집회 · 경기 · 축제를 상권별 승하차 비율로 역추적한다."""

import pandas as pd

from weglee_ai.seoulevent.build.district_day import YEAR_SPIKE

THRESHOLD = 2.0  # 같은 요일 평소의 2배
MIN_BASE = 1000  # 평소 승하차가 이보다 적은 상권은 작은 변동도 배수로 크게 보여 뺀다
WEEKDAYS = "월화수목금토일"


def year_artifact(df: pd.DataFrame) -> pd.Series:
    """8주 기준선으로는 2배지만 1년 기준선의 1.5배도 안 되는 날 — 방학 뒤 개강처럼 평소로 돌아온 것 (급증 아님)."""
    if "riders_base_year" not in df:
        return pd.Series(False, index=df.index)
    return (df["riders"] < YEAR_SPIKE * df["riders_base_year"]).fillna(False)


def detect_spikes(district_day: pd.DataFrame, areas: pd.DataFrame, threshold: float = THRESHOLD,
                  min_base: float = MIN_BASE) -> pd.DataFrame:
    s = district_day[(district_day["ratio"] >= threshold) & (district_day["riders_base"] >= min_base) & ~year_artifact(district_day)]
    s = s[["trdar_cd", "date", "riders", "riders_base", "ratio"]].copy()
    s["trdar_nm"] = s["trdar_cd"].map(areas.set_index("TRDAR_CD")["TRDAR_CD_NM"])
    s["weekday"] = [WEEKDAYS[pd.Timestamp(d).weekday()] for d in s["date"]]
    # 같은 날 급증한 상권 수: 많으면 도시 규모 행사(불꽃축제 등), 1~2곳이면 국지 행사
    s["n_districts_same_day"] = s.groupby("date")["trdar_cd"].transform("size")
    cols = ["date", "weekday", "trdar_cd", "trdar_nm", "riders", "riders_base", "ratio", "n_districts_same_day"]
    return s.sort_values(["date", "ratio"], ascending=[True, False])[cols].reset_index(drop=True)
