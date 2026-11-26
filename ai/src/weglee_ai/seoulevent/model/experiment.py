"""급증일 실험: `python -m weglee_ai.seoulevent.model.experiment` → data/out/experiment_spike.csv

가설 1 (정보): 날짜가 1주 안팎 움직이는 행사 · 격년 행사를 놓친다 → 작년 ±7일 최대 배수, 2년 전 급증, 그 달 몇째 주.
가설 2 (손실): 평균 오차 회귀는 드문 급증을 작게 잡는다 → 허들(급증 분류기 + 급증 크기 전용 회귀).
허들 기준값은 2024 검증 구간에서만 고르고 2025~ 시험 구간에는 손대지 않는다.
"""

import os
import shutil
import sys
import time
import warnings
from datetime import date, datetime
from pathlib import Path

import numpy as np
import pandas as pd
from xgboost import XGBClassifier, XGBRegressor
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import average_precision_score, precision_score, recall_score

from weglee_ai.seoulevent import config, holidays_kr
from weglee_ai.seoulevent.build import geo
from weglee_ai.seoulevent.model.features import FEATURES, LAST_YEAR, WINDOW, build_features
from weglee_ai.seoulevent.build.district_day import BIG_KINDS, BIG_SEATS, SHORT_EVENT_DAYS, VENUE_PREFIX, clean_baseline, with_venue_areas
from weglee_ai.seoulevent.build.spikes import detect_spikes
from weglee_ai.seoulevent.model.history_effects import COVID, HISTORY_COLUMNS, _ref, add_history_effects
from weglee_ai.seoulevent.model.train import SPIKE

EXT_FEATURES = FEATURES + ["ly_win_max_ratio", "ly2_spike", "nth_week"]
EXT2_FEATURES = EXT_FEATURES + HISTORY_COLUMNS  # 예정 행사 장소 · 공휴일 · 수능에 대한 과거 반응
WEATHER = ["avg_ta", "sum_rn", "rainy"]
# 운영 조건: 미래 예측 때는 날씨 예보 이력이 없어 실측 날씨를 쓸 수 없다 → 날씨를 뺀 특징으로 평가 · 예측 (ChatGPT 검토 1)
OPS_FEATURES = [c for c in EXT2_FEATURES if c not in WEATHER] + ["base_event_k"]  # v2 (41단계): 기준선 창 안 행사 횟수 — 14일 지연 조건에서 평균정밀도 0.582 → 0.606
# 행사 일정에서 나온 특징. 과거 일정은 사후 수집이라(시점별 스냅샷 없음) 이를 뺀 결과가 사전 예측 성능의 하한 (재평가 1)
# 공휴일 · 개강 · 수능은 미리 공표되는 고정 일정이라 남긴다
# 불꽃축제는 약 30일 전 공표, 개화일은 피는 날 발표라 4주 앞 엄격 평가에서는 뺀다
SCHEDULE_FEATURES = ["base_event_k", "short_event_count", "short_festival_count", "max_event_seats", "big_event_count", "max_event_popularity", "venue_effect",
                     "citywide_effect", "bloom_day", "bloom_effect", "big_dist_km", "big_near_seats"]
OPS_LAG = 14  # 41단계: 승하차 최근 시트가 매일 3일 전까지 → 자료 4일 + 7일 앞 = 11일 → 같은 요일 기준 14일
LAG_DAYS = 56  # 40단계: 운영 시 승하차 자료 지연 (월 파일 공개가 40 ~ 70일 늦음) — 같은 요일이 되도록 7의 배수
HORIZON = 7  # 7일 앞 예측 (사용자 결정 11/04, 28 → 7) — 과거 반응 · 평소 규모는 7일 전까지만, 같은 요일이 되도록 7의 배수
RECALL_TARGET = 0.7  # 재현율 0.7 을 먼저 지키는 기준값 (2024 검증에서 선택) — 놓치는 비용이 커서 (사용자 결정 A1)
VARIANTS = {}  # 비교용 데이터 접미사 → 이름 (11단계 한강 비교 끝, 기본 = 대형 행사만)
ROLLING_YEARS = range(2021, 2027)  # 2021 은 판정 없이 다음 해 기준값용 out-of-fold 예측만 (코로나 제외 후 남는 1 ~ 2월)
# 경보 목표 (후보 상권 · 추정 신호): 1차 = 재현율 0.7 일 때 정밀도 0.5, 최종 0.8 (사용자 결정 11/05 — 12단계 최고 0.42 라 단계적으로)
TARGET_PRECISION, TARGET_RECALL, FINAL_PRECISION = 0.5, 0.7, 0.8
BUSY = 1.3  # 붐빔 (급증은 SPIKE = 2배)
MIN_POOL_SPIKES, MIN_POOL_ALERTS = 50, 20  # 기준값 보정 최소 지원량 — 정밀도 0.5 근처 이항 오차 ±0.2 안쪽이 되는 경보 수 · 한 해 급증 신호 수 정도
WIDE_WINDOW = 7
TWO_YEARS = 2 * LAST_YEAR
# 급증은 0.04% 로 드물어 기준값이 1 에 가깝게 고를 수 있다 → 위쪽을 촘촘히 (0.95 끝에 걸렸던 1차 실행 반영)
# 0.01 ~ 0.03: 검증 해에 급증 확률이 낮게 나와 0.05 에서도 재현율 0.7 에 못 닿았다 (13단계)
GRID = np.round(np.r_[[0.01, 0.02, 0.03], np.arange(0.05, 0.96, 0.05), [0.97, 0.98, 0.99, 0.995, 0.997, 0.999]], 3)
# GPU 서버(H200)에서는 cuda, 없으면 cpu. scikit-learn 부스팅은 GPU 를 못 써 같은 히스토그램 부스팅인 XGBoost 로 바꿨다 (2026-11-03)
DEVICE = os.getenv("SEOULEVENT_DEVICE") or ("cuda" if shutil.which("nvidia-smi") else "cpu")
# 이전 HistGradientBoosting(300 그루 · 학습률 0.1 · 잎 31 · 잎 최소 20행)과 같은 크기
TREE = dict(n_estimators=300, learning_rate=0.1, tree_method="hist", grow_policy="lossguide", max_depth=0, max_leaves=31,
            max_bin=255, random_state=0, device=DEVICE,
            n_jobs=int(os.getenv("SEOULEVENT_THREADS", "4")))  # 공용 GPU 서버: 계산은 GPU, CPU 는 데이터 준비용 몇 개만
VAL_YEARS = 2  # 기준값은 직전 2년에서 고른다 — 직전 1년이면 재현율 0.7 을 못 맞춰 해마다 바닥(0.05)으로 내려갔다 (12단계)
VAL_START, TEST_START = date(2023, 1, 1), date(2025, 1, 1)


def add_extended_features(f: pd.DataFrame, spikes: pd.DataFrame) -> pd.DataFrame:
    f = f.copy()
    ts = pd.to_datetime(f["date"])
    by_day = f.assign(_d=ts).set_index(["trdar_cd", "_d"])["ratio"]
    win = np.vstack([by_day.reindex(pd.MultiIndex.from_arrays([f["trdar_cd"], ts - pd.Timedelta(days=LAST_YEAR - k)])).to_numpy(dtype=float)
                     for k in range(-WIDE_WINDOW, WIDE_WINDOW + 1)])
    with np.errstate(all="ignore"):
        f["ly_win_max_ratio"] = np.where(np.isnan(win).all(axis=0), np.nan, np.nanmax(np.nan_to_num(win, nan=-np.inf), axis=0))

    sp = spikes.assign(date=pd.to_datetime(spikes["date"])).groupby(["trdar_cd", "date"]).size()
    hit = np.vstack([sp.reindex(pd.MultiIndex.from_arrays([f["trdar_cd"], ts - pd.Timedelta(days=TWO_YEARS - k)])).notna().to_numpy()
                     for k in range(-3, 4)]).any(axis=0)
    f["ly2_spike"] = np.where((ts - pd.Timedelta(days=TWO_YEARS + 3)) >= ts.min(), hit.astype(float), np.nan)
    f["nth_week"] = (ts.dt.day - 1) // 7 + 1  # 그 달 몇째 같은 요일 (불꽃축제 · 논술처럼 '몇째 주 토/일' 행사)
    # 36단계 운영 채택: 도시 행사 날의 작년 특징은 직전 같은 행사 날 기준 (평균정밀도 0.622 → 0.632 · 정밀도 0.63 → 0.65 · 재현율 0.63 → 0.64)
    for c in [c for c in LY_ALIGNED if c in f]:  # 정렬 전 달력 기준 값 — '행사 일정 없이' 하한 비교용 (ChatGPT 27: 정렬된 작년 특징도 일정 정보다)
        f[c + "_cal"] = f[c]
    return align_ly_to_citywide(f, spikes)


def mask_unannounced(f: pd.DataFrame, citywide: pd.DataFrame, horizon: int = HORIZON) -> pd.Series:
    """도시 행사일(불꽃축제 · 마라톤) 효과를, 그 날짜가 예측 시점(horizon 일 전)에 공식 공개돼 있었을 때만 남긴다.
    과거 그날의 실제 반응(이력)은 관측값이라 그대로 쓰고, '그날이 행사일' 이라는 정보만 공개일로 거른다. 공개일 모르면 모름으로 본다."""
    known = citywide.assign(announced=pd.to_datetime(citywide.get("announced"), errors="coerce"),
                            origin=pd.to_datetime(citywide["date"]) - pd.Timedelta(days=horizon))
    ok = set(known.loc[known["announced"].notna() & (known["announced"] <= known["origin"]), "date"])
    return f["citywide_effect"].where(f["date"].isin(ok))


CAUSE_COUNTS = ["short_event_count", "short_festival_count", "is_holiday", "suneung_week"]
CAUSE_EFFECTS = ["venue_effect", "holiday_name_effect", "citywide_effect", "bloom_day", "semester_effect"]


def known_cause(f: pd.DataFrame) -> pd.Series:
    """그날 예측 시점에 알려진 원인(행사 · 축제 · 공휴일 · 수능 주말 · 학기 · 봄꽃 · 도시 행사)이 하나라도 있으면 1.
    25단계 분석: 원인 없는 날 높은 위험 41건 정밀도 0.07 — 작년 배수(평균 2.4배)만 보고 울렸다."""
    on = pd.Series(False, index=f.index)
    for c in CAUSE_COUNTS:
        if c in f:
            on |= f[c].fillna(0) > 0
    for c in CAUSE_EFFECTS:
        if c in f:
            on |= f[c].notna()
    return on.astype(float)


def at_horizon(f: pd.DataFrame, events: pd.DataFrame, horizon: int = HORIZON) -> pd.DataFrame:
    """예측 시점이 horizon 일 앞일 때 알 수 있는 값만: 과거 반응은 horizon 일 전 기록까지, 평소 규모(log_base)도 horizon 일 전 값."""
    f = add_history_effects(f, events, horizon)
    f["known_cause"] = known_cause(f)
    ts = pd.to_datetime(f["date"])
    base = f.assign(_d=ts).set_index(["trdar_cd", "_d"])["log_base"]
    f["log_base"] = base.reindex(pd.MultiIndex.from_arrays([f["trdar_cd"], ts - pd.Timedelta(days=horizon)])).to_numpy(dtype=float)
    return add_base_event_count(add_overlap_features(add_recent(f, horizon)))


LY_ALIGNED = ["ly_spike", "ly_max_ratio", "ly_win_max_ratio"]


def align_ly_to_citywide(f: pd.DataFrame, spikes: pd.DataFrame, city: pd.DataFrame | None = None, horizon: int = HORIZON) -> pd.DataFrame:
    """도시 행사 날(불꽃축제 · 마라톤 등, ref/citywide_days.csv)의 '작년' 특징을 달력 날짜 대신 직전 같은 이름 행사 날 기준으로.
    36단계 · 심층 분석 4절: 불꽃축제가 10/5 → 9/27 → 9/5 로 옮겨지자 작년 같은 날(±3일) 급증 = 0 이 되어 점수 0.89 → 0.25 → 0.09.
    ly_spike · ly_max_ratio(급증표) · ly_win_max_ratio(그날 배수)만 바꾼다. 직전 행사는 1년 넘게 앞이라 7일 앞 예측 시점에 이미 관측값.
    39단계 (ChatGPT 27): 그날이 행사일이라는 정보가 예측 시점(그날 − horizon)까지 공개됐을 때만 정렬. 공개일 모르면 정렬 안 함(달력 기준 유지)."""
    city = _ref("citywide_days.csv", ["date", "name", "announced"]) if city is None else city
    c = city.assign(date=pd.to_datetime(city["date"]),
                    announced=pd.to_datetime(city["announced"] if "announced" in city else pd.NaT, errors="coerce")).sort_values("date")
    c["prev"] = c.groupby("name")["date"].shift(1)  # 직전 행사는 과거 관측값이라 공개 여부와 무관
    known = c["announced"].notna() & (c["announced"] <= c["date"] - pd.Timedelta(days=horizon))
    pairs = c[known].dropna(subset=["prev"]).drop_duplicates("date")[["date", "prev"]]
    return _ly_from_prev(f, spikes, dict(zip(pairs["date"], pairs["prev"])))


LUNAR_HOLIDAYS = ("설날", "추석", "부처님오신날")


def align_ly_to_lunar_holidays(f: pd.DataFrame, spikes: pd.DataFrame, holidays: pd.DataFrame) -> pd.DataFrame:
    """음력 공휴일(설 · 추석 · 부처님오신날)은 날짜가 해마다 바뀐다 → '작년' 특징을 직전 해 같은 공휴일의 같은 순번 날 기준으로 (42단계).
    북한산우이역 부처님오신날 2019 ~ 2026 배수 2.30 · 2.53 · 2.44 · 1.81 · 1.35 · 2.22 · 2.21 · 1.88 인데 점수 0.00 ~ 0.14 (불꽃축제와 같은 원인).
    공휴일은 몇 년 앞서 공개되므로 공개일 조건이 필요 없다."""
    h = holidays[holidays["name"].isin(LUNAR_HOLIDAYS)].assign(date=lambda d: pd.to_datetime(d["date"])).drop_duplicates(["date", "name"])
    h = h.assign(year=h["date"].dt.year).sort_values("date")
    h["k"] = h.groupby(["name", "year"]).cumcount()
    prev = h.merge(h.assign(year=h["year"] + 1)[["name", "year", "k", "date"]].rename(columns={"date": "prev"}), on=["name", "year", "k"])
    return _ly_from_prev(f, spikes, dict(zip(prev["date"], prev["prev"])))


def _ly_from_prev(f: pd.DataFrame, spikes: pd.DataFrame, prev_of: dict) -> pd.DataFrame:
    """prev_of(그날 → 비교할 직전 같은 행사 날)가 있는 행의 ly_spike · ly_max_ratio · ly_win_max_ratio 를 그 날 기준으로 바꾼다."""
    f = f.copy()
    ts = pd.to_datetime(f["date"])
    prev = ts.map(prev_of)
    on = prev.notna().to_numpy()
    if not on.any():
        return f
    key = pd.MultiIndex.from_arrays([f.loc[on, "trdar_cd"], prev[on]])
    sp = spikes.assign(date=pd.to_datetime(spikes["date"])).groupby(["trdar_cd", "date"])["ratio"].max()
    day_ratio = f.assign(_d=ts).set_index(["trdar_cd", "_d"])["ratio"]
    sr = sp.reindex(key).to_numpy(dtype=float)
    f.loc[on, "ly_spike"] = (~np.isnan(sr)).astype(float)
    f.loc[on, "ly_max_ratio"] = sr
    f.loc[on, "ly_win_max_ratio"] = day_ratio.reindex(key).to_numpy(dtype=float)
    return f


