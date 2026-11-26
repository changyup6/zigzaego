from datetime import date

import pandas as pd
import pytest

from weglee_ai.seoulevent.build import events, geo

LAT0, LON0 = 37.5720, 126.9746  # 광화문역 상권 중심 부근
M_PER_DEG_LAT = 111_000


def _areas():
    x, y = geo.to_5181(LAT0, LON0)
    return pd.DataFrame({"TRDAR_CD": ["3110001"], "TRDAR_CD_NM": ["광화문역"], "x": [x], "y": [y]})


def _culture(title, lot, lat, start="2026-11-01 00:00:00.0", end="2026-11-03 00:00:00.0", collected="2026-11-02"):
    return {"TITLE": title, "CODENAME": "축제-문화/예술", "GUNAME": "종로구", "PLACE": "광화문광장",
            "STRTDATE": start, "END_DATE": end, "LOT": lot, "LAT": lat, "collected_at": collected}


EMPTY_HOLIDAYS = pd.DataFrame(columns=["date", "name", "is_holiday"])
EMPTY_MANUAL = pd.DataFrame(columns=["date_start", "date_end", "kind", "name", "venue", "lat", "lon", "source_url"])


def test_fix_latlon_both_orders_and_invalid():
    assert geo.fix_latlon(37.51, 127.05) == (37.51, 127.05)
    assert geo.fix_latlon(127.05, 37.51) == (37.51, 127.05)
    assert geo.fix_latlon(0, 0) is None
    assert geo.fix_latlon(None, "") is None


def test_nearest_district_within_radius():
    areas = _areas()
    x, y = geo.to_5181(LAT0 + 300 / M_PER_DEG_LAT, LON0)
    assert geo.nearest_district(x, y, areas, radius_m=500) == "3110001"
    x, y = geo.to_5181(LAT0 + 800 / M_PER_DEG_LAT, LON0)
    assert geo.nearest_district(x, y, areas, radius_m=500) is None


def _build(cultural=None, holidays=EMPTY_HOLIDAYS, manual=EMPTY_MANUAL):
    return events.build_events(pd.DataFrame(cultural or [], columns=list(_culture("", "", "").keys())), holidays, manual, _areas())


def test_culture_event_mapped_with_swapped_coords():
    # 실제 응답처럼 LOT 에 위도, LAT 에 경도
    out = _build([_culture("축제", str(LAT0), str(LON0))])
    row = out.iloc[0]
    assert (row["source"], row["kind"], row["name"], row["gu"]) == ("culture", "축제-문화/예술", "축제", "종로구")
    assert (row["start_date"], row["end_date"]) == (date(2026, 11, 1), date(2026, 11, 3))
    assert row["trdar_cd"] == "3110001"
    assert row["lat"] == pytest.approx(LAT0)


def test_bad_coords_kept_without_district():
    out = _build([_culture("좌표없음", "0", "0")])
    assert len(out) == 1 and pd.isna(out.iloc[0]["trdar_cd"])


def test_same_event_from_two_collect_days_once():
    out = _build([_culture("축제", str(LAT0), str(LON0), collected="2026-11-01"),
                  _culture("축제", str(LAT0), str(LON0), collected="2026-11-02")])
    assert len(out) == 1


def test_holiday_and_manual_sources():
    holidays = pd.DataFrame({"date": [date(2026, 10, 3)], "name": ["개천절"], "is_holiday": [True]})
    manual = pd.DataFrame({"date_start": ["2026-10-01"], "date_end": ["2026-10-02"], "kind": ["sports"], "name": ["경기"],
                           "venue": ["잠실야구장"], "lat": [LAT0], "lon": [LON0], "source_url": ["x"]})
    out = _build(holidays=holidays, manual=manual).set_index("source")
    assert out.loc["holiday", "start_date"] == out.loc["holiday", "end_date"] == date(2026, 10, 3)
    assert pd.isna(out.loc["holiday", "trdar_cd"])
    assert out.loc["manual", "kind"] == "sports" and out.loc["manual", "trdar_cd"] == "3110001"
    assert out["event_id"].is_unique


