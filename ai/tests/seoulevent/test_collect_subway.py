from datetime import date
from urllib.parse import parse_qs

import httpx
import numpy as np
import pandas as pd
import pytest

from weglee_ai.seoulevent import collect_subway as cs
from weglee_ai.seoulevent import collect_weather, seoul_file

PAGE = """
<form name="frmFile" id="frmFile"><input type="hidden" name="infSeq" value="3"/></form>
<span onclick="javascript:downloadFile('110');" title="CARD_SUBWAY_MONTH_2019.csv">x</span>
<span onclick="javascript:downloadFile('158');" title="CARD_SUBWAY_MONTH_202609.csv">x</span>
"""


def test_list_files_reads_titles_and_inf_seq():
    client = httpx.Client(transport=httpx.MockTransport(lambda r: httpx.Response(200, text=PAGE)))
    inf_seq, files = seoul_file.list_files("OA-12914", client=client)
    assert inf_seq == 3
    assert files == {"CARD_SUBWAY_MONTH_2019.csv": 110, "CARD_SUBWAY_MONTH_202609.csv": 158}


def test_pick_files_since_year():
    files = {"CARD_SUBWAY_MONTH_2018.csv": 109, "CARD_SUBWAY_MONTH_2019.csv": 110, "CARD_SUBWAY_MONTH_202609.csv": 158}
    assert cs.pick_files(files, since_year=2019) == {"CARD_SUBWAY_MONTH_2019.csv": 110, "CARD_SUBWAY_MONTH_202609.csv": 158}


def test_read_old_cp949_float_format(tmp_path):
    p = tmp_path / "old.csv"
    p.write_bytes('"사용일자","노선명","역명",승차총승객수,하차총승객수,"등록일자"\n'
                  '"20190101","2호선","을지로4가","3862.0","3728.0","20190104"\n'.encode("cp949"))
    df = cs.read_csv_file(p)
    assert df.to_dict("records") == [{"date": date(2019, 1, 1), "line": "2호선", "station": "을지로4가", "board": 3862, "alight": 3728}]


def test_read_new_utf8_with_trailing_column(tmp_path):
    p = tmp_path / "new.csv"
    p.write_bytes('"사용일자","노선명","역명","승차총승객수","하차총승객수","등록일자"\n'
                  '"20260901","1호선","서울역","85495","82269","20260904",""\n'.encode("utf-8-sig"))
    df = cs.read_csv_file(p)
    assert df.iloc[0]["station"] == "서울역" and df.iloc[0]["board"] == 85495


def test_load_dedupes_same_day_line_station(tmp_path):
    for name, n in [("a.csv", "100"), ("b.csv", "200")]:
        (tmp_path / name).write_text('"사용일자","노선명","역명","승차총승객수","하차총승객수","등록일자"\n'
                                     f'"20260901","1호선","서울역","{n}","1","20260904"\n', encoding="utf-8-sig")
    df = cs.load(tmp_path)
    assert len(df) == 1 and df.iloc[0]["board"] == 200


@pytest.mark.parametrize("raw", ["서울역", "서울역(1)", "서울(경부선)", " 서울 "])
def test_normalize_station(raw):
    assert cs.normalize_station(raw) == "서울"


def test_normalize_keeps_leading_yeok_and_applies_alias():
    assert cs.normalize_station("역삼") == "역삼"
    assert cs.normalize_station("총신대입구(이수)", {"총신대입구": "이수"}) == "이수"


def test_stations_from_sheet_csv(tmp_path):
    p = tmp_path / "s.csv"
    p.write_bytes('"역사_ID","역사명","호선","위도","경도"\n"4201","서울역","수도권 광역철도 1호선","37.553247","126.969769"\n'
                  '"9999","좌표없음","1호선","",""\n'.encode("cp949"))
    st = cs.stations_from_sheet(p)
    assert st.to_dict("records") == [{"station": "서울", "line": "수도권 광역철도 1호선", "lat": 37.553247, "lon": 126.969769}]


