"""한강공원 행사 · 공연 (미래한강본부 공식 목록): `python -m weglee_ai.seoulevent.collect_hangang`

뚝섬 · 자양 상권의 원인 모를 주말 붐빔(48일, 3 · 5 · 9월)이 한강공원 축제 · 드론쇼로 보여 넣는다.
hangang.seoul.go.kr 는 robots.txt 가 없다(404). 목록 한 쪽에 행사명 · 기간 · 장소 · 좌표가 다 있어 쪽마다 한 번 받는다.
8일 이상 기간으로 올라온 행사(드론쇼 '상반기 4/27~6/15' 등)는 상세 화면의 회차 날짜('1회차 공연 : 4. 27.(일)')를 하루짜리 행으로 더한다
— 기간 행은 '짧은 행사'가 아니라 모델에서 빠지고, 실제 인파는 회차 날에만 몰린다 (자양 · 응봉산 놓침 7).
저장: data/raw/hangang/events.parquet (evnt_sn · category · title · start · end · place · park · lat · lon · session · collected_at)
"""

import html
import re
import sys
import time
from datetime import date

import httpx
import pandas as pd

from weglee_ai.seoulevent import config

URL = "https://hangang.seoul.go.kr/www/eventMng/list.do"
DETAIL = "https://hangang.seoul.go.kr/www/eventMng/detail.do"
LONG_DAYS = 8
WEEKDAYS = "월화수목금토일"
_SESSION = re.compile(r"(\d{1,2})\s*\.\s*(\d{1,2})\s*\.?\s*\(\s*([월화수목금토일])\s*\)")
MAX_PAGES = 300
_ITEM = re.compile(r'<li class="event-ind[^"]*">(?P<cat>[^<]*)</li>.*?goDetail\(\'(?P<sn>\d+)\'\)" class="event-tit" title="(?P<title>[^"]*)"'
                   r'.*?<dt>기간</dt>\s*<dd>(?P<start>\d{4}-\d{2}-\d{2})[^~<]*~\s*(?P<end>\d{4}-\d{2}-\d{2})?'
                   r'.*?<dt>장소</dt>\s*<dd>(?P<place>[^<]*)</dd>.*?data-lot="(?P<lon>[^"]*)" data-lat="(?P<lat>[^"]*)"', re.S)
_PARK = re.compile(r"(\S+)한강공원")


def parse_page(page: str) -> list[dict]:
    rows = []
    for m in _ITEM.finditer(page):
        place = html.unescape(m["place"]).strip()
        park = _PARK.search(place)
        rows.append({"evnt_sn": int(m["sn"]), "category": m["cat"].strip(), "title": html.unescape(m["title"]).strip(),
                     "start": m["start"], "end": m["end"] or m["start"], "place": place,
                     "park": park[0] if park else place, "lat": float(m["lat"] or "nan"), "lon": float(m["lon"] or "nan")})
    return rows


def session_places(text: str, start: str, end: str, parks: list[str] = ()) -> dict[str, str | None]:
    """상세 설명에서 '5. 11.(일)' 처럼 요일이 붙은 날짜 — 요일이 맞고 행사 기간(앞뒤 2주 여유) 안인 것. 연도는 시작일 기준.
    날짜 바로 뒤('/ 뚝섬한강공원')에 아는 공원 이름이 있으면 그 회차 장소 '<공원>한강공원', 없으면 None — 드론쇼처럼 회차마다 공원이 바뀐다."""
    s, e = pd.Timestamp(start), pd.Timestamp(end)
    near = re.compile(r"\s*[/:·-]?\s*(" + "|".join(map(re.escape, parks)) + r")\s*(?:한강공원)?") if parks else None
    out = {}
    for m in _SESSION.finditer(text):
        try:
            t = pd.Timestamp(year=s.year, month=int(m[1]), day=int(m[2]))
        except ValueError:
            continue
        if WEEKDAYS[t.day_of_week] == m[3] and s - pd.Timedelta(days=14) <= t <= e + pd.Timedelta(days=14):
            p = near.match(text, m.end()) if near else None
            out.setdefault(t.date().isoformat(), f"{p[1]}한강공원" if p else None)
    return dict(sorted(out.items()))


def session_dates(text: str, start: str, end: str) -> list[str]:
    return list(session_places(text, start, end))


def main() -> int:
    cfg = config.load()
    client = httpx.Client(timeout=60, follow_redirects=True)
    rows = []
    for page in range(1, MAX_PAGES + 1):
        resp = client.get(URL, params={"mid": 53, "pageNo": page})
        resp.raise_for_status()
        got = parse_page(resp.text)
        if not got:
            break
        rows += got
        time.sleep(0.5)
    ev = pd.DataFrame(rows).drop_duplicates("evnt_sn").assign(session=False)
    # 공원 좌표 = 대표 장소가 그 공원인 행사들의 좌표 평균 (손 입력 없이 목록에서) — 회차 장소가 대표 위치와 다를 때 쓴다
    coords = ev[ev["park"].str.fullmatch(r"\S+한강공원")].groupby("park")[["lat", "lon"]].mean()
    parks = [p.removesuffix("한강공원") for p in coords.index]
    sessions = []
    for r in ev[(pd.to_datetime(ev["end"]) - pd.to_datetime(ev["start"])).dt.days + 1 >= LONG_DAYS].itertuples(index=False):
        text = re.sub(r"<[^>]+>|&nbsp;", " ", client.get(DETAIL, params={"evntSn": r.evnt_sn, "mid": 53}).text)
        for d, park in session_places(text, r.start, r.end, parks).items():
            where = {"park": park, "lat": coords.at[park, "lat"], "lon": coords.at[park, "lon"]} if park in coords.index else {}
            sessions.append(r._asdict() | {"title": f"{r.title} ({d[5:]} 회차)", "start": d, "end": d, "session": True} | where)
        time.sleep(0.5)
    ev = pd.concat([ev, pd.DataFrame(sessions)], ignore_index=True).assign(collected_at=date.today().isoformat())
    out = cfg.data_dir / "raw" / "hangang"
    out.mkdir(parents=True, exist_ok=True)
    ev.to_parquet(out / "events.parquet", index=False)
    return 0


if __name__ == "__main__":
    sys.exit(main())
