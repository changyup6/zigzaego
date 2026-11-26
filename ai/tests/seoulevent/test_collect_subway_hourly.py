import pandas as pd

from weglee_ai.seoulevent import collect_subway_hourly as sh


def test_hour_col_variants():
    assert sh.hour_col("06시-07시") == sh.hour_col("06-07시간대") == sh.hour_col("06:00 ~ 07:00") == sh.hour_col("06 ~ 07") == "h06"
    assert sh.hour_col("06시 이전") == sh.hour_col("06:00 이전") == "h_early"
    assert sh.hour_col("24시 이후") == "h_late" and sh.hour_col("23시 이후") == "h23"   # 2021: 23시 이후 전부
    assert sh.hour_col("합 계") is None and sh.hour_col("역명") is None


def test_load_normalizes_years_and_later_file_wins(tmp_path):
    a = "연번,수송일자,호선,역번호,역명,승하차구분,06시이전,06-07시간대,23-24시간대,24시이후\n1,2023-01-01,1,150,서울역,승차,1,2,3,4\n"
    b = "연번,날짜,호선,역번호,역명,구분,06시 이전,06시-07시,23시-24시,24시 이후\n1,2023-01-01,1호선,150,서울역,승차,10,20,30,40\n" \
        "2,2023-01-02,1호선,150,서울역,하차,\"1,000\",2,3,4\n"
    (tmp_path / "seq40.csv").write_bytes(a.encode("cp949"))
    (tmp_path / "seq45.csv").write_bytes(b.encode("utf-8-sig"))
    df = sh.load(tmp_path)
    assert len(df) == 2 and set(df["line"]) == {"1호선"} and list(df["kind"]) == ["board", "alight"]
    first = df.iloc[0]
    assert first["h_early"] == 10 and first["h23"] == 30 and first["h_late"] == 40   # 뒤 파일(seq45)이 이김
    assert df.iloc[1]["h_early"] == 1000 and pd.isna(first["h12"])
