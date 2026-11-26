"""82장소(실시간 도시데이터 영역 다각형) ↔ 상권 · 행사 · 일 매출 연결.

- map_districts: 상권 중심점이 어느 장소 영역 안에 있나
- place_events: 행사 좌표가 어느 장소 영역 안에 있나
- build_place_day: 장소 × 날짜 일 결제 + 같은 요일 기준선 + 행사 · 공휴일 · 날씨
"""

import zipfile
from pathlib import Path

import numpy as np
import pandas as pd

from weglee_ai.seoulevent.build import geo
from weglee_ai.seoulevent.build.district_day import FESTIVAL_PREFIX, SHORT_EVENT_DAYS
from weglee_ai.seoulevent.collect_district import _read_dbf

MIN_BASE_DAYS = 2  # 같은 요일 다른 날이 이보다 적으면 기준선 없음

PLACE_DAY_COLUMNS = ["area_cd", "area_nm", "date", "amt", "cnt", "amt_base", "sales_ratio", "is_holiday", "avg_ta", "sum_rn",
                     "events", "event_count", "festival_count"]


def load_place_areas(zip_path: Path) -> pd.DataFrame:
    with zipfile.ZipFile(zip_path) as z:
        names = {i.filename.rsplit(".", 1)[-1].lower(): i for i in z.infolist()}
        encoding = z.read(names["cpg"]).decode().strip() if "cpg" in names else "cp949"
        attrs = _read_dbf(z.read(names["dbf"]), encoding)
        polys = geo.read_polygons(z.read(names["shp"]))
    if len(attrs) != len(polys):
        raise ValueError(f"DBF {len(attrs)}행 · SHP {len(polys)}개 수가 다름")
    return pd.DataFrame({"area_cd": attrs["AREA_CD"], "area_nm": attrs["AREA_NM"], "category": attrs["CATEGORY"], "parts": polys})


def _bbox(parts) -> tuple[float, float, float, float]:
    pts = np.array([p for ring in parts for p in ring])
    return pts[:, 0].min(), pts[:, 1].min(), pts[:, 0].max(), pts[:, 1].max()


def _inside(places: pd.DataFrame, lon: np.ndarray, lat: np.ndarray) -> list[tuple[str, int]]:
    """(area_cd, 점 번호) 쌍. 장소마다 외곽 사각형으로 먼저 걸러 다각형 판정 횟수를 줄인다."""
    hits = []
    for r in places.itertuples(index=False):
        x0, y0, x1, y1 = _bbox(r.parts)
        for i in np.nonzero((lon >= x0) & (lon <= x1) & (lat >= y0) & (lat <= y1))[0]:
            if geo.point_in_polygon(lon[i], lat[i], r.parts):
                hits.append((r.area_cd, int(i)))
    return hits


def map_districts(places: pd.DataFrame, areas: pd.DataFrame) -> pd.DataFrame:
    latlon = np.array([geo.to_4326(x, y) for x, y in zip(areas["x"], areas["y"])]).reshape(-1, 2)
    hits = _inside(places, latlon[:, 1], latlon[:, 0])
    return pd.DataFrame([{"area_cd": cd, "trdar_cd": areas["TRDAR_CD"].iloc[i]} for cd, i in hits], columns=["area_cd", "trdar_cd"])


def place_events(places: pd.DataFrame, events: pd.DataFrame) -> pd.DataFrame:
    ev = events[(events["source"] != "holiday") & events["lat"].notna()].reset_index(drop=True)
    hits = _inside(places, ev["lon"].to_numpy(dtype=float), ev["lat"].to_numpy(dtype=float))
    idx = pd.DataFrame(hits, columns=["area_cd", "i"])
    return idx.join(ev, on="i").drop(columns="i")[["area_cd", "event_id", "kind", "name", "start_date", "end_date"]]


def _leave_one_out_median(g: pd.Series) -> pd.Series:
    v = g.to_numpy(dtype=float)
    return pd.Series([np.median(np.delete(v, k)) if len(v) - 1 >= MIN_BASE_DAYS else np.nan for k in range(len(v))], index=g.index)


def build_place_day(rtd: pd.DataFrame, place_ev: pd.DataFrame, holidays: pd.DataFrame, weather: pd.DataFrame) -> pd.DataFrame:
    """기준선 = 같은 장소 · 같은 요일의 다른 날 중앙값(자기 자신 제외).
    shortcut: 쌓인 기간이 28일 남짓이라 '과거만' 보는 기준선을 못 만든다 → 서술 분석용. 8주 이상 쌓이면 district_day 처럼 과거 8주로 바꾼다."""
    d = rtd.copy()
    d["wd"] = pd.to_datetime(d["date"]).dt.day_of_week
    d["amt_base"] = d.groupby(["area_cd", "wd"])["amt"].transform(_leave_one_out_median)
    d["sales_ratio"] = d["amt"] / d["amt_base"]
    hol = set(holidays.loc[holidays["is_holiday"].astype(bool), "date"])
    d["is_holiday"] = d["date"].isin(hol)
    d = d.merge(weather[["date", "avg_ta", "sum_rn"]], on="date", how="left")

    rows = [(r.area_cd, day.date(), r.name, (r.end_date - r.start_date).days + 1 <= SHORT_EVENT_DAYS and str(r.kind).startswith(FESTIVAL_PREFIX))
            for r in place_ev.itertuples(index=False) for day in pd.date_range(r.start_date, r.end_date)]
    ev = pd.DataFrame(rows, columns=["area_cd", "date", "name", "festival"])
    ev = ev.groupby(["area_cd", "date"]).agg(events=("name", list), festival_count=("festival", "sum"))
    d = d.join(ev, on=["area_cd", "date"])
    d["events"] = d["events"].map(lambda v: v if isinstance(v, list) else [])
    d["event_count"] = d["events"].map(len).astype("int64")
    d["festival_count"] = d["festival_count"].fillna(0).astype("int64")
    return d[PLACE_DAY_COLUMNS].sort_values(["area_cd", "date"]).reset_index(drop=True)
