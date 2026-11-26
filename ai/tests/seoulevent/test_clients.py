import json
from urllib.parse import parse_qs

import httpx
import pytest

from weglee_ai.seoulevent import datagokr, seoul_api, seoul_file


def _client(handler) -> httpx.Client:
    return httpx.Client(transport=httpx.MockTransport(handler))


# ── seoul_api.fetch_all ────────────────────────────────────────


def test_fetch_all_pages_through_total_count():
    calls = []

    def handler(req: httpx.Request):
        start, end = map(int, req.url.path.split("/")[4:6])
        calls.append((start, end))
        rows = [{"i": i} for i in range(start, min(end, 2500) + 1)]
        return httpx.Response(200, json={"Svc": {"list_total_count": 2500, "RESULT": {"CODE": "INFO-000"}, "row": rows}})

    rows = seoul_api.fetch_all("Svc", "20241", key="K", client=_client(handler))
    assert calls == [(1, 1000), (1001, 2000), (2001, 3000)]
    assert len(rows) == 2500


def test_fetch_all_extra_args_in_path():
    seen = []

    def handler(req):
        seen.append(req.url.path)
        return httpx.Response(200, json={"Svc": {"list_total_count": 0, "RESULT": {"CODE": "INFO-000"}, "row": []}})

    seoul_api.fetch_all("Svc", "20241", key="K", client=_client(handler))
    assert seen == ["/K/json/Svc/1/1000/20241"]


def test_fetch_all_no_data_is_empty():
    def handler(req):
        return httpx.Response(200, json={"RESULT": {"CODE": "INFO-200", "MESSAGE": "해당하는 데이터가 없습니다."}})

    assert seoul_api.fetch_all("Svc", key="K", client=_client(handler)) == []


@pytest.mark.parametrize("code", ["INFO-100", "ERROR-500"])
def test_fetch_all_error_raises_without_key(code):
    def handler(req):
        return httpx.Response(200, json={"RESULT": {"CODE": code, "MESSAGE": "실패"}})

    with pytest.raises(seoul_api.SeoulApiError) as e:
        seoul_api.fetch_all("Svc", key="SECRET123", client=_client(handler))
    assert e.value.code == code
    assert "SECRET123" not in str(e.value)


def test_fetch_all_retries_connection_errors_1_2_4():
    fails = {"n": 3}
    slept = []

    def handler(req):
        if fails["n"]:
            fails["n"] -= 1
            raise httpx.ConnectError("down")
        return httpx.Response(200, json={"Svc": {"list_total_count": 1, "RESULT": {"CODE": "INFO-000"}, "row": [{}]}})

    assert len(seoul_api.fetch_all("Svc", key="K", client=_client(handler), sleep=slept.append)) == 1
    assert slept == [1, 2, 4]


def test_fetch_all_gives_up_after_three_retries_without_key():
    def handler(req):
        raise httpx.ConnectError("down")

    with pytest.raises(httpx.TransportError) as e:
        seoul_api.fetch_all("Svc", key="SECRET123", client=_client(handler), sleep=lambda s: None)
    assert "SECRET123" not in str(e.value)


# ── seoul_api.fetch_one ────────────────────────────────────────


@pytest.mark.parametrize(
    "body",
    [
        {"RESULT": {"RESULT.CODE": "INFO-000"}, "AREA_NM": "광화문·덕수궁", "LIVE_CMRCL_STTS": {}},
        {"row": [{"RESULT": {"CODE": "INFO-000"}, "AREA_NM": "광화문·덕수궁"}]},
    ],
)
def test_fetch_one_returns_body_as_is(body):
    def handler(req):
        assert req.url.path == "/K/json/citydata_cmrcl/1/5/POI001"
        return httpx.Response(200, json=body)

    assert seoul_api.fetch_one("citydata_cmrcl", "POI001", key="K", client=_client(handler)) == body


