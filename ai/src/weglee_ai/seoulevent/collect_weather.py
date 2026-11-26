"""서울 일 날씨 (기상청 ASOS 일자료, data.go.kr): `python -m weglee_ai.seoulevent.collect_weather`"""

import sys
from collections.abc import Callable
from datetime import date, timedelta

import pandas as pd

from weglee_ai.seoulevent import config, datagokr

URL = "https://apis.data.go.kr/1360000/AsosDalyInfoService/getWthrDataList"
SEOUL_STN = 108
FIELDS = {"avgTa": "avg_ta", "minTa": "min_ta", "maxTa": "max_ta", "sumRn": "sum_rn", "avgWs": "avg_ws"}


def fetch(start: date, end: date, get_items: Callable[[str, dict], list[dict]]) -> pd.DataFrame:
    rows = []
    cur = start
    while cur <= end:
        chunk_end = min(date(cur.year, 12, 31), end)  # 1년씩 끊어 호출
        params = {"dataCd": "ASOS", "dateCd": "DAY", "stnIds": SEOUL_STN, "dataType": "JSON", "numOfRows": 999,
                  "startDt": cur.strftime("%Y%m%d"), "endDt": chunk_end.strftime("%Y%m%d")}
        for item in get_items(URL, params):
            row = {"date": date.fromisoformat(item["tm"])}
            for src, dst in FIELDS.items():
                v = item.get(src)
                row[dst] = float(v) if v not in (None, "") else (0.0 if dst == "sum_rn" else None)  # 비 안 온 날은 빈 값
            rows.append(row)
        cur = chunk_end + timedelta(days=1)
    return pd.DataFrame(rows, columns=["date", *FIELDS.values()])


def main() -> int:
    cfg = config.load()
    if not cfg.data_go_kr_key:
        print("DATA_GO_KR_KEY 가 .env 에 없습니다.", file=sys.stderr)
        return 2
    end = date.today() - timedelta(days=1)  # ASOS 는 전날까지
    df = fetch(date(2019, 1, 1), end, lambda url, p: datagokr.get_items(url, p, key=cfg.data_go_kr_key))
    out = cfg.data_dir / "raw" / "weather_seoul.parquet"
    out.parent.mkdir(parents=True, exist_ok=True)
    df.to_parquet(out, index=False)
    return 0


if __name__ == "__main__":
    sys.exit(main())
