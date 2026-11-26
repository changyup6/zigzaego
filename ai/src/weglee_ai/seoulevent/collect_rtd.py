"""82장소 일별 카드 결제(최근 28일): `python -m weglee_ai.seoulevent.collect_rtd`

서울 실시간 도시데이터 웹 화면(data.seoul.go.kr/SeoulRtd)이 쓰는 주소에서 받는다 (교내망 가능 · 키 불필요).
한 번에 28일치가 오므로 28일 안에 한 번씩만 돌면 일 매출이 끊기지 않는다 → 매주 실행 권장.
저장: data/raw/rtd/raw/<수집일>/<AREA_CD>.json (원본), data/raw/rtd/rtd_daily.parquet (누적, 같은 날짜는 최신 수집값)
"""

import csv
import json
import sys
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import date, datetime
from pathlib import Path

import httpx
import pandas as pd

from weglee_ai.seoulevent import config, places

SITE = "https://data.seoul.go.kr/SeoulRtd"
COLUMNS = ["area_cd", "area_nm", "date", "amt", "cnt", "collected_at"]
LOG_FIELDS = ["collected_at", "ok", "failed_count", "failed_places", "errors", "rows", "gap_days"]


class SessionExpired(Exception):
    """화면 세션 없이 부르면 오류 페이지로 302 된다."""


@dataclass
class RunResult:
    ok: int = 0
    failed: list[str] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)
    rows: int = 0
    gap_days: int = 0


def open_session() -> httpx.Client:
    client = httpx.Client(timeout=30, headers={"User-Agent": "Mozilla/5.0", "Accept": "application/json",
                                               "Referer": f"{SITE}/map"})
    client.get(f"{SITE}/map")  # 세션 쿠키
    return client


def fetch_28days(client: httpx.Client, area_nm: str) -> dict:
    resp = client.get(f"{SITE}/api/consumption/hotspot-28days", params={"hotspotNm": area_nm}, follow_redirects=False)
    if resp.status_code in (301, 302, 403):
        raise SessionExpired()
    resp.raise_for_status()
    return resp.json()


def session_fetch(open_=open_session, fetch=fetch_28days) -> Callable[[str], dict]:
    """세션은 첫 요청 때 열고, 만료되면 한 번 다시 열어 다시 부른다 — 안 그러면 만료 뒤 남은 장소가 모두 실패하고,
    세션 열기 실패도 run 안에서 장소별 실패로 로그에 남는다."""
    state = {"client": None}

    def get(area_nm: str) -> dict:
        for attempt in (0, 1):
            if state["client"] is None:
                state["client"] = open_()
            try:
                return fetch(state["client"], area_nm)
            except SessionExpired:
                state["client"] = None
                if attempt:
                    raise
    return get


def parse(body: dict) -> list[dict]:
    return [{"date": datetime.strptime(r["apvTsD"], "%Y%m%d").date(), "amt": int(r["dailyAmtSum"]), "cnt": int(r["dailyCntSum"])}
            for r in body.get("card28DayList") or []]


def run(places_: list[tuple[str, str]], fetch: Callable[[str], dict], out_dir: Path, today: date,
        pause: float = 0.3) -> RunResult:
    result = RunResult()
    stamp = today.isoformat()
    raw_dir = out_dir / "raw" / stamp
    raw_dir.mkdir(parents=True, exist_ok=True)
    new = []
    for area_cd, area_nm in places_:
        try:
            body = fetch(area_nm)
            rows = parse(body)
        except Exception as e:  # 한 곳 실패로 나머지를 잃지 않는다
            result.failed.append(area_cd)
            result.errors.append(f"{area_cd}:{type(e).__name__}")
        else:
            (raw_dir / f"{area_cd}.json").write_text(json.dumps(body, ensure_ascii=False), encoding="utf-8")
            new += [{"area_cd": area_cd, "area_nm": area_nm, **r, "collected_at": stamp} for r in rows]
            result.ok += 1
        if pause:
            time.sleep(pause)

    store = out_dir / "rtd_daily.parquet"
    old = pd.read_parquet(store) if store.exists() else pd.DataFrame(columns=COLUMNS)
    fresh = pd.DataFrame(new, columns=COLUMNS)
    if len(old) and len(fresh):
        # 28일 창을 놓치면 그 사이 일 매출은 다시 받을 수 없다 → 빈 날 수를 기록
        result.gap_days = max((min(fresh["date"]) - max(old["date"])).days - 1, 0)
    merged = pd.concat([old, fresh], ignore_index=True).sort_values("collected_at")
    merged = merged.drop_duplicates(["area_cd", "date"], keep="last").sort_values(["area_cd", "date"])
    merged.to_parquet(store, index=False)
    result.rows = len(fresh)

    log = out_dir / "_log.csv"
    first = not log.exists()
    with open(log, "a", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, LOG_FIELDS)
        if first:
            w.writeheader()
        w.writerow({"collected_at": stamp, "ok": result.ok, "failed_count": len(result.failed),
                    "failed_places": ";".join(result.failed), "errors": ";".join(result.errors),
                    "rows": result.rows, "gap_days": result.gap_days})
    return result


def main() -> int:
    cfg = config.load()
    place_list = places.load(places.ensure(cfg.data_dir))
    result = run(place_list, session_fetch(), cfg.data_dir / "raw" / "rtd", date.today())
    store = pd.read_parquet(cfg.data_dir / "raw" / "rtd" / "rtd_daily.parquet")
    return 0 if result.ok else 1


if __name__ == "__main__":
    sys.exit(main())
