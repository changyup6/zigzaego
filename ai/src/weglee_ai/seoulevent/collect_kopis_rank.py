"""KOPIS 서울 예매 순위(주간 상위 50): `python -m weglee_ai.seoulevent.collect_kopis_rank`

공연 규모를 좌석 수로만 보면 매진 공연과 빈 공연이 같아진다 → 공연 시작 전 몇 주 동안 서울 예매 순위에 들었는지를 '인기' 로 쓴다.
KOPIS boxoffice 는 기간(stdate ~ eddate)의 예매 상위 공연을 준다. 한 주(월 ~ 일)씩 받고, 지난 주는 바뀌지 않아 이어 받는다.
저장: data/raw/kopis/boxoffice.parquet (week_end · mt20id · rnum)
"""

import re
import sys
import time
from datetime import date, timedelta

import httpx
import pandas as pd

from weglee_ai.seoulevent import config
from weglee_ai.seoulevent.collect_kopis import BASE, SEOUL, KopisError

FIRST_WEEK = date(2019, 1, 7)  # 월요일
RANKS = 50
LEAD = (7, 56)  # 공연 시작 7 ~ 56일 전 주간 순위만 — 7일 앞 예측 때 이미 알 수 있는 값
_ROW = re.compile(r"<boxof>.*?<rnum>(\d+)</rnum>.*?<mt20id>(\w+)</mt20id>.*?</boxof>", re.S)


def parse_boxoffice(xml: str) -> list[dict]:
    return [{"rnum": int(r), "mt20id": m} for r, m in _ROW.findall(xml)]


def pre_show_popularity(perf: pd.DataFrame, box: pd.DataFrame) -> pd.Series:
    """공연(mt20id · start_date)마다 시작 LEAD 일 전 사이 주간 순위 중 가장 높은 것 → 인기 = 51 - 순위 (순위 밖이면 0)."""
    if box.empty or perf.empty:
        return pd.Series(0.0, index=perf.index)
    m = perf[["mt20id", "start_date"]].reset_index().merge(box, on="mt20id")
    lag = (pd.to_datetime(m["start_date"]) - pd.to_datetime(m["week_end"])).dt.days
    best = m[lag.between(*LEAD)].groupby("index")["rnum"].min()
    return (RANKS + 1 - best).reindex(perf.index).fillna(0.0)


def _save(path, old: pd.DataFrame, rows: list[dict]) -> pd.DataFrame:
    box = pd.concat([old, pd.DataFrame(rows, columns=["week_end", "mt20id", "rnum"])], ignore_index=True)
    box.to_parquet(path, index=False)
    return box


def main() -> int:
    cfg = config.load()
    if not cfg.kopis_key:
        print("KOPIS_API_KEY 가 .env 에 없습니다.", file=sys.stderr)
        return 2
    path = cfg.data_dir / "raw" / "kopis" / "boxoffice.parquet"
    old = pd.read_parquet(path) if path.exists() else pd.DataFrame(columns=["week_end", "mt20id", "rnum"])
    done = set(pd.to_datetime(old["week_end"]).dt.date)
    client = httpx.Client(timeout=60)
    rows = []
    week = FIRST_WEEK
    last = date.today() - timedelta(days=date.today().weekday() + 1)  # 지난 일요일까지 (끝난 주만)
    while week + timedelta(days=6) <= last:
        end = week + timedelta(days=6)
        if end not in done:
            for attempt in range(5):  # 가끔 오류 화면이 온다 (collect_kopis 와 같은 재시도)
                time.sleep(0.2 + attempt * 2)
                try:
                    text = client.get(f"{BASE}/boxoffice", params={"service": cfg.kopis_key, "stdate": f"{week:%Y%m%d}",
                                                                    "eddate": f"{end:%Y%m%d}", "area": SEOUL}).text
                except httpx.TransportError:
                    continue
                if "<boxofs" in text:
                    break
            else:
                _save(path, old, rows)  # 받은 데까지 남기고 멈춘다 — 다시 돌리면 이어 받는다
                raise KopisError(f"{week} 주 5회 시도해도 정상 응답 없음")
            rows += [r | {"week_end": end} for r in parse_boxoffice(text)]
        week += timedelta(days=7)
    box = _save(path, old, rows)
    return 0


if __name__ == "__main__":
    sys.exit(main())
