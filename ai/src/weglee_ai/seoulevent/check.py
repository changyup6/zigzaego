"""세 호스트 접속 확인: `python -m weglee_ai.seoulevent.check`"""

from collections.abc import Callable

import httpx

from weglee_ai.seoulevent import config

SEOUL_API = "http://openapi.seoul.go.kr:8088/{key}/json/culturalEventInfo/1/1"
SEOUL_FILE = "https://datafile.seoul.go.kr"
DATA_GO_KR = "https://apis.data.go.kr"


def _seoul_code(body: dict) -> str | None:
    result = body.get("RESULT") or next(
        (v.get("RESULT") for v in body.values() if isinstance(v, dict)), None
    )
    return (result or {}).get("CODE")


def diagnose(fetch: Callable[[str], httpx.Response], key: str) -> str:
    lines = []
    try:
        code = _seoul_code(fetch(SEOUL_API.format(key=key)).json())
        if code == "INFO-000":
            lines.append("서울 API: 정상")
        elif code == "INFO-100":
            lines.append("서울 API: 인증키 오류")
        else:
            lines.append(f"서울 API: 응답 이상 ({code})")
    except httpx.TransportError:
        lines.append("서울 API: 차단 (openapi.seoul.go.kr:8088 에 연결 안 됨)")
    for label, url in (("서울 파일", SEOUL_FILE), ("data.go.kr", DATA_GO_KR)):
        try:
            fetch(url)
            lines.append(f"{label}: 정상")
        except httpx.TransportError:
            lines.append(f"{label}: 차단 ({url})")
    return "\n".join(lines)


def main() -> None:
    cfg = config.load()
    with httpx.Client(timeout=10) as client:
        print(diagnose(client.get, key=cfg.seoul_key or "sample"))


if __name__ == "__main__":
    main()
