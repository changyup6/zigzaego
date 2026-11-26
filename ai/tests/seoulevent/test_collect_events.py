from datetime import date

import pandas as pd

from weglee_ai.seoulevent import collect_events, collect_holidays

CULTURE_COLUMNS = ["CODENAME", "GUNAME", "TITLE", "DATE", "PLACE", "ORG_NAME", "USE_TRGT", "USE_FEE", "INQUIRY", "PLAYER",
                   "PROGRAM", "ETC_DESC", "ORG_LINK", "MAIN_IMG", "RGSTDATE", "TICKET", "STRTDATE", "END_DATE", "THEMECODE",
                   "LOT", "LAT", "IS_FREE", "HMPG_ADDR", "PRO_TIME"]


def _event(title: str) -> dict:
    row = {c: "" for c in CULTURE_COLUMNS}
    row |= {"TITLE": title, "STRTDATE": "2026-11-01 00:00:00.0", "END_DATE": "2026-11-03 00:00:00.0",
            "LOT": "37.57", "LAT": "126.97", "GUNAME": "종로구", "CODENAME": "축제-문화/예술"}
    return row


def test_events_saved_per_collect_day_with_all_columns(tmp_path):
    calls = []

    def fetch_all(service, *args):
        calls.append(service)
        return [_event("A"), _event("B")]

    path = collect_events.run(fetch_all, tmp_path, today=date(2026, 11, 2))
    assert calls == ["culturalEventInfo"]
    assert path == tmp_path / "2026-11-02.parquet"
    df = pd.read_parquet(path)
    assert len(df) == 2
    assert set(CULTURE_COLUMNS) <= set(df.columns)
    assert (df["collected_at"] == "2026-11-02").all()


def test_events_previous_days_kept(tmp_path):
    collect_events.run(lambda s: [_event("A")], tmp_path, today=date(2026, 11, 1))
    collect_events.run(lambda s: [_event("B")], tmp_path, today=date(2026, 11, 2))
    assert sorted(p.name for p in tmp_path.glob("*.parquet")) == ["2026-11-01.parquet", "2026-11-02.parquet"]


def test_holidays_parsed_and_single_item_ok(tmp_path):
    def get_items(url, params):
        assert url == collect_holidays.URL
        assert params["_type"] == "json"
        if params["solYear"] == 2026:
            return [{"locdate": 20261003, "dateName": "개천절", "isHoliday": "Y", "seq": 1, "dateKind": "01"}]
        return []

    path = collect_holidays.run([2025, 2026], get_items, tmp_path)
    df = pd.read_parquet(path)
    assert df.to_dict("records") == [{"date": date(2026, 10, 3), "name": "개천절", "is_holiday": True}]


def test_holidays_years_called_once_each(tmp_path):
    years = []
    collect_holidays.run([2024, 2025], lambda url, p: years.append(p["solYear"]) or [], tmp_path)
    assert years == [2024, 2025]


SHEET_HEADER = ["분류", "자치구", "공연/행사명", "날짜", "장소", "기관명", "이용대상", "이용요금", "문의", "출연자정보",
                "프로그램소개", "기타내용", "홈페이지?주소", "대표이미지", "신청일", "시민/기관", "시작일", "종료일", "테마분류",
                "경도(X좌표)", "위도(Y좌표)", "유무료", "문화포털상세URL", "행사시간"]


def test_load_sheet_maps_to_api_columns(tmp_path):
    row = ["콘서트", "강동구", "재즈", "2026-12-24~2026-12-24", "강동아트센터", "기타", "성인", "유료", "070", "", "", "", "",
           "", "2026-11-01", "기관", "2026-12-24 00:00:00.0", "2026-12-24 00:00:00.0", "", "127.15", "37.55", "유료", "", ""]
    text = "\n".join(",".join(f'"{v}"' for v in r) for r in [SHEET_HEADER, row]) + "\n"
    p = tmp_path / "s.csv"
    p.write_bytes(text.encode("cp949", errors="replace"))
    df = collect_events.load_sheet(p)
    assert set(CULTURE_COLUMNS) <= set(df.columns)
    r = df.iloc[0]
    assert (r["CODENAME"], r["GUNAME"], r["TITLE"], r["PLACE"]) == ("콘서트", "강동구", "재즈", "강동아트센터")
    assert (r["STRTDATE"], r["LOT"], r["LAT"]) == ("2026-12-24 00:00:00.0", "127.15", "37.55")


def test_load_sheet_tolerates_bytes_outside_cp949(tmp_path):
    header = ",".join(f'"{h}"' for h in SHEET_HEADER).encode("cp949", errors="replace")
    row = ",".join(['"x"'] * 24).encode().replace(b'"x"', b'"\x82x"', 1)  # cp949 로 못 읽는 바이트
    p = tmp_path / "s.csv"
    p.write_bytes(header + b"\n" + row + b"\n")
    assert len(collect_events.load_sheet(p)) == 1
