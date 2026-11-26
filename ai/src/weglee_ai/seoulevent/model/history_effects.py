"""예정 행사를 더 잘 반영하는 '과거 반응' 특징. 모두 예측하는 날 '이전' 기록만 쓴다.

- venue_effect: 그날 예정된 행사의 장소(공연장 · 경기장 · 행사장)에서 과거 행사가 열린 날, 이 상권은 평균 몇 배였나 (기하평균).
  좌석 수 기준선 없이 장소마다 실제 반응을 배운다 (블루스퀘어 1,766석처럼 3천석 아래인 곳도).
- holiday_effect: 이 상권은 과거 공휴일에 평균 몇 배였나 — 한강 · 공원처럼 공휴일에 오히려 붐비는 곳.
- holiday_name_effect: 이 상권은 과거 '같은 이름' 공휴일에 몇 배였나 — 삼일절 · 광복절의 독립문, 어린이날의 어린이대공원처럼
  특정 공휴일에만 붐비는 곳 (holiday_effect 는 모든 공휴일 평균이라 묻힌다).
- citywide_effect: 이 상권은 과거 같은 '도시 행사일'(불꽃축제 · 마라톤 · 근로자의 날, ref/citywide_days.csv)에 몇 배였나.
  근로자의 날(5/1)은 공휴일이 아니라 공휴일 표에 없지만 직장가가 0.45배 이하로 급감한다 (2023~2025 역 14~24곳).
- bloom_day · bloom_effect: 서울 벚꽃 개화일(기상청, ref/bloom_seoul.csv) 기준 며칠째(-4 ~ +10)와, 이 상권의 과거 개화 기간
  주말 · 평일 따로 반응
  — 응봉산 개나리 · 여의도 · 뚝섬처럼 해마다 날짜가 1~2주 움직이는 봄꽃 인파.
- semester_effect: 이 상권은 과거 같은 '학기 몇째 주' 평일(봄 3/2 · 가을 9/1 부터 16주)에 몇 배였나 — 한양대역처럼
  학기 내내 방학 기준선보다 높은 대학가 (개강 4주만 보던 semester_week 로는 남은 급증 167일).
- suneung_week: 수능 다음 1 · 2 · 3번째 주말 (대학 논술 · 면접). 수능일은 공개된 고정 일정.
"""

from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

from weglee_ai.seoulevent import config

from weglee_ai.seoulevent.build.district_day import SHORT_EVENT_DAYS
from weglee_ai.seoulevent.model.features import SEMESTER_STARTS
from weglee_ai.seoulevent.model.train import SPIKE

# 대학수학능력시험일 (교육부 공고)
SUNEUNG = [date(2019, 11, 14), date(2020, 12, 3), date(2021, 11, 18), date(2022, 11, 17), date(2023, 11, 16),
           date(2024, 11, 14), date(2025, 11, 13), date(2026, 11, 19)]
SUNEUNG_WEEKS = 3
HISTORY_COLUMNS = ["venue_effect", "holiday_effect", "holiday_name_effect", "citywide_effect", "bloom_day", "bloom_effect",
                   "semester_effect", "suneung_week", "opponent_effect"]  # opponent_effect: 25단계 채택
TERM_WEEKS = 16
LONG_FESTIVAL_DAYS = 31
HOLIDAY_VARIANTS = ["holiday_name_full", "holiday_spike_rate"]  # 기본 특징에는 없음 — 9단계 실험에서 켜고 끄며 비교
REF = config.REF_DIR
BLOOM_WINDOW = (-4, 10)  # 적중이 -4 ~ +7일에 몰림, +4 ~ +10일 평일 헛경보가 많았다 (5단계 경보 분석)
RECENT = 3  # 과거 반응은 최근 3번 — 행사 규모 · 인기가 해마다 바뀐다
COVID = (pd.Timestamp("2020-02-23"), pd.Timestamp("2022-04-17"))  # 감염병 위기 '심각' ~ 사회적 거리두기 전면 해제 전날


