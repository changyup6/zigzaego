"""마라톤 · 달리기 대회 일정: `python -m weglee_ai.seoulevent.collect_marathon`

마라톤온라인 대회 상세 화면(www.roadrun.co.kr/schedule/view.php?no=N, robots.txt 없음 = 제한 없음, 2026-11-07 확인)을 번호 순으로 받는다.
원인 모를 급증 중 서울마라톤 · JTBC 마라톤 같은 도심 대회가 있다. 번호가 늘수록 최근, 빈 번호(대회일시 빈칸)가 섞이고 41xxx 대 번호도 있다.
받은 화면은 data/raw/marathon/pages/<no>.html 로 남겨 다시 돌리면 건너뛴다(이어 받기). 빈 번호는 empty.txt 에 적어 마지막 구간 끝만 다시 확인한다.
저장: data/raw/marathon/races.parquet (no, date, name, region, place, organizer, events, lat, lon, is_seoul, collected_at) — 전국 대회, 서울 거르기는 build 에서 is_seoul 로
"""

import html
import re
import sys
import time
from datetime import date

import httpx
import pandas as pd

from weglee_ai.seoulevent import config

URL = "http://www.roadrun.co.kr/schedule/view.php"
FIRST_DATE = date(2019, 1, 1)
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
GAP = 300  # 빈 번호가 이만큼 이어지면 그 구간 끝
RANGES = [(1, 20000), (40000, 60000)]  # 번호 구간 (41xxx 대가 따로 있다). 각 구간에서 2019-01 이후 시작점을 찾아 GAP 까지 받는다
FIELDS = {"대회명": "name", "대회일시": "date", "대회지역": "region", "대회장소": "place", "주최단체": "organizer", "대회종목": "events"}
_ROW = re.compile(r'<p align="center">\s*([^<]+?)\s*</p>\s*</td>\s*<td[^>]*>(.*?)</td>', re.S)
_DATE = re.compile(r"(\d{4})\s*년\s*(\d{1,2})\s*월\s*(\d{1,2})\s*일")
_LATLNG = re.compile(r"Marker\(\{\s*position:\s*new naver\.maps\.LatLng\(([\d.]+),\s*([\d.]+)\)")


def _text(s: str) -> str:
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", s))).strip()


def parse_page(page: str) -> dict | None:
    """상세 화면 → 대회 한 행. 대회일시가 비었거나 날짜가 아니면 빈 번호(None)."""
    cells = {FIELDS[k]: _text(v) for k, v in _ROW.findall(page) if k in FIELDS}
    m = _DATE.search(cells.get("date", ""))
    if not m:
        return None
    ll = _LATLNG.search(page)
    return {**cells, "date": date(int(m[1]), int(m[2]), int(m[3])),
            "lat": float(ll[1]) if ll else None, "lon": float(ll[2]) if ll else None}


SEOUL_GU = ("종로구 용산구 성동구 광진구 동대문구 중랑구 성북구 강북구 도봉구 노원구 은평구 서대문구 마포구 양천구 구로구 금천구 "
            "영등포구 동작구 관악구 서초구 강남구 송파구 강동구").split()
# 지역 칸이 '지역' · '기타' 인 대회(전체의 1/4 가량)는 장소 문구로 서울인지 본다. 서울에만 있는 장소 이름만 (서울대공원은 과천이라, 중구 · 강서구는 부산 · 대구 · 인천에도 있어 뺀다)
SEOUL_PLACE = re.compile("서울(?!대공원)|한강공원|광화문|잠실|여의도|상암|월드컵공원|평화의\\s*광장|뚝섬|반포|올림픽공원|" + "|".join(SEOUL_GU))


def is_seoul(region: str | None, place: str | None = None) -> bool:
    region = str(region or "").strip()
    return region.startswith("서울") or (region in ("지역", "기타", "") and bool(SEOUL_PLACE.search(str(place or ""))))


