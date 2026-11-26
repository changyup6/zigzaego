"""버스 정류장별 일별 승하차 (서울 열린데이터 OA-12912) + 정류장 좌표 (OA-15067): `python -m weglee_ai.seoulevent.collect_bus`

지하철만으로는 버스가 주된 상권의 인파를 못 본다 (v2-plan 다음 개선안 2). 월 파일(2019 ~, 한 달 약 150만 행 · 170MB)은
노선별이라 받자마자 **정류장 · 날짜 합계**로 줄여 달마다 저장하고 원본 CSV 는 지운다 — shortcut: 원본 9GB 를 남기지 않음, 노선별 분석이 필요하면 다시 받는다.
월 파일 뒤 공백은 시트(매일 3일 전까지 최근 약 40일)로 채운다 — 지하철(collect_subway)과 같은 방식, 겹치면 월 파일 우선.
저장: data/raw/bus/stop_day/<YYYYMM | 0_RECENT_YYYYMMDD>.parquet (date, stop_id, board, alight) · data/raw/bus/stops.csv (stop_id, name, lat, lon)
     · data/raw/bus_daily.parquet (전 기간 합본)
"""

import io
import re
import sys
import zipfile
from datetime import date
from pathlib import Path

import pandas as pd

from weglee_ai.seoulevent import config, seoul_file

INF_ID = "OA-12912"
STOPS_INF_ID = "OA-15067"
RECENT_PREFIX = "0_RECENT_"  # 정렬상 월 파일(YYYYMM)보다 앞 → 합칠 때 월 파일이 이긴다
COLUMNS = ["date", "stop_id", "board", "alight"]


SINCE_YEAR = 2018  # 지하철은 2019 ~ — '작년 같은 시기' 버스 특징을 위해 한 해 앞부터


def pick_files(files: dict[str, int], since_year: int = SINCE_YEAR) -> dict[str, int]:
    """2023 ~ 은 달마다 CSV(YYYYMM.csv), 2022 까지는 한 해 ZIP(YYYY.zip, 안에 달마다 CSV)."""
    out = {}
    for title, seq in files.items():
        m = re.fullmatch(r"BUS_STATION_BOARDING_MONTH_(\d{4})(\d{2})?\.(csv|zip)", title)
        if m and int(m.group(1)) >= since_year:
            out[title] = seq
    return out


def months_of(title: str) -> list[str]:
    """파일이 담는 달(YYYYMM) — 이미 받은 달은 건너뛰기용."""
    m = re.fullmatch(r"BUS_STATION_BOARDING_MONTH_(\d{4})(\d{2})?\.(csv|zip)", title)
    return [m.group(1) + m.group(2)] if m.group(2) else [f"{m.group(1)}{k:02d}" for k in range(1, 13)]


def save_months(g: pd.DataFrame, days_dir: Path) -> list[str]:
    """정류장 · 날짜 합계를 달마다 파일로 (ZIP 하나가 여러 달)."""
    ym = pd.to_datetime(g["date"]).dt.strftime("%Y%m")
    for k, part in g.groupby(ym):
        part.to_parquet(days_dir / f"{k}.parquet", index=False)
    return sorted(ym.unique())


def _decode(raw: bytes) -> str:
    """해마다 utf-8(BOM 있음 · 없음) 이거나 cp949."""
    try:
        return raw.decode("utf-8-sig")
    except UnicodeDecodeError:
        return raw.decode("cp949")


def _col(cols: list[str], *keys: str) -> str:
    """연도마다 열 이름이 달라 키워드로 찾는다 (예: '표준버스정류장ID' · '버스정류장ARS번호'). 키워드 묶음을 '|' 로 이으면 그중 하나
    — 일부 해는 영어 열 이름(use_dt · stnd_bsst_id · ride_pasgr_num · alight_pasgr_num · bsst_ars_no)."""
    for alt in "|".join(keys).split("|") if any("|" in k for k in keys) else [None]:
        want = [alt] if alt is not None else list(keys)
        for c in cols:
            if all(k.lower() in c.lower() for k in want):
                return c
    raise KeyError(f"열을 못 찾음: {keys} in {cols}")


def aggregate(raw: bytes, ars_map: dict[str, str] | None = None) -> pd.DataFrame:
    """노선별 CSV → 정류장 · 날짜 합계. 2022 까지의 파일은 표준정류장ID 없이 ARS 번호만 있어 ars_map(ARS → 표준ID)으로 바꾼다
    (지금 시트에서 만든 짝 — 그 사이 없어진 정류장은 빠진다)."""
    df = pd.read_csv(io.StringIO(_decode(raw)), dtype=str, low_memory=False)
    # 열 이름은 믿지 않고 위치로 읽는다 — 2020-05 ~ 07 은 영어 머리글(stnd_bsst_id, use_dt, …)이 실제 자료 순서와 다르다.
    # 자료 순서는 두 가지뿐: 9열 = 사용일자 · 노선번호 · 노선명 · 표준정류장ID · ARS · 역명 · 승차 · 하차 · 등록일자 (2023 ~ · 2020-05 ~ 07)
    #                        8열 = 사용일자 · 노선번호 · 노선명 · ARS · 역명 · 승차 · 하차 · 등록일자 (2022 까지 대부분)
    if df.shape[1] == 9:
        d, b, a = df.iloc[:, 0], df.iloc[:, 6], df.iloc[:, 7]
        sid = df.iloc[:, 3].str.strip()
    elif df.shape[1] == 8:
        d, b, a = df.iloc[:, 0], df.iloc[:, 5], df.iloc[:, 6]
        sid = df.iloc[:, 3].str.strip().str.zfill(5).map(ars_map or {})
    else:
        raise ValueError(f"모르는 열 구성 ({df.shape[1]}열): {list(df.columns)}")
    out = pd.DataFrame({"date": pd.to_datetime(d, format="%Y%m%d").dt.date, "stop_id": sid,
                        "board": pd.to_numeric(b, errors="coerce").fillna(0), "alight": pd.to_numeric(a, errors="coerce").fillna(0)})
    out = out[out["stop_id"].notna() & (out["stop_id"] != "")]
    g = out.groupby(["date", "stop_id"], as_index=False)[["board", "alight"]].sum()
    g[["board", "alight"]] = g[["board", "alight"]].round().astype("int64")
    return g[COLUMNS]


