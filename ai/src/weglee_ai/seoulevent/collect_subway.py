"""지하철 역별 일별 승하차(OA-12914): `python -m weglee_ai.seoulevent.collect_subway [--stations]`

승하차는 파일 다운로드(교내망 가능). 역 좌표는 저장소의 ref/stations.csv 를 쓰고,
`--stations` 로 서울 역사마스터(OA-21232) 시트를 CSV 로 내려받아 만든다 (교내망 가능 · 키 불필요).
"""

import csv
import io
import re
import sys
from datetime import date
from pathlib import Path

import pandas as pd

from weglee_ai.seoulevent import config, seoul_file

INF_ID = "OA-12914"
STATION_INF_ID = "OA-21232"
REF_DIR = config.REF_DIR
COLUMNS = ["date", "line", "station", "board", "alight"]
RECENT_PREFIX = "0_RECENT_"  # 최근 시트 파일 — 정렬상 월 파일(CARD_SUBWAY_MONTH_*)보다 앞


def pick_files(files: dict[str, int], since_year: int) -> dict[str, int]:
    out = {}
    for title, seq in files.items():
        m = re.fullmatch(r"CARD_SUBWAY_MONTH_(\d{4})(\d{2})?\.csv", title)
        if m and int(m.group(1)) >= since_year:
            out[title] = seq
    return out


def _decode(raw: bytes) -> str:
    """서울 열린데이터 CSV 는 파일마다 utf-8(BOM) 이거나 cp949."""
    return raw.decode("utf-8-sig") if raw.startswith(b"\xef\xbb\xbf") else raw.decode("cp949")


def read_csv_file(path: Path) -> pd.DataFrame:
    rows = list(csv.reader(io.StringIO(_decode(path.read_bytes()))))[1:]
    # 연도마다 끝에 빈 열이 붙기도 하고 숫자가 '3862.0' 으로 오기도 한다 → 앞 5열만, 실수로 읽어 정수화
    df = pd.DataFrame([r[:5] for r in rows if len(r) >= 5], columns=COLUMNS)
    df["date"] = pd.to_datetime(df["date"], format="%Y%m%d").dt.date
    for col in ("board", "alight"):
        df[col] = pd.to_numeric(df[col]).round().astype("int64")
    return df


def load(csv_dir: Path) -> pd.DataFrame:
    df = pd.concat([read_csv_file(p) for p in sorted(csv_dir.glob("*.csv"))], ignore_index=True)
    return df.drop_duplicates(["date", "line", "station"], keep="last").reset_index(drop=True)


def normalize_station(name: str, alias: dict[str, str] | None = None) -> str:
    n = re.sub(r"\(.*?\)", "", name).strip()
    if len(n) > 1 and n.endswith("역"):
        n = n[:-1]
    return (alias or {}).get(n, n)


def load_alias(path: Path = REF_DIR / "station_alias.csv") -> dict[str, str]:
    """raw 는 normalize_station 을 거친 형태(괄호 · 끝의 '역' 제거)로 적는다."""
    if not path.exists():
        return {}
    return dict(pd.read_csv(path, dtype=str)[["raw", "canonical"]].itertuples(index=False))


def stations_from_sheet(path: Path) -> pd.DataFrame:
    """역사마스터 시트 CSV(역사_ID · 역사명 · 호선 · 위도 · 경도) → station · line · lat · lon. 좌표 없는 행은 버린다."""
    df = pd.read_csv(io.StringIO(_decode(path.read_bytes())), dtype=str).dropna(subset=["위도", "경도"])
    return pd.DataFrame({
        "station": df["역사명"].map(normalize_station),
        "line": df["호선"],
        "lat": df["위도"].astype(float),
        "lon": df["경도"].astype(float),
    }).reset_index(drop=True)


SAME_PLACE_M = 1000  # 같은 이름의 역 좌표가 이보다 멀면 다른 역 (5호선 양평 vs 중앙선 양평)


def attach_coords(daily: pd.DataFrame, stations: pd.DataFrame, alias: dict[str, str] | None = None) -> tuple[pd.DataFrame, list[str]]:
    """역명이 하나의 자리(환승역 포함)면 이름으로, 같은 이름이 먼 곳에 여럿이면 (역명, 노선)으로만 맞춘다."""
    st = stations.assign(station=stations["station"].map(lambda s: normalize_station(s, alias)))
    spread = st.groupby("station").agg(dlat=("lat", lambda v: v.max() - v.min()), dlon=("lon", lambda v: v.max() - v.min()))
    one_place = spread.index[((spread["dlat"] * 111_000) ** 2 + (spread["dlon"] * 88_000) ** 2) ** 0.5 <= SAME_PLACE_M]
    by_name = st[st["station"].isin(one_place)].groupby("station")[["lat", "lon"]].mean()
    st["line"] = st["line"].str.replace(" ", "")  # '공항철도 1호선' / '공항철도1호선'
    by_name_line = st.groupby(["station", "line"])[["lat", "lon"]].mean()

    key = daily["station"].map(lambda s: normalize_station(s, alias))
    exact = pd.MultiIndex.from_arrays([key, daily["line"].str.replace(" ", "")])
    out = daily.assign(
        lat=by_name_line["lat"].reindex(exact).to_numpy(),
        lon=by_name_line["lon"].reindex(exact).to_numpy(),
    )
    out["lat"] = out["lat"].fillna(key.map(by_name["lat"]))
    out["lon"] = out["lon"].fillna(key.map(by_name["lon"]))
    unmatched = sorted(out.loc[out["lat"].isna(), "station"].unique())
    return out, unmatched


def main(argv: list[str] | None = None) -> int:
    argv = argv or []
    cfg = config.load()
    if "--stations" in argv:
        sheet = seoul_file.download_sheet(STATION_INF_ID, cfg.data_dir / "raw" / "subway" / "stations_sheet.csv",
                                          order_by="BLDN_ID DESC", force=True)
        st = stations_from_sheet(sheet)
        REF_DIR.mkdir(exist_ok=True)
        st.to_csv(REF_DIR / "stations.csv", index=False)
        return 0

    inf_seq, files = seoul_file.list_files(INF_ID)
    csv_dir = cfg.data_dir / "raw" / "subway" / "csv"
    for title, seq in pick_files(files, since_year=2019).items():
        seoul_file.download(INF_ID, seq, inf_seq, csv_dir / title)
    # 월 파일은 40 ~ 70일 늦다 (40단계: 56일 지연이면 평균정밀도 0.640 → 0.571). 같은 데이터셋의 시트는 '매일 3일 전' 까지 최근 약 40일
    # (11/9 받은 시트 = 9/28 ~ 11/5). 받을 때마다 날짜를 붙여 남기고, 이름이 월 파일보다 앞에 정렬돼 겹치는 날은 확정된 월 파일이 이긴다 (load 의 keep="last")
    seoul_file.download_sheet(INF_ID, csv_dir / f"{RECENT_PREFIX}{date.today():%Y%m%d}.csv", force=True)
    daily = load(csv_dir)
    out = cfg.data_dir / "raw" / "subway_daily.parquet"
    daily.to_parquet(out, index=False)
    stations_csv = REF_DIR / "stations.csv"
    if stations_csv.exists():
        _, unmatched = attach_coords(daily, pd.read_csv(stations_csv), load_alias())
    else:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