def add_base_partial(f: pd.DataFrame, first_week: int = 2) -> pd.DataFrame:
    """log_base_part: 그날 정답 기준선(직전 8주 같은 요일 중앙값) 중 예측 시점에 이미 공개된 주들(first_week ~ 8주 전)의 중앙값 (46단계).
    7일 앞 · 자료 4일 지연이면 8개 중 7개(14 ~ 56일 전)를 안다 — v2 의 '14일 전 시점 기준선'(21 ~ 70일 전, 8개 중 1개만 겹침)보다
    실제 기준선에 훨씬 가깝다 (2024 ~ 로컬 점검: 오차(log) 중앙값 0.0104 → 0.0042, 90% 분위 0.0426 → 0.0174). 4주 미만이면 NaN (정답 규칙과 같음)."""
    f = f.copy()
    ts = pd.to_datetime(f["date"])
    r = f["riders"].set_axis(pd.MultiIndex.from_arrays([f["trdar_cd"], ts]))
    lags = np.vstack([r.reindex(pd.MultiIndex.from_arrays([f["trdar_cd"], ts - pd.Timedelta(weeks=w)])).to_numpy(dtype=float)
                      for w in range(first_week, 9)])
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", RuntimeWarning)
        med = np.nanmedian(lags, axis=0)
    f["log_base_part"] = np.where((~np.isnan(lags)).sum(axis=0) >= 4, np.log(med), np.nan)
    return f


BUS_FEATURES = ["log_bus_base", "ly_bus_max_ratio"]  # 45단계 비교 특징


def add_bus_features(f: pd.DataFrame, bus_day: pd.DataFrame, lag: int) -> pd.DataFrame:
    """버스(collect_bus · build_bus_day, 상권 300m 안 정류장 합) 특징 (45단계).
    log_bus_base: lag 일 전 버스 기준선(8주 같은 요일 중앙값)의 로그 — 지하철처럼 운영 지연을 같이 둔다.
    ly_bus_max_ratio: 364일 전 ±3일 버스 배수(승하차 / 기준선) 최대 — 지하철이 못 본 작년 같은 시기 행사를 버스로."""
    f = f.copy()
    ts = pd.to_datetime(f["date"])
    b = bus_day.assign(date=pd.to_datetime(bus_day["date"])).set_index(["trdar_cd", "date"])
    look = lambda s, k: s.reindex(pd.MultiIndex.from_arrays([f["trdar_cd"], ts - pd.Timedelta(days=k)])).to_numpy(dtype=float)
    f["log_bus_base"] = np.log(look(b["bus_base"], lag))
    r = b["bus_riders"] / b["bus_base"]
    with np.errstate(all="ignore"), warnings.catch_warnings():
        warnings.simplefilter("ignore", RuntimeWarning)  # 창 전체가 빈 행은 NaN
        f["ly_bus_max_ratio"] = np.nanmax(np.vstack([look(r, LAST_YEAR - k) for k in range(-WINDOW, WINDOW + 1)]), axis=0)
    return f


def add_base_nowcast(f: pd.DataFrame, lag: int) -> pd.DataFrame:
    """log_base_now: 승하차가 lag 일 늦게 공개될 때 그날 기준선(8주 같은 요일 중앙값)의 추정 (40c).
    = 작년 같은 날 기준선 × (lag 일 전 기준선 / 그 1년 전 기준선) — 계절(방학 · 개강)은 작년이, 올해 추세는 증감률이 담는다.
    쓰는 값은 모두 lag 일 이상 지난 기준선이라 예측 시점에 이미 공개돼 있다. 40b: 기준선만 56일 늦어도 평균정밀도 0.640 → 0.593."""
    f = f.copy()
    ts = pd.to_datetime(f["date"])
    b = np.log(f["riders_base"]).set_axis(pd.MultiIndex.from_arrays([f["trdar_cd"], ts]))
    look = lambda k: b.reindex(pd.MultiIndex.from_arrays([f["trdar_cd"], ts - pd.Timedelta(days=k)])).to_numpy(dtype=float)
    f["log_base_now"] = look(LAST_YEAR) + look(lag) - look(lag + LAST_YEAR)
    return f


BASE_WEEKS = 8  # 급증 정답의 기준선 = 최근 8주 같은 요일 중앙값 (build.district_day)
BASE_EVENT = ["base_event_k"]  # 35단계 비교 특징 (심층 분석 3절)


def add_base_event_count(f: pd.DataFrame) -> pd.DataFrame:
    """base_event_k: 그날 정답 기준선 창(직전 8주 같은 요일) 중 큰 행사가 있던 날 수. 창 안 날짜는 모두 그날보다 7일 이상 앞이라
    7일 앞 예측 시점에 이미 지난 일정이다. 심층 분석 3절 — 월드컵경기장 경기 날 급증률이 이 수 0 ~ 2 에서 0.7, 5 에서 0.2 (기준선 오염)."""
    f = f.copy()
    ts = pd.to_datetime(f["date"])
    big = f["big_event_count"] if "big_event_count" in f else pd.Series(0.0, index=f.index)
    ev = (big.fillna(0) > 0).astype(float).set_axis(pd.MultiIndex.from_arrays([f["trdar_cd"], ts]))
    lags = [ev.reindex(pd.MultiIndex.from_arrays([f["trdar_cd"], ts - pd.Timedelta(weeks=w)])).to_numpy() for w in range(1, BASE_WEEKS + 1)]
    f["base_event_k"] = np.nansum(np.vstack(lags), axis=0)  # 기록 앞 날짜는 0 으로 (첫 8주만 해당)
    return f


ATTR = ["trdar_se", "big_dist_km", "big_near_seats"]  # 47단계 비교 특징
SE_CODE = {"A": 0, "D": 1, "R": 2, "U": 3}  # 골목 · 발달 · 전통시장 · 관광특구, 행사장 상권 = 4


def add_district_attrs(f: pd.DataFrame, areas: pd.DataFrame, events: pd.DataFrame, radius_m: float = 3000) -> pd.DataFrame:
    """상권 · 행사 속성 (47단계): 이력 적은 상권도 '어떤 상권이 큰 행사장에서 얼마나 떨어졌나' 로 다른 상권 경험을 빌린다.
    venue_effect 는 (상권, 장소) 이력만이라 3년에 1 ~ 2번 급증한 상권에선 비어 있다 (재현율 0.31, 심층 분석 후속).
    trdar_se: 상권 구분. big_dist_km · big_near_seats: 그날 radius_m 안 가장 가까운 큰 행사(좌석 3천+ · 대중음악 · 축구 · 야구)까지 거리 · 그 좌석 수."""
    f = f.copy()
    a = areas.drop_duplicates("TRDAR_CD").set_index("TRDAR_CD")
    f["trdar_se"] = f["trdar_cd"].map(a["TRDAR_SE_CD"].map(SE_CODE)).fillna(4).astype(float)
    short = (pd.to_datetime(events["end_date"]) - pd.to_datetime(events["start_date"])).dt.days + 1 <= SHORT_EVENT_DAYS
    ev = events[short & (events["seats"].fillna(0) >= BIG_SEATS) & events["kind"].isin(BIG_KINDS) & events["lat"].notna()].drop_duplicates("event_id")
    f["big_dist_km"], f["big_near_seats"] = np.nan, np.nan
    if ev.empty:
        return f
    xy = np.array([geo.to_5181(la, lo) for la, lo in zip(ev["lat"], ev["lon"])]).reshape(-1, 2)
    ed = pd.DataFrame([(d.date(), x, y, s) for (x, y), s, st, en in zip(xy, ev["seats"], ev["start_date"], ev["end_date"])
                       for d in pd.date_range(st, en)], columns=["date", "ex", "ey", "seats"])
    da = a.loc[a.index.intersection(pd.unique(f["trdar_cd"])), ["x", "y"]]
    d = np.hypot(ed["ex"].to_numpy()[:, None] - da["x"].to_numpy()[None, :], ed["ey"].to_numpy()[:, None] - da["y"].to_numpy()[None, :])
    ei, di = np.nonzero(d <= radius_m)
    near = pd.DataFrame({"trdar_cd": da.index.to_numpy()[di], "date": ed["date"].to_numpy()[ei], "big_dist_km": d[ei, di] / 1000,
                         "big_near_seats": ed["seats"].to_numpy()[ei]}).sort_values("big_dist_km").drop_duplicates(["trdar_cd", "date"])
    return f.drop(columns=["big_dist_km", "big_near_seats"]).merge(near, on=["trdar_cd", "date"], how="left")


# 점검 20 · ChatGPT 25 — 원인 겹침 비교용 특징 (운영 특징 OPS_FEATURES 에는 없음, 비교 실험에서만 하나씩 켠다)
WEATHER_X = ["temp_anom", "rainy", "outdoor", "outdoor_rain", "outdoor_weekend", "outdoor_warm"]  # 실측 날씨 → '실측 날씨 진단'
OVERLAP = ["n_causes", "outdoor"]
WARM_ANOM = 5.0


def _prior_year_anomaly(dates: pd.Series, temp: pd.Series) -> np.ndarray:
    """그날 기온 − (이전 해들의 같은 철 평균, 앞뒤 7일). 그해 · 이후 해는 안 쓴다 (첫 해는 빈 값)."""
    d = pd.DataFrame({"t": pd.to_datetime(dates).to_numpy(), "v": temp.to_numpy()}).groupby("t")["v"].first().reset_index()
    d["doy"], d["year"] = d["t"].dt.day_of_year, d["t"].dt.year
    out = np.full(len(d), np.nan)
    for y in sorted(d["year"].unique()):
        past = d[d["year"] < y]
        if past.empty:
            continue
        clim = past.groupby("doy")["v"].mean().reindex(range(1, 367)).rolling(15, center=True, min_periods=1).mean()
        m = (d["year"] == y).to_numpy()
        out[m] = d.loc[m, "v"].to_numpy() - d.loc[m, "doy"].map(clim).to_numpy()
    return pd.Series(out, index=d["t"]).reindex(pd.to_datetime(dates)).to_numpy()


def add_overlap_features(f: pd.DataFrame) -> pd.DataFrame:
    """야외 원인(축제 · 한강 · 기간형 축제 · 봄꽃 · 도시 행사) × 날씨 · 주말, 겹친 원인 수, 봄꽃 주말 창, 연휴 길이, 연말 주말.
    점검 20: 야외 원인 주말 비 없음 5.2 ~ 7.1% vs 비 1.1%, 겹친 원인 수에 따라 급증률이 곱으로 오름."""
    f = f.copy()
    ts = pd.to_datetime(f["date"])
    weekend = pd.Series((ts.dt.day_of_week >= 5).to_numpy(), index=f.index)
    g = lambda c: f[c] if c in f else pd.Series(np.nan, index=f.index)
    f["temp_anom"] = _prior_year_anomaly(f["date"], g("avg_ta")) if g("avg_ta").notna().any() else np.nan
    outdoor = (g("short_festival_count").fillna(0) > 0) | (g("long_festival_count").fillna(0) > 0) | g("bloom_day").notna() | g("citywide_effect").notna()
    f["outdoor"] = outdoor.astype(float)
    f["outdoor_rain"] = (outdoor & (g("rainy") == 1)).astype(float).where(g("rainy").notna())
    f["outdoor_weekend"] = (outdoor & weekend).astype(float)
    f["outdoor_warm"] = (outdoor & (f["temp_anom"] >= WARM_ANOM)).astype(float).where(f["temp_anom"].notna())
    flags = [g("big_event_count") > 0, g("short_festival_count") > 0, (g("short_event_count") > 0) & ~(g("short_festival_count") > 0),
             g("long_festival_count") > 0, g("is_holiday") == 1, (g("holiday_before") == 1) | (g("holiday_after") == 1),
             g("semester_week") > 0, g("suneung_week") > 0, g("bloom_day").notna(), g("citywide_effect").notna()]
    f["n_causes"] = np.sum([x.fillna(False).to_numpy(dtype=bool) for x in flags], axis=0).astype(float)
    f["n_causes_v"] = f["n_causes"] + g("venue_effect").notna().to_numpy()  # 31단계: 보정 진단(cause_tags)과 같게 장소 이력까지 센 원인 수
    f["bloom_wknd"] = g("bloom_day").where(weekend & g("bloom_day").between(2, 10))  # 개화 +2 ~ +10일 주말만 (점검 20 §2a, 사후 선택)
    # 연휴 길이: 그날이 든 '쉬는 날(주말 · 공휴일)' 연속 일수 — 연휴 시작 토 · 일 1.25% (§2d). 평일은 0
    hol = f.assign(_t=ts).groupby("_t")["is_holiday"].max() if "is_holiday" in f else pd.Series(dtype=float)
    full = pd.DataFrame({"t": pd.date_range(ts.min(), ts.max())})
    full["off"] = (full["t"].dt.day_of_week >= 5) | (full["t"].map(hol).fillna(0) == 1)
    run_id = (full["off"] != full["off"].shift()).cumsum()
    full["off_run"] = np.where(full["off"], full.groupby(run_id)["off"].transform("size"), 0)
    f["off_run"] = ts.map(full.set_index("t")["off_run"]).to_numpy(dtype=float)
    f["year_end"] = (((ts.dt.month == 12) & (ts.dt.day >= 13) & weekend) | ((ts.dt.month == 12) & (ts.dt.day == 24))).astype(float)
    return f


RECENT = ["recent_ratio_mean", "recent_ratio_max", "recent_spikes"]


