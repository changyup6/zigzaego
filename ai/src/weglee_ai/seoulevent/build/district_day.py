"""상권 × 날짜: 반경 안 지하철역 승하차 합 · 같은 요일 최근 8주 중앙값 대비 비율 · 그날 이벤트 · 날씨."""

import warnings

import numpy as np
import pandas as pd

from weglee_ai.seoulevent.build import geo
from weglee_ai.seoulevent.collect_subway import SAME_PLACE_M

COLUMNS = ["trdar_cd", "date", "riders", "riders_base", "ratio", "is_holiday", "avg_ta", "sum_rn", "events", "event_count",
           "short_event_count", "short_festival_count", "max_event_seats", "big_event_count", "max_event_popularity", "riders_base_year", "station_key"]
# 몇 달짜리 전시는 기준선(최근 8주)에도 들어 있어 ratio ≈ 1 이 된다 → 이벤트 효과는 짧은 행사로만 본다
SHORT_EVENT_DAYS = 7
YEAR_WEEKS = 52  # 1년 기준선: 같은 요일 지난 52주 중앙값 — 8주 기준선이 방학 등으로 낮아진 '착시 급증' 을 가린다
YEAR_SPIKE = 1.5  # 급증 = 8주 기준선의 2배 이상 + 1년 기준선의 1.5배 이상 (사용자 결정 11/05, 착시 96 / 800 · 한양대 개강 72)
# 실데이터에서 사람을 모으는 건 축제류(행사일 배수 중앙값 1.04~1.20), 공연 · 교육 · 전시는 1.00 근처
FESTIVAL_PREFIX = "축제"
BIG_SEATS = 3000  # 공연장 가장 큰 홀 좌석 수 — 체조경기장 · 고척돔 · 월드컵경기장 급 (KOPIS)
# 큰 홀이라도 클래식 · 국악은 상권 사람 흐름을 거의 안 바꾼다 (행사 효과표: 공연류 배수 ≈ 1.00). 축구는 예상 관중 기준
BIG_KINDS = {"대중음악", "축구", "야구"}
BASE_WEEKS, MIN_BASE_WEEKS = 8, 4


# 행사장 바로 앞 역은 반경 500m 안에 상권이 없어 승하차 지표에서 빠진다 (잠실 야구 · 고척 · 올림픽공원) → 역 자리에 '행사장 상권' 을 더한다
VENUE_PREFIX = "9000"  # 행사장 상권 코드 — 학습에는 쓰고 경보 대상에서는 뺀다 (19단계: 일정 정보가 부족해 헛경보)
VENUE_AREAS = [("9000001", "잠실종합운동장(종합운동장역)", 37.511224, 127.074990),
               ("9000002", "고척스카이돔(구일역)", 37.496756, 126.870793),
               ("9000003", "올림픽공원(올림픽공원역)", 37.516235, 127.130606)]


BUS_RADIUS_M = 300  # 버스 정류장은 지하철역보다 촘촘해 상권 중심 300m 안만 (지하철 500m)


