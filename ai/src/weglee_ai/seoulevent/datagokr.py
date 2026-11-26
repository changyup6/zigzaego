"""공공데이터포털 (apis.data.go.kr). 교내망에서도 된다."""

import time
from collections.abc import Callable

import httpx

from weglee_ai.seoulevent._http import redact, send


class DataGoKrError(Exception):
    pass


def get_items(
    url: str,
    params: dict,
    key: str,
    client: httpx.Client | None = None,
    sleep: Callable[[float], None] = time.sleep,
) -> list[dict]:
    client = client or httpx.Client(timeout=30)
    rows_per_page = int(params.get("numOfRows", 100))
    items: list[dict] = []
    page = 1
    while True:
        query = {**params, "numOfRows": rows_per_page, "pageNo": page, "serviceKey": key}
        res = send(client, "GET", url, params=query, secret=key, sleep=sleep).json()["response"]
        header = res["header"]
        if header.get("resultCode") != "00":
            raise DataGoKrError(f"{header.get('resultCode')}: {redact(str(header.get('resultMsg')), key)}")
        body = res["body"]
        got = (body.get("items") or {}).get("item") or [] if body.get("items") else []
        items.extend([got] if isinstance(got, dict) else got)
        if page * rows_per_page >= int(body.get("totalCount") or 0):
            return items
        page += 1