# ── 리뷰 수정 ─────────────────────────────────────────────────


def test_missing_end_date_uses_start_and_bad_rows_dropped():
    manual = pd.DataFrame({"date_start": ["2026-10-01", "", "2026-10-05"], "date_end": ["", "2026-10-02", "2026-10-03"],
                           "kind": ["sports"] * 3, "name": ["하루", "시작없음", "거꾸로"], "venue": ["v"] * 3,
                           "lat": [LAT0] * 3, "lon": [LON0] * 3, "source_url": ["x"] * 3})
    out = _build([_culture("종료없음", str(LAT0), str(LON0), end="")], manual=manual)
    assert set(out["name"]) == {"하루", "종료없음"}
    assert (out["start_date"] == out["end_date"]).all()
    assert out.attrs["dropped"] == 2


def test_same_day_holidays_unique_ids_and_non_holiday_excluded():
    holidays = pd.DataFrame({"date": [date(2025, 5, 5)] * 2 + [date(2025, 5, 8)],
                             "name": ["어린이날", "부처님오신날", "어버이날"], "is_holiday": [True, True, False]})
    out = _build(holidays=holidays)
    assert len(out) == 2 and out["event_id"].is_unique


def test_place_kept_for_venue_effects():
    manual = pd.DataFrame({"date_start": ["2026-10-01"], "date_end": ["2026-10-01"], "kind": ["sports"], "name": ["경기"],
                           "venue": ["잠실야구장"], "lat": [LAT0], "lon": [LON0], "source_url": ["x"]})
    out = _build([_culture("축제", str(LAT0), str(LON0))], manual=manual).set_index("source")
    assert out.loc["culture", "place"] == "광화문광장" and out.loc["manual", "place"] == "잠실야구장"


def test_kopis_performances_with_seats():
    kopis = pd.DataFrame({"mt20id": ["PF1"], "prfnm": ["대형 콘서트"], "start_date": [date(2026, 11, 21)], "end_date": [date(2026, 11, 22)],
                          "fcltynm": ["체조경기장"], "genrenm": ["대중음악"], "lat": [LAT0], "lon": [LON0], "seats": [15000]})
    out = events.build_events(pd.DataFrame(columns=list(_culture("", "", "").keys())), EMPTY_HOLIDAYS, EMPTY_MANUAL, _areas(), kopis=kopis)
    r = out.iloc[0]
    assert (r["source"], r["kind"], r["place"], r["seats"], r["trdar_cd"]) == ("kopis", "대중음악", "체조경기장", 15000, "3110001")
    assert pd.isna(_build([_culture("축제", str(LAT0), str(LON0))]).iloc[0]["seats"])


def test_culture_duplicate_of_kopis_performance_dropped():
    # ChatGPT 25: 문화행사 · KOPIS 에 같은 공연이 따로 올라 행사 수가 부풀었다 — 좌석 · 인기가 있는 KOPIS 쪽만 남긴다
    kopis = pd.DataFrame({"mt20id": ["PF1"], "prfnm": ["CHANGMO: MONOLITH"], "start_date": [date(2026, 11, 13)], "end_date": [date(2026, 11, 15)],
                          "fcltynm": ["세종문화회관"], "genrenm": ["대중음악"], "lat": [LAT0], "lon": [LON0], "seats": [3000]})
    cul = [_culture("[세종문화회관] CHANGMO : MONOLITH", str(LAT0), str(LON0), "2026-11-13 00:00:00.0", "2026-11-15 00:00:00.0"),
           _culture("CHANGMO MONOLITH", str(LAT0 + 0.05), str(LON0), "2026-11-13 00:00:00.0", "2026-11-13 00:00:00.0"),  # 5km 떨어짐 → 다른 행사
           _culture("다른 축제", str(LAT0), str(LON0), "2026-11-13 00:00:00.0", "2026-11-13 00:00:00.0")]
    out = events.build_events(pd.DataFrame(cul), EMPTY_HOLIDAYS, EMPTY_MANUAL, _areas(), kopis=kopis)
    assert sorted(out.drop_duplicates("event_id")["name"]) == ["CHANGMO MONOLITH", "CHANGMO: MONOLITH", "다른 축제"]


