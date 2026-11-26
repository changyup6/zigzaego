"""서울시 문화행사(OA-15486): `python -m weglee_ai.seoulevent.collect_events`

열린데이터광장 시트 CSV 내려받기를 쓴다 (교내망 가능 · 키 불필요, 2021~ 약 2만 건).
수집일별 파일로 쌓는다. 저장: data/raw/cultural_events/<수집일>.parquet
"""

import io
import sys
from collections.abc import Callable
from datetime import date
from pathlib import Path

import pandas as pd

from weglee_ai.seoulevent import config, seoul_file

SERVICE = "culturalEventInfo"
INF_ID = "OA-15486"
# 시트 CSV 의 한글 헤더는 API 필드와 같은 순서다
API_COLUMNS = ["CODENAME", "GUNAME", "TITLE", "DATE", "PLACE", "ORG_NAME", "USE_TRGT", "USE_FEE", "INQUIRY", "PLAYER",
               "PROGRAM", "ETC_DESC", "ORG_LINK", "MAIN_IMG", "RGSTDATE", "TICKET", "STRTDATE", "END_DATE", "THEMECODE",
               "LOT", "LAT", "IS_FREE", "HMPG_ADDR", "PRO_TIME"]
EXPECTED = {0: "분류", 2: "공연/행사명", 16: "시작일", 17: "종료일", 19: "경도(X좌표)", 20: "위도(Y좌표)"}


def run(fetch_all: Callable[..., list[dict]], out_dir: Path, today: date) -> Path:
    df = pd.DataFrame(fetch_all(SERVICE), dtype=str)
    df["collected_at"] = today.isoformat()
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f"{today.isoformat()}.parquet"
    df.to_parquet(path, index=False)
    return path


def load_sheet(path: Path) -> pd.DataFrame:
    raw = path.read_bytes()
    # 대부분 cp949 인데 몇 글자가 cp949 밖이다 (실파일 2만 건 중 4글자) → 그 글자만 대체
    text = raw.decode("utf-8-sig") if raw.startswith(b"\xef\xbb\xbf") else raw.decode("cp949", errors="replace")
    df = pd.read_csv(io.StringIO(text), dtype=str)
    wrong = {i: name for i, name in EXPECTED.items() if df.columns[i] != name}
    if len(df.columns) != len(API_COLUMNS) or wrong:  # 시트 형식이 바뀌면 열을 엇갈려 붙이지 않고 멈춘다
        raise ValueError(f"문화행사 시트 헤더가 예상과 다름: {list(df.columns)}")
    df.columns = API_COLUMNS
    return df


def main() -> int:
    cfg = config.load()
    sheet = seoul_file.download_sheet(INF_ID, cfg.data_dir / "raw" / "cultural_events" / "_sheet.csv", force=True)
    rows = load_sheet(sheet).to_dict("records")
    path = run(lambda s: rows, cfg.data_dir / "raw" / "cultural_events", date.today())
    df = pd.read_parquet(path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