def test_download_sheet_posts_form(tmp_path):
    seen = {}

    def handler(req):
        seen["url"] = str(req.url)
        seen["body"] = parse_qs(req.content.decode())
        return httpx.Response(200, content="역사명\n".encode("cp949"), headers={"content-type": "application/octet-stream"})

    dest = seoul_file.download_sheet("OA-21232", tmp_path / "s.csv", client=httpx.Client(transport=httpx.MockTransport(handler)))
    assert seen["url"].endswith("/bigfile/iot/sheet/csv/download.do")
    assert seen["body"]["infId"] == ["OA-21232"] and seen["body"]["srvType"] == ["S"]
    assert dest.read_bytes() == "역사명\n".encode("cp949")


def test_ambiguous_name_line_match_ignores_spaces():
    stations = pd.DataFrame({"station": ["양평", "양평"], "line": ["5호선", "공항철도1호선"],
                             "lat": [37.5257, 37.4927], "lon": [126.8862, 127.4917]})
    daily = pd.DataFrame({"date": [date(2026, 9, 1)], "line": ["공항철도 1호선"], "station": ["양평"], "board": [1], "alight": [1]})
    out, _ = cs.attach_coords(daily, stations)
    assert out["lat"].iloc[0] == 37.4927


def test_attach_coords_reports_unmatched():
    daily = pd.DataFrame({"date": [date(2026, 9, 1)] * 2, "line": ["1호선", "9호선"], "station": ["서울역", "없는역"],
                          "board": [1, 2], "alight": [1, 2]})
    stations = pd.DataFrame({"station": ["서울"], "line": ["1호선"], "lat": [37.55], "lon": [126.97]})
    out, unmatched = cs.attach_coords(daily, stations)
    assert out.loc[out.station == "서울역", "lat"].iloc[0] == 37.55
    assert unmatched == ["없는역"]


def test_weather_chunks_by_year_and_fills_rain():
    calls = []

    def get_items(url, params):
        calls.append((params["startDt"], params["endDt"]))
        return [{"tm": "2019-01-01", "avgTa": "-3.1", "minTa": "-8", "maxTa": "1.2", "sumRn": "", "avgWs": "2.1"}]

    df = collect_weather.fetch(date(2019, 1, 1), date(2020, 6, 30), get_items)
    assert calls == [("20190101", "20191231"), ("20200101", "20200630")]
    assert df.iloc[0].to_dict() == {"date": date(2019, 1, 1), "avg_ta": -3.1, "min_ta": -8.0, "max_ta": 1.2, "sum_rn": 0.0, "avg_ws": 2.1}


# ── 리뷰 수정 ─────────────────────────────────────────────────


def test_same_name_far_apart_stations_matched_by_line():
    stations = pd.DataFrame({"station": ["양평", "양평", "서울"], "line": ["5호선", "중앙선", "1호선"],
                             "lat": [37.5257, 37.4927, 37.5547], "lon": [126.8862, 127.4917, 126.9726]})
    daily = pd.DataFrame({"date": [date(2026, 9, 1)] * 4, "line": ["5호선", "중앙선", "경의중앙선", "4호선"],
                          "station": ["양평", "양평", "양평", "서울역"], "board": [1] * 4, "alight": [1] * 4})
    out, unmatched = cs.attach_coords(daily, stations)
    assert out["lat"].tolist()[:2] == [37.5257, 37.4927]
    assert np.isnan(out["lat"].iloc[2])  # 이름이 겹치는 먼 역인데 노선이 안 맞으면 추측하지 않음
    assert out["lat"].iloc[3] == 37.5547  # 이름이 하나뿐인 역은 노선이 달라도 이름으로
    assert unmatched == ["양평"]


def test_load_prefers_monthly_file_over_recent_sheet(tmp_path):
    hdr = "사용일자,호선명,역명,승차총승객수,하차총승객수,등록일자\n"
    (tmp_path / f"{cs.RECENT_PREFIX}20261109.csv").write_text(hdr + "20260930,2호선,강남,1,1,x\n20261105,2호선,강남,7,7,x\n", encoding="utf-8-sig")
    (tmp_path / "CARD_SUBWAY_MONTH_202609.csv").write_text(hdr + "20260930,2호선,강남,5,5,x\n", encoding="utf-8-sig")
    d = cs.load(tmp_path).set_index("date")["board"]
    assert d[date(2026, 9, 30)] == 5  # 겹치는 날은 확정된 월 파일
    assert d[date(2026, 11, 5)] == 7  # 월 파일에 없는 최근 날은 시트에서
