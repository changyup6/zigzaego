"""서울 열린데이터광장 Open API (openapi.seoul.go.kr:8088). 교내망에서는 차단된다."""

import time
from collections.abc import Callable

import httpx

from weglee_ai.seoulevent._http import redact, send

BASE = "http://openapi.seoul.go.kr:8088"


class SeoulApiError(Exception):
    def __init__(self, code: str, message: str, body: dict | None = None):
        super().__init__(f"{code}: {message}")
        self.code = code
        self.body = body  # 응답 형식을 못 알아봤을 때 본문을 버리지 않도록 함께 넘긴다


def _url(key: str, service: str, start: int, end: int, args: tuple[str, ...]) -> str:
    return "/".join([BASE, key, "json", service, str(start), str(end), *args])


def _result(body: dict) -> tuple[str | None, str]:
    """RESULT 는 최상위 · 서비스명 아래 · row[0] 안 중 한 곳에 온다. 실시간 도시데이터는 키가 'RESULT.CODE'."""
    candidates = [body, *(v for v in body.values() if isinstance(v, dict))]
    if isinstance(body.get("row"), list) and body["row"]:
        candidates.append(body["row"][0])
    for c in candidates:
        r = c.get("RESULT") if isinstance(c, dict) else None
        if isinstance(r, dict):
            return r.get("CODE") or r.get("RESULT.CODE"), r.get("MESSAGE") or r.get("RESULT.MESSAGE") or ""
    return None, ""


def fetch_all(
    service: str,
    *args: str,
    key: str,
    client: httpx.Client | None = None,
    page_size: int = 1000,
    sleep: Callable[[float], None] = time.sleep,
) -> list[dict]:
    client = client or httpx.Client(timeout=30)
    rows: list[dict] = []
    start = 1
    while True:
        end = start + page_size - 1
        body = send(client, "GET", _url(key, service, start, end, args), secret=key, sleep=sleep).json()
        code, msg = _result(body)
        if code == "INFO-200":
            return rows
        if code != "INFO-000":
            raise SeoulApiError(code or "UNKNOWN", redact(msg, key))
        data = body[service]
        rows.extend(data.get("row", []))
        if end >= int(data["list_total_count"]):
            return rows
        start = end + 1


def fetch_one(
    service: str,
    *args: str,
    key: str,
    client: httpx.Client | None = None,
    sleep: Callable[[float], None] = time.sleep,
) -> dict:
    """페이지 없이 본문 통째로. 실시간 도시데이터처럼 서비스명 래퍼가 없는 응답용."""
    client = client or httpx.Client(timeout=30)
    body = send(client, "GET", _url(key, service, 1, 5, args), secret=key, sleep=sleep).json()
    code, msg = _result(body)
    if code is None:
        raise SeoulApiError("UNKNOWN", "RESULT 를 찾지 못함", body=body)
    if code != "INFO-000":
        raise SeoulApiError(code or "UNKNOWN", redact(msg, key))
    return body