def merge_days(stop_day_dir: Path) -> pd.DataFrame:
    """달마다 · 최근 시트 파일을 합친다. 이름순(최근 시트가 앞)으로 이어 붙여 겹치는 날은 마지막(월 파일)을 남긴다."""
    parts = [pd.read_parquet(p) for p in sorted(stop_day_dir.glob("*.parquet"))]
    if not parts:
        return pd.DataFrame(columns=COLUMNS)
    return pd.concat(parts, ignore_index=True).drop_duplicates(["date", "stop_id"], keep="last").reset_index(drop=True)


def stops_from_sheet(raw: bytes) -> pd.DataFrame:
    """정류소 위치 시트(노드 ID · 정류소번호 · 정류소명 · X좌표 · Y좌표 · 정류소 타입) → stop_id · name · lat · lon."""
    df = pd.read_csv(io.StringIO(_decode(raw)), dtype=str)
    out = pd.DataFrame({"stop_id": df["정류소번호"].str.strip(), "name": df["정류소명"],
                        "lon": pd.to_numeric(df["X좌표"], errors="coerce"), "lat": pd.to_numeric(df["Y좌표"], errors="coerce")})
    return out.dropna(subset=["lat", "lon"]).drop_duplicates("stop_id").reset_index(drop=True)


def main(argv: list[str] | None = None) -> int:
    cfg = config.load()
    root = cfg.data_dir / "raw" / "bus"
    days_dir, tmp = root / "stop_day", root / "_tmp"
    days_dir.mkdir(parents=True, exist_ok=True)
    tmp.mkdir(parents=True, exist_ok=True)

    stops_csv = seoul_file.download_sheet(STOPS_INF_ID, tmp / "stops_sheet.csv", force=True)
    stops = stops_from_sheet(stops_csv.read_bytes())
    stops.to_csv(root / "stops.csv", index=False)

    # 최근 시트를 먼저 받아 ARS → 표준정류장ID 짝을 만든다 (2022 까지 파일용)
    sheet = seoul_file.download_sheet(INF_ID, tmp / "recent_sheet.csv", force=True)
    sh = pd.read_csv(io.StringIO(_decode(sheet.read_bytes())), dtype=str, low_memory=False)
    ars_map = dict(zip(sh[_col(list(sh.columns), "ARS")].str.strip().str.zfill(5), sh[_col(list(sh.columns), "표준", "ID")].str.strip()))
    pd.Series(ars_map, name="stop_id").rename_axis("ars").to_csv(root / "ars_map.csv")

    inf_seq, files = seoul_file.list_files(INF_ID)
    picked = pick_files(files)
    todo = {t: s for t, s in picked.items() if not all((days_dir / f"{m}.parquet").exists() for m in months_of(t))}
    monthly = [t for t in picked if t.endswith(".csv")]
    if monthly:  # 마지막 달 파일은 다시 받는다 (그 달 도중 공개분이면 덜 찼을 수 있음)
        todo.setdefault(max(monthly), picked[max(monthly)])
    for i, (title, seq) in enumerate(sorted(todo.items()), 1):
        p = seoul_file.download(INF_ID, seq, inf_seq, tmp / title, force=True)
        if title.endswith(".zip"):  # 한 해 ZIP — 안의 달마다 CSV 를 하나씩
            with zipfile.ZipFile(p) as z:
                done = [m for name in z.namelist() if name.lower().endswith(".csv") for m in save_months(aggregate(z.read(name), ars_map), days_dir)]
        else:
            done = save_months(aggregate(p.read_bytes(), ars_map), days_dir)
        p.unlink()  # 원본 노선별 파일은 지운다 (위 shortcut)

    aggregate(sheet.read_bytes()).to_parquet(days_dir / f"{RECENT_PREFIX}{date.today():%Y%m%d}.parquet", index=False)  # 위에서 받은 최근 시트
    sheet.unlink()

    daily = merge_days(days_dir)
    out = cfg.data_dir / "raw" / "bus_daily.parquet"
    daily.to_parquet(out, index=False)
    covered = daily["stop_id"].isin(set(stops["stop_id"]))
    total = max(float(daily[["board", "alight"]].sum().sum()), 1.0)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