def build_bus_day(bus: pd.DataFrame, stops: pd.DataFrame, areas: pd.DataFrame, radius_m: float = BUS_RADIUS_M) -> pd.DataFrame:
    """상권별 일별 버스 승하차(반경 안 정류장 합)와 기준선(8주 같은 요일 중앙값, 지하철과 같은 규칙). bus 는 collect_bus 의 정류장 · 날짜 합계."""
    st = stops.dropna(subset=["lat", "lon"]).drop_duplicates("stop_id")
    xy = np.array([geo.to_5181(lat, lon) for lat, lon in zip(st["lat"], st["lon"])]).reshape(-1, 2)
    d = np.hypot(xy[:, None, 0] - areas["x"].to_numpy()[None, :], xy[:, None, 1] - areas["y"].to_numpy()[None, :])
    si, ai = np.nonzero(d <= radius_m)
    pairs = pd.DataFrame({"stop_id": st["stop_id"].to_numpy()[si], "trdar_cd": areas["TRDAR_CD"].to_numpy()[ai]})
    per = bus.assign(riders=bus["board"] + bus["alight"]).merge(pairs, on="stop_id")
    wide = per.groupby(["date", "trdar_cd"])["riders"].sum().unstack("trdar_cd")
    wide.index = pd.to_datetime(wide.index)
    wide = wide.reindex(pd.date_range(wide.index.min(), wide.index.max()))
    past = np.stack([wide.shift(7 * w).to_numpy() for w in range(1, BASE_WEEKS + 1)])
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", RuntimeWarning)
        med = np.nanmedian(past, axis=0)
    med[(~np.isnan(past)).sum(axis=0) < MIN_BASE_WEEKS] = np.nan
    base = pd.DataFrame(med, index=wide.index, columns=wide.columns)
    riders = wide.stack(future_stack=True).dropna()
    out = pd.DataFrame({"bus_riders": riders, "bus_base": base.stack(future_stack=True).reindex(riders.index)})
    out.index.names = ["date", "trdar_cd"]
    out = out.reset_index()
    out["date"] = out["date"].dt.date
    return out[["trdar_cd", "date", "bus_riders", "bus_base"]]


CLEAN_LOOKBACK = 16  # 행사 날을 빼고도 같은 요일 8번을 채우려고 최대 16주까지 거슬러 간다


def clean_baseline(day: pd.DataFrame, skip_dates=()) -> pd.DataFrame:
    """정답 기준선 = 같은 요일 최근 '행사 없는' 8번의 중앙값 (v3 · 47단계 채택, 심층 분석 3절 기준선 오염).
    큰 행사 날(big_event_count > 0)과 skip_dates(도시 행사일, ref/citywide_days.csv)는 기준선에서 뺀다 → 직전 경기가 많아
    같은 3만 인파가 급증이 아니게 되던 구조를 없앤다. 깨끗한 주가 4번 미만이면 NaN. riders_base_year(1년 기준선)는 그대로."""
    d = day.copy()
    ts = pd.to_datetime(d["date"])
    rid = d.assign(_t=ts).pivot(index="_t", columns="trdar_cd", values="riders").astype(float)
    rid = rid.reindex(pd.date_range(rid.index.min(), rid.index.max()))
    ev = d.assign(_t=ts, _e=d["big_event_count"].fillna(0) > 0).pivot(index="_t", columns="trdar_cd", values="_e").reindex_like(rid).fillna(False)
    ev = ev.astype(bool) | rid.index.isin(pd.to_datetime(list(skip_dates)))[:, None]
    clean = rid.where(~ev)
    lags = np.stack([clean.shift(7 * w).to_numpy() for w in range(1, CLEAN_LOOKBACK + 1)])
    seen = np.cumsum(~np.isnan(lags), axis=0)
    lags = np.where(seen <= BASE_WEEKS, lags, np.nan)  # 가장 최근 깨끗한 8번만
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", RuntimeWarning)
        med = np.nanmedian(lags, axis=0)
    med[(~np.isnan(lags)).sum(axis=0) < MIN_BASE_WEEKS] = np.nan
    base = pd.DataFrame(med, index=rid.index, columns=rid.columns).stack(future_stack=True)
    d["riders_base"] = base.reindex(pd.MultiIndex.from_arrays([ts, d["trdar_cd"]])).to_numpy()
    d["ratio"] = d["riders"] / d["riders_base"]
    return d


def with_venue_areas(areas: pd.DataFrame) -> pd.DataFrame:
    """상권 표에 행사장 상권을 더한다 (좌표는 역 위치 — 그 역 승하차가 이 상권 지표가 된다)."""
    xy = [geo.to_5181(lat, lon) for _, _, lat, lon in VENUE_AREAS]
    venues = pd.DataFrame({"TRDAR_CD": [c for c, *_ in VENUE_AREAS], "TRDAR_CD_NM": [n for _, n, *_ in VENUE_AREAS],
                           "x": [x for x, _ in xy], "y": [y for _, y in xy]})
    return pd.concat([areas, venues[~venues["TRDAR_CD"].isin(areas["TRDAR_CD"])]], ignore_index=True)


