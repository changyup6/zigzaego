"""공휴일 (한국천문연구원 특일정보, data.go.kr): `python -m weglee_ai.seoulevent.collect_holidays`"""

import sys
from collections.abc import Callable
from datetime import date, datetime
from pathlib import Path

import pandas as pd

from weglee_ai.seoulevent import config, datagokr

URL = "https://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService/getRestDeInfo"
FIRST_YEAR = 2019


def run(years: list[int], get_items: Callable[[str, dict], list[dict]], out_dir: Path) -> Path:
    rows = []
    for year in years:
        for item in get_items(URL, {"solYear": year, "numOfRows": 100, "_type": "json"}):
            rows.append({
                "date": datetime.strptime(str(item["locdate"]), "%Y%m%d").date(),
                "name": item["dateName"],
                "is_holiday": item["isHoliday"] == "Y",
            })
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / "holidays.parquet"
    pd.DataFrame(rows, columns=["date", "name", "is_holiday"]).to_parquet(path, index=False)
    return path


def main() -> int:
    cfg = config.load()
    if not cfg.data_go_kr_key:
        print("DATA_GO_KR_KEY 가 .env 에 없습니다.", file=sys.stderr)
        return 2
    years = list(range(FIRST_YEAR, date.today().year + 2))
    path = run(years, lambda url, p: datagokr.get_items(url, p, key=cfg.data_go_kr_key), cfg.data_dir / "raw")
    return 0


if __name__ == "__main__":
    sys.exit(main())
