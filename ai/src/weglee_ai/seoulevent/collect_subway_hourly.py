"""서울교통공사 역별 일별 시간대별 승하차(OA-12921): `python -m weglee_ai.seoulevent.collect_subway_hourly`

날짜 × 역 × 시간대(06시 이전 · 06 ~ 23시 한 시간씩 · 24시 이후). 1 ~ 8호선 중심(서울교통공사 관할), 해마다 한 번 · 약 10개월 늦게 공개 →
운영 예측 입력이 아니라 정답 분석 · 1년 이상 지난 과거 반응 · 무정차 통과 사후 확인용 (2025-09-27 여의나루 19 ~ 21시 하차 ≈ 0).
연도마다 열 이름이 다르다 (날짜 / 수송일자, '06시-07시' / '06-07시간대' / '06:00 ~ 07:00', 2019 은 xlsx) → 열 이름의 숫자로 맞춘다.
"""

import io
import re
import sys
from pathlib import Path

import pandas as pd

from weglee_ai.seoulevent import config, seoul_file
from weglee_ai.seoulevent.collect_subway import _decode

INF_ID = "OA-12921"
SINCE_SEQ = 36  # 2019 (xlsx) 부터 — 학습은 2019 ~ (코로나 기간 제외)
HOURS = ["h_early"] + [f"h{h:02d}" for h in range(6, 24)] + ["h_late"]
KEYS = ["date", "line", "station_no", "station", "kind"]


def hour_col(name: str) -> str | None:
    """'06시-07시' · '06-07시간대' · '06:00 ~ 07:00' → h06, '06시 이전' → h_early, '24시 이후' → h_late.
    2021 파일의 '23시 이후' 는 23시 이후 전부라 h23 에 넣는다 (그해 h_late 는 빈 값)."""
    n = re.findall(r"\d+", name)
    if not n or "합" in name:
        return None
    if "이전" in name:
        return "h_early"
    if "이후" in name:
        return "h_late" if int(n[0]) >= 24 else f"h{int(n[0]):02d}"
    return f"h{int(n[0]):02d}" if 6 <= int(n[0]) <= 23 else None


def normalize(raw: pd.DataFrame) -> pd.DataFrame:
    cols = {}
    for c in raw.columns:
        c = str(c).strip()
        if c in ("날짜", "수송일자"):
            cols[c] = "date"
        elif c == "호선":
            cols[c] = "line"
        elif "역번호" in c:
            cols[c] = "station_no"
        elif c == "역명":
            cols[c] = "station"
        elif c in ("구분", "승하차구분"):
            cols[c] = "kind"
        elif h := hour_col(c):
            cols[c] = h
    df = raw.rename(columns=lambda c: str(c).strip()).rename(columns=cols)[list(cols.values())]
    df = df[df["kind"].isin(["승차", "하차"])].copy()
    df["date"] = pd.to_datetime(df["date"]).dt.date
    df["line"] = df["line"].astype(str).str.replace(r"^(\d+)$", r"\1호선", regex=True)
    df["station_no"] = df["station_no"].astype(str).str.replace(r"\.0$", "", regex=True)
    df["kind"] = df["kind"].map({"승차": "board", "하차": "alight"})
    for h in HOURS:
        df[h] = pd.to_numeric(df[h].astype(str).str.replace(",", ""), errors="coerce") if h in df else float("nan")
    return df[KEYS + HOURS]


def read_file(path: Path) -> pd.DataFrame:
    if path.suffix == ".xlsx":
        raw = pd.read_excel(path, header=None)
        head = raw.index[raw.astype(str).apply(lambda r: r.str.strip().eq("역명").any(), axis=1)][0]  # 맨 위 '(단위:명)' 줄을 건너뛴다
        raw = raw.iloc[head + 1:].set_axis(raw.iloc[head].astype(str), axis=1)
    else:
        raw = pd.read_csv(io.StringIO(_decode(path.read_bytes())), dtype=str)
    return normalize(raw)


def load(src_dir: Path) -> pd.DataFrame:
    """seq 순서로 읽어 겹치는 날(2023 파일 여럿 · 23.11 ~ 24.01)은 뒤 파일이 이긴다."""
    files = sorted(src_dir.glob("seq*.*"), key=lambda p: int(re.search(r"\d+", p.stem)[0]))
    df = pd.concat([read_file(p) for p in files], ignore_index=True)
    return df.drop_duplicates(["date", "line", "station_no", "kind"], keep="last").sort_values(KEYS).reset_index(drop=True)


def main(argv: list[str] | None = None) -> int:
    cfg = config.load()
    src = cfg.data_dir / "raw" / "subway_hourly"
    inf_seq, files = seoul_file.list_files(INF_ID)
    for title, seq in files.items():
        if seq >= SINCE_SEQ:
            seoul_file.download(INF_ID, seq, inf_seq, src / f"seq{seq}.{title.rsplit('.', 1)[-1]}")
    df = load(src)
    out = cfg.data_dir / "raw" / "subway_hourly.parquet"
    df.to_parquet(out, index=False)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
