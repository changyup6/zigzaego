"""OCR 실행기 공통 HTTP. 표준 urllib 만 쓴다. 지라 S15P31A308-109.

post(url, headers, data: bytes) -> (상태 코드, 본문 str) 는 주입할 수 있다(테스트·dry-run).
"""

import json
import urllib.error
import urllib.request

TIMEOUT = 120


class ApiError(RuntimeError):
    """API 가 실패 응답을 줬거나 재시도를 다 썼다."""


def urllib_post(url: str, headers: dict, data: bytes) -> tuple[int, str]:
    """기본 post. HTTP 오류 코드도 (코드, 본문) 으로 돌려준다. 연결 오류는 OSError 로 올라간다."""
    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def post_json(post, url, headers, body) -> tuple[int, str]:
    data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    return (post or urllib_post)(url, {"Content-Type": "application/json; charset=utf-8", **headers}, data)


def is_timeout(e: BaseException) -> bool:
    """시간 초과(TimeoutError·socket.timeout, urllib 이 URLError 로 감싼 것 포함)."""
    return isinstance(e, TimeoutError) or (isinstance(e, urllib.error.URLError)
                                           and isinstance(getattr(e, "reason", None), TimeoutError))