def holiday_group(name: str) -> str:
    """대체 · 임시공휴일과 선거일은 '쉬는 날' 일 뿐 그날 고유 행사가 없어 하나로 묶는다."""
    for prefix in ("대체공휴일", "임시공휴일"):
        if name.startswith(prefix):
            return prefix
    return "선거" if "선거" in name else name


def suneung_weekend(dates: pd.Series) -> pd.Series:
    def week(d: date) -> int:
        if d.weekday() < 5:
            return 0
        for s in SUNEUNG:
            gap = (d - s).days
            if 1 <= gap <= 7 * SUNEUNG_WEEKS:
                return (gap - 1) // 7 + 1
        return 0

    return dates.map(week).astype(int)


def _asof_effect(target: pd.DataFrame, records: pd.DataFrame, by: list[str], horizon: int, recent: int | None = None) -> pd.Series:
    """by 묶음 안에서 target 날짜보다 horizon 일 이상 앞선 records 의 log 배수 평균 → 배수(기하평균). 기록 없으면 NaN.
    recent 를 주면 코로나 거리두기 기간을 빼고 최근 recent 번만 — 이름별 반응용 (전체 평균이면 독립문 삼일절 최근 2.2 · 2.8배가 1.18배로 묻힘).
    모든 공휴일 평균(holiday_effect) · 공연장 반응(venue_effect)은 표본이 많아 전체 평균 (6단계: 최근 3번으로 줄이니 공휴일 0.091 → 0.106 악화)
    horizon=1 은 전날까지(매일 갱신 운영), 28 은 4주 앞을 한 번에 예측하는 운영과 같은 조건 (ChatGPT 재평가 2)."""
    r = records.assign(_t=pd.to_datetime(records["date"]).astype("datetime64[ns]")).sort_values("_t")
    g = r.groupby(by)["logr"]
    if recent is None:
        r["incl"] = g.transform(lambda v: v.fillna(0).cumsum()) / g.transform(lambda v: v.notna().cumsum()).where(lambda n: n > 0)
    else:
        r["logr"] = r["logr"].where(~r["_t"].between(*COVID))
        r["incl"] = r.groupby(by)["logr"].transform(lambda v: v.dropna().rolling(recent, min_periods=1).mean().reindex(v.index).ffill())
    left = target[by].assign(_t=(pd.to_datetime(target["date"]) - pd.Timedelta(days=horizon)).astype("datetime64[ns]")).reset_index().sort_values("_t")
    m = pd.merge_asof(left, r[by + ["_t", "incl"]], on="_t", by=by)
    return np.exp(m.set_index("index")["incl"].reindex(target.index))


def _ref(name: str, columns: list[str]) -> pd.DataFrame:
    path = REF / name
    df = pd.read_csv(path) if path.exists() else pd.DataFrame(columns=columns)
    for c in ("date", "bloom_date"):
        if c in df:
            df[c] = pd.to_datetime(df[c]).dt.date
    return df


def _named_effect(f: pd.DataFrame, pairs: pd.DataFrame, horizon: int, recent: int | None = RECENT, spike: bool = False) -> pd.Series:
    """(date, hname) 이름 붙은 날마다, 이 상권이 과거 같은 이름 날에 몇 배였나 (horizon 일 이상 앞선 기록만, 기하평균).
    한 날짜에 이름이 여럿이면(겹친 공휴일) 그날 반응을 이름마다 이력으로 남기고, 그날 값은 이름별 효과의 기하평균."""
    rec = (f[["trdar_cd", "date", "ratio"]].rename_axis("row").reset_index().merge(pairs.drop_duplicates(), on="date")
           .assign(logr=lambda d: (d["ratio"] >= SPIKE).astype(float).where(d["ratio"].notna()) if spike else np.log(d["ratio"])))
    if rec.empty:
        return pd.Series(np.nan, index=f.index)
    eff = np.log(_asof_effect(rec, rec, ["trdar_cd", "hname"], horizon, recent=recent))
    return np.exp(eff.groupby(rec["row"]).mean()).reindex(f.index)


