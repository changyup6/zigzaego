"""KOPIS 공연예술통합전산망 — 서울 공연 · 공연장: `python -m weglee_ai.seoulevent.collect_kopis`

공연(날짜 · 공연장 · 장르)과 공연장(좌석 수 · 좌표)을 받는다. 좌석 수로 행사 규모(체조경기장 · 고척돔 · 월드컵경기장)를 가늠한다.
키: .env 의 KOPIS_API_KEY. 한 번에 최대 100건이라 페이지를 넘긴다.
저장: data/raw/kopis/performances.parquet (공연, mt20id 기준 중복 제거) · facilities.parquet (공연장 상세, 이미 받은 곳은 건너뜀)
"""

import html
import re
import sys
import time
from collections.abc import Callable
from datetime import date, datetime, timedelta
from pathlib import Path

import httpx
import pandas as pd

from weglee_ai.seoulevent import config
from weglee_ai.seoulevent._http import redact

BASE = "http://kopis.or.kr/openApi/restful"
SEOUL = "11"
ROWS = 100
FIRST_MONTH = date(2019, 1, 1)
AHEAD_DAYS = 180  # 미래 일정도 받는다 — 앞으로 열릴 행사를 보고 예측하기 위해
LIST_FIELDS = ["mt20id", "prfnm", "prfpdfrom", "prfpdto", "fcltynm", "genrenm", "openrun", "prfstate"]


class KopisError(Exception):
    pass


def _tag(xml: str, tag: str) -> str | None:
    m = re.search(f"<{tag}>(.*?)</{tag}>", xml, re.S)
    return html.unescape(m.group(1)).strip() if m else None


def _day(s: str) -> date:
    return datetime.strptime(s, "%Y.%m.%d").date()


def parse_list(xml: str) -> list[dict]:
    rows = []
    for db in re.findall(r"<db>(.*?)</db>", xml, re.S):
        r = {f: _tag(db, f) for f in LIST_FIELDS}
        rows.append({"mt20id": r["mt20id"], "prfnm": r["prfnm"], "start_date": _day(r["prfpdfrom"]), "end_date": _day(r["prfpdto"]),
                     "fcltynm": r["fcltynm"], "genrenm": r["genrenm"], "openrun": r["openrun"], "prfstate": r["prfstate"]})
    return rows


def parse_facility(xml: str) -> dict | None:
    """오류 응답이면 None."""
    if "<returncode>" in xml or not _tag(xml, "mt10id"):
        return None

    def num(v: str | None) -> float | None:
        return float(v.replace(",", "")) if v and v.strip() else None

    seats = num(_tag(xml, "seatscale"))
    # 시설 좌석 수는 모든 홀의 합계 — 예술의전당 작은 홀 공연까지 '큰 공연' 이 된다 → 가장 큰 홀을 따로 둔다
    halls = [num(_tag(h, "seatscale")) for h in re.findall(r"<mt13>(.*?)</mt13>", xml, re.S)]
    halls = [h for h in halls if h]
    return {"mt10id": _tag(xml, "mt10id"), "fcltynm": _tag(xml, "fcltynm"), "seats": int(seats) if seats else None,
            "max_hall_seats": int(max(halls)) if halls else (int(seats) if seats else None), "n_halls": len(halls),
            "lat": num(_tag(xml, "la")), "lon": num(_tag(xml, "lo")), "fcltychartr": _tag(xml, "fcltychartr"),
            "adres": _tag(xml, "adres")}


def fetch_pages(get: Callable[[str, dict], str], url: str, params: dict, key: str = "") -> list[str]:
    """<db> 조각 목록. 100건 미만 쪽이 오면 끝."""
    out, page = [], 1
    while True:
        xml = get(url, {**params, "cpage": page, "rows": ROWS})
        if "<dbs" not in xml:  # 일시적 서버 오류 페이지를 '마지막 쪽' 으로 착각해 조용히 덜 받던 문제 (1차 수집에서 2021~2024 누락)
            raise KopisError("XML 이 아닌 응답")
        if "<returncode>" in xml:
            raise KopisError(redact(f"{_tag(xml, 'returncode')}: {_tag(xml, 'errmsg')}", key))
        dbs = re.findall(r"<db>.*?</db>", xml, re.S)
        out += dbs
        if len(dbs) < ROWS:
            return out
        page += 1


