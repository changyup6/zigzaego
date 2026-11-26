"""이벤트 많은 상권 후보표 (Jira 225): 최근 1년 이벤트 수 · 승하차 급증일 수 · 종류 · 데이터 유무 · 행사일 승하차 비율."""

from datetime import date, timedelta

import pandas as pd

COLUMNS = ["trdar_cd", "trdar_nm", "events_last_365d", "spike_days_365d", "kinds", "has_subway", "has_citydata",
           "median_event_ratio"]


def _in_last_year(dates: pd.Series, as_of: date) -> pd.Series:
    return (dates > as_of - timedelta(days=365)) & (dates <= as_of)


def rank_candidates(events: pd.DataFrame, district_day: pd.DataFrame, areas: pd.DataFrame, as_of: date,
                    top_n: int = 10, citydata_trdars: set[str] = frozenset(),
                    spikes: pd.DataFrame | None = None) -> pd.DataFrame:
    """citydata_trdars: 실시간 상권현황 82장소 안에 드는 상권코드 (아직 매핑 안 함 → 기본 빈 집합).
    spikes: detect_spikes 결과. 공개 데이터가 없는 집회 · 경기를 대신 세는 데 쓴다."""
    recent = events[(events["source"] != "holiday") & events["trdar_cd"].notna() & _in_last_year(events["start_date"], as_of)]
    spike_days = pd.Series(dtype="int64")
    if spikes is not None and len(spikes):
        sp = spikes[_in_last_year(spikes["date"], as_of)]
        spike_days = sp.groupby("trdar_cd")["date"].nunique()
    with_subway = set(district_day["trdar_cd"])

    rows = []
    for cd in sorted(set(recent["trdar_cd"]) | set(spike_days.index)):
        g = recent[recent["trdar_cd"] == cd]
        on_event = district_day[(district_day["trdar_cd"] == cd) & (district_day["short_event_count"] > 0)]["ratio"].dropna()
        rows.append({"trdar_cd": cd, "events_last_365d": len(g), "spike_days_365d": int(spike_days.get(cd, 0)),
                     "kinds": ", ".join(f"{k}:{n}" for k, n in g["kind"].value_counts(sort=True).items()),
                     "has_subway": cd in with_subway, "has_citydata": cd in citydata_trdars,
                     "median_event_ratio": on_event.median() if len(on_event) else float("nan")})
    out = pd.DataFrame(rows, columns=[c for c in COLUMNS if c != "trdar_nm"])
    out["trdar_nm"] = out["trdar_cd"].map(areas.set_index("TRDAR_CD")["TRDAR_CD_NM"])
    # 일 단위 지표(지하철)가 없는 상권은 분석이 안 되므로 이벤트 수와 상관없이 뒤로
    out = out.sort_values(["has_subway", "events_last_365d", "spike_days_365d"], ascending=[False, False, False], kind="stable")
    return out[COLUMNS].head(top_n).reset_index(drop=True)