def term_weeks(dates: pd.Series) -> pd.Series:
    """평일이면 '봄학기 3주' 처럼 학기 몇째 주 이름, 아니면 NaN. 학사일정은 해마다 거의 같은 고정 일정."""
    ts = pd.to_datetime(dates)
    out = pd.Series(np.nan, index=dates.index, dtype=object)
    for (m, d), term in zip(SEMESTER_STARTS, ("봄학기", "가을학기")):
        k = (ts - pd.to_datetime(dict(year=ts.dt.year, month=m, day=d))).dt.days
        on = (k >= 0) & (k < 7 * TERM_WEEKS) & (ts.dt.day_of_week < 5)
        out[on] = term + " " + (k[on] // 7 + 1).astype(str) + "주"
    return out


def add_history_effects(f: pd.DataFrame, events: pd.DataFrame, horizon: int = 1,
                        citywide: pd.DataFrame | None = None, bloom: pd.DataFrame | None = None) -> pd.DataFrame:
    """citywide · bloom 을 안 주면 ref/ 의 표를 읽는다."""
    f = f.copy()
    ratio = f.set_index(["trdar_cd", "date"])["ratio"]

    ev = events[(events["source"] != "holiday") & events["trdar_cd"].notna() & events["place"].notna()]
    ev = ev[[(e - s).days + 1 <= SHORT_EVENT_DAYS for s, e in zip(ev["start_date"], ev["end_date"])]]
    vd = pd.DataFrame([(r.trdar_cd, r.place, d.date()) for r in ev.itertuples(index=False)
                       for d in pd.date_range(r.start_date, r.end_date)], columns=["trdar_cd", "place", "date"]).drop_duplicates()
    if len(vd):
        vd["logr"] = np.log(ratio.reindex(pd.MultiIndex.from_frame(vd[["trdar_cd", "date"]])).to_numpy(dtype=float))
        vd["effect"] = _asof_effect(vd, vd, ["trdar_cd", "place"], horizon)
        f = f.join(vd.groupby(["trdar_cd", "date"])["effect"].max().rename("venue_effect"), on=["trdar_cd", "date"])
    else:
        f["venue_effect"] = np.nan

    # 같은 경기장 · 같은 대진(홈 vs 상대)의 과거 반응 (K리그 · 잠실 프로야구 — 한화 · KIA · 롯데 원정 응원) — 월드컵경기장 전북전 3.4배 vs 강원전 1.9배 (16 검토 4, 예상 관중은 상관 0.01).
    # 최근 3경기만: 전체 평균은 장소 반응 대비 남는 몫 상관 -0.08, 최근 3경기는 0.13 (팀 인기 · 순위가 해마다 바뀜)
    kl = vd.merge(ev.loc[ev["source"].isin(["kleague", "kbo"]), ["trdar_cd", "place", "start_date", "name"]]
                  .rename(columns={"start_date": "date"}), on=["trdar_cd", "place", "date"]) if len(vd) and "name" in ev else pd.DataFrame()
    if len(kl):
        kl["effect"] = _asof_effect(kl, kl, ["trdar_cd", "place", "name"], horizon, recent=3)
        f = f.join(kl.groupby(["trdar_cd", "date"])["effect"].max().rename("opponent_effect"), on=["trdar_cd", "date"])
    else:
        f["opponent_effect"] = np.nan

    # 기간형 축제(8 ~ 31일) 진행 중인 수 — 단기 행사(7일 이하) 특징에서 빠지던 긴 축제. 진행 중 날 급증률 1.4% vs 0.4% (26단계, ChatGPT 19 '기간형 행사' 입력)
    lf = events[events.get("kind", pd.Series("", index=events.index)).astype(str).str.startswith("축제") & events["trdar_cd"].notna()]
    lf = lf[[8 <= (e - s).days + 1 <= LONG_FESTIVAL_DAYS for s, e in zip(lf["start_date"], lf["end_date"])]]
    lfd = pd.DataFrame([(r.trdar_cd, d.date()) for r in lf.itertuples(index=False) for d in pd.date_range(r.start_date, r.end_date)],
                       columns=["trdar_cd", "date"])
    cnt = lfd.groupby(["trdar_cd", "date"]).size().rename("long_festival_count") if len(lfd) else pd.Series(dtype=float, name="long_festival_count")
    f = f.join(cnt, on=["trdar_cd", "date"]) if len(lfd) else f.assign(long_festival_count=np.nan)
    f["long_festival_count"] = f["long_festival_count"].fillna(0)
    # 원천 커버리지: 문화행사 축제 원천이 시작되기 전 날짜는 축제 수가 '0' 이 아니라 '모름' (점검 20 §0 — 2019 학습 행이 '축제 없이 급증' 으로 배워짐)
    fest = events[(events.get("source") == "culture") & events.get("kind", pd.Series("", index=events.index)).astype(str).str.startswith("축제")]
    if len(fest):
        before = pd.to_datetime(f["date"]) < pd.Timestamp(min(fest["start_date"]))
        f.loc[before, [c for c in ("short_festival_count", "long_festival_count") if c in f]] = np.nan

    hol = f.loc[f["is_holiday"] == 1, ["trdar_cd", "date", "ratio"]].assign(logr=lambda d: np.log(d["ratio"]))
    f["holiday_effect"] = _asof_effect(f, hol, ["trdar_cd"], horizon) if len(hol) else np.nan

    hev = events[events["source"] == "holiday"] if "name" in events else pd.DataFrame(columns=["start_date", "name"])
    hpairs = pd.DataFrame({"date": hev["start_date"].to_numpy(), "hname": hev["name"].map(holiday_group).to_numpy()})
    f["holiday_name_effect"] = _named_effect(f, hpairs, horizon)  # 최근 3번 — 급증 분류에 유리 (7단계 평균정밀도 0.41)
    # 9단계 비교용: 전체 기간 평균 — 공휴일 크기에 유리 (8단계 공휴일 0.086), 과거 같은 이름 공휴일에 급증(2배+)했던 비율
    f["holiday_name_full"] = _named_effect(f, hpairs, horizon, recent=None)
    f["holiday_spike_rate"] = np.log(_named_effect(f, hpairs, horizon, recent=None, spike=True))

    city = _ref("citywide_days.csv", ["date", "name"]) if citywide is None else citywide
    f["citywide_effect"] = _named_effect(f, pd.DataFrame({"date": city["date"].to_numpy(), "hname": city["name"].to_numpy()}), horizon)

    bloom = _ref("bloom_seoul.csv", ["year", "bloom_date"]) if bloom is None else bloom
    by_year = dict(zip(bloom["year"].astype(int), pd.to_datetime(bloom["bloom_date"])))
    ts = pd.to_datetime(f["date"])
    day = (ts - pd.to_datetime(ts.dt.year.map(by_year))).dt.days  # 개화일 표가 비어도 NaT 로
    f["bloom_day"] = day.where(day.between(*BLOOM_WINDOW))
    bd = pd.Series(f.loc[f["bloom_day"].notna(), "date"].unique())
    weekend = pd.to_datetime(bd).dt.day_of_week >= 5
    f["bloom_effect"] = _named_effect(f, pd.DataFrame({"date": bd, "hname": np.where(weekend, "벚꽃 주말", "벚꽃 평일")}), horizon)

    days = pd.Series(pd.unique(f["date"]))
    f["semester_effect"] = _named_effect(f, pd.DataFrame({"date": days, "hname": term_weeks(days)}).dropna(), horizon)

    f["suneung_week"] = suneung_weekend(f["date"])
    return f