def test_fetch_one_error_raises():
    def handler(req):
        return httpx.Response(200, json={"RESULT": {"RESULT.CODE": "ERROR-500", "RESULT.MESSAGE": "서버 오류"}})

    with pytest.raises(seoul_api.SeoulApiError):
        seoul_api.fetch_one("citydata_cmrcl", "POI001", key="K", client=_client(handler))


# ── seoul_file.download ────────────────────────────────────────


def test_download_posts_ids_and_skips_existing(tmp_path):
    bodies = []

    def handler(req):
        bodies.append(parse_qs(req.content.decode()))
        return httpx.Response(200, content=b"XLSX")

    dest = tmp_path / "ref" / "places.xlsx"
    client = _client(handler)
    assert seoul_file.download("OA-22385", 8, 2, dest, client=client) == dest
    assert dest.read_bytes() == b"XLSX"
    assert bodies == [{"infId": ["OA-22385"], "seq": ["8"], "infSeq": ["2"]}]
    seoul_file.download("OA-22385", 8, 2, dest, client=client)
    assert len(bodies) == 1
    seoul_file.download("OA-22385", 8, 2, dest, client=client, force=True)
    assert len(bodies) == 2


def test_download_rejects_html_error_page(tmp_path):
    def handler(req):
        return httpx.Response(200, content=b"<html>error</html>", headers={"content-type": "text/html"})

    dest = tmp_path / "x.xlsx"
    with pytest.raises(seoul_file.DownloadError):
        seoul_file.download("OA-1", 1, 1, dest, client=_client(handler))
    assert not dest.exists()


# ── datagokr.get_items ─────────────────────────────────────────


def _dg(items, total, code="00"):
    return {"response": {"header": {"resultCode": code, "resultMsg": "OK"},
                         "body": {"items": items, "totalCount": total, "numOfRows": 100}}}


def test_get_items_single_dict_becomes_list():
    def handler(req):
        return httpx.Response(200, json=_dg({"item": {"locdate": 20261003}}, 1))

    assert datagokr.get_items("https://x/api", {}, key="K", client=_client(handler)) == [{"locdate": 20261003}]


def test_get_items_pages_and_sends_key():
    pages = []

    def handler(req):
        q = parse_qs(req.url.query.decode())
        pages.append(q["pageNo"][0])
        assert q["serviceKey"] == ["K"]
        n = 100 if q["pageNo"] == ["1"] else 50
        return httpx.Response(200, json=_dg({"item": [{}] * n}, 150))

    assert len(datagokr.get_items("https://x/api", {"numOfRows": 100}, key="K", client=_client(handler))) == 150
    assert pages == ["1", "2"]


def test_get_items_empty_string_items():
    def handler(req):
        return httpx.Response(200, json=_dg("", 0))

    assert datagokr.get_items("https://x/api", {}, key="K", client=_client(handler)) == []


def test_get_items_error_code_raises_without_key():
    def handler(req):
        return httpx.Response(200, json=_dg("", 0, code="30"))

    with pytest.raises(datagokr.DataGoKrError) as e:
        datagokr.get_items("https://x/api", {}, key="SECRET123", client=_client(handler))
    assert "SECRET123" not in str(e.value)


# ── 리뷰 수정 ─────────────────────────────────────────────────


def test_http_error_message_hides_url_encoded_key():
    def handler(req):
        return httpx.Response(500)

    key = "abc+def/ghi=="
    with pytest.raises(httpx.HTTPError) as e:
        datagokr.get_items("https://x/api", {}, key=key, client=_client(handler))
    msg = str(e.value)
    assert "abc%2Bdef" not in msg and "abc+def" not in msg and "serviceKey" not in msg


def test_fetch_one_unknown_result_keeps_body():
    body = {"SOMETHING": {"AREA_NM": "광화문·덕수궁"}}

    def handler(req):
        return httpx.Response(200, json=body)

    with pytest.raises(seoul_api.SeoulApiError) as e:
        seoul_api.fetch_one("citydata_cmrcl", "POI001", key="K", client=_client(handler))
    assert e.value.code == "UNKNOWN"
    assert e.value.body == body