def add_recent(f: pd.DataFrame, horizon: int = HORIZON) -> pd.DataFrame:
    """최근 흐름 (27단계): 같은 요일 최근 4번 배수의 기하평균 · 최대, 최근 6주 급증일 수. 지하철 자료가 며칠 늦게 공개돼
    예측 시점(horizon 일 전)보다 7일 더 앞선 값만 — 7일 앞이면 14 · 21 · 28 · 35일 전. 작년 특징만 있고 최근 몇 주 흐름이 없었다."""
    first = 7 * -(-(horizon + 7) // 7)
    ts = pd.to_datetime(f["date"])
    r = f.assign(_d=ts).set_index(["trdar_cd", "_d"])["ratio"].astype(float)
    lags = np.column_stack([r.reindex(pd.MultiIndex.from_arrays([f["trdar_cd"], ts - pd.Timedelta(days=first + 7 * k)])).to_numpy()
                            for k in range(4)])
    with np.errstate(all="ignore"):
        f["recent_ratio_mean"] = np.exp(np.nanmean(np.log(np.clip(lags, 0.05, None)), axis=1))
        f["recent_ratio_max"] = np.nanmax(np.where(np.isnan(lags), -np.inf, lags), axis=1)
    f.loc[np.isinf(f["recent_ratio_max"]), "recent_ratio_max"] = np.nan
    spk = (r >= SPIKE).astype(float).reset_index().sort_values(["trdar_cd", "_d"])
    roll = spk.set_index("_d").groupby("trdar_cd")["ratio"].rolling("42D").sum()
    f["recent_spikes"] = roll.reindex(pd.MultiIndex.from_arrays([f["trdar_cd"], ts - pd.Timedelta(days=first)])).to_numpy()
    return f


def hurdle(p: np.ndarray, normal: np.ndarray, spike: np.ndarray, threshold: float) -> np.ndarray:
    return np.where(p >= threshold, spike, normal)


def choose_threshold(actual: np.ndarray, p: np.ndarray, normal: np.ndarray, spike: np.ndarray, grid=GRID) -> float:
    errs = [np.abs(hurdle(p, normal, spike, t) - actual).mean() for t in grid]
    return float(grid[int(np.argmin(errs))])


def signal_ids(df: pd.DataFrame) -> np.ndarray:
    """같은 날 같은 역들을 쓰는 상권들은 한 신호 — 한 역을 놓치면 여러 상권이 함께 틀린다.
    43단계: district_day 의 역 묶음(station_key)으로 묶는다. 없는 옛 데이터 · 테스트 표는 예전 '추정 신호'(승하차 합계가 같은지) —
    우연히 합계가 같은 날 섞이고 데이터 순서에 따라 대표 상권이 바뀌어 실행마다 숫자가 흔들렸다 (41단계)."""
    if "station_key" in df and df["station_key"].notna().all():
        return df.groupby(["date", "station_key"], sort=False).ngroup().to_numpy()
    return df.groupby(["date", "riders", "riders_base"], dropna=False, sort=False).ngroup().to_numpy()


def detection_scores(actual: np.ndarray, p: np.ndarray, threshold: float, dates: np.ndarray | None = None,
                     signals: np.ndarray | None = None) -> dict:
    """급증 탐지. 'n_spike_rows' 는 상권-일 수, 'n_spike_dates' 는 급증이 있던 고유 날짜 수 (검토 2: 둘을 섞어 '일' 로 부르지 않는다).
    signals 를 주면 같은 역 중복을 묶은 독립 신호 단위 정밀도 · 재현율도 낸다."""
    y, pred = actual >= SPIKE, p >= threshold
    out = {"precision": precision_score(y, pred, zero_division=0), "recall": recall_score(y, pred, zero_division=0),
           "avg_precision": average_precision_score(y, p) if y.any() else np.nan, "n_spike_rows": int(y.sum())}
    if dates is not None:
        spike_dates = set(dates[y])
        hit_dates = set(dates[y & pred])
        out |= {"n_spike_dates": len(spike_dates), "date_recall": len(hit_dates) / len(spike_dates) if spike_dates else np.nan}
    if signals is not None:
        g = pd.DataFrame({"s": signals, "y": y, "a": pred}).groupby("s")[["y", "a"]].any()
        out |= {"n_spike_signals": int(g["y"].sum()),
                "signal_precision": (g["y"] & g["a"]).sum() / g["a"].sum() if g["a"].any() else 0.0,
                "signal_recall": (g["y"] & g["a"]).sum() / g["y"].sum() if g["y"].any() else np.nan}
    return out


def threshold_for_recall(actual: np.ndarray, p: np.ndarray, target: float = RECALL_TARGET, grid=GRID) -> float:
    """재현율 target 이상을 지키는 가장 높은(헛경보가 가장 적은) 기준값. 없으면 가장 낮은 값."""
    ok = [t for t in grid if recall_score(actual >= SPIKE, p >= t, zero_division=0) >= target]
    return float(max(ok)) if ok else float(min(grid))


def threshold_curve(actual: np.ndarray, p: np.ndarray, dates: np.ndarray, grid=GRID, signals: np.ndarray | None = None) -> pd.DataFrame:
    rows = []
    for t in grid:
        d = detection_scores(actual, p, t, dates, signals)
        rows.append({"threshold": float(t), "alerts": int((p >= t).sum()), "precision": d["precision"], "recall": d["recall"],
                     "date_recall": d["date_recall"]} | {k: d[k] for k in ("signal_precision", "signal_recall") if k in d})
    return pd.DataFrame(rows)


def daily_top_k(test: pd.DataFrame, p: np.ndarray, ks=(1, 2, 3, 5)) -> pd.DataFrame:
    """날짜마다 급증 확률 상위 k 상권에만 경보 — 고정 기준값과 비교 (04 권고 6: 놓친 날짜를 줄이는 대신 경보 수 · 헛경보가 는다)."""
    rank = pd.Series(p, index=test.index).groupby(test["date"].to_numpy()).rank(method="first", ascending=False).to_numpy()
    actual, dates, sig = test["ratio"].to_numpy(), test["date"].to_numpy(), signal_ids(test)
    rows = []
    for k in ks:
        alert = (rank <= k).astype(float)
        d = detection_scores(actual, alert, 0.5, dates, sig)
        rows.append({"k": k, "alerts": int(alert.sum())} | {c: d[c] for c in ("precision", "recall", "date_recall", "signal_precision", "signal_recall")})
    return pd.DataFrame(rows)


def top_k_hit_rate(actual: np.ndarray, p: np.ndarray, ks=(50, 100, 238)) -> dict:
    """급증 확률 상위 k 개 경보 중 실제 급증 비율."""
    order = np.argsort(-p)
    return {k: float((actual[order[:k]] >= SPIKE).mean()) for k in ks}


def _cols(df: pd.DataFrame, features: list[str]) -> list[str]:
    return [c for c in features if df[c].notna().any()]


def _reg(train: pd.DataFrame, features: list[str]):
    cols = _cols(train, features)
    m = XGBRegressor(**{"min_child_weight": 20, **TREE})  # 제곱 오차는 행마다 헤시안 1 → 잎 최소 20행 (튜닝이 TREE 로 덮을 수 있게)
    return m.fit(train[cols], train["y"]), cols


BALANCE = {"on": True}
# 진행 기록 (사용자 요청 11/06: 로그로 학습 정도를 남긴다) — 서버 로그(exp_gpuNN.log)를 tail -f 로 보면 어디까지 왔는지 보인다
_T0 = time.monotonic()
PROGRESS = {"i": 0, "n": 0}


def log(msg: str) -> None:
    print(f"[{datetime.now():%H:%M:%S} +{(time.monotonic() - _T0) / 60:.1f}분] {msg}", flush=True)


def _clf(train: pd.DataFrame, features: list[str]):
    cols = _cols(train, features)
    y = train["ratio"] >= SPIKE
    w = (~y).sum() / max(y.sum(), 1) if BALANCE["on"] else 1.0  # class_weight="balanced" 와 같은 효과 (27단계: 끄고 비교)
    m = XGBClassifier(**TREE, scale_pos_weight=w)
    return m.fit(train[cols], y), cols


def size_features(features: list[str]) -> list[str]:
    """크기(평소 · 급증 배수) 회귀용: 공휴일 이름 반응을 전체 기간 평균으로. 급증 분류기는 최근 3번 그대로 (9-A2 채택:
    공휴일 0.103 → 0.086, 평균정밀도 0.41 · 신호 정밀도 0.41 유지)."""
    return [("holiday_name_full" if c == "holiday_name_effect" else c) for c in features]


def fit_hurdle(train: pd.DataFrame, features: list[str], clf_cand: set | None = None, blend: bool = False) -> dict:
    """허들 모델 학습 → {이름: (모델, 열)} (저장 · 불러오기용으로 학습과 예측을 나눔).
    clf_cand 를 주면 급증 분류기는 그 상권들로만 학습 (평소 · 급증 크기 회귀는 전체).
    blend 면 전체 상권 분류기와 후보 상권 분류기 확률의 반반 평균 — 비율을 미리 고정해 검증 자료로 고르지 않는다 (12 검토 1)."""
    reg_features = size_features(features)
    h = {"normal": _reg(train, reg_features), "spike": _reg(train[train["ratio"] >= SPIKE], reg_features),
         "clf": _clf(train[train["trdar_cd"].isin(clf_cand)] if clf_cand else train, features)}
    if blend and clf_cand:
        h["whole"] = _clf(train, features)
    return h


def predict_hurdle(h: dict, target: pd.DataFrame) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """fit_hurdle 결과로 (급증 확률, 평소 배수, 급증 배수)."""
    (clf, cc), (normal, nc), (spike, sc) = h["clf"], h["normal"], h["spike"]
    p = clf.predict_proba(target[cc])[:, 1]
    if "whole" in h:
        whole, wc = h["whole"]
        p = (p + whole.predict_proba(target[wc])[:, 1]) / 2
    return p, np.exp(normal.predict(target[nc])), np.exp(spike.predict(target[sc]))


def _hurdle_parts(train: pd.DataFrame, target: pd.DataFrame, features: list[str], clf_cand: set | None = None, blend: bool = False):
    return predict_hurdle(fit_hurdle(train, features, clf_cand, blend), target)


def _scores(name: str, pred: np.ndarray, test: pd.DataFrame, extra: dict | None = None) -> dict:
    actual = test["ratio"].to_numpy()
    masks = {"all": np.ones(len(test), bool), "spike": actual >= SPIKE, "holiday": test["is_holiday"].to_numpy() == 1}
    row = {"variant": name} | {f"mae_{k}": np.abs(pred - actual)[m].mean() for k, m in masks.items()}
    return row | (extra or {})


def without_covid(f: pd.DataFrame) -> pd.DataFrame:
    """학습에서 코로나 거리두기 기간을 뺀다 — 시간 순서 검증에서 모든 해 정밀도 +0.03 ~ +0.08 (12단계)."""
    return f[~pd.to_datetime(f["date"]).between(*COVID)]


def _split(feat: pd.DataFrame):
    f = feat[feat["y"].notna()]
    return without_covid(f[f["date"] < TEST_START]), f[f["date"] >= TEST_START]


def candidate_districts(train: pd.DataFrame, min_spikes: int = 1) -> set:
    """경보 대상 = 학습 기간에 급증(2배+)한 날이 min_spikes 번 이상인 상권 (사용자 결정 B1: 이벤트 상권만). 시험 구간은 안 본다.
    행사장 상권(역 자리에 더한 것)은 학습에만 쓰고 경보 대상에서는 뺀다 (19단계).
    1번이면 일회성 급증 상권이 쌓여 급증 비율이 떨어진다 — 2025 새 후보 17곳의 그해 급증 비율 0.12% (21단계)."""
    sp = train.loc[train["ratio"] >= SPIKE]
    n = sp.groupby("trdar_cd")["date"].nunique() if "date" in sp else sp.groupby("trdar_cd").size()
    return {c for c, k in n.items() if k >= min_spikes and (VENUES["on"] or not str(c).startswith(VENUE_PREFIX))}


VENUES = {"on": False}  # 50단계 비교: 행사장 상권(고척 · 잠실 · 올림픽공원)도 경보 대상으로 — 모든 호출(rolling · 보정 · 예측표)이 같이 따른다


def signal_threshold(test: pd.DataFrame, p: np.ndarray, cand: set, target: float = TARGET_RECALL, grid=GRID) -> tuple[float, bool]:
    """후보 상권 · 추정 신호 재현율이 target 이상인 가장 높은 기준값. 어떤 기준값으로도 안 되면 (가장 낮은 값, False) — '달성 불가' 로 기록 (11 검토)."""
    ok = [t for t in grid if cand_scores(test, p, t, cand)["cand_recall"] >= target]
    return (float(max(ok)), True) if ok else (float(min(grid)), False)


def precision_threshold(test: pd.DataFrame, p: np.ndarray, cand: set, target: float = TARGET_PRECISION, grid=GRID) -> tuple[float, bool]:
    """높은 위험 확정 경보 기준값: 정밀도 target 이상인 가장 낮은 기준값(그중 재현율이 가장 큼). 없으면 (가장 높은 값, False)."""
    ok = [t for t in grid if (sc := cand_scores(test, p, t, cand))["cand_alerts"] > 0 and sc["cand_precision"] >= target]
    return (float(min(ok)), True) if ok else (float(max(grid)), False)


def _hurdle_eval(train: pd.DataFrame, test: pd.DataFrame, features: list[str], cand: set | None = None, val_start: date = VAL_START,
                 clf_cand: set | None = None):
    """기준값은 2023 까지로 학습 → 2024 검증에서만 고른다 (전체 오차 최소 / 재현율 목표 — 재현율은 후보 상권에서). 시험 구간 예측 확률 · 두 기준값 · 두 예측."""
    fit_part, val = train[train["date"] < val_start], train[train["date"] >= val_start]
    pv, nv, sv = _hurdle_parts(fit_part, val, features, clf_cand)
    t_mae = choose_threshold(val["ratio"].to_numpy(), pv, nv, sv)
    if cand:  # 검증 구간 후보 상권은 검증 이전 자료로만 정한다 — 검증 해 급증으로 후보를 정하면 정답을 미리 본 셈 (11 검토 누수)
        t_rec, _ = signal_threshold(val, pv, candidate_districts(fit_part))
    else:
        t_rec = threshold_for_recall(val["ratio"].to_numpy(), pv)
    p, normal, spike = _hurdle_parts(train, test, features, clf_cand)
    return p, {t_mae: hurdle(p, normal, spike, t_mae), t_rec: hurdle(p, normal, spike, t_rec)}, t_mae, t_rec


def by_year(test: pd.DataFrame, p: np.ndarray, threshold: float) -> pd.DataFrame:
    """연도별 탐지 — 검증에서 맞춘 재현율이 해마다 얼마나 흔들리나 (재평가 4)."""
    years = pd.to_datetime(test["date"]).dt.year.to_numpy()
    actual, dates, sig = test["ratio"].to_numpy(), test["date"].to_numpy(), signal_ids(test)
    return pd.DataFrame([{"year": int(y), "threshold": threshold} | detection_scores(actual[m], p[m], threshold, dates[m], sig[m])
                         for y in np.unique(years) for m in [years == y]])


def known_share(events: pd.DataFrame, horizon: int = HORIZON, start=TEST_START) -> float:
    """시험 구간 일정 중 예측 시점(행사일 - horizon)에 이미 수집돼 있던 비율. 과거 스냅샷이 없으면 0 (03 검토 P1)."""
    ev = events[(events["source"] != "holiday") & events["trdar_cd"].notna() & (events["start_date"] >= start)]
    if ev.empty:
        return float("nan")
    seen = pd.to_datetime(ev["collected_at"], errors="coerce")
    origin = pd.to_datetime(ev["start_date"]) - pd.Timedelta(days=horizon)
    return float((seen.notna() & (seen <= origin)).mean())


def cand_scores(test: pd.DataFrame, p: np.ndarray, threshold: float, cand: set) -> dict:
    """후보 상권만의 경보 성능 + 목표(정밀도 0.8 · 재현율 0.7)까지 거리 + 높은 위험 경보 중 실제 붐빔(1.3배+) 이상 비율."""
    m = test["trdar_cd"].isin(cand).to_numpy()
    t, pm, actual = test[m], p[m], test["ratio"].to_numpy()[m]
    sig = signal_ids(t)
    d = detection_scores(actual, pm, threshold, t["date"].to_numpy(), sig)
    alerts = pm >= threshold
    # 평균정밀도도 판정 단위(추정 신호)로: 신호마다 급증 여부 = 상권 중 하나라도 급증, 점수 = 상권 중 최고 (11 검토)
    g = pd.DataFrame({"s": sig, "y": actual >= SPIKE, "p": pm, "r": actual}).groupby("s").agg(y=("y", "any"), p=("p", "max"), r=("r", "max"))
    ap = average_precision_score(g["y"], g["p"]) if g["y"].any() else np.nan
    on = g["p"] >= threshold
    n_alerts = int(on.sum())
    return {"cand_precision": d["signal_precision"], "cand_recall": d["signal_recall"], "cand_avg_precision": ap, "cand_alerts": n_alerts,
            "cand_date_recall": d["date_recall"], "cand_busy_hit": float((g.loc[on, "r"] >= BUSY).mean()) if on.any() else np.nan,
            "gap_precision": max(0.0, TARGET_PRECISION - d["signal_precision"]), "gap_recall": max(0.0, TARGET_RECALL - d["signal_recall"])}


def _alert_scores(test: pd.DataFrame, p: np.ndarray, t: float, cand: set | None) -> dict:
    out = detection_scores(test["ratio"].to_numpy(), p, t, test["date"].to_numpy(), signal_ids(test))
    return out | (cand_scores(test, p, t, cand) if cand else {})


def _variant(rows: list, label: str, train: pd.DataFrame, test: pd.DataFrame, features: list[str], cand: set | None = None) -> None:
    """같은 분할 · 같은 기준값 정책(2024 검증에서 재현율 목표)으로 회귀 + 허들 두 줄."""
    m, cols = _reg(train, size_features(features))
    rows.append(_scores(f"{label} · 회귀", np.exp(m.predict(test[cols])), test))
    p, preds, _, t = _hurdle_eval(train, test, features, cand)
    rows.append(_scores(f"{label} · 허들 재현율 목표 (기준값 {t})", preds[t], test, _alert_scores(test, p, t, cand)))


def run_experiment(feat: pd.DataFrame, share: float = float("nan"), variants: dict[str, pd.DataFrame] | None = None):
    """feat 은 7일 앞 조건(at_horizon)으로 만든 특징. 경보 성능은 전체 상권과 후보 상권(cand_*)을 함께 낸다."""
    train, test = _split(feat)
    cand = candidate_districts(train)
    rows = [_scores("배수 1 (요일 기준선)", np.ones(len(test)), test),
            _scores("작년 같은 요일", test["ly_ratio"].fillna(1.0).to_numpy(), test)]
    no_schedule = [c for c in OPS_FEATURES if c not in SCHEDULE_FEATURES]
    for name, feats in (("확장 + 과거 반응 (실측 날씨)", EXT2_FEATURES), ("운영 회귀 (날씨 제외)", size_features(OPS_FEATURES)),
                        ("운영 · 행사 일정 없이 (사전 예측 하한)", no_schedule)):
        m, cols = _reg(train, feats)
        rows.append(_scores(name, np.exp(m.predict(test[cols])), test))

    actual, dates, sig = test["ratio"].to_numpy(), test["date"].to_numpy(), signal_ids(test)
    p, preds, t_mae, t_rec = _hurdle_eval(train, test, OPS_FEATURES, cand)
    for label, t in ((f"운영 허들 · 오차 최소 (기준값 {t_mae})", t_mae), (f"운영 허들 · 재현율 {RECALL_TARGET:.0%} 목표 (기준값 {t_rec})", t_rec)):
        rows.append(_scores(label, preds[t], test, _alert_scores(test, p, t, cand)))

    for label, fv in (variants or {}).items():  # 같은 조건에서 입력 데이터만 다른 비교 (한강 행사 구분 등)
        _variant(rows, f"비교 · {label}", *_split(fv), OPS_FEATURES, cand)

    # 엄격: 예측 시점에 공개 확인된 일정만. shortcut: 과거 스냅샷이 없어 확인된 일정이 0% → 일정 특징을 통째로 뺀다
    _variant(rows, f"엄격 (공개 확인 일정 {share:.0%})", train, test, no_schedule, cand)
    city = _ref("citywide_days.csv", ["date", "name", "announced"])
    tr_, te_ = train.copy(), test.copy()
    tr_["citywide_effect"], te_["citywide_effect"] = mask_unannounced(tr_, city), mask_unannounced(te_, city)
    _variant(rows, "엄격 + 공개일 확인된 도시 행사", tr_, te_, no_schedule + ["citywide_effect"], cand)

    return (pd.DataFrame(rows), by_cause(test, preds[t_mae]), threshold_curve(actual, p, dates, signals=sig), top_k_hit_rate(actual, p),
            by_year(test, p, t_rec), alert_outcomes(test, p, t_rec), daily_top_k(test, p))


def cause_label(df: pd.DataFrame) -> np.ndarray:
    """상권-일마다 그날 알려진 원인 하나 (위에서부터 우선)."""
    return np.select(
        [df["big_event_count"] > 0, df["semester_week"] > 0, df["suneung_week"] > 0, df["is_holiday"] == 1,
         df["short_festival_count"] > 0, df["citywide_effect"].notna(), df["bloom_day"].notna(), df["venue_effect"].notna()],
        ["큰 행사(경기 · 공연)", "개강", "수능 후 주말", "공휴일", "축제", "도시 행사(불꽃축제 · 마라톤 · 근로자의 날)", "봄꽃 개화기", "그 밖의 예정 행사"],
        default="원인 모름")


CAUSE_TAGS = [("큰 행사(경기 · 공연)", lambda d: d["big_event_count"] > 0), ("개강", lambda d: d["semester_week"] > 0),
              ("수능 후 주말", lambda d: d["suneung_week"] > 0), ("공휴일", lambda d: d["is_holiday"] == 1),
              ("축제", lambda d: d["short_festival_count"] > 0), ("도시 행사", lambda d: d["citywide_effect"].notna()),
              ("봄꽃 개화기", lambda d: d["bloom_day"].notna()), ("그 밖의 예정 행사", lambda d: d["venue_effect"].notna())]


DAY_HIGH = 0.2  # 그날 문맥: 이 점수 이상인 상권 수


def _stack_x(frame: pd.DataFrame, p: np.ndarray, ctx: bool = False, extra: tuple = ()) -> np.ndarray:
    """extra (47단계): frame 의 열을 보정 입력에 더한다 — '_mu'(회귀 예측 log 배수) · '_q95'(95% 분위 log 배수).
    분류기는 2배 넘은 날만 양성으로 보지만 회귀는 1.5 ~ 1.99배 '거의 급증' 까지 배운다."""
    x = _stack_base(frame, p, ctx)
    return np.column_stack([x] + [frame[c].fillna(0).to_numpy(dtype=float) for c in extra]) if extra else x


def _stack_base(frame: pd.DataFrame, p: np.ndarray, ctx: bool = False) -> np.ndarray:
    """보정 단계 입력: logit(원래 점수) · 그날 알려진 원인 수 (cause_tags 와 같은 정의, scripts/overlap_calibration.py 와 같음).
    ctx (42단계, v2-plan B): 그날 전체 문맥 — 그날 모든 상권 점수의 최고(logit) · 높은 상권 수(log1p). 헛경보의 70% 가 급증 없는 날이라
    '그날 도시 전체가 붐비는 날인가' 를 같이 본다. 같은 날 다른 상권의 점수뿐이라 미래 정답을 쓰지 않는다."""
    q = np.clip(p, 1e-6, 1 - 1e-6)
    lp = np.log(q / (1 - q))
    n = np.sum([cond(frame).fillna(False).to_numpy(dtype=bool) for _, cond in CAUSE_TAGS], axis=0)
    if not ctx:
        return np.column_stack([lp, n])
    day = pd.DataFrame({"d": frame["date"].to_numpy(), "lp": lp, "hi": p >= DAY_HIGH}).groupby("d")
    return np.column_stack([lp, n, day["lp"].transform("max").to_numpy(), np.log1p(day["hi"].transform("sum").to_numpy())])


def _stack_fit(pool: pd.DataFrame, pp: np.ndarray, ctx: bool = False, extra: tuple = (), halflife: float | None = None) -> LogisticRegression:
    """이전 해들의 시간순 예측(원래 점수)과 그 실제 급증으로만 학습 — 그해 정답은 안 본다 (보고 22 §4).
    halflife (47단계): 해마다 급증 비율이 줄어(0.58 → 0.35%) 오래된 해가 점수를 높게 끌어올린다 → 최근 해에 무게 0.5 ** (몇 해 전 / halflife)."""
    w = None
    if halflife:
        yr = pd.to_datetime(pool["date"]).dt.year.to_numpy()
        w = 0.5 ** ((yr.max() - yr) / halflife)
    return LogisticRegression(C=1e6, max_iter=1000).fit(_stack_x(pool, pp, ctx, extra), pool["ratio"].to_numpy() >= SPIKE, sample_weight=w)


def _stack_apply(model: LogisticRegression, frame: pd.DataFrame, p: np.ndarray, ctx: bool = False, extra: tuple = ()) -> np.ndarray:
    return model.predict_proba(_stack_x(frame, p, ctx, extra))[:, 1]


def _qreg(train: pd.DataFrame, features: list[str], alpha: float = 0.95):
    """log 배수의 alpha 분위 회귀 (47단계) — 평균 μ 와 함께 '이 상권 · 이 날의 꼬리가 얼마나 긴가' (경기장 · 축제 날은 넓다)."""
    cols = _cols(train, features)
    m = XGBRegressor(**{"min_child_weight": 20, **TREE, "objective": "reg:quantileerror", "quantile_alpha": alpha})
    return m.fit(train[cols], train["y"]), cols


def cause_tags(df: pd.DataFrame) -> np.ndarray:
    """그날 알려진 원인을 **모두** ' + ' 로 (cause_label 은 우선순위 하나만 — 겹침 분석에는 이것, ChatGPT 25 · 점검 20). 없으면 '원인 모름'."""
    on = np.column_stack([cond(df).fillna(False).to_numpy(dtype=bool) for _, cond in CAUSE_TAGS])
    names = [n for n, _ in CAUSE_TAGS]
    return np.array([" + ".join(n for n, x in zip(names, row) if x) or "원인 모름" for row in on])


def by_cause(test: pd.DataFrame, pred: np.ndarray) -> pd.DataFrame:
    """급증일을 원인별로 나눠 실제 인원 대비 오차. 어떤 정보를 더 넣어야 하는지 보여 준다."""
    s = test.assign(pred=pred)
    s = s[s["ratio"] >= SPIKE].copy()
    s["cause"] = cause_label(s)
    s["rel"] = (s["pred"] - s["ratio"]).abs() / s["ratio"]
    s["signal"] = signal_ids(s)
    return (s.groupby("cause").agg(days=("rel", "size"), signals=("signal", "nunique"), actual=("ratio", "mean"), predicted=("pred", "mean"), rel_error=("rel", "mean"))
            .sort_values("days", ascending=False).reset_index())


def alert_outcomes(test: pd.DataFrame, p: np.ndarray, threshold: float) -> pd.DataFrame:
    """경보 · 실제 급증 상권-일을 맞음(적중) · 헛경보 · 놓침으로 나누고 원인을 붙인다 (04 권고 5: 오탐과 누락을 따로 분석)."""
    t = test.assign(p=p, alert=p >= threshold, spike=test["ratio"].to_numpy() >= SPIKE)
    t = t[t["alert"] | t["spike"]].copy()
    t["outcome"] = np.select([t["alert"] & t["spike"], t["alert"]], ["적중", "헛경보"], default="놓침")
    t["cause"] = cause_label(t)
    t["causes"] = cause_tags(t)
    t["signal"] = signal_ids(t)
    return t[["trdar_cd", "date", "ratio", "p", "outcome", "cause", "causes", "signal"]].sort_values(["outcome", "date"])


def precision_at_recall(test: pd.DataFrame, p: np.ndarray, cand: set, target: float = TARGET_RECALL, grid=GRID) -> float:
    """후보 상권 · 추정 신호에서 재현율이 target 이상인 기준값들 중 가장 높은 정밀도 — 목표 판정 한 줄 (07 검토 4).
    시험 구간에서 기준값을 고르는 셈이라 '이 모델로 닿을 수 있는 최선' 이지 운영 성능이 아니다."""
    best = 0.0
    for t in grid:
        sc = cand_scores(test, p, t, cand)
        if sc["cand_recall"] >= target:
            best = max(best, sc["cand_precision"])
    return best


def rolling(feat: pd.DataFrame, years=ROLLING_YEARS, drop_covid: bool = True, features: list[str] = OPS_FEATURES,
            cand_clf: bool = False, blend: bool = False, label: str = "기본", min_spikes: int = 1, tree: dict | None = None,
            test_feat: pd.DataFrame | None = None, stack: bool = False, conf_target: float = TARGET_PRECISION, stack_ctx: bool = False,
            stack_extra: tuple = (), stack_halflife: float | None = None) -> pd.DataFrame:
    """시간 순서 검증 (11 · 12 검토): 해마다 그 전 해까지로 학습해 그해를 예측한다 (out-of-fold). 그해 기준값 두 개는
    **이전 해들의 out-of-fold 예측을 모아** 고르고, 그해 정답은 판정에만 쓴다 — 높은 위험 확정(정밀도 0.5) · 참고(재현율 0.7).
    upper_precision_at_recall70 은 그해 정답으로 고른 상한(진단용)이지 판정이 아니다.
    test_feat 를 주면 시험 해 행은 그 표에서 (학습 · 시험 입력 분리 비교, ChatGPT 18 — 예: 프로야구를 학습에만 / 시험에만).
    conf_target = 높은 위험 기준값을 고를 때 지킬 이전 해 정밀도 (33단계: 보정 점수에서는 0.5 가 보수적이라 실험으로 고른다)."""
    f = feat[feat["y"].notna()]
    tf = f if test_feat is None else test_feat[test_feat["y"].notna()]
    if drop_covid:
        f, tf = without_covid(f), without_covid(tf)
    if tree:  # 하이퍼파라미터 비교 — 이 실행 동안만 바꾼다
        saved = dict(TREE)
        TREE.update(tree)
        try:
            return rolling(feat, years, drop_covid, features, cand_clf, blend, label, min_spikes, test_feat=test_feat, stack=stack,
                           conf_target=conf_target, stack_ctx=stack_ctx, stack_extra=stack_extra, stack_halflife=stack_halflife)
        finally:
            TREE.clear()
            TREE.update(saved)
    rows, oof, sigs = [], [], []
    oof_s = []  # stack: 보정된 이전 해 점수 — 높은 위험 기준값은 이것으로 고른다 (그해 정답 안 씀)
    oof_v = []  # 행사장 상권의 이전 해 점수 — 행사장 참고 기준값용
    PROGRESS["i"] += 1
    log(f"▶ [{PROGRESS['i']}/{PROGRESS['n'] or '?'}] {label} 시작 (특징 {len(features)}개, 연도 {min(years)} ~ {max(years)})")
    t_run = t_year = time.monotonic()
    for y in years:
        train, test = f[f["date"] < date(y, 1, 1)], tf[(tf["date"] >= date(y, 1, 1)) & (tf["date"] < date(y + 1, 1, 1))]
        if test.empty:
            continue
        cand = candidate_districts(train, min_spikes)  # 그해 직전까지 자료로만
        p, normal, _ = _hurdle_parts(train, test, features, cand if (cand_clf or blend) else None, blend=blend)
        if "_mu" in stack_extra:
            test = test.assign(_mu=np.log(normal))
        if "_q95" in stack_extra:
            q, qc = _qreg(train, size_features(features))
            test = test.assign(_q95=q.predict(test[qc]))
        p_raw = p
        if stack and oof:  # 31단계 보정 단계: 이전 해 시간순 예측(원래 점수 + 원인 수)으로만 학습한 로지스틱으로 그해 점수를 다시
            pool_r, pp_r = pd.concat([o[0] for o in oof], ignore_index=True), np.concatenate([o[1] for o in oof])
            n_pool = int(signal_frame(pool_r, pp_r, set(pool_r["trdar_cd"]))["y"].sum())
            if n_pool >= MIN_POOL_SPIKES:  # 준비 연도 규칙과 같은 최소 표본
                cal = _stack_fit(pool_r, pp_r, stack_ctx, stack_extra, stack_halflife)
                p = _stack_apply(cal, test, p_raw, stack_ctx, stack_extra)
                log(f"   {y}: 보정 적용 — 이전 해 급증 신호 {n_pool} · 계수 logit(점수) {cal.coef_[0][0]:.2f} · 원인 수 {cal.coef_[0][1]:.2f}")
            else:
                log(f"   {y}: 보정 안 함 — 이전 해 급증 신호 {n_pool} < {MIN_POOL_SPIKES} (원래 점수)")
        row = {"variant": label, "year": y}
        if oof:
            src = oof_s if stack else oof
            pool = pd.concat([o[0] for o in src], ignore_index=True)
            pp = np.concatenate([o[1] for o in src])
            pc = set(pool["trdar_cd"])
            for tier, (t, ok) in (("conf", precision_threshold(pool, pp, pc, target=conf_target)), ("ref", signal_threshold(pool, pp, pc))):
                sc = cand_scores(test, p, t, cand)
                row |= {f"{tier}_threshold": t, f"{tier}_rule_ok": ok, f"{tier}_precision": sc["cand_precision"],
                        f"{tier}_recall": sc["cand_recall"], f"{tier}_alerts": sc["cand_alerts"], f"{tier}_busy": sc["cand_busy_hit"]}
            # 기준값 보정 표본 (ChatGPT 21): 고른 높은 위험 기준값을 지탱한 이전 해 out-of-fold 급증 신호 · 경보 수. 모자라면 '준비 연도' —
            # 성적은 남기되 합산 대표값에서는 뺀다. 규칙은 2026-11-06 등록(28단계 2023 의 기준값 0.999 · 경보 1건을 본 뒤라 사후 등록임을 기록)
            row["conf_pool_spikes"] = int(signal_frame(pool, pp, pc)["y"].sum())
            row["conf_pool_alerts"] = cand_scores(pool, pp, row["conf_threshold"], pc)["cand_alerts"]
            row["warmup"] = row["conf_pool_spikes"] < MIN_POOL_SPIKES or row["conf_pool_alerts"] < MIN_POOL_ALERTS
            row["goal_met"] = row["ref_recall"] >= TARGET_RECALL and row["ref_precision"] >= TARGET_PRECISION
            row |= band_scores(test, p, row["ref_threshold"], row["conf_threshold"], cand)
            sigs.append(signal_frame(test, p, cand).assign(year=y, threshold=row["conf_threshold"]))
        row |= {"cand_avg_precision": cand_scores(test, p, 0.5, cand)["cand_avg_precision"],
                "upper_precision_at_recall70": precision_at_recall(test, p, cand)}
        # 행사장 참고 (52단계 결정): 행사장 상권은 높은 위험 대상 밖 — 같은 점수로 이전 해 행사장 신호에서 정밀도 VENUE_TARGET 기준값을 따로 고른다
        vset = {c for c in test["trdar_cd"].unique() if str(c).startswith(VENUE_PREFIX)}
        if oof_v and vset:
            pool_v, pp_v = pd.concat([o[0] for o in oof_v], ignore_index=True), np.concatenate([o[1] for o in oof_v])
            tv, _ = precision_threshold(pool_v, pp_v, set(pool_v["trdar_cd"]), target=VENUE_TARGET)
            sv = cand_scores(test, p, tv, vset)
            row |= {"venue_threshold": tv, "venue_precision": sv["cand_precision"], "venue_recall": sv["cand_recall"], "venue_alerts": sv["cand_alerts"]}
        vm = test["trdar_cd"].isin(vset).to_numpy()
        oof_v.append((test[vm], p[vm]))
        rows.append(row)
        red = (f"높은 위험 정밀도 {row['conf_precision']:.2f} · 재현율 {row['conf_recall']:.2f} · 경보 {row['conf_alerts']}" + (" (준비 연도)" if row.get("warmup") else "")
               if "conf_precision" in row else "기준값용 첫 해 (판정 없음)")
        log(f"   {y}: 학습 {len(train):,}행 · 시험 {len(test):,}행 · 후보 {len(cand)} · 평균정밀도 {row['cand_avg_precision']:.3f} · {red} "
            f"({time.monotonic() - t_year:.0f}초)")
        t_year = time.monotonic()
        m = test["trdar_cd"].isin(cand).to_numpy()
        oof.append((test[m], p_raw[m]))  # 보정기 학습용은 원래 점수
        oof_s.append((test[m], p[m]))     # 기준값 선택용은 실제로 쓴 점수
    out = pd.DataFrame(rows)
    out.attrs["signals"] = pd.concat(sigs, ignore_index=True) if sigs else pd.DataFrame()
    judged = out[out["conf_alerts"].notna()] if "conf_alerts" in out else out.iloc[0:0]
    hits = (judged["conf_precision"] * judged["conf_alerts"]).round().sum() if len(judged) else 0
    log(f"■ {label} 끝 ({(time.monotonic() - t_run) / 60:.1f}분) — 판정 연도 합산 경보 {judged['conf_alerts'].sum() if len(judged) else 0:.0f} · "
        f"적중 {hits:.0f} · 평균정밀도 평균 {out['cand_avg_precision'].mean():.3f}" if len(out) else f"■ {label} 끝 (결과 없음)")
    return out


# 높은 위험 적중률 올리기 비교 (23단계) — 같은 예측 점수에 거르는 규칙만 바꾼다. 기준값은 거른 뒤의 이전 해 out-of-fold 로 다시 고른다
FILTERS = ["기본", "원인 있는 날만", "자주 틀린 상권 제외", "원인 + 상권", "엄격 (정밀도 0.7)"]


def bad_districts(pool: pd.DataFrame, pp: np.ndarray, t: float, min_alerts: int = 3, max_precision: float = 0.25) -> set:
    """이전 해 예측에서 경보가 min_alerts 번 이상인데 적중률이 max_precision 미만인 상권."""
    a = pd.DataFrame({"trdar_cd": pool["trdar_cd"].to_numpy(), "y": pool["ratio"].to_numpy() >= SPIKE, "alert": pp >= t})
    g = a[a["alert"]].groupby("trdar_cd")["y"].agg(["size", "mean"])
    return set(g[(g["size"] >= min_alerts) & (g["mean"] < max_precision)].index)


def filtered_scores(name: str, frame: pd.DataFrame, p: np.ndarray, bad: set) -> np.ndarray:
    """규칙에 걸린 상권-일은 점수 0 (경보 안 함). 원인 = 그날 알려진 원인(경기 · 공연 · 축제 · 공휴일 · 개강 · 도시 행사 · 봄꽃 · 예정 행사)."""
    out = p.copy()
    if "원인" in name:
        out = np.where(cause_label(frame) == "원인 모름", 0.0, out)
    if "상권" in name:
        out = np.where(frame["trdar_cd"].isin(bad).to_numpy(), 0.0, out)
    return out


def precision_filters(feat: pd.DataFrame, years=ROLLING_YEARS) -> pd.DataFrame:
    """운영 설정(반반 평균 · 코로나 제외)으로 해마다 out-of-fold 예측 → 규칙별 높은 위험 정밀도 · 재현율 · 경보 수."""
    f = without_covid(feat[feat["y"].notna()])
    rows, oof = [], []
    for y in years:
        train, test = f[f["date"] < date(y, 1, 1)], f[(f["date"] >= date(y, 1, 1)) & (f["date"] < date(y + 1, 1, 1))]
        if test.empty:
            continue
        cand = candidate_districts(train)
        p, _, _ = _hurdle_parts(train, test, OPS_FEATURES, cand, blend=True)
        if oof:
            pool = pd.concat([o[0] for o in oof], ignore_index=True)
            pp = np.concatenate([o[1] for o in oof])
            pc = set(pool["trdar_cd"])
            bad = bad_districts(pool, pp, precision_threshold(pool, pp, pc)[0])
            for name in FILTERS:
                t, ok = precision_threshold(pool, filtered_scores(name, pool, pp, bad), pc, target=0.7 if "엄격" in name else TARGET_PRECISION)
                sc = cand_scores(test, filtered_scores(name, test, p, bad), t, cand)
                rows.append({"filter": name, "year": y, "threshold": t, "rule_ok": ok, "bad_districts": len(bad),
                             "precision": sc["cand_precision"], "recall": sc["cand_recall"], "alerts": sc["cand_alerts"]})
        m = test["trdar_cd"].isin(cand).to_numpy()
        oof.append((test[m], p[m]))
    return pd.DataFrame(rows)


BLEND_LABEL = "반반 평균 (전체 + 후보)"
# 운영 설정 (사용자 결정 11/09): 보정 단계 채택 — 31 · 32단계에서 연도별 평균정밀도 8/8 상승 (v6 0.594 → 0.622)
OPS_STACK = True
OPS_CONF_TARGET = 0.4  # 33단계: 보정 점수에서 목표 0.4 가 기준선과 같은 재현율 0.63 · 적중 112 에 정밀도 0.54 → 0.63 (목표 0.5 · 0.6 은 재현율 0.55 · 0.50)
OPS_LABEL = BLEND_LABEL + " · 보정"


def fit_calibrator(hist: pd.DataFrame, features: list[str] = OPS_FEATURES, years=ROLLING_YEARS) -> LogisticRegression | None:
    """예측표용 보정기: rolling(stack=True) 과 같은 방식 — 해마다 그 전 해까지로 학습한 모델(반반 평균 · 코로나 제외)의 그해 원래 점수를
    후보 상권 행만 모아 _stack_fit. 급증 신호가 MIN_POOL_SPIKES 보다 적으면 None (rolling 의 '보정 안 함' 과 같은 규칙)."""
    f = without_covid(hist[hist["y"].notna()])
    log(f"보정 학습 시작 — 연도 {min(years)} ~ {max(years)}")
    t0, frames, scores = time.monotonic(), [], []
    for y in years:
        train, test = f[f["date"] < date(y, 1, 1)], f[(f["date"] >= date(y, 1, 1)) & (f["date"] < date(y + 1, 1, 1))]
        if train.empty or test.empty:
            continue
        cand = candidate_districts(train)
        p, _, _ = _hurdle_parts(train, test, features, cand, blend=True)
        m = test["trdar_cd"].isin(cand).to_numpy()
        frames.append(test[m])
        scores.append(p[m])
        log(f"   {y}: 학습 {len(train):,}행 · 후보 행 {int(m.sum()):,}")
    if not frames:
        log("보정 학습 끝 — 자료 없음 (보정 안 함)")
        return None
    pool, pp = pd.concat(frames, ignore_index=True), np.concatenate(scores)
    n_pool = int(signal_frame(pool, pp, set(pool["trdar_cd"]))["y"].sum())
    if n_pool < MIN_POOL_SPIKES:
        log(f"보정 학습 끝 — 급증 신호 {n_pool} < {MIN_POOL_SPIKES} (보정 안 함)")
        return None
    cal = _stack_fit(pool, pp)
    log(f"보정 학습 끝 ({(time.monotonic() - t0) / 60:.1f}분) — 급증 신호 {n_pool} · 계수 logit(점수) {cal.coef_[0][0]:.2f} · "
        f"원인 수 {cal.coef_[0][1]:.2f}")
    return cal
ALERT_HIGH, ALERT_REF = "높은 위험 · 우선 확인", "참고 신호"  # '확정' 은 과한 이름 (13 검토 1)
ALERT_VENUE = "행사장 참고"  # 52단계 결정: 고척 · 잠실 · 올림픽공원 — 높은 위험 과 따로 (이전 해 정밀도 0.5 기준값, 50단계 실험 0.55 · 재현율 0.41)
VENUE_TARGET = 0.5


def band_scores(test: pd.DataFrame, p: np.ndarray, lo: float, hi: float, cand: set) -> dict:
    """참고 신호 구간만 (lo ≤ 점수 < hi, 높은 위험 을 뺀 나머지) — 누적이 아닌 구간 지표 (13 검토 2). 추정 신호 단위, 점수 = 신호 안 상권 최고."""
    m = test["trdar_cd"].isin(cand).to_numpy()
    t = test[m]
    g = pd.DataFrame({"s": signal_ids(t), "y": t["ratio"].to_numpy() >= SPIKE, "p": p[m]}).groupby("s").agg(y=("y", "any"), p=("p", "max"))
    band = (g["p"] >= lo) & (g["p"] < hi)
    return {"band_alerts": int(band.sum()), "band_precision": float(g.loc[band, "y"].mean()) if band.any() else np.nan,
            "band_recall": float((band & g["y"]).sum() / g["y"].sum()) if g["y"].any() else np.nan}


def signal_frame(test: pd.DataFrame, p: np.ndarray, cand: set) -> pd.DataFrame:
    """후보 상권 추정 신호마다 날짜 · 급증 여부 · 점수(신호 안 최고) — 연도별 정밀도 불확실성 분석용."""
    t = test[test["trdar_cd"].isin(cand).to_numpy()]
    pm = p[test["trdar_cd"].isin(cand).to_numpy()]
    key = t["station_key"].to_numpy() if "station_key" in t else t["trdar_cd"].to_numpy()  # 모델끼리 같은 신호를 맞출 때 (47단계 공정 비교)
    return (pd.DataFrame({"s": signal_ids(t), "trdar_cd": t["trdar_cd"].to_numpy(), "date": t["date"].to_numpy(), "key": key,
                          "y": t["ratio"].to_numpy() >= SPIKE, "ratio": t["ratio"].to_numpy(), "p": pm, "cause": cause_label(t), "causes": cause_tags(t)})
            .sort_values("p", ascending=False).groupby("s").agg(trdar_cd=("trdar_cd", "first"), date=("date", "first"), key=("key", "first"), y=("y", "any"),
                                                                ratio=("ratio", "max"), p=("p", "max"), cause=("cause", "first"), causes=("causes", "first"))
            .reset_index(drop=True))


def precision_uncertainty(sig: pd.DataFrame, n_boot: int = 2000, seed: int = 0) -> pd.DataFrame:
    """연도별 높은 위험 정밀도와 날짜 묶음 부트스트랩 95% 구간 (같은 날 신호끼리 연관 — 15 검토 3).
    jan_sep_* 는 1 ~ 9월만 (2026 이 9월까지라 같은 달 범위로 비교)."""
    rng = np.random.default_rng(seed)
    rows = []
    for (year, t), g in sig.groupby(["year", "threshold"]):
        a = g[g["p"] >= t]
        row = {"year": year, "threshold": t, "alerts": len(a), "hits": int(a["y"].sum()), "precision": a["y"].mean() if len(a) else np.nan}
        if len(a):
            days = a.groupby("date")["y"].agg(["sum", "count"])
            boot = []
            for _ in range(n_boot):
                pick = days.iloc[rng.integers(0, len(days), len(days))]
                boot.append(pick["sum"].sum() / pick["count"].sum())
            row |= {"ci_low": np.quantile(boot, 0.025), "ci_high": np.quantile(boot, 0.975)}
        js = a[pd.to_datetime(a["date"]).dt.month <= 9]
        row |= {"jan_sep_alerts": len(js), "jan_sep_precision": js["y"].mean() if len(js) else np.nan}
        rows.append(row)
    return pd.DataFrame(rows)


def worst_years(r: pd.DataFrame) -> pd.DataFrame:
    """모델별 판정 연도 중 가장 나쁜 값 (12 검토 2) — 평균에 가려지는 흔들림을 본다."""
    j = r[r["ref_precision"].notna()] if "ref_precision" in r else r.iloc[0:0]
    return j.groupby("variant").agg(min_conf_precision=("conf_precision", "min"), min_ref_precision=("ref_precision", "min"),
                                    min_ref_recall=("ref_recall", "min"), min_avg_precision=("cand_avg_precision", "min"),
                                    min_upper=("upper_precision_at_recall70", "min"), years_goal_met=("goal_met", "sum")).reset_index()


def stage47(out, day: pd.DataFrame, spikes: pd.DataFrame, holidays: pd.DataFrame, events: pd.DataFrame, feat: pd.DataFrame) -> None:
    """47단계 (분석 → 개선 후보 한 번에): v2 운영 대조 · 상권 · 행사 속성 · 보정에 회귀 μ · μ + 95% 분위 · 최근 해 가중 보정 · 정답 재정의(깨끗한 기준선).
    정답 재정의 줄은 정답이 달라 평균정밀도를 다른 줄과 바로 비교하지 않는다 — 급증 수 · 쏠림과 함께 본다.
    SEOULEVENT_S47 = 쉼표로 줄 이름(a ~ f) 일부만 (기본 전부)."""
    areas = with_venue_areas(pd.read_parquet(Path(out).parent / "raw" / "district_areas" / "areas.parquet"))
    kw = {"stack": OPS_STACK, "conf_target": OPS_CONF_TARGET}
    plan = {"a": lambda: (feat, {**kw, "label": OPS_LABEL}),
            "b": lambda: (add_district_attrs(feat, areas, events), {**kw, "features": OPS_FEATURES + ATTR, "label": OPS_LABEL + " · + 상권 · 행사 속성"}),
            "c": lambda: (feat, {**kw, "stack_extra": ("_mu",), "label": OPS_LABEL + " · 보정 + 회귀 μ"}),
            "d": lambda: (feat, {**kw, "stack_extra": ("_mu", "_q95"), "label": OPS_LABEL + " · 보정 + μ + 95% 분위"}),
            "e": lambda: (feat, {**kw, "stack_halflife": 1.0, "label": OPS_LABEL + " · 최근 해 가중 보정 (반감기 1년)"}),
            "f": lambda: (_clean_label_feat(day, spikes, holidays, events, areas), {**kw, "label": OPS_LABEL + " · 정답 재정의 (행사 뺀 기준선)"})}
    pick = list(os.getenv("SEOULEVENT_S47", "abcdef").replace(",", ""))
    PROGRESS["n"] = len(pick)
    runs = []
    for k in pick:
        fr, kwk = plan[k]()
        runs.append(rolling(fr, blend=True, **kwk))
        if k == "f":
            lab = fr[fr["y"].notna() & (pd.to_datetime(fr["date"]) >= "2024-01-01")]
            old = feat[feat["y"].notna() & (pd.to_datetime(feat["date"]) >= "2024-01-01")]
            for name, x in (("지금 정답", old), ("재정의", lab)):
                sp = x[x["ratio"] >= SPIKE]
                log(f"   정답 비교 2024 ~ : {name} 급증 상권-일 {len(sp):,} · 상권 {sp['trdar_cd'].nunique()} · 월드컵경기장 몫 {(sp['trdar_cd'] == '3120099').mean():.0%}")
    signals = [(x["variant"].iloc[0], x.attrs.pop("signals")) for x in runs]
    r = pd.concat(runs, ignore_index=True)
    r.to_csv(out / "experiment_s47.csv", index=False, encoding="utf-8-sig")
    pd.concat([sg.assign(variant=v) for v, sg in signals], ignore_index=True).to_parquet(out / "experiment_s47_signals.parquet", index=False)
    print(r.round(3).to_string(index=False))
    print("\n모델별 가장 나쁜 해:")
    print(worst_years(r).round(3).to_string(index=False))
    config.write_manifest(out, "experiment_s47_manifest.json", {"variants": list(r["variant"].unique())},
                          outputs=("experiment_s47.csv", "experiment_s47_signals.parquet"))
    log("끝 — experiment_s47.csv · experiment_s47_signals.parquet")


def relabel(target: pd.DataFrame, source: pd.DataFrame) -> pd.DataFrame:
    """target 의 특징(모델 입력)은 그대로 두고 채점 정답(ratio · y)만 source 것으로 — 정답이 다른 두 모델을 같은 정답으로 채점 (47단계 공정 비교)."""
    return target.drop(columns=["ratio", "y"]).merge(source[["trdar_cd", "date", "ratio", "y"]], on=["trdar_cd", "date"])


def paired_ap(a: pd.DataFrame, b: pd.DataFrame, start: int = 2024, n_boot: int = 300, seed: int = 0) -> dict:
    """두 모델의 신호(signal_frame + year)를 같은 (날짜, 역 묶음)만 남겨 연도별 평균정밀도 평균 · 차이(b − a) · 날짜 묶음 부트스트랩 95% 구간.
    후보 상권이 모델마다 달라 공통 신호에서만 비교한다. 같은 정답이어야 한다 (y 가 다르면 오류)."""
    m = a[a["year"] >= start].merge(b[b["year"] >= start], on=["date", "key"], suffixes=("_a", "_b"))
    assert (m["y_a"] == m["y_b"]).all(), "같은 정답으로 채점한 신호끼리만 비교한다"

    def ap(g, col):
        return np.mean([average_precision_score(x["y_a"], x[col]) for _, x in g.groupby("year_a") if x["y_a"].any()])
    rng = np.random.default_rng(seed)
    dates = m["date"].unique()
    boot = []
    for _ in range(n_boot):
        w = m["date"].map(pd.Series(rng.choice(dates, len(dates))).value_counts()).fillna(0).astype(int).to_numpy()
        r = m.loc[m.index.repeat(w)]
        boot.append(ap(r, "p_b") - ap(r, "p_a"))
    out = {"signals": len(m), "spikes": int(m["y_a"].sum()), "ap_a": ap(m, "p_a"), "ap_b": ap(m, "p_b")}
    for s in ("a", "b"):
        on = m[f"p_{s}"] >= m[f"threshold_{s}"]
        out |= {f"alerts_{s}": int(on.sum()), f"precision_{s}": float(m.loc[on, "y_a"].mean()) if on.any() else np.nan,
                f"recall_{s}": float((on & m["y_a"]).sum() / max(m["y_a"].sum(), 1))}
    return out | {"diff": out["ap_b"] - out["ap_a"], "ci_low": np.quantile(boot, 0.025), "ci_high": np.quantile(boot, 0.975)}


def fair47(out, day, spikes, holidays, events, feat) -> None:
    """47단계 공정 비교: v2 정답 모델(old) · 재정의 정답 모델(new)을 같은 정답으로 채점 — old 정답에서 A vs D, new 정답에서 B vs C."""
    areas = with_venue_areas(pd.read_parquet(Path(out).parent / "raw" / "district_areas" / "areas.parquet"))
    new = _clean_label_feat(day, spikes, holidays, events, areas)
    kw = {"stack": OPS_STACK, "conf_target": OPS_CONF_TARGET, "blend": True}
    plan = {"A": (feat, None, "A · v2 정답 모델 → v2 정답 채점"), "D": (new, relabel(new, feat), "D · 재정의 모델 → v2 정답 채점"),
            "C": (new, None, "C · 재정의 모델 → 재정의 정답 채점"), "B": (feat, relabel(feat, new), "B · v2 정답 모델 → 재정의 정답 채점")}
    PROGRESS["n"] = len(plan)
    sig = {}
    for k, (tr, te, label) in plan.items():
        r = rolling(tr, test_feat=te, label=label, **kw)
        sig[k] = r.attrs.pop("signals")
    rows = [{"채점 정답": "v2", "a": "A v2 모델", "b": "D 재정의 모델"} | paired_ap(sig["A"], sig["D"]),
            {"채점 정답": "재정의", "a": "B v2 모델", "b": "C 재정의 모델"} | paired_ap(sig["B"], sig["C"])]
    res = pd.DataFrame(rows)
    res.to_csv(out / "experiment_s47_fair.csv", index=False, encoding="utf-8-sig")
    pd.concat([s.assign(variant=k) for k, s in sig.items()], ignore_index=True).to_parquet(out / "experiment_s47_fair_signals.parquet", index=False)
    print(res.round(3).T.to_string())
    config.write_manifest(out, "experiment_s47_fair_manifest.json", {"runs": list(plan)},
                          outputs=("experiment_s47_fair.csv", "experiment_s47_fair_signals.parquet"))
    log("끝 — experiment_s47_fair.csv")


WC = "3120099"  # 월드컵경기장역 상권 — 급증의 절반이라 계층을 따로 본다

EVENING = ["eve_venue_effect", "eve_ly2_win_max"]  # 53단계 비교 특징 (시간대 승하차, collect_subway_hourly)
EVE_HOURS = [f"h{h}" for h in range(17, 23)]  # 17 ~ 23시 — 저녁 공연 · 경기 인파
HOURLY_PUB = (10, 30)  # 시간대 자료는 한 해치가 다음 해 10월 말에 한꺼번에 공개 (2025년분 2026-10-30)


def hourly_cutoff(origin: pd.Series) -> pd.Series:
    """예측 시점(origin)에 공개돼 있던 시간대 자료의 마지막 날 — X 년 자료는 X+1 년 10/30 공개."""
    o = pd.to_datetime(origin)
    pub = pd.to_datetime(pd.DataFrame({"year": o.dt.year, "month": HOURLY_PUB[0], "day": HOURLY_PUB[1]}))
    last = o.dt.year - 1 - (o < pub).astype(int)
    return pd.to_datetime(pd.DataFrame({"year": last, "month": 12, "day": 31}))


def _norm_station(s) -> str:
    """'광화문(세종문화회관)' · '양평@5호선' → '광화문' · '양평' (시간대 자료와 승하차 자료의 역 이름 맞춤)."""
    import re
    return re.sub(r"\(.*?\)", "", str(s).split("@")[0]).strip()


def evening_ratio(f: pd.DataFrame, hourly: pd.DataFrame) -> pd.Series:
    """상권(역 묶음 station_key)의 저녁 승하차 ÷ 같은 요일 최근 8주 저녁 중앙값 → (date, trdar_cd) 색인. 서울교통공사 역만 (9호선 · 코레일 없음)."""
    hr = hourly.assign(st=hourly["station"].map(_norm_station), eve=hourly[EVE_HOURS].sum(axis=1, min_count=1))
    per = hr.groupby(["st", "date"])["eve"].sum().reset_index()
    pairs = f[["trdar_cd", "station_key"]].dropna().drop_duplicates("trdar_cd")
    pairs = pairs.assign(st=pairs["station_key"].str.split("|")).explode("st")
    pairs["st"] = pairs["st"].map(_norm_station)
    wide = per.merge(pairs[["trdar_cd", "st"]].drop_duplicates(), on="st").groupby(["date", "trdar_cd"])["eve"].sum().unstack()
    wide.index = pd.to_datetime(wide.index)
    wide = wide.reindex(pd.date_range(wide.index.min(), wide.index.max()))
    past = np.stack([wide.shift(7 * w).to_numpy() for w in range(1, BASE_WEEKS + 1)])
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", RuntimeWarning)
        med = np.nanmedian(past, axis=0)
    med[(~np.isnan(past)).sum(axis=0) < 4] = np.nan
    r = pd.DataFrame(wide.to_numpy() / med, index=wide.index, columns=wide.columns).stack()
    r.index.names = ["date", "trdar_cd"]
    return r


def add_evening_features(f: pd.DataFrame, events: pd.DataFrame, hourly: pd.DataFrame, horizon: int = HORIZON) -> pd.DataFrame:
    """53단계: 하루 합계가 저녁 공연을 묻는다(종합운동장 2배+ 65 → 107일) → 저녁 배수로 만든 과거 반응. 예측 시점(그날 − horizon)에
    공개돼 있던 해의 시간대 자료만 쓴다 (hourly_cutoff).
    eve_venue_effect: 이 상권이 같은 장소 과거 행사 날 저녁에 몇 배였나 (기하평균, 누적). eve_ly2_win_max: 2년 전 ±7일 저녁 배수 최대."""
    f = f.copy()
    r = evening_ratio(f, hourly)
    ts = pd.to_datetime(f["date"])
    cut = hourly_cutoff(ts - pd.Timedelta(days=horizon)).to_numpy()
    vals = []
    for k in range(-7, 8):
        d = ts - pd.Timedelta(days=TWO_YEARS - k)
        v = r.reindex(pd.MultiIndex.from_arrays([d, f["trdar_cd"]])).to_numpy(dtype=float)
        vals.append(np.where(d.to_numpy() <= cut, v, np.nan))
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", RuntimeWarning)
        f["eve_ly2_win_max"] = np.nanmax(np.vstack(vals), axis=0)

    ev = events[(events["source"] != "holiday") & events["trdar_cd"].notna() & events["place"].notna()]
    ev = ev[(pd.to_datetime(ev["end_date"]) - pd.to_datetime(ev["start_date"])).dt.days + 1 <= SHORT_EVENT_DAYS]
    vd = pd.DataFrame([(t, p, d) for t, p, s, e in zip(ev["trdar_cd"], ev["place"], ev["start_date"], ev["end_date"])
                       for d in pd.date_range(s, e)], columns=["trdar_cd", "place", "date"]).drop_duplicates()
    f["eve_venue_effect"] = np.nan
    if vd.empty:
        return f
    vd["logr"] = np.log(r.reindex(pd.MultiIndex.from_arrays([vd["date"], vd["trdar_cd"]])).to_numpy(dtype=float))
    rec = vd.dropna(subset=["logr"]).sort_values("date")
    rec["incl"] = rec.groupby(["trdar_cd", "place"])["logr"].cumsum() / (rec.groupby(["trdar_cd", "place"]).cumcount() + 1)
    # 실데이터는 날짜 단위가 섞여 있다(us · s) → merge_asof 전에 둘 다 ns 로
    left = vd.assign(cut=hourly_cutoff(vd["date"] - pd.Timedelta(days=horizon)).astype("datetime64[ns]")).sort_values("cut")
    right = rec[["trdar_cd", "place", "date", "incl"]].rename(columns={"date": "cut"}).assign(cut=lambda d: d["cut"].astype("datetime64[ns]"))
    m = pd.merge_asof(left, right.sort_values("cut"), on="cut", by=["trdar_cd", "place"])
    eff = np.exp(m.groupby(["trdar_cd", "date"])["incl"].max()).rename("eve_venue_effect")
    eff.index = pd.MultiIndex.from_arrays([eff.index.get_level_values(0), eff.index.get_level_values(1).date])
    return f.drop(columns="eve_venue_effect").join(eff, on=["trdar_cd", "date"])


SENS = ["rain_sens", "nice_sens", "rain_sens_x", "nice_sens_x"]  # 54단계 비교 특징
NICE_TA = (15.0, 27.0)  # 나들이 좋은 날: 비 없음 + 평균기온 15 ~ 27°C
MIN_SENS_DAYS = 5


def add_weather_sensitivity(f: pd.DataFrame, obs: pd.DataFrame, lag: int) -> pd.DataFrame:
    """54단계: 상권별 날씨 민감도 — 51단계 날씨 효과가 한강 · 응봉산 같은 상권에 몰려 있다 (경기장 밖 놓침 분석).
    과거 주말(실측 날씨, lag 일 전까지 공개된 승하차)의 log 배수 평균 차이: rain_sens = 맑은 주말 − 비 온 주말, nice_sens = 좋은 날 − 그 밖.
    *_x = 민감도 × 그날 예보(f 의 avg_ta · sum_rn — 3일 앞이면 3일 전 예보)가 비 · 좋은 날인지. 조건마다 5일 미만이면 빈 값."""
    f = f.copy()
    w = obs.assign(date=pd.to_datetime(obs["date"]).dt.date)[["date", "avg_ta", "sum_rn"]].dropna()
    ts = pd.to_datetime(f["date"])
    wk = f.loc[(ts.dt.day_of_week >= 5).to_numpy() & f["ratio"].notna().to_numpy(), ["trdar_cd", "date", "ratio"]].merge(w, on="date")
    rainy = wk["sum_rn"] >= 1.0
    nice = ~rainy & wk["avg_ta"].between(*NICE_TA)
    wk = wk.assign(_t=pd.to_datetime(wk["date"]).astype("datetime64[ns]"), lr=np.log(wk["ratio"].clip(*RATIO_CLIP_SENS)),
                   r1=rainy.astype(float), r0=(~rainy).astype(float), n1=nice.astype(float), n0=(~nice).astype(float)).sort_values("_t")
    for c in ("r1", "r0", "n1", "n0"):
        wk[c + "s"] = (wk["lr"] * wk[c]).groupby(wk["trdar_cd"]).cumsum()
        wk[c + "c"] = wk[c].groupby(wk["trdar_cd"]).cumsum()

    def gap(a, b):
        ok = (wk[a + "c"] >= MIN_SENS_DAYS) & (wk[b + "c"] >= MIN_SENS_DAYS)
        return np.where(ok, wk[a + "s"] / wk[a + "c"].clip(lower=1) - wk[b + "s"] / wk[b + "c"].clip(lower=1), np.nan)
    wk["rain_sens"], wk["nice_sens"] = gap("r0", "r1"), gap("n1", "n0")
    left = f[["trdar_cd"]].assign(_t=(ts - pd.Timedelta(days=lag)).astype("datetime64[ns]").to_numpy(), _i=np.arange(len(f))).sort_values("_t")
    m = pd.merge_asof(left, wk[["trdar_cd", "_t", "rain_sens", "nice_sens"]], on="_t", by="trdar_cd").sort_values("_i")
    f["rain_sens"], f["nice_sens"] = m["rain_sens"].to_numpy(), m["nice_sens"].to_numpy()
    fc_rain = (f["sum_rn"] >= 1.0).astype(float).where(f["sum_rn"].notna())
    fc_nice = ((f["sum_rn"] < 1.0) & f["avg_ta"].between(*NICE_TA)).astype(float).where(f["sum_rn"].notna() & f["avg_ta"].notna())
    f["rain_sens_x"], f["nice_sens_x"] = f["rain_sens"] * fc_rain, f["nice_sens"] * fc_nice
    return f


RATIO_CLIP_SENS = (0.05, 20.0)


def stage54(out, day, spikes, holidays, events) -> None:
    """54단계: 3일 앞(+ 3일 전 예보)에 상권별 날씨 민감도를 더해 공통 신호 비교 (전체 · 경기장 밖)."""
    raw = Path(out).parent / "raw"
    obs = pd.read_parquet(raw / "weather_seoul.parquet")
    h = SHORT_HORIZON
    fr = horizon_frame(day, spikes, holidays, events, h, raw)
    kw = {"stack": OPS_STACK, "conf_target": OPS_CONF_TARGET, "blend": True}
    PROGRESS["n"] = 2
    a = rolling(fr, features=features_for(h), label=f"{OPS_LABEL} · {h}일 앞", **kw).attrs["signals"]
    b = rolling(add_weather_sensitivity(fr, obs, ops_lag(h)), features=features_for(h) + SENS, label=f"{OPS_LABEL} · {h}일 앞 + 날씨 민감도", **kw).attrs["signals"]
    res = pd.DataFrame([{"계층": "전체"} | paired_ap(a, b), {"계층": "경기장 밖"} | paired_ap(a[a["trdar_cd"] != WC], b[b["trdar_cd"] != WC])])
    res.to_csv(out / "experiment_s54.csv", index=False, encoding="utf-8-sig")
    pd.concat([a.assign(variant="3일"), b.assign(variant="3일 + 민감도")], ignore_index=True).to_parquet(out / "experiment_s54_signals.parquet", index=False)
    print(res.round(3).T.to_string())
    config.write_manifest(out, "experiment_s54_manifest.json", {"runs": ["3일", "3일 + 민감도"]}, outputs=("experiment_s54.csv", "experiment_s54_signals.parquet"))
    log("끝 — experiment_s54.csv")


def stage53(out, day, spikes, holidays, events, feat) -> None:
    """53단계: 저녁 반응 특징을 7일 앞 v3 · 3일 앞에 각각 더해 공통 신호 비교."""
    raw = Path(out).parent / "raw"
    hourly = pd.read_parquet(raw / "subway_hourly.parquet")
    kw = {"stack": OPS_STACK, "conf_target": OPS_CONF_TARGET, "blend": True}
    rows, sig = [], {}
    PROGRESS["n"] = 4
    for h in (HORIZON, SHORT_HORIZON):
        fr = feat if h == HORIZON else horizon_frame(day, spikes, holidays, events, h, raw)
        a = rolling(fr, features=features_for(h), label=f"{OPS_LABEL} · {h}일 앞", **kw).attrs["signals"]
        b = rolling(add_evening_features(fr, events, hourly, h), features=features_for(h) + EVENING, label=f"{OPS_LABEL} · {h}일 앞 + 저녁 반응", **kw).attrs["signals"]
        sig[f"{h}일"], sig[f"{h}일 + 저녁"] = a, b
        rows += [{"시점": h, "계층": "전체"} | paired_ap(a, b), {"시점": h, "계층": "경기장 밖"} | paired_ap(a[a["trdar_cd"] != WC], b[b["trdar_cd"] != WC])]
    res = pd.DataFrame(rows)
    res.to_csv(out / "experiment_s53.csv", index=False, encoding="utf-8-sig")
    pd.concat([s.assign(variant=k) for k, s in sig.items()], ignore_index=True).to_parquet(out / "experiment_s53_signals.parquet", index=False)
    print(res.round(3).to_string(index=False))
    config.write_manifest(out, "experiment_s53_manifest.json", {"runs": list(sig)}, outputs=("experiment_s53.csv", "experiment_s53_signals.parquet"))
    log("끝 — experiment_s53.csv")


def stage48(out, feat: pd.DataFrame) -> None:
    """48단계 진단 (v3): 실측 날씨를 넣으면 경기장 밖 상권을 얼마나 더 잡나 — 상한 진단 (운영에는 미래 실측 날씨가 없다).
    v3 오류 분석: 경기장 밖 놓침 69 중 뚝섬한강공원 15 · 응봉산 9 — 좋은 날씨 주말 나들이로 보인다. 크면 7일 예보 이력 원천을 찾는다."""
    kw = {"stack": OPS_STACK, "conf_target": OPS_CONF_TARGET, "blend": True}
    plan = {"v3": OPS_FEATURES, "실측 날씨 (상한)": OPS_FEATURES + WEATHER + [c for c in WEATHER_X if c not in WEATHER]}
    PROGRESS["n"] = len(plan)
    sig = {k: rolling(feat, features=fs, label=f"{OPS_LABEL} · {k}", **kw).attrs["signals"] for k, fs in plan.items()}
    a, b = sig["v3"], sig["실측 날씨 (상한)"]
    rows = [{"계층": "전체"} | paired_ap(a, b), {"계층": "나머지 상권"} | paired_ap(a[a["trdar_cd"] != WC], b[b["trdar_cd"] != WC])]
    res = pd.DataFrame(rows)
    res.to_csv(out / "experiment_s48.csv", index=False, encoding="utf-8-sig")
    pd.concat([s.assign(variant=k) for k, s in sig.items()], ignore_index=True).to_parquet(out / "experiment_s48_signals.parquet", index=False)
    print(res.round(3).T.to_string())
    config.write_manifest(out, "experiment_s48_manifest.json", {"runs": list(plan)}, outputs=("experiment_s48.csv", "experiment_s48_signals.parquet"))
    log("끝 — experiment_s48.csv")


WEATHER_ALL = WEATHER + [c for c in WEATHER_X if c not in WEATHER]


def with_forecast_weather(day: pd.DataFrame, fc: pd.DataFrame) -> pd.DataFrame:
    """실측 날씨(avg_ta · sum_rn)를 7일 전 예보(collect_weather_fc)로 바꾼다 — 예보 없는 날(2021-01-20 전)은 빈 값 (49단계)."""
    fc = fc.assign(date=pd.to_datetime(fc["date"]).dt.date)[["date", "avg_ta", "sum_rn"]]
    return day.drop(columns=["avg_ta", "sum_rn"]).merge(fc, on="date", how="left")


def stage49(out, day, spikes, holidays, events, feat) -> None:
    """49단계: 48단계 상한(실측 날씨)을 운영 조건으로 — 7일 전 예보 날씨로 학습 · 검증. v3 와 공통 신호 비교 (전체 · 경기장 밖)."""
    fc = pd.read_parquet(Path(out).parent / "raw" / "weather_fc7.parquet")
    ff = at_horizon(add_extended_features(build_features(with_forecast_weather(day, fc), spikes, holidays), spikes), events, OPS_LAG)
    kw = {"stack": OPS_STACK, "conf_target": OPS_CONF_TARGET, "blend": True}
    PROGRESS["n"] = 2
    a = rolling(feat, label=f"{OPS_LABEL} · v3", **kw).attrs["signals"]
    b = rolling(ff, features=OPS_FEATURES + WEATHER_ALL, label=f"{OPS_LABEL} · 7일 전 예보 날씨", **kw).attrs["signals"]
    res = pd.DataFrame([{"계층": "전체"} | paired_ap(a, b), {"계층": "나머지 상권"} | paired_ap(a[a["trdar_cd"] != WC], b[b["trdar_cd"] != WC])])
    res.to_csv(out / "experiment_s49.csv", index=False, encoding="utf-8-sig")
    pd.concat([a.assign(variant="v3"), b.assign(variant="예보 날씨")], ignore_index=True).to_parquet(out / "experiment_s49_signals.parquet", index=False)
    print(res.round(3).T.to_string())
    config.write_manifest(out, "experiment_s49_manifest.json", {"runs": ["v3", "예보 날씨"]}, outputs=("experiment_s49.csv", "experiment_s49_signals.parquet"))
    log("끝 — experiment_s49.csv")


DATA_DELAY = 4  # 승하차 최근 시트가 3일 전까지 → 자료 4일 지연 (41단계)


def ops_lag(horizon: int) -> int:
    """h 일 앞 예측의 운영 지연 = 자료 4일 + h → 같은 요일이 되도록 7의 배수로 올림 (7일 앞 → 14 = OPS_LAG)."""
    return 7 * -(-(horizon + DATA_DELAY) // 7)


SHORT_HORIZON = 3  # 51단계 채택: 3일 앞 예측표 (+ 3일 전 예보 날씨) — 7일 앞(HORIZON)과 함께 낸다


def horizon_arg(argv: list[str]) -> int:
    """`--horizon N` (기본 7일 앞)."""
    return int(argv[argv.index("--horizon") + 1]) if "--horizon" in argv else HORIZON


def horizon_suffix(h: int) -> str:
    """7일 앞 출력은 예전 이름 그대로, 다른 시점은 _h3 처럼 붙인다 (예측표 · 기준값 파일 · 저장 모델)."""
    return "" if h == HORIZON else f"_h{h}"


def features_for(h: int) -> list[str]:
    """7일 앞 = 운영 특징(날씨 없음), 그보다 짧으면 + 그 시점 예보 날씨 (49단계: 7일 앞 예보는 효과 없음, 51단계: 3일 앞은 유의)."""
    return OPS_FEATURES if h == HORIZON else OPS_FEATURES + WEATHER_ALL


def horizon_frame(day, spikes, holidays, events, h: int, raw: Path) -> pd.DataFrame:
    """h 일 앞 운영 조건의 특징 표 — 날씨는 h 일 전 예보(weather_fc{h}.parquet), 지연은 ops_lag(h)."""
    d = day if h == HORIZON else with_forecast_weather(day, pd.read_parquet(raw / f"weather_fc{h}.parquet"))
    return at_horizon(add_extended_features(build_features(d, spikes, holidays), spikes), events, ops_lag(h))


def stage51(out, day, spikes, holidays, events, feat) -> None:
    """51단계: 예측 시점 단축 + 그 시점의 날씨 예보. 1 · 3일 앞 예보는 비 온 날을 90% · 81% 맞힌다 (7일 앞 60%).
    7일 앞 v3 · 3일 앞 날씨 없이 · 3일 앞 + 3일 전 예보 · 1일 앞 + 1일 전 예보 — 정답은 같고 입력만 다르다."""
    raw = Path(out).parent / "raw"
    kw = {"stack": OPS_STACK, "conf_target": OPS_CONF_TARGET, "blend": True}

    def frame(h, lead):
        d = day if lead is None else with_forecast_weather(day, pd.read_parquet(raw / f"weather_fc{lead}.parquet"))
        return at_horizon(add_extended_features(build_features(d, spikes, holidays), spikes), events, ops_lag(h))
    plan = [("7일 앞 v3", lambda: feat, OPS_FEATURES), ("3일 앞 · 날씨 없이", lambda: frame(3, None), OPS_FEATURES),
            ("3일 앞 + 3일 전 예보", lambda: frame(3, 3), OPS_FEATURES + WEATHER_ALL), ("1일 앞 + 1일 전 예보", lambda: frame(1, 1), OPS_FEATURES + WEATHER_ALL)]
    PROGRESS["n"] = len(plan)
    sig = {}
    for name, make, fs in plan:
        sig[name] = rolling(make(), features=fs, label=f"{OPS_LABEL} · {name}", **kw).attrs["signals"]
    pairs = [("7일 앞 v3", "3일 앞 · 날씨 없이"), ("3일 앞 · 날씨 없이", "3일 앞 + 3일 전 예보"), ("7일 앞 v3", "3일 앞 + 3일 전 예보"), ("7일 앞 v3", "1일 앞 + 1일 전 예보")]
    rows = []
    for x, y in pairs:
        a, b = sig[x], sig[y]
        rows += [{"비교": f"{x} → {y}", "계층": "전체"} | paired_ap(a, b),
                 {"비교": f"{x} → {y}", "계층": "경기장 밖"} | paired_ap(a[a["trdar_cd"] != WC], b[b["trdar_cd"] != WC])]
    res = pd.DataFrame(rows)
    res.to_csv(out / "experiment_s51.csv", index=False, encoding="utf-8-sig")
    pd.concat([s.assign(variant=k) for k, s in sig.items()], ignore_index=True).to_parquet(out / "experiment_s51_signals.parquet", index=False)
    print(res.round(3).to_string(index=False))
    config.write_manifest(out, "experiment_s51_manifest.json", {"runs": list(sig)}, outputs=("experiment_s51.csv", "experiment_s51_signals.parquet"))
    log("끝 — experiment_s51.csv")


def stage50(out, feat: pd.DataFrame) -> None:
    """50단계: 행사장 상권도 경보 대상으로 (19단계 제외 이후 일정 원천이 늘었다 · 11/14 고척 2.8배 예측이 경보 밖).
    기존 상권은 v3 와 공통 신호로 비교, 행사장 상권은 그 경보만의 정밀도 · 재현율 · 평균정밀도."""
    kw = {"stack": OPS_STACK, "conf_target": OPS_CONF_TARGET, "blend": True}
    PROGRESS["n"] = 2
    a = rolling(feat, label=f"{OPS_LABEL} · v3", **kw).attrs["signals"]
    VENUES["on"] = True
    try:
        b = rolling(feat, label=f"{OPS_LABEL} · + 행사장 상권", **kw).attrs["signals"]
    finally:
        VENUES["on"] = False
    ven = b["trdar_cd"].astype(str).str.startswith(VENUE_PREFIX)
    rows = [{"계층": "기존 상권"} | paired_ap(a, b[~ven]), {"계층": "기존 상권 · 경기장 밖"} | paired_ap(a[a["trdar_cd"] != WC], b[~ven & (b["trdar_cd"] != WC)])]
    v = b[ven & (b["year"] >= 2024)]
    on = v["p"] >= v["threshold"]
    vap = np.mean([average_precision_score(g["y"], g["p"]) for _, g in v.groupby("year") if g["y"].any()]) if v["y"].any() else np.nan
    rows.append({"계층": "행사장 상권만", "signals": len(v), "spikes": int(v["y"].sum()), "ap_b": vap, "alerts_b": int(on.sum()),
                 "precision_b": float(v.loc[on, "y"].mean()) if on.any() else np.nan, "recall_b": float((on & v["y"]).sum() / max(v["y"].sum(), 1))})
    allb = b[b["year"] >= 2024]
    ab = allb["p"] >= allb["threshold"]
    rows.append({"계층": "전체 (행사장 포함)", "signals": len(allb), "spikes": int(allb["y"].sum()), "alerts_b": int(ab.sum()),
                 "precision_b": float(allb.loc[ab, "y"].mean()), "recall_b": float((ab & allb["y"]).sum() / allb["y"].sum())})
    res = pd.DataFrame(rows)
    res.to_csv(out / "experiment_s50.csv", index=False, encoding="utf-8-sig")
    pd.concat([a.assign(variant="v3"), b.assign(variant="+ 행사장")], ignore_index=True).to_parquet(out / "experiment_s50_signals.parquet", index=False)
    print(res.round(3).T.to_string())
    config.write_manifest(out, "experiment_s50_manifest.json", {"runs": ["v3", "+ 행사장"]}, outputs=("experiment_s50.csv", "experiment_s50_signals.parquet"))
    log("끝 — experiment_s50.csv")


def _clean_label_feat(day, spikes, holidays, events, areas) -> pd.DataFrame:
    city = _ref("citywide_days.csv", ["date", "name"])
    cday = clean_baseline(day, city["date"])
    csp = detect_spikes(cday, areas)
    csp["date"] = pd.to_datetime(csp["date"]).dt.date
    log(f"정답 재정의 — 급증 {len(spikes):,} → {len(csp):,}")
    return at_horizon(add_extended_features(build_features(cday, csp, holidays), csp), events, OPS_LAG)


def main() -> None:
    cfg = config.load()
    out = cfg.data_dir / "out"
    log(f"시작 — 장치 {DEVICE}, 인자 {' '.join(sys.argv[1:]) or '(없음)'}")
    day = pd.read_parquet(out / "district_day.parquet")
    spikes = pd.read_csv(out / "spikes.csv", encoding="utf-8-sig", dtype={"trdar_cd": str}, parse_dates=["date"])
    spikes["date"] = spikes["date"].dt.date
    holidays = holidays_kr.load(cfg.data_dir / "raw", range(min(day["date"]).year, max(day["date"]).year + 1))
    events = pd.read_parquet(out / "events.parquet")
    log(f"데이터 읽음 — 상권-일 {len(day):,} · 급증 {len(spikes):,} · 행사 {len(events):,}")
    base = add_extended_features(build_features(day, spikes, holidays), spikes)
    feat = at_horizon(base, events, OPS_LAG)  # v2 운영 조건: 7일 앞 + 승하차 자료 4일 지연 → 같은 요일 기준 14일 (41단계)
    log(f"특징 완료 — {feat.shape[1]}열")
    if "--filters" in sys.argv:  # 높은 위험 적중률 올리기 비교: python -m weglee_ai.seoulevent.model.experiment --filters
        r = precision_filters(feat)
        r.to_csv(out / "experiment_filters.csv", index=False, encoding="utf-8-sig")
        r["hits"] = (r["precision"] * r["alerts"]).round()
        g = r.groupby("filter", sort=False).agg(alerts=("alerts", "sum"), hits=("hits", "sum"), min_precision=("precision", "min"),
                                                 max_precision=("precision", "max"), mean_recall=("recall", "mean"))
        g["pooled_precision"] = g["hits"] / g["alerts"]
        print(r.round(3).to_string(index=False))
        print("\n규칙별 합산 (2023 ~ 2026):")
        print(g.round(3).to_string())
        return
    if "--stage54" in sys.argv:  # 54단계 — 상권별 날씨 민감도 (3일 앞)
        stage54(out, day, spikes, holidays, events)
        return
    if "--stage53" in sys.argv:  # 53단계 — 시간대 승하차 저녁 반응 특징
        stage53(out, day, spikes, holidays, events, feat)
        return
    if "--stage51" in sys.argv:  # 51단계 — 1 · 3일 앞 + 날씨 예보
        stage51(out, day, spikes, holidays, events, feat)
        return
    if "--stage50" in sys.argv:  # 50단계 — 행사장 상권 경보
        stage50(out, feat)
        return
    if "--stage49" in sys.argv:  # 49단계 — 7일 전 예보 날씨 (운영 조건)
        stage49(out, day, spikes, holidays, events, feat)
        return
    if "--stage48" in sys.argv:  # 48단계 — 실측 날씨 상한 진단 (v3)
        stage48(out, feat)
        return
    if "--fair47" in sys.argv:  # 47단계 공정 비교 — 두 정답 모델을 같은 정답으로 채점
        fair47(out, day, spikes, holidays, events, feat)
        return
    if "--stage47" in sys.argv:  # 47단계 비교 — 결과는 experiment_s47* (운영 기준값 파일 experiment_rolling.csv 는 안 건드림)
        stage47(out, day, spikes, holidays, events, feat)
        return
    if "--rolling" in sys.argv:  # 시간 순서 검증만: python -m weglee_ai.seoulevent.model.experiment --rolling
        # 운영 설정(반반 평균) + 후보 상권 규칙 비교 (21단계: 급증 1번 → 2 · 3번 이상)
        # 운영 설정(대진 반응 포함) — 25단계 데이터 수정(쿠팡플레이 분류 · 공원 축제 연결) 전후 분리 비교. 튜닝 A 는 새 연도 재확인 대기라 뺌
        def load(v):  # build 를 SEOULEVENT_VARIANT=v 로 만든 비교용 데이터
            vb = add_extended_features(build_features(pd.read_parquet(out / f"district_day_{v}.parquet"), spikes, holidays), spikes)
            return at_horizon(vb, pd.read_parquet(out / f"events_{v}.parquet"))
        # 26단계 결과: 프로야구 효과는 학습 쪽 · 엄격 변형은 가용성 민감도(정밀도 0.49 · 재현율 0.58) — 민감도는 계속 함께 보고
        # 30단계(v5 · 겹침 특징 5개 미채택) 결과는 model-226 30단계. 31단계(원인 수 · 행사 수 특징, 정정별 되돌림) 결과는 model-226 31단계
        # 32단계: KFA 국가대표 경기 원천 추가(v6) — 보정 단계 평균정밀도 0.594 → 0.622 (31 · 32단계 8/8 상승) → 운영 채택
        # 33단계: 보정 점수의 높은 위험 목표 정밀도 고르기 — 0.5 규칙이 실제 정밀도 0.68 · 재현율 0.55 로 보수적. 0.4 · 0.5 · 0.6 비교,
        # 보정 없는 기준선과 엄격(가용성 민감도) + 보정을 함께. 결과: 목표 0.4 = 경보 178 · 적중 112 · 정밀도 0.63 · 재현율 0.63 (기준선 206 · 112 · 0.54 · 0.63) → OPS_CONF_TARGET 0.4
        # 34단계: 마라톤 원천 추가(v7) — 33단계 운영 줄(v6, 실행 결정적)과 비교
        # 34단계 결과: v7 운영 평균정밀도 0.610 · 정밀도 0.56 (v6 0.622 · 0.63) → 마라톤 전부는 미채택, 기본 빌드 v6 로 되돌림
        # 35단계 (심층 분석 다음 개선 1 · 7): v6 운영 줄(목표 0.4 기준값 — 예측표용) · + 기준선 창 행사 수 · 풀코스 마라톤만
        # 35단계 결과: 기준선 창 행사 수 0.614 · 풀코스 마라톤 0.620 (운영 0.622) → 둘 다 미채택 (model-226 35단계)
        # 36단계: 도시 행사 날 '작년' 특징을 직전 같은 행사 날 기준으로 (불꽃축제 날짜 이동 — 심층 분석 4절)
        # 36단계 결과: 0.622 → 0.632 (연도별 2023 −0.010 · 2024 −0.010 · 2025 +0.025 · 2026 +0.016), 2026 불꽃축제 점수 회복 → 운영 채택
        # (add_extended_features 안에서 맞춘다 — feat 이 이미 맞춘 표). 다음 단계 비교는 이 운영 줄 기준
        # 39단계 (ChatGPT 27): 정렬은 7일 전 공개된 도시 행사만 · 정렬 안 한 달력 기준 · 행사 일정 전혀 없이(정렬 전 작년 특징, 보정 없음 — 원인 수도 일정 정보) 하한.
        # 높은 위험 목표 0.4 는 고정 (다시 고르지 않음)
        cal = feat.assign(**{c: feat[c + "_cal"] for c in LY_ALIGNED})
        no_schedule = [c for c in OPS_FEATURES if c not in SCHEDULE_FEATURES]
        # 39단계 결과: 운영 0.640 · 달력 0.622 · 일정 없이 0.342 (model-226 39단계). 이 상태가 model-v1.
        # 40단계 (v2-plan A): 운영 자료 지연 — 승하차는 월 파일이라 실제로 40 ~ 70일 늦다. 평소 규모 · 과거 반응을 56일(8주, 같은 요일) 전 기준으로
        # 40단계 결과: 56일 지연이면 평균정밀도 0.640 → 0.571 · 정밀도 0.66 → 0.56 (4년 모두 하락) → v1 숫자가 운영보다 낙관적.
        # 40b: 일별 API 를 쓴다고 본 14일 지연 · 원인 분해(평소 규모만 56일 지연 / 과거 반응만 56일 지연)
        # 40b 결과: 14일 지연 0.598 · 평소 규모만 56일 0.593 · 과거 반응만 56일 0.607 (운영 0.640) — 기준선이 낡은 게 가장 큰 손실
        # 40c: 56일 지연에서 기준선 추정(작년 같은 날 × 올해 증감률)을 더하거나 바꿔 넣어 손실을 되찾는지
        kw = {"stack": OPS_STACK, "conf_target": OPS_CONF_TARGET}
        # 40c 결과: 56일 지연 0.571 → 추정 추가 0.566 · 추정으로 교체 0.580 (정밀도 0.65 · 재현율 0.54) — 계절 추정은 '행사로 부푼 기준선' 을 못 맞춘다
        # 40d 결과: + 기준선 창 행사 수 0.579 · 추정 + 행사 수 0.567 — 특징으로는 못 되찾는다, 손실 원천은 최신 기준선 값
        # 41단계: 승하차 최근 시트(매일 3일 전, collect_subway) → 실제 운영 지연 = 자료 4일 + 7일 앞 → 같은 요일 기준 14일 (OPS_LAG).
        # 41단계 결과 (2026 은 11/5 까지): 7일 조건 0.598 · 14일 지연 0.582 · 14일 + 기준선 창 행사 수 0.606 · 14일 + 기준선 추정 0.591 → v2 운영 채택
        # v2 운영 = feat(14일 지연) + OPS_FEATURES(기준선 창 행사 수 포함). 비교용으로 v1 조건(7일 · 행사 수 없음)을 함께
        # 42단계: v2 운영 줄 + 음력 공휴일 작년 맞춤 · 그날 전체 문맥 보정(v2-plan B) · 둘 다. 운영 줄은 예측표 기준값용
        # 공휴일 표(holidays_kr)는 날짜마다 이름 하나라 2025-05-05 부처님오신날(어린이날과 겹침)이 빠진다 → 이름을 모두 가진 행사 표의 공휴일 행
        # 42단계 결과: 음력 공휴일 맞춤 0.598 · 그날 문맥 0.601 (정밀도 0.71 · 재현율 0.50) — 둘 다 운영 미채택 (v2 0.606)
        # 43단계: 신호 단위를 역 묶음(station_key)으로 — 같은 v2 모델을 예전 추정 신호(승하차 합계)로도 채점해 차이를 잰다
        # 43단계 결과: 두 신호 단위가 숫자까지 같았다 — 대신 41 ~ 43단계 데이터에서 환승역 좌표가 바뀌어 상권 15곳(서울역 · 시청 · 건대입구 ·
        # 홍대입구 등)이 빠져 있었음을 발견 (0c99771 에서 고침, v1 기간 행 2,831,165 전부 v1 과 같음)
        # 44단계: 고친 데이터로 v2 채택 재검증 — v2 운영 · 14일 지연만 · v1 조건(7일 · 행사 수 없음) · v2 를 예전 추정 신호로
        # 44단계 결과: v2 0.584 · 14일 지연만 0.578 · v1 조건 0.612 · 예전 추정 신호 0.584 (같음) → v2 유지, 숫자 정정
        # 45단계: 버스 승하차(collect_bus, 300m 정류장) 특징을 v2 에 더해 비교
        # 45단계 결과: + 버스 0.575 (재현율 −0.064 유의) → 미채택, 데이터는 유지
        # 46단계: 부분 기준선 — 이미 공개된 7주(14 ~ 56일 전) 중앙값. log_base 를 바꿔 넣기 · 특징으로 더하기
        # 46단계 결과는 model-226 46단계. 47단계: 정답 재정의(build 의 clean_baseline) 채택 → v3. 운영 줄 하나 (예측표 높은 위험 기준값용)
        # 51단계 채택: `--horizon 3` 이면 3일 앞 + 3일 전 예보 날씨 (출력 experiment_rolling_h3*, 단기 예측표 높은 위험 기준값용)
        h = horizon_arg(sys.argv)
        sfx = horizon_suffix(h)
        if h != HORIZON:
            feat = horizon_frame(day, spikes, holidays, events, h, cfg.data_dir / "raw")
        plan = [(feat, {**kw, "features": features_for(h), "label": OPS_LABEL})]
        PROGRESS["n"] = len(plan)
        runs = [rolling(fr, blend=True, **kw) for fr, kw in plan]
        # 27단계: 최근 흐름 특징 · 급증 가중치 끄기는 평균정밀도 0.531 → 0.510 · 0.496 으로 나빠져 미채택 (경보만 줄어 정밀도가 높아 보임)
        # 28단계(예측 시점 단축, exp_gpu32): 평균정밀도 7일 0.514 · 3일 0.506 · 1일 0.503 · 1일 + 실측 날씨(상한) 0.508 → 미채택.
        # 시점에 따라 바뀌는 입력이 과거 반응 기준일 · 평소 규모뿐이라 당겨도 정보가 늘지 않는다 (정밀도 상승은 경보가 줄어서). 막판 원천이 생기면 at_horizon(base, events, h) 로 다시
        # 29단계: 양평역 연결 수정(688cb57, 상권 3곳 추가) 효과 — 28단계 운영 줄(경보 263 · 적중 133 · 평균정밀도 0.514)과 비교
        # attrs 에 표(signals)를 담으면 pandas 가 concat 때 attrs 끼리 비교하다 오류 → 먼저 꺼낸다
        signals = [(x["variant"].iloc[0], x.attrs.pop("signals")) for x in runs]
        r = pd.concat(runs, ignore_index=True)
        r.to_csv(out / f"experiment_rolling{sfx}.csv", index=False, encoding="utf-8-sig")
        print(f"시간 순서 검증 (후보 상권 · 추정 신호 · {h}일 앞, 1차 목표 재현율 ≥ {TARGET_RECALL} 일 때 정밀도 ≥ {TARGET_PRECISION} · 최종 {FINAL_PRECISION}):")
        print(r.round(3).to_string(index=False))
        pd.concat([sg.assign(variant=v) for v, sg in signals], ignore_index=True).to_parquet(out / f"experiment_rolling_signals{sfx}.parquet", index=False)
        unc = pd.concat([precision_uncertainty(sg).assign(variant=v) for v, sg in signals], ignore_index=True)
        unc.to_csv(out / f"experiment_rolling_precision_ci{sfx}.csv", index=False, encoding="utf-8-sig")
        print("\n연도별 높은 위험 정밀도 · 날짜 묶음 부트스트랩 95% 구간 · 1 ~ 9월만:")
        print(unc.round(3).to_string(index=False))
        w = worst_years(r)
        w.to_csv(out / f"experiment_rolling_worst{sfx}.csv", index=False, encoding="utf-8-sig")
        print("\n모델별 가장 나쁜 해 (판정 연도):")
        print(w.round(3).to_string(index=False))
        # 모든 출력을 쓴 뒤 기록 — 출력 해시까지 (ChatGPT 26)
        config.write_manifest(out, f"experiment_rolling_manifest{sfx}.json", {"variants": list(r["variant"].unique())},
                              outputs=(f"experiment_rolling{sfx}.csv", f"experiment_rolling_signals{sfx}.parquet",
                                       f"experiment_rolling_precision_ci{sfx}.csv", f"experiment_rolling_worst{sfx}.csv"))
        log("끝 — 결과 · 기록: experiment_rolling*.csv · _signals.parquet · experiment_rolling_manifest.json")
        return
    share = known_share(events)
    print(f"시험 구간 일정 중 {HORIZON}일 전에 수집돼 있던 비율: {share:.1%}")
    variants = {}  # build 를 SEOULEVENT_VARIANT 로 돌려 만든 비교용 데이터 (한강 행사 구분 방식)
    for v, label in VARIANTS.items():
        if (out / f"district_day_{v}.parquet").exists():
            vb = add_extended_features(build_features(pd.read_parquet(out / f"district_day_{v}.parquet"), spikes, holidays), spikes)
            variants[label] = at_horizon(vb, pd.read_parquet(out / f"events_{v}.parquet"))
    res, cause, curve, topk, years, alerts, daily = run_experiment(feat, share, variants)
    print(f"\n목표(후보 상권 · 추정 신호): 1차 정밀도 ≥ {TARGET_PRECISION} · 재현율 ≥ {TARGET_RECALL} (최종 정밀도 {FINAL_PRECISION})")
    alert_rows = res[res["cand_precision"].notna()] if "cand_precision" in res else res.iloc[0:0]
    print(alert_rows[["variant", "mae_spike", "mae_holiday", "cand_precision", "cand_recall", "cand_avg_precision", "cand_date_recall",
                      "cand_busy_hit", "gap_precision", "gap_recall"]].round(3).to_string(index=False))
    daily.to_csv(out / "experiment_daily_topk.csv", index=False, encoding="utf-8-sig")
    print("\n날짜마다 상위 k 상권 경보 (고정 기준값과 비교):")
    print(daily.round(3).to_string(index=False))
    alerts.to_csv(out / "experiment_alerts.csv", index=False, encoding="utf-8-sig")
    print("\n경보 결과 × 원인 (재현율 목표 기준값, 추정 신호 수):")
    print(alerts.pivot_table(index="cause", columns="outcome", values="signal", aggfunc="nunique", fill_value=0).to_string())
    years.to_csv(out / "experiment_spike_by_year.csv", index=False, encoding="utf-8-sig")
    curve.to_csv(out / "experiment_threshold_curve.csv", index=False, encoding="utf-8-sig")
    res.to_csv(out / "experiment_spike.csv", index=False, encoding="utf-8-sig")
    cause.to_csv(out / "experiment_spike_cause.csv", index=False, encoding="utf-8-sig")
    print(res.round(3).to_string(index=False))
    print("\n급증일 원인별 (마지막 허들 모델, 실제 인원 대비 오차):")
    print(cause.round(2).to_string(index=False))
    print("\n기준값별 급증 탐지 (시험 구간):")
    print(curve.round(3).to_string(index=False))
    print("\n연도별 탐지 (재현율 목표 기준값):")
    print(years.round(3).to_string(index=False))
    print("\n급증 확률 상위 k 건 적중률:", {k: round(v, 3) for k, v in topk.items()})


if __name__ == "__main__":
    main()
