import httpx

from weglee_ai.seoulevent.check import diagnose


def _resp(url: str, json: dict | None = None) -> httpx.Response:
    return httpx.Response(200, json=json or {}, request=httpx.Request("GET", url))


def _fetch(seoul_body: dict | None, blocked: set[str] = frozenset()):
    def fetch(url: str) -> httpx.Response:
        for host in blocked:
            if host in url:
                raise httpx.ConnectTimeout("timeout")
        if "openapi.seoul.go.kr" in url:
            return _resp(url, seoul_body)
        return _resp(url)

    return fetch


def _line(report: str, label: str) -> str:
    return next(line for line in report.splitlines() if line.startswith(label))


def test_blocked_seoul_api_reported_as_network_block():
    report = diagnose(_fetch(None, blocked={"openapi.seoul.go.kr"}), key="sample")
    assert "차단" in _line(report, "서울 API")
    assert "정상" in _line(report, "서울 파일")
    assert "정상" in _line(report, "data.go.kr")


def test_bad_key_reported_separately_from_block():
    body = {"RESULT": {"CODE": "INFO-100", "MESSAGE": "인증키가 유효하지 않습니다."}}
    assert "인증키 오류" in _line(diagnose(_fetch(body), key="bad"), "서울 API")


def test_ok_seoul_api():
    body = {"culturalEventInfo": {"list_total_count": 1, "RESULT": {"CODE": "INFO-000"}, "row": [{}]}}
    assert "정상" in _line(diagnose(_fetch(body), key="sample"), "서울 API")


def test_key_never_printed():
    report = diagnose(_fetch(None, blocked={"openapi.seoul.go.kr", "datafile", "apis.data.go.kr"}), key="SECRET123")
    assert "SECRET123" not in report
