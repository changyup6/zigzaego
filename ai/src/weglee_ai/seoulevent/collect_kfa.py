"""국가대표 경기 일정 (A매치 · 연령별 · 여자대표): `python -m weglee_ai.seoulevent.collect_kfa`

대한축구협회 월별 경기 일정 화면(www.kfa.or.kr/live/live.php?act=match_schedule, robots.txt 없음 = 제한 없음, 2026-11-07 확인)에서 받는다.
경기장 공식 달력(collect_stadium)에 빠진 국가대표 경기가 있다 (예: 2023-11-16 월드컵 예선 싱가포르전, 서울월드컵경기장).
주의: date_div=pre 는 now_date 로 준 달의 '전달' 을 보여 준다 → 받고 싶은 달 + 1 을 넘긴다. 날짜는 MM-DD 뿐이라 조회 달의 연도를 붙인다.
저장: data/raw/kfa/matches.parquet (date, competition, team, opponent, country, venue, collected_at) — 국내외 모든 경기, 서울 경기장 거르기는 build 에서
"""

import html
import re
import sys
import time
from datetime import date

import httpx
import pandas as pd

from weglee_ai.seoulevent import config

URL = "https://www.kfa.or.kr/live/live.php"
FIRST_MONTH = (2019, 1)
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
_TD = re.compile(r'<p class="count_title"[^>]*>(.*?)<span class="count_date">[^<]*?(\d{2})-(\d{2})</span>\s*'
                 r'<span class="count_from">(.*?)</span>.*?<li class="vs_korea">(.*?)</li>(.*?)</ul>', re.S)
_OPP = re.compile(r'<li class="vs_mexico"[^>]*>(.*?)</li>', re.S)


def _text(s: str) -> str:
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", s))).strip()


def parse_month(page: str, year: int) -> list[dict]:
    """월 일정 화면 → 경기 행. 장소 칸은 '국가&nbsp;경기장명' 이라 첫 &nbsp; 에서 나눈다."""
    rows = []
    for title, mm, dd, place, team, rest in _TD.findall(page):
        country, _, venue = place.partition("&nbsp;")
        opp = _OPP.search(rest)
        rows.append({"date": date(year, int(mm), int(dd)), "competition": _text(title), "team": _text(team),
                     "opponent": _text(opp.group(1)) if opp else None, "country": _text(country), "venue": _text(venue)})
    return rows


def months(first: tuple[int, int], last: date) -> list[tuple[int, int]]:
    y, m = first
    out = []
    while (y, m) <= (last.year, last.month):
        out.append((y, m))
        y, m = (y + 1, 1) if m == 12 else (y, m + 1)
    return out


def main() -> int:
    cfg = config.load()
    client = httpx.Client(timeout=30, follow_redirects=True, headers={"User-Agent": UA})
    rows = []
    for y, m in months(FIRST_MONTH, date.today()):
        ny, nm = (y + 1, 1) if m == 12 else (y, m + 1)  # pre = 전달 → 다음 달을 넘긴다
        resp = client.get(URL, params={"act": "match_schedule", "date_div": "pre", "now_date": f"{ny}-{nm:02d}"})
        resp.raise_for_status()
        rows += parse_month(resp.text, y)
        time.sleep(0.6)
    # collected_at: 수집 시점 기록 (시점 기준 재현 평가용). 과거 일정 스냅샷은 없어 그 전 결과는 사후 수집 기준
    games = (pd.DataFrame(rows).drop_duplicates(["date", "team", "opponent", "venue"])
             .sort_values("date").reset_index(drop=True).assign(collected_at=date.today().isoformat()))
    out = cfg.data_dir / "raw" / "kfa"
    out.mkdir(parents=True, exist_ok=True)
    games.to_parquet(out / "matches.parquet", index=False)
    home = games[games["country"] == "대한민국"]
    return 0


if __name__ == "__main__":
    sys.exit(main())