class Fetcher:
    """번호 → 대회 행. 받은 화면은 pages/ 에, 빈 번호는 empty.txt 에 남겨 다시 받지 않는다."""

    def __init__(self, out):
        self.pages = out / "pages"
        self.pages.mkdir(parents=True, exist_ok=True)
        self.empty_path = out / "empty.txt"
        # 가장 큰 받은 번호 위의 빈 번호는 새 대회가 올라올 자리라 다시 확인한다 (주간 수집)
        # shortcut: 받은 화면은 다시 안 받아 미래 대회의 날짜 변경 · 취소는 놓친다 → 필요하면 pages/ 의 최근 번호를 지우고 다시 돌린다
        top = max((int(p.stem) for p in self.pages.glob("*.html")), default=0)
        self.empty = {n for n in map(int, self.empty_path.read_text().split()) if n < top} if self.empty_path.exists() else set()
        self.client = httpx.Client(timeout=30, follow_redirects=True, headers={"User-Agent": UA})
        self.requests = 0

    def get(self, no: int) -> dict | None:
        path = self.pages / f"{no}.html"
        if path.exists():
            return parse_page(path.read_bytes().decode("euc-kr", errors="replace"))
        if no in self.empty:
            return None
        for attempt in range(3):
            try:
                resp = self.client.get(URL, params={"no": no})
                resp.raise_for_status()
                break
            except httpx.HTTPError:
                if attempt == 2:
                    raise
                time.sleep(5)
        time.sleep(0.6)
        self.requests += 1
        row = parse_page(resp.content.decode("euc-kr", errors="replace"))
        if row is None:
            self.empty.add(no)
            with self.empty_path.open("a") as f:
                f.write(f"{no}\n")
        else:
            path.write_bytes(resp.content)
        return row


def first_after(fetch: Fetcher, lo: int, hi: int, start: date) -> int:
    """[lo, hi] 에서 대회일이 start 이후인 첫 번호 무렵을 이진 탐색으로 찾는다 (빈 번호는 다음 찬 번호로 대신 본다).
    shortcut: 번호 순과 날짜 순이 대략만 맞아 경계 근처 대회 몇 개는 놓칠 수 있다 → 찾은 번호에서 GAP 만큼 앞에서 시작한다."""
    floor = lo
    while hi - lo > 50:
        mid = (lo + hi) // 2
        d = None
        for n in range(mid, min(mid + 30, hi)):
            row = fetch.get(n)
            if row:
                d = row["date"]
                break
        if d is None or d >= start:
            hi = mid
        else:
            lo = mid
    return max(lo - GAP, floor)


def scan(fetch: Fetcher, begin: int, end: int) -> list[dict]:
    """begin 부터 빈 번호가 GAP 개 이어질 때까지(또는 end 까지) 받는다."""
    rows, gap, no = [], 0, begin
    while gap < GAP and no <= end:
        row = fetch.get(no)
        if row:
            rows.append({"no": no, **row})
            gap = 0
        else:
            gap += 1
        if fetch.requests and fetch.requests % 200 == 0 and row is not None:
            pass
        no += 1
    return rows


def main() -> int:
    cfg = config.load()
    out = cfg.data_dir / "raw" / "marathon"
    fetch = Fetcher(out)
    rows = []
    for lo, hi in RANGES:
        if not any(fetch.get(n) for n in range(lo, lo + 30)) and lo > 1:
            # 구간 시작에 대회가 없으면 처음 찬 번호를 듬성듬성 찾는다
            probe = next((n for n in range(lo, hi, 100) if fetch.get(n)), None)
            if probe is None:
                continue
            lo = max(probe - 100, lo)
        begin = first_after(fetch, lo, hi, FIRST_DATE)
        rows += scan(fetch, begin, hi)
    # collected_at: 수집 시점 기록 (시점 기준 재현 평가용). 과거 일정 스냅샷은 없어 그 전 결과는 사후 수집 기준
    races = (pd.DataFrame(rows).drop_duplicates("no").query("date >= @FIRST_DATE").sort_values(["date", "no"])
             .reset_index(drop=True))
    races = races.assign(is_seoul=[is_seoul(r, p) for r, p in zip(races["region"], races["place"])], collected_at=date.today().isoformat())
    races.to_parquet(out / "races.parquet", index=False)
    seoul = races[races["is_seoul"]]
    return 0


if __name__ == "__main__":
    sys.exit(main())
