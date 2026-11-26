"""서울 행사 · 집회 뉴스 기사 (NAVER API HUB 검색-뉴스): `python -m weglee_ai.seoulevent.collect_news`

원인 모름 급증(집회 · 경기장 오픈 트레이닝 등)은 공식 일정 원천에 없고 뉴스에만 나온다 (심층 분석 후속, 2026-11-09).
기사는 대부분 하루 이틀 전에 나와 7일 앞 모델에는 바로 못 쓴다 → 지금은 원본을 쌓기만 한다 (모델 입력 아님).
키: .env NAVER_CLIENT_ID · NAVER_CLIENT_SECRET (API HUB 키 — 주소 naverapihub.apigw.ntruss.com, 헤더 X-NCP-APIGW-API-KEY-ID · -KEY).
한도 하루 25,000회 — 한 번 실행에 질의 수 × 최대 10쪽 (지금 약 300회).
저장: data/raw/news/articles.parquet (query, title, description, link, originallink, pub_date, collected_at) — 실행마다 (query, link) 기준 누적.
"""

import sys
import time
from datetime import date
from email.utils import parsedate_to_datetime

import httpx
import pandas as pd

from weglee_ai.seoulevent import config

URL = "https://naverapihub.apigw.ntruss.com/search/v1/news"
PAGE = 100
MAX_START = 1000  # API 가 start 1000 까지만 준다
COLUMNS = ["query", "title", "description", "link", "originallink", "pub_date", "collected_at"]
# 장소 · 행사 묶음 — 원인 모름 급증 (여의도 · 광화문 집회, 경기장 오픈 트레이닝, 공원 축제)과 큰 행사장
QUERIES = ["서울월드컵경기장", "잠실종합운동장", "잠실야구장", "고척스카이돔", "올림픽공원 공연", "KSPO DOME", "서울광장 행사", "광화문 집회",
           "여의도 집회", "서울 도심 집회 교통통제", "서울 교통통제 행사", "오픈 트레이닝", "여의도한강공원 축제", "한강공원 축제", "하늘공원",
           "노들섬", "서울 불꽃축제", "서울 마라톤 교통통제", "서울 대학 축제", "코엑스 행사", "DDP 행사", "성수동 팝업", "홍대 축제",
           "서울 팬미팅", "서울 콘서트 관람객", "서울 페스티벌 개막", "청계천 축제", "서울 거리 퍼레이드", "응봉산", "석촌호수"]


def parse(payload: dict) -> list[dict]:
    """오류면 errorCode/error 가 온다 → 예외. 정상이면 items 목록."""
    if "items" not in payload:
        raise RuntimeError(f"뉴스 검색 응답 오류: {str(payload)[:200]}")
    return payload["items"]


def to_rows(items: list[dict], query: str, today: str) -> list[dict]:
    return [{"query": query, "title": i.get("title", ""), "description": i.get("description", ""), "link": i.get("link", ""),
             "originallink": i.get("originallink", ""), "pub_date": parsedate_to_datetime(i["pubDate"]).isoformat() if i.get("pubDate") else None,
             "collected_at": today} for i in items]


def merge(old: pd.DataFrame | None, new: pd.DataFrame) -> pd.DataFrame:
    """같은 질의 · 같은 기사는 처음 받은 것을 남긴다 (collected_at = 처음 안 날)."""
    both = new if old is None else pd.concat([old, new], ignore_index=True)
    return both.drop_duplicates(["query", "link"], keep="first").reset_index(drop=True)


def fetch(query: str, cid: str, secret: str, client: httpx.Client) -> list[dict]:
    h = {"X-NCP-APIGW-API-KEY-ID": cid, "X-NCP-APIGW-API-KEY": secret}
    out = []
    for start in range(1, MAX_START + 1, PAGE):
        r = client.get(URL, params={"query": query, "display": PAGE, "start": start, "sort": "date", "format": "json"}, headers=h)
        r.raise_for_status()
        items = parse(r.json())
        out += items
        if len(items) < PAGE:
            break
        time.sleep(0.1)
    return out


def main() -> int:
    cfg = config.load()
    if not (cfg.naver_id and cfg.naver_secret):
        print("NAVER_CLIENT_ID · NAVER_CLIENT_SECRET 이 .env 에 없습니다.", file=sys.stderr)
        return 2
    today = date.today().isoformat()
    client = httpx.Client(timeout=30)
    rows = []
    for q in QUERIES:
        rows += to_rows(fetch(q, cfg.naver_id, cfg.naver_secret, client), q, today)
    path = cfg.data_dir / "raw" / "news"
    path.mkdir(parents=True, exist_ok=True)
    file = path / "articles.parquet"
    old = pd.read_parquet(file) if file.exists() else None
    out = merge(old, pd.DataFrame(rows, columns=COLUMNS))
    out.to_parquet(file, index=False)
    return 0


if __name__ == "__main__":
    sys.exit(main())