def test_event_ids_unique_for_doubleheader_and_hangang_session():
    # ChatGPT 25: KBO 더블헤더 두 경기 · 한강 긴 행사의 대표 행과 첫 회차가 같은 ID 를 받았다
    kbo = pd.DataFrame({"date": [date(2024, 5, 4)] * 2, "time": ["14:00", "18:00"], "away": ["한화"] * 2, "home": ["LG"] * 2,
                        "series": ["정규"] * 2, "note": ["-"] * 2})
    hg = pd.DataFrame({"evnt_sn": [59, 59], "title": ["드론 라이트쇼 축제", "드론 라이트쇼 축제 (04-29 회차)"], "start": ["2023-04-29"] * 2,
                       "end": ["2023-05-07", "2023-04-29"], "park": ["뚝섬한강공원"] * 2, "lat": [LAT0] * 2, "lon": [LON0] * 2,
                       "collected_at": ["2026-11-03"] * 2})
    out = events.build_events(pd.DataFrame(columns=list(_culture("", "", "").keys())), EMPTY_HOLIDAYS, EMPTY_MANUAL, _areas(), kbo=kbo, hangang=hg)
    assert out.groupby("source")["event_id"].nunique().to_dict() == {"hangang": 2, "kbo": 2}


def test_kleague_games_as_events():
    games = pd.DataFrame({"date": [date(2026, 11, 7)], "home": ["서울"], "away": ["전북"], "field_full": ["서울 월드컵 경기장"],
                          "lat": [LAT0], "lon": [LON0], "expected_audience": [18000.0]})
    out = events.build_events(pd.DataFrame(columns=list(_culture("", "", "").keys())), EMPTY_HOLIDAYS, EMPTY_MANUAL, _areas(), kleague=games)
    r = out.iloc[0]
    assert (r["source"], r["kind"], r["name"], r["seats"], r["trdar_cd"]) == ("kleague", "축구", "서울 vs 전북", 18000.0, "3110001")


def test_stadium_events_keep_category_in_place():
    st = pd.DataFrame({"date": [date(2025, 10, 10), date(2025, 8, 8)], "collected_at": ["2026-11-03"] * 2,
                       "text": ["경 기 명 : 남자축구 국가대표팀 친선경기 브라질전", "[2025 K리그1] 서울 vs 대구"]})
    out = events.build_events(pd.DataFrame(columns=list(_culture("", "", "").keys())), EMPTY_HOLIDAYS, EMPTY_MANUAL, _areas(), stadium=st)
    assert len(out) == 1                                              # K리그는 collect_kleague 쪽
    r = out.iloc[0]
    assert (r["source"], r["kind"], r["place"], r["seats"]) == ("stadium", "축구", "서울월드컵경기장 국가대표", 66704)


