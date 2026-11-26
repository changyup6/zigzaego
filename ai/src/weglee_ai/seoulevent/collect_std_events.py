"""전국문화축제 · 전국공연행사 표준데이터 (공공데이터포털, 2026-11-06 활용 승인): `python -m weglee_ai.seoulevent.collect_std_events`

장미축제 · 한성백제문화제처럼 서울 문화행사 원천에 빠진 지역 축제의 기간 · 좌표가 있다. 과거 회차는 거의 없어(최근 회차만 남는 구조)
매주 받아 스냅샷(weekly)으로 쌓는 용도 — 1년쯤 쌓인 뒤 events 원천으로 붙여 비교한다. 서울 행만 저장.
저장: data/raw/std_events/events.parquet (kind = festival | event, 원본 열 + collected_at)
"""

import sys
import time
from datetime import date

import httpx
import pandas as pd

from weglee_ai.seoulevent import config

APIS = {"festival": "http://api.data.go.kr/openapi/tn_pubr_public_cltur_fstvl_api",
        "event": "http://api.data.go.kr/openapi/tn_pubr_public_pblprfr_event_info_api"}
ROWS = 1000


def parse(payload: dict) -> tuple[list[dict], int]:
    """응답이 response 로 감싸져 오기도, 안 오기도 한다. 정상(00)이 아니면 오류."""
    res = payload.get("response", payload)
    code = res.get("header", {}).get("resultCode")
    if code != "00":
        raise RuntimeError(f"표준데이터 응답 오류: {code} {res.get('header', {}).get('resultMsg')}")
    body = res.get("body", {})
    items = (body.get("items") or {}).get("item") or [] if body.get("items") else []
    return ([items] if isinstance(items, dict) else items), int(body.get("totalCount") or 0)


def seoul(df: pd.DataFrame) -> pd.DataFrame:
    addr = df.get("rdnmadr", pd.Series("", index=df.index)).astype(str) + " " + df.get("lnmadr", pd.Series("", index=df.index)).astype(str)
    return df[addr.str.contains("서울")]


def fetch(url: str, key: str, client: httpx.Client) -> list[dict]:
    rows, page = [], 1
    while True:
        resp = client.get(url, params={"serviceKey": key, "pageNo": page, "numOfRows": ROWS, "type": "json"})
        resp.raise_for_status()
        got, total = parse(resp.json())
        rows += got
        if page * ROWS >= total or not got:
            return rows
        page += 1
        time.sleep(0.3)


def main() -> int:
    cfg = config.load()
    if not cfg.data_go_kr_key:
        print("DATA_GO_KR_KEY 가 .env 에 없습니다.", file=sys.stderr)
        return 2
    client = httpx.Client(timeout=120)
    frames = [seoul(pd.DataFrame(fetch(url, cfg.data_go_kr_key, client))).assign(kind=kind) for kind, url in APIS.items()]
    out = pd.concat(frames, ignore_index=True).assign(collected_at=date.today().isoformat())
    path = cfg.data_dir / "raw" / "std_events"
    path.mkdir(parents=True, exist_ok=True)
    out.astype(str).to_parquet(path / "events.parquet", index=False)  # 원본 열 형식이 섞여 문자열로 (날짜 · 좌표는 빌드에서 변환)
    return 0


if __name__ == "__main__":
    sys.exit(main())