def _station_district_pairs(subway: pd.DataFrame, areas: pd.DataFrame, radius_m: float) -> pd.DataFrame:
    """역 하나가 여러 상권 반경에 들 수 있다 → (station, trdar_cd) 쌍 전부."""
    # 환승역은 노선마다 좌표가 조금 다르다 → 역 대표 좌표를 '가장 이른 기록의 노선' 으로 고정 (안정 정렬). 행 순서의 첫 줄을 쓰면
    # 최근 시트(0_RECENT_)가 앞에 붙은 뒤 서울역 · 시청 · 건대입구 · 홍대입구 등의 좌표가 바뀌어 상권 15곳이 빠졌다 (43단계에서 발견)
    st = subway.dropna(subset=["lat", "lon"])
    if "date" in st:
        st = st.sort_values("date", kind="stable")
    st = st.drop_duplicates("station")[["station", "lat", "lon"]]
    xy = np.array([geo.to_5181(lat, lon) for lat, lon in zip(st["lat"], st["lon"])]).reshape(-1, 2)
    d = np.hypot(xy[:, None, 0] - areas["x"].to_numpy()[None, :], xy[:, None, 1] - areas["y"].to_numpy()[None, :])
    si, ai = np.nonzero(d <= radius_m)
    return pd.DataFrame({"station": st["station"].to_numpy()[si], "trdar_cd": areas["TRDAR_CD"].to_numpy()[ai]})