def test_kfa_fills_seoul_national_matches_missing_from_stadium_calendar():
    st = pd.DataFrame({"date": [date(2025, 10, 10)], "collected_at": ["2026-11-03"], "site": ["worldcupst"],
                       "text": ["경 기 명 : 남자축구 국가대표팀 친선경기 브라질전"]})
    kfa = pd.DataFrame({"date": [date(2025, 10, 10), date(2023, 11, 16), date(2024, 8, 28), date(2024, 9, 1)],
                        "team": ["남자 국가대표팀", "남자 국가대표팀", "남자 U-19 대표팀", "남자 국가대표팀"],
                        "opponent": ["브라질", "싱가포르", "태국", "일본"],
                        "venue": ["서울월드컵경기장", "서울월드컵경기장", "목동종합운동장", "수원월드컵경기장"], "collected_at": ["2026-11-09"] * 4})
    out = events.build_events(pd.DataFrame(columns=list(_culture("", "", "").keys())), EMPTY_HOLIDAYS, EMPTY_MANUAL, _areas(), stadium=st, kfa=kfa)
    k = out[out["source"] == "kfa"]
    # 브라질전은 달력에 있어 빼고(중복), 수원은 서울 밖이라 버린다
    assert sorted(zip(k["start_date"], k["place"], k["seats"])) == [(date(2023, 11, 16), "서울월드컵경기장 국가대표", 66704),
                                                                     (date(2024, 8, 28), "목동종합운동장 국가대표", 15000)]
    assert out.attrs["kfa_dups"] == 1
    assert events.SITES["worldcupst"] + " 국가대표" == "서울월드컵경기장 국가대표"  # 달력 장소와 같은 이름이라 과거 반응을 함께 배운다


def test_hangang_events_grouped_by_park():
    hg = pd.DataFrame({"evnt_sn": [339], "title": ["가을 음악회 공연"], "start": ["2025-10-18"], "end": ["2025-10-19"],
                       "park": ["반포한강공원"], "lat": [LAT0], "lon": [LON0], "collected_at": ["2026-11-03"]})
    out = events.build_events(pd.DataFrame(columns=list(_culture("", "", "").keys())), EMPTY_HOLIDAYS, EMPTY_MANUAL, _areas(), hangang=hg)
    r = out.iloc[0]
    assert (r["source"], r["kind"], r["place"], r["trdar_cd"], r["end_date"]) == ("hangang", "축제-한강", "반포한강공원", "3110001", date(2025, 10, 19))


def test_big_venue_events_reach_every_district_within_1km():
    kopis = pd.DataFrame({"mt20id": ["PF1", "PF2"], "prfnm": ["대형 콘서트", "소극장"], "start_date": [date(2026, 11, 21)] * 2,
                          "end_date": [date(2026, 11, 21)] * 2, "fcltynm": ["체조경기장", "소극장"], "genrenm": ["대중음악"] * 2,
                          "lat": [LAT0] * 2, "lon": [LON0] * 2, "seats": [15000, 300]})
    x, y = geo.to_5181(LAT0, LON0)
    areas = pd.DataFrame({"TRDAR_CD": ["A", "B", "C"], "TRDAR_CD_NM": ["at", "800m", "1500m"], "x": [x, x + 800, x + 1500], "y": [y] * 3})
    out = events.build_events(pd.DataFrame(columns=list(_culture("", "", "").keys())), EMPTY_HOLIDAYS, EMPTY_MANUAL, areas, kopis=kopis)
    assert out.loc[out["name"] == "대형 콘서트", "trdar_cd"].tolist() == ["A", "B"]
    assert out.loc[out["name"] == "소극장", "trdar_cd"].tolist() == ["A"]


def test_hangang_big_and_small_events_split():
    assert events.hangang_big("2025 드론 라이트쇼(하반기) (09-07 회차)") and events.hangang_big("2024 차 없는 잠수교 뚜벅뚜벅 축제")
    assert not events.hangang_big("한강여름생태교실 - 2023 한강페스티벌 여름축제") and not events.hangang_big("2024 한강수상스포츠체험")
    hg = pd.DataFrame({"evnt_sn": [1, 2], "title": ["드론쇼", "책읽는 한강공원"], "start": ["2025-05-01"] * 2, "end": ["2025-05-01"] * 2,
                       "park": ["뚝섬한강공원"] * 2, "lat": [LAT0] * 2, "lon": [LON0] * 2, "collected_at": ["2026-11-03"] * 2})
    empty = pd.DataFrame(columns=list(_culture("", "", "").keys()))
    split = events.build_events(empty, EMPTY_HOLIDAYS, EMPTY_MANUAL, _areas(), hangang=hg, hangang_mode="split").set_index("name")
    assert (split.loc["드론쇼", "kind"], split.loc["책읽는 한강공원", "kind"]) == ("축제-한강", "한강-소규모")
    assert split.loc["책읽는 한강공원", "place"] == "뚝섬한강공원 소규모"
    assert len(events.build_events(empty, EMPTY_HOLIDAYS, EMPTY_MANUAL, _areas(), hangang=hg)) == 1   # 기본 = 대형만
    assert set(events.build_events(empty, EMPTY_HOLIDAYS, EMPTY_MANUAL, _areas(), hangang=hg, hangang_mode="nosplit")["kind"]) == {"축제-한강"}


