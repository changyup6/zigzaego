"""공휴일: data.go.kr 특일정보(collect_holidays) 가 있으면 그것, 없으면 holidays 패키지로 오프라인 계산."""

from pathlib import Path

import holidays
import pandas as pd


def load(raw_dir: Path, years: range) -> pd.DataFrame:
    path = raw_dir / "holidays.parquet"
    if path.exists():
        return pd.read_parquet(path).assign(source="datagokr")
    kr = holidays.KR(years=years, language="ko")
    rows = [{"date": d, "name": n, "is_holiday": True, "source": "holidays_lib"} for d, n in sorted(kr.items())]
    return pd.DataFrame(rows, columns=["date", "name", "is_holiday", "source"])
