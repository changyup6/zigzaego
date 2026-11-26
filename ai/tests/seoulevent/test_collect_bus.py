from datetime import date

import pandas as pd

from weglee_ai.seoulevent import collect_bus as cb

CSV = ("사용일자,노선번호,노선명,표준버스정류장ID,버스정류장ARS번호,역명,승차총승객수,하차총승객수,등록일자\n"
       "20261106,N37,N37번,100000001,01001,종로2가,23,15,20261109\n"
       "20261106,470,470번,100000001,01001,종로2가,336,354,20261109\n"
       "20261106,470,470번,100000002,01002,종로3가,5,6,20261109\n")


def test_aggregate_sums_routes_per_stop_and_day():
    g = cb.aggregate(CSV.encode("utf-8-sig")).set_index("stop_id")
    assert g.loc["100000001", "board"] == 359 and g.loc["100000001", "alight"] == 369
    assert g.loc["100000002", "date"] == date(2026, 11, 6)
    assert cb.aggregate(CSV.replace("표준버스정류장ID", "표준 버스정류장 ID").encode("cp949")).shape[0] == 2  # 열 이름 · 인코딩 달라도
    old = "사용일자,노선번호,노선명,버스정류장ARS번호,역명,승차총승객수,하차총승객수,등록일자\n20220105,470,470번,1001,종로2가,7,8,20220108\n20220105,470,470번,99999,없어진곳,1,1,20220108\n"
    g = cb.aggregate(old.encode("cp949"), {"01001": "100000001"})  # 2022 까지: ARS 번호만 → 표준ID 로, 짝 없는 정류장은 빠짐
    assert g["stop_id"].tolist() == ["100000001"] and g["board"].tolist() == [7]
    eng = ("stnd_bsst_id,use_dt,bus_route_no,bus_route_nm,bsst_ars_no,bus_sta_nm,ride_pasgr_num,alight_pasgr_num,work_dt\n"
           "20200501,100,100번,110000327,11428,한성여객종점,8,1,20200504\n")  # 2020-05 ~ 07: 영어 머리글이 실제 순서와 다름 → 위치로
    r = cb.aggregate(eng.encode("utf-8")).iloc[0]
    assert r["date"] == date(2020, 5, 1) and [r["stop_id"], r["board"], r["alight"]] == ["110000327", 8, 1]


def test_merge_days_prefers_monthly_file(tmp_path):
    rec = pd.DataFrame({"date": [date(2026, 10, 31), date(2026, 11, 6)], "stop_id": ["A", "A"], "board": [1, 7], "alight": [1, 7]})
    mon = pd.DataFrame({"date": [date(2026, 10, 31)], "stop_id": ["A"], "board": [5], "alight": [5]})
    rec.to_parquet(tmp_path / f"{cb.RECENT_PREFIX}20261109.parquet")
    mon.to_parquet(tmp_path / "202610.parquet")
    d = cb.merge_days(tmp_path).set_index("date")["board"]
    assert d[date(2026, 10, 31)] == 5 and d[date(2026, 11, 6)] == 7


def test_stops_and_pick_files():
    raw = "노드 ID,정류소번호,정류소명,X좌표,Y좌표,정류소 타입\n1,100000001,종로2가,126.98,37.57,중앙차로\n2,100000009,좌표없음,,,가로변\n"
    s = cb.stops_from_sheet(raw.encode("utf-8-sig"))
    assert s["stop_id"].tolist() == ["100000001"] and s.loc[0, "lat"] == 37.57
    files = {"BUS_STATION_BOARDING_MONTH_2017.zip": 1, "BUS_STATION_BOARDING_MONTH_2018.zip": 2, "BUS_STATION_BOARDING_MONTH_202610.csv": 3, "좌표.xlsx": 4}
    assert list(cb.pick_files(files)) == ["BUS_STATION_BOARDING_MONTH_2018.zip", "BUS_STATION_BOARDING_MONTH_202610.csv"]
    assert cb.months_of("BUS_STATION_BOARDING_MONTH_2018.zip")[0] == "201801" and cb.months_of("BUS_STATION_BOARDING_MONTH_202610.csv") == ["202610"]


def test_save_months_splits_by_month(tmp_path):
    g = pd.DataFrame({"date": [date(2022, 1, 31), date(2022, 2, 1)], "stop_id": ["A", "A"], "board": [1, 2], "alight": [1, 2]})
    assert cb.save_months(g, tmp_path) == ["202201", "202202"] and (tmp_path / "202202.parquet").exists()