def build_district_day(subway: pd.DataFrame, weather: pd.DataFrame, events: pd.DataFrame, holidays: pd.DataFrame,
                       areas: pd.DataFrame, station_radius_m: float = 500, split_far_stations: bool = True) -> pd.DataFrame:
    """subway 는 attach_coords 로 lat · lon 이 붙은 표. 반경 안 역이 없는 상권은 빠진다."""
    # 5호선 양평(서울) · 중앙선 양평(경기)처럼 이름만 같고 SAME_PLACE_M 넘게 떨어진 역은 노선별로 따로 — 합치면 첫 좌표 하나로 가서
    # 서울 양평 쪽 상권 3곳이 통째로 빠졌다. 환승역(노선마다 좌표가 조금 다름)은 지금처럼 한 역으로 둔다
    sp = subway.dropna(subset=["lat", "lon"]).groupby("station")[["lat", "lon"]].agg(lambda v: v.max() - v.min()) if split_far_stations else \
        pd.DataFrame(columns=["lat", "lon"])  # 31단계 되돌리기 비교용 (False = 688cb57 이전처럼 이름만으로 묶음)
    far = sp.index[((sp["lat"] * 111_000) ** 2 + (sp["lon"] * 88_000) ** 2) ** 0.5 > SAME_PLACE_M]
    subway = subway.assign(station=subway["station"].where(~subway["station"].isin(far), subway["station"] + "@" + subway["line"]))
    pairs = _station_district_pairs(subway, areas, station_radius_m)
    per_station = subway.assign(riders=subway["board"] + subway["alight"]).groupby(["date", "station"], as_index=False)["riders"].sum()
    daily = per_station.merge(pairs, on="station").groupby(["trdar_cd", "date"])["riders"].sum()
    # 상권의 역 묶음 — 같은 역들을 쓰는 상권은 한 신호(experiment.signal_ids). 승하차 합계가 같은지로 추정하던 방식은
    # 우연히 합계가 같은 날 섞이고 데이터 순서에 따라 대표 상권이 바뀌었다 (41단계)
    station_key = pairs.groupby("trdar_cd")["station"].agg(lambda s: "|".join(sorted(set(s))))

    wide = daily.unstack("trdar_cd")
    wide.index = pd.to_datetime(wide.index)
    wide = wide.reindex(pd.date_range(wide.index.min(), wide.index.max()))
    # 같은 요일 최근 8주의 중앙값: 명절 · 대체공휴일이 몇 주 끼어도 기준선이 낮아지지 않는다 (평균이면 2021-10-18 에 59개 상권이 '급증')
    past = np.stack([wide.shift(7 * w).to_numpy() for w in range(1, BASE_WEEKS + 1)])
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", RuntimeWarning)  # 이력이 하나도 없는 칸 (아래에서 NaN 처리)
        med = np.nanmedian(past, axis=0)
    med[(~np.isnan(past)).sum(axis=0) < MIN_BASE_WEEKS] = np.nan
    base = pd.DataFrame(med, index=wide.index, columns=wide.columns)
    riders = wide.stack(future_stack=True).dropna()  # 승하차 기록이 있는 날만
    out = pd.DataFrame({"riders": riders, "riders_base": base.stack(future_stack=True).reindex(riders.index)})
    out.index.names = ["date", "trdar_cd"]
    out = out.reset_index()
    out["riders"] = out["riders"].astype("int64")
    out["ratio"] = out["riders"] / out["riders_base"]
    out["date"] = out["date"].dt.date
    out["station_key"] = out["trdar_cd"].map(station_key)

    hol_days = set(holidays.loc[holidays["is_holiday"].astype(bool), "date"])
    out["is_holiday"] = out["date"].isin(hol_days)
    out = out.merge(weather[["date", "avg_ta", "sum_rn"]], on="date", how="left")

    local = events[(events["source"] != "holiday") & events["trdar_cd"].notna()]
    if "seats" not in local.columns:
        local = local.assign(seats=np.nan)
    if "popularity" not in local.columns:
        local = local.assign(popularity=np.nan)
    ev_days = pd.DataFrame(
        [(r.trdar_cd, d.date(), r.name, short, short and str(r.kind).startswith(FESTIVAL_PREFIX),
          seats, seats >= BIG_SEATS and r.kind in BIG_KINDS, pop)
         for r in local.itertuples(index=False)
         for short in [(r.end_date - r.start_date).days + 1 <= SHORT_EVENT_DAYS]
         for seats in [float(r.seats) if short and pd.notna(r.seats) else 0.0]  # 장기 공연은 기준선에도 들어 있어 규모에서 뺀다
         for pop in [float(r.popularity) if short and pd.notna(r.popularity) else 0.0]
         for d in pd.date_range(r.start_date, r.end_date)],
        columns=["trdar_cd", "date", "name", "short", "festival", "seats", "big", "popularity"])
    ev = ev_days.groupby(["trdar_cd", "date"]).agg(events=("name", list), short_event_count=("short", "sum"),
                                                   short_festival_count=("festival", "sum"),
                                                   max_event_seats=("seats", "max"), big_event_count=("big", "sum"),
                                                   max_event_popularity=("popularity", "max"))
    out = out.join(ev, on=["trdar_cd", "date"])
    out["events"] = out["events"].map(lambda v: v if isinstance(v, list) else [])
    out["event_count"] = out["events"].map(len).astype("int64")
    for col in ("short_event_count", "short_festival_count", "max_event_seats", "big_event_count", "max_event_popularity"):
        out[col] = out[col].fillna(0).astype("int64")
    out = out.sort_values(["trdar_cd", "date"])
    wd = pd.to_datetime(out["date"]).dt.day_of_week
    out["riders_base_year"] = out.groupby([out["trdar_cd"], wd])["riders"].transform(
        lambda s: s.shift(1).rolling(YEAR_WEEKS, min_periods=YEAR_WEEKS // 2).median())
    return out[COLUMNS].reset_index(drop=True)
