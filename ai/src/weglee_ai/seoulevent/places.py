"""실시간 상권현황 82장소 목록 (OA-22385 첨부 xlsx)."""

import warnings
from pathlib import Path

import pandas as pd

from weglee_ai.seoulevent import seoul_file

INF_ID, SEQ, INF_SEQ = "OA-22385", 8, 2


def ensure(data_dir: Path) -> Path:
    return seoul_file.download(INF_ID, SEQ, INF_SEQ, data_dir / "ref" / "places_82.xlsx")


def load(path: Path) -> list[tuple[str, str]]:
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", UserWarning)  # openpyxl 의 'Unknown extension' 경고
        df = pd.read_excel(path, dtype=str)
    return list(zip(df["AREA_CD"].str.strip(), df["AREA_NM"].str.strip()))
