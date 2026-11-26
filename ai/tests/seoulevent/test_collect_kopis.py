from datetime import date

import pytest

from weglee_ai.seoulevent import collect_kopis as ck

LIST_XML = """<?xml version="1.0" encoding="UTF-8"?><dbs>
<db><mt20id>PF1</mt20id><prfnm>콘서트 A</prfnm><prfpdfrom>2024.10.05</prfpdfrom><prfpdto>2024.10.06</prfpdto>
<fcltynm>KT&amp;G 상상마당 라이브홀 [마포]</fcltynm><area>서울특별시</area><genrenm>대중음악</genrenm><openrun>N</openrun><prfstate>공연완료</prfstate></db>
<db><mt20id>PF2</mt20id><prfnm>B</prfnm><prfpdfrom>2024.10.10</prfpdfrom><prfpdto>2024.10.10</prfpdto>
<fcltynm>고척스카이돔</fcltynm><area>서울특별시</area><genrenm>대중음악</genrenm><openrun>N</openrun><prfstate>공연완료</prfstate></db>
</dbs>"""

FACILITY_XML = """<?xml version="1.0" encoding="UTF-8"?><dbs><db><fcltynm>고척스카이돔</fcltynm><mt10id>FC001901</mt10id>
<mt13cnt>1</mt13cnt><fcltychartr>공공(기타)</fcltychartr><seatscale>16744</seatscale><adres>서울 구로구</adres>
<la>37.498415000000000</la><lo>126.86721900000000</lo><mt13s><mt13><prfplcnm>고척스카이돔</prfplcnm><mt13id>FC001901-01</mt13id><seatscale>16,744</seatscale></mt13></mt13s></db></dbs>"""


def test_parse_list():
    rows = ck.parse_list(LIST_XML)
    assert len(rows) == 2
    assert rows[0] == {"mt20id": "PF1", "prfnm": "콘서트 A", "start_date": date(2024, 10, 5), "end_date": date(2024, 10, 6),
                       "fcltynm": "KT&G 상상마당 라이브홀 [마포]", "genrenm": "대중음악", "openrun": "N", "prfstate": "공연완료"}


def test_parse_facility():
    f = ck.parse_facility(FACILITY_XML)
    assert (f["mt10id"], f["fcltynm"], f["seats"], f["lat"], f["lon"]) == ("FC001901", "고척스카이돔", 16744, 37.498415, 126.867219)


def test_fetch_pages_until_short_page_and_error_hides_key():
    calls = []

    def get(url, params):
        calls.append(params["cpage"])
        n = 100 if params["cpage"] == 1 else 3
        return "<dbs>" + "<db><mt20id>x</mt20id></db>" * n + "</dbs>"

    assert len(ck.fetch_pages(get, "u", {})) == 103 and calls == [1, 2]

    def bad(url, params):
        return f"<dbs><db><returncode>02</returncode><errmsg>키 {params['service']} 오류</errmsg></db></dbs>"

    with pytest.raises(ck.KopisError) as e:
        ck.fetch_pages(lambda u, p: bad(u, {**p, "service": "SECRET123"}), "u", {}, key="SECRET123")
    assert "SECRET123" not in str(e.value)


@pytest.mark.parametrize("name,expected", [
    ("고척스카이돔", "FC001901"),
    ("세종문화회관 대극장", "FC000020"),      # 홀 이름이 붙어도 시설 앞부분으로
    ("블루 스퀘어", "FC000031"),              # 공백 차이
    ("어디에도 없는 홀", None),
])
def test_match_venue(name, expected):
    facilities = {"고척스카이돔": "FC001901", "세종문화회관": "FC000020", "블루스퀘어": "FC000031", "세종": "FCXXX"}
    assert ck.match_venue(name, facilities) == expected


def test_non_xml_response_is_error_not_last_page():
    def get(url, params):
        return "<html>Service Unavailable</html>"

    with pytest.raises(ck.KopisError):
        ck.fetch_pages(get, "u", {})


def test_facility_error_response_skipped():
    assert ck.parse_facility("<dbs><db><returncode>99</returncode><errmsg>x</errmsg></db></dbs>") is None


def test_facility_max_hall_seats_not_total():
    xml = """<dbs><db><fcltynm>예술의전당 [서울]</fcltynm><mt10id>FC000001</mt10id><seatscale>7000</seatscale><la>37.4</la><lo>127.0</lo>
    <mt13s><mt13><prfplcnm>오페라극장</prfplcnm><seatscale>2,283</seatscale></mt13><mt13><prfplcnm>자유소극장</prfplcnm><seatscale>241</seatscale></mt13></mt13s></db></dbs>"""
    f = ck.parse_facility(xml)
    assert f["seats"] == 7000 and f["max_hall_seats"] == 2283 and f["n_halls"] == 2