def _norm(s: str) -> str:
    return re.sub(r"\s+", "", html.unescape(s)).lower()


def venue_matcher(facilities: dict[str, str]) -> Callable[[str], str | None]:
    """공연 목록의 공연장 이름 → 시설 id. 정확히 같으면 그것, 아니면 이름 앞부분이 가장 길게 겹치는 시설 ('세종문화회관 대극장' → 세종문화회관).
    시설 이름 정리는 한 번만 한다 — 공연마다 다시 하면 수만 × 1,100 번이라 수집 마지막 단계가 수십 분 걸렸다."""
    norm = {_norm(k): v for k, v in facilities.items() if isinstance(k, str)}
    by_len = sorted(norm, key=len, reverse=True)

    def match(name: str) -> str | None:
        if not isinstance(name, str):
            return None
        n = _norm(name)
        if n in norm:
            return norm[n]
        return next((norm[k] for k in by_len if n.startswith(k)), None)

    return match


def match_venue(name: str, facilities: dict[str, str]) -> str | None:
    return venue_matcher(facilities)(name)


def _months(start: date, end: date):
    d = start
    while d <= end:
        nxt = (d.replace(day=28) + timedelta(days=4)).replace(day=1)
        yield d, nxt - timedelta(days=1)
        d = nxt


def main() -> int:
    cfg = config.load()
    if not cfg.kopis_key:
        print("KOPIS_API_KEY 가 .env 에 없습니다.", file=sys.stderr)
        return 2
    key = cfg.kopis_key
    out = cfg.data_dir / "raw" / "kopis"
    out.mkdir(parents=True, exist_ok=True)
    client = httpx.Client(timeout=60, follow_redirects=True)

    def get(url: str, params: dict) -> str:
        for attempt in range(5):
            time.sleep(0.1 + attempt * 2)
            try:
                text = client.get(url, params={"service": key, **params}).text
            except httpx.TransportError:
                continue
            if "<dbs" in text:
                return text
        raise KopisError("5회 시도해도 정상 응답 없음")

    # 달마다 따로 저장해 이어 받는다. 지난달까지는 한 번 받으면 다시 받지 않고, 이번 달 · 미래는 일정이 바뀌므로 매번 다시.
    month_dir = out / "months"
    month_dir.mkdir(exist_ok=True)
    this_month = date.today().replace(day=1)
    counts = []
    for a, b in _months(FIRST_MONTH, date.today() + timedelta(days=AHEAD_DAYS)):
        path = month_dir / f"{a:%Y-%m}.parquet"
        if not path.exists() or a >= this_month:
            dbs = fetch_pages(get, f"{BASE}/pblprfr", {"stdate": a.strftime("%Y%m%d"), "eddate": b.strftime("%Y%m%d"), "signgucode": SEOUL}, key)
            # collected_at: 이 일정을 언제 알고 있었나 — 이후 수집분부터 시점 기준 재현 평가가 가능 (ChatGPT 검토 4)
            pd.DataFrame(parse_list("".join(dbs))).assign(collected_at=date.today().isoformat()).to_parquet(path, index=False)
        counts.append({"month": f"{a:%Y-%m}", "rows": len(pd.read_parquet(path))})
    pd.DataFrame(counts).to_csv(out / "_months.csv", index=False)
    perf = pd.concat([pd.read_parquet(p) for p in sorted(month_dir.glob("*.parquet"))], ignore_index=True).drop_duplicates("mt20id")
    perf.to_parquet(out / "performances.parquet", index=False)

    ids = [_tag(db, "mt10id") for db in fetch_pages(get, f"{BASE}/prfplc", {"signgucode": SEOUL}, key)]
    fac_path = out / "facilities.parquet"
    old = pd.read_parquet(fac_path) if fac_path.exists() else pd.DataFrame(columns=["mt10id"])
    done = set(old["mt10id"])
    new = [f for f in (parse_facility(get(f"{BASE}/prfplc/{i}", {})) for i in ids if i and i not in done) if f]
    fac = pd.concat([old, pd.DataFrame(new)], ignore_index=True).drop_duplicates("mt10id")
    fac.to_parquet(fac_path, index=False)

    names = dict(zip(fac["fcltynm"], fac["mt10id"]))
    matched = perf["fcltynm"].map(venue_matcher(names)).notna()
    return 0


if __name__ == "__main__":
    sys.exit(main())