def test_park_festival_with_no_district_within_500m_reaches_1km():
    lat = LAT0 + 800 / M_PER_DEG_LAT  # 공원 한가운데처럼 가장 가까운 상권이 800m
    fest = pd.DataFrame([_culture("중랑 서울장미축제", LON0, lat)])
    show = pd.DataFrame([{**_culture("작은 전시", LON0, lat), "CODENAME": "전시/미술"}])
    linked = events.build_events(pd.concat([fest, show]), EMPTY_HOLIDAYS, EMPTY_MANUAL, _areas(), park_festival_m=1000).set_index("name")["trdar_cd"]
    assert linked["중랑 서울장미축제"] == "3110001" and pd.isna(linked["작은 전시"])   # 축제만 넓힌다
    old = events.build_events(fest, EMPTY_HOLIDAYS, EMPTY_MANUAL, _areas(), park_festival_m=0)
    assert old["trdar_cd"].isna().all()


def test_marathon_seoul_only_located_by_place_keyword():
    mr = pd.DataFrame({"no": [1, 2, 3, 4, 5], "date": [date(2023, 3, 19), date(2023, 11, 5), date(2023, 4, 1), date(2023, 5, 1), date(2023, 6, 1)],
                       "name": ["2023 서울마라톤", "JTBC 마라톤", "예산 마라톤", "한강 달리기(대회취소)", "광화문 달리기"],
                       "place": ["광화문광장 ~ 잠실종합운동장", "한강공원", "예산군 종합운동장", "여의도한강공원", "광화문광장"],
                       "is_seoul": [True, True, False, True, True], "collected_at": ["2026-11-09"] * 5})
    out = events.build_events(pd.DataFrame(columns=list(_culture("", "", "").keys())), EMPTY_HOLIDAYS, EMPTY_MANUAL, _areas(), marathon=mr)
    m = out[out["source"] == "marathon"].set_index("name")
    # 서울 밖 · 취소 대회는 빼고, 키워드 없는 '한강공원' 은 좌표 없음
    assert sorted(m.index) == ["2023 서울마라톤", "JTBC 마라톤", "광화문 달리기"]
    assert (m["kind"] == "축제-마라톤").all() and (m["start_date"] == m["end_date"]).all()
    assert (m.loc["2023 서울마라톤", "lat"], m.loc["2023 서울마라톤", "lon"]) == events.marathon_latlon("잠실종합운동장")  # 긴 키워드 먼저
    assert pd.isna(m.loc["JTBC 마라톤", "lat"])
    assert m.loc["광화문 달리기", "trdar_cd"] == "3110001" and pd.isna(m.loc["2023 서울마라톤", "trdar_cd"])  # 잠실은 테스트 상권(광화문)에서 멀다


def test_marathon_latlon_uses_station_coordinates():
    lat, lon = events.marathon_latlon("광화문광장 출발")
    assert abs(lat - 37.571525) < 1e-6 and abs(lon - 126.97717) < 1e-6  # ref/stations.csv 광화문(5호선)
    assert events.marathon_latlon("서울 어딘가") is None
