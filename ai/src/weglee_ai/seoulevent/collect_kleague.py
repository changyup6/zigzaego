"""K리그 서울 홈경기 일정 · 관중: `python -m weglee_ai.seoulevent.collect_kleague`

K리그 공식 사이트(www.kleague.com, robots.txt 없음)의 일정 조회 주소에서 월별로 받는다. 미래 경기도 나온다(관중 0 → 빈 값).
월드컵경기장 상권 급증일이 급증일 오차의 25% 를 차지해 넣는다.
저장: data/raw/kleague/games.parquet (서울 경기장만, expected_audience = 같은 홈팀 이전 경기 평균 관중)
"""

import sys
import time
from datetime import date, datetime

import httpx
import pandas as pd

from weglee_ai.seoulevent import config

URL = "https://www.kleague.com/getScheduleList.do"
LEAGUES = (1, 2)
FIRST_YEAR = 2019
# 서울 경기장: 좌표 · 수용 인원. 월드컵경기장은 KOPIS 공연장 정보, 목동 · 잠실은 대략값(shortcut: 공식 좌표 확인 시 교체)
VENUES = {
    "서울 월드컵": (37.568288, 126.897273, 66704),
    "목동": (37.530556, 126.881389, 15000),
    "잠실": (37.515600, 127.072700, 69950),
}


def _venue(field: str):
    return next((v for k, v in VENUES.items() if field and field.startswith(k)), None)


def parse_schedule(body: dict) -> list[dict]:
    rows = []
    for g in (body.get("data") or {}).get("scheduleList") or []:
        v = _venue(g.get("fieldName"))
        if not v:
            continue
        finished = g.get("endYn") == "Y" and (g.get("audienceQty") or 0) > 0
        rows.append({"date": datetime.strptime(g["gameDate"], "%Y.%m.%d").date(), "time": g.get("gameTime"),
                     "league": g.get("leagueId"), "home": g.get("homeTeamName"), "away": g.get("awayTeamName"),
                     "field": g.get("fieldName"), "field_full": g.get("fieldNameFull"),
                     "audience": g["audienceQty"] if finished else None, "lat": v[0], "lon": v[1], "capacity": v[2]})
    return rows


def add_expected_audience(games: pd.DataFrame) -> pd.DataFrame:
    """미래 경기는 관중을 모르므로 같은 홈팀의 '이전' 경기 평균을 쓴다 (그날 관중은 안 씀 — 예측 때 모르는 값)."""
    g = games.sort_values("date").copy()
    g["expected_audience"] = g.groupby("home")["audience"].transform(lambda s: s.astype(float).shift(1).expanding().mean())
    return g


def main() -> int:
    cfg = config.load()
    client = httpx.Client(timeout=30, headers={"Content-Type": "application/json"})
    rows = []
    for year in range(FIRST_YEAR, date.today().year + 2):
        for league in LEAGUES:
            for month in range(1, 13):
                resp = client.post(URL, json={"leagueId": str(league), "year": str(year), "month": f"{month:02d}"})
                resp.raise_for_status()
                rows += parse_schedule(resp.json())
                time.sleep(0.3)
    # collected_at: 수집 시점 기록 (시점 기준 재현 평가용, ChatGPT 검토 4). 과거 일정 스냅샷은 없어 그 전 결과는 사후 수집 기준
    games = add_expected_audience(pd.DataFrame(rows).drop_duplicates(["date", "home", "field"])).assign(collected_at=date.today().isoformat())
    out = cfg.data_dir / "raw" / "kleague"
    out.mkdir(parents=True, exist_ok=True)
    games.to_parquet(out / "games.parquet", index=False)
    done = games["audience"].notna()
    return 0


if __name__ == "__main__":
    sys.exit(main())
