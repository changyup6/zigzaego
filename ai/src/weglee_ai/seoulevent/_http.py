"""세 클라이언트가 함께 쓰는 재시도 · 키 가리기."""

import time
from collections.abc import Callable
from urllib.parse import quote, quote_plus

import httpx

RETRY_DELAYS = (1, 2, 4)


def send(
    client: httpx.Client,
    method: str,
    url: str,
    *,
    secret: str = "",
    sleep: Callable[[float], None] = time.sleep,
    **kwargs,
) -> httpx.Response:
    """연결 오류는 1 · 2 · 4초 뒤 다시 시도. 끝내 실패하면 키를 지운 메시지로 다시 던진다."""
    for delay in (*RETRY_DELAYS, None):
        try:
            resp = client.request(method, url, **kwargs)
        except httpx.TransportError as e:
            if delay is None:
                raise type(e)(redact(str(e), secret)) from None
            sleep(delay)
            continue
        if resp.status_code >= 400:
            # 쿼리에 인코딩된 키가 들어 있으므로 URL 은 host · path 만 남긴다
            raise httpx.HTTPError(f"HTTP {resp.status_code}: {redact(str(resp.url.copy_with(query=None)), secret)}")
        return resp
    raise AssertionError("unreachable")


def redact(text: str, secret: str) -> str:
    if not secret:
        return text
    for form in {secret, quote(secret, safe=""), quote_plus(secret)}:
        text = text.replace(form, "***")
    return text
