"""서울월드컵경기장 · 고척스카이돔 경기 · 행사 일정 (서울시설공단 공식 달력): `python -m weglee_ai.seoulevent.collect_stadium`

고척스카이돔(키움 홈 · 콘서트)은 같은 달력 형식이라 함께 받는다. KBO 한글 사이트는 robots.txt 로 모든 봇을 막아(Disallow: /) 쓰지 않는다 — 잠실 경기는 영문 사이트의 허용된 일정 화면에서 (collect_kbo).

월드컵경기장 상권 급증 41 상권-일 중 19가 K리그 · KOPIS 에 없는 날 (국가대표 · 친선경기 · 콘서트) 이라 넣는다.
www.sisul.or.kr 의 robots.txt 는 /open_content/sub/schedule/ 을 막지 않는다. 달마다 한 번 받는다 (상세 화면 달력 칸의 title 에 행사 설명이 다 있다).
저장: data/raw/stadium/events.parquet (site · date · text · collected_at)
"""

import html
import re
import sys
import time
from datetime import date

import httpx
import pandas as pd

from weglee_ai.seoulevent import config

URL = "https://www.sisul.or.kr/open_content/sub/schedule/detail.do"
FIRST_YEAR = 2019
SITES = {"worldcupst": "서울월드컵경기장", "skydome": "고척스카이돔"}
_CELL = r"thisSchedule\('(\d{{4}})','(\d{{2}})','(\d{{2}})','{site}'\);\"(.*?)</td>"
_TITLE = re.compile(r'<a href="#none" title="([^"]*)"')


# (분류, 판단 단어) — 위에서부터. K리그는 collect_kleague (예상 관중 있음), 보조경기장 · 여자 · 내부 행사는 주경기장 인파가 아니라 뺀다
CATEGORIES = [
    (None, ("WK리그", "K리그", "보조경기장", "면접")),
    ("프로야구 포스트시즌", ("포스트시즌", "한국시리즈", "플레이오프", "와일드카드")),
    ("프로야구", ("KBO", "프로야구", "키움")),  # 고척 2023~24 는 '키움-SSG전' 처럼 리그 이름 없이 적혀 있다
    ("콘서트", ("콘서트", "LIVE", "어워드", "가요", "TOUR", "투어", "공연", "팬미팅", "내한")),
    # 공사 · 정비 · 훈련 · 준비 · '예정' 안내는 인파가 아니다. shortcut: 설치 기간을 행사일과 함께 적은 칸은 콘서트로 잡혀 앞뒤 며칠이 섞인다
    (None, ("공사", "정비", "훈련", "스프링캠프", "보수", "코로나", "준비", "예정")),
    ("클럽 대회", ("ACL", "코리아컵", "FA컵", "fA컵", "FC서울 vs", "대 진 : FC서울")),  # 코리아컵 결승도 '대한축구협회' 문의라 국가대표보다 먼저
    ("국가대표", ("A매치", "국가대표", "대한민국 vs", "대한민국 VS", "대한축구협회", "월드컵 아시아", "월드컵 출정식")),
    ("친선 · 이벤트 경기", ("친선", "쿠팡플레이", "아이콘 매치", "챔피언스 임팩트", "맨시티")),
]


# 쿠팡플레이 시리즈의 '팀 K리그' 는 K리그 제외 단어에 걸려 버려졌다 (25단계 원인 조사: 2023 · 2024 · 2026 시리즈 급증 8건) → 제외보다 먼저
EVENT_MATCH = ("쿠팡플레이", "팀 K리그", "팀K리그")


def classify(text: str, legacy: bool = False) -> str | None:
    """행사 설명 → 분류. 장소별 과거 반응(venue_effect)을 분류마다 따로 배우도록 장소 이름에 붙인다. 모르면 '기타 행사'.
    legacy=True 는 25단계 전 규칙 (수정 효과 비교용)."""
    if not text.strip():
        return None  # 설명 없는 칸 (고척 달력에 있음)
    if not legacy and any(w in text for w in EVENT_MATCH):
        return "친선 · 이벤트 경기"
    for cat, words in CATEGORIES:
        if any(w in text for w in words):
            return cat
    return "기타 행사"


def parse_month(page: str, site: str = "worldcupst") -> list[dict]:
    rows = []
    for y, m, d, cell in re.findall(_CELL.format(site=site), page, re.S):
        for t in _TITLE.findall(cell):
            rows.append({"site": site, "date": date(int(y), int(m), int(d)), "text": html.unescape(t).strip()})
    return rows


def merge(old: pd.DataFrame, new: pd.DataFrame, since: date) -> pd.DataFrame:
    """since 이전은 기존 파일을 그대로 두고(수집 시각 보존), 이후는 새로 받은 것으로 바꾼다."""
    keep = old[old["date"] < since] if len(old) else old
    return pd.concat([keep, new], ignore_index=True).sort_values("date").reset_index(drop=True)


def main() -> int:
    cfg = config.load()
    out = cfg.data_dir / "raw" / "stadium"
    out.mkdir(parents=True, exist_ok=True)
    path = out / "events.parquet"
    old = pd.read_parquet(path) if path.exists() else pd.DataFrame(columns=["site", "date", "text", "collected_at"])
    if "site" not in old:
        old["site"] = "worldcupst"  # 경기장 하나만 받던 때의 파일
    today = date.today()
    client = httpx.Client(timeout=30, follow_redirects=True)
    merged, fresh = [], []
    for site in SITES:
        mine = old[old["site"] == site]
        # 처음 받는 경기장은 2019 부터, 다음부턴 작년 1월부터만 (지난 일정은 바뀌지 않음)
        since = date(FIRST_YEAR if mine.empty else today.year - 1, 1, 1)
        rows = []
        for year in range(since.year, today.year + 2):
            for month in range(1, 13):
                resp = client.get(URL, params={"year": year, "month": f"{month:02d}", "day": "01", "site_div": site})
                resp.raise_for_status()
                rows += parse_month(resp.text, site)
                time.sleep(0.5)
        new = pd.DataFrame(rows, columns=["site", "date", "text"]).drop_duplicates().assign(collected_at=today.isoformat())
        fresh.append(new)
        merged.append(merge(mine, new, since))
    # 날짜별 스냅샷도 따로 보관해 '그 시점에 알던 일정' 평가에 쓴다
    (out / "snapshots").mkdir(exist_ok=True)
    pd.concat(fresh, ignore_index=True).to_parquet(out / "snapshots" / f"{today.isoformat()}.parquet", index=False)
    ev = pd.concat(merged, ignore_index=True)
    ev.to_parquet(path, index=False)
    cats = ev["site"].map(SITES) + " · " + ev["text"].map(classify).fillna("제외 (K리그 · WK리그 · 보조경기장 · 면접)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
