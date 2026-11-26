import pandas as pd

from weglee_ai.baseline import fill_daily, forecast_day, rolling_forecast


def _series(values: dict[str, float]) -> pd.Series:
    return pd.Series(list(values.values()), index=pd.to_datetime(list(values))).sort_index()


def test_same_weekday_average_last_4_weeks():
    # 목표 2026-10-12(월). 직전 4주 월요일 4개 + 5주 전 월요일(창 밖)
    history = _series(
        {
            "2026-09-07": 1000,  # 5주 전 — 제외
            "2026-09-14": 10,
            "2026-09-21": 20,
            "2026-09-28": 30,
            "2026-10-05": 40,
            "2026-10-11": 999,  # 일요일 — 다른 요일
        }
    )
    assert forecast_day(history, "2026-10-12") == 25


def test_closed_days_are_skipped():
    # 휴무일은 행이 없다 → 남은 같은 요일 2개 평균
    history = _series({"2026-09-21": 20, "2026-10-05": 40})
    assert forecast_day(history, "2026-10-12") == 30


def test_fallback_to_14_day_mean_when_under_2_same_weekday():
    history = _series({"2026-10-05": 40, "2026-10-08": 10, "2026-10-09": 10})
    assert forecast_day(history, "2026-10-12") == 20


def test_uses_only_past_data():
    history = _series({"2026-09-28": 10, "2026-10-05": 20, "2026-10-12": 1000})
    assert forecast_day(history, "2026-10-12") == 15


def test_rolling_forecast_shape():
    sales = pd.DataFrame(
        {
            "sold_date": ["2026-09-28", "2026-10-05", "2026-10-12"],
            "menu_name": ["A", "A", "A"],
            "qty": [10, 20, 30],
        }
    )
    out = rolling_forecast(sales, "2026-10-12", "2026-10-12")
    assert list(out.columns) == ["sold_date", "channel", "menu_name", "actual", "predicted"]
    assert out.iloc[0]["channel"] == "NONE"  # 채널 열이 없으면 한 계열
    assert out.iloc[0]["actual"] == 30
    assert out.iloc[0]["predicted"] == 15


def _sales(rows):
    return pd.DataFrame(rows, columns=["sold_date", "menu_name", "qty", "channel"])


def test_channels_are_forecast_separately():
    rows = []
    for d in ["2026-09-28", "2026-10-05", "2026-10-12"]:
        rows += [(d, "A", 10, "HALL"), (d, "A", 30, "DELIVERY")]
    out = rolling_forecast(_sales(rows), "2026-10-12", "2026-10-12").set_index("channel")
    assert out.loc["HALL", "predicted"] == 10
    assert out.loc["DELIVERY", "predicted"] == 30


def test_missing_menu_row_on_open_day_is_zero():
    # 10/05는 B가 팔렸으니 영업일 → A는 0개 판매. 휴무로 빼면 예측이 (10+10)/2=10 으로 부풀려진다
    sales = _sales(
        [
            ("2026-09-28", "A", 10, "HALL"),
            ("2026-09-28", "B", 5, "HALL"),
            ("2026-10-05", "B", 5, "HALL"),
            ("2026-10-12", "A", 10, "HALL"),
            ("2026-10-12", "B", 5, "HALL"),
        ]
    )
    a = rolling_forecast(sales, "2026-10-12", "2026-10-12").set_index("menu_name").loc["A"]
    assert a["predicted"] == 5  # (10 + 0) / 2


def test_day_with_no_sales_in_channel_is_closed():
    # 10/05는 HALL 판매가 전혀 없다 → HALL 휴무. 0으로 채우지 않는다
    sales = _sales(
        [
            ("2026-09-21", "A", 20, "HALL"),
            ("2026-09-28", "A", 10, "HALL"),
            ("2026-10-05", "A", 7, "DELIVERY"),
            ("2026-10-12", "A", 10, "HALL"),
        ]
    )
    out = rolling_forecast(sales, "2026-10-12", "2026-10-12").set_index("channel")
    assert out.loc["HALL", "predicted"] == 15  # (20 + 10) / 2


def test_new_menu_not_filled_before_first_sale():
    sales = _sales(
        [
            ("2026-09-28", "A", 10, "HALL"),
            ("2026-10-05", "A", 10, "HALL"),
            ("2026-10-05", "NEW", 8, "HALL"),
        ]
    )
    daily = fill_daily(sales)
    assert daily[daily["menu_name"] == "NEW"]["sold_date"].min() == pd.Timestamp("2026-10-05")


def test_first_sale_is_menu_wide_not_per_channel():
    # A는 홀에서 9/28부터 팔렸다. 배달 첫 주문은 10/12지만 그 전 배달 영업일(9/28 · 10/05)은 0개 판매
    sales = _sales(
        [
            ("2026-09-28", "A", 10, "HALL"),
            ("2026-09-28", "B", 4, "DELIVERY"),
            ("2026-10-05", "A", 10, "HALL"),
            ("2026-10-05", "B", 4, "DELIVERY"),
            ("2026-10-12", "A", 10, "HALL"),
            ("2026-10-12", "A", 6, "DELIVERY"),
        ]
    )
    a_delivery = fill_daily(sales).query("menu_name == 'A' and channel == 'DELIVERY'")
    assert list(a_delivery["qty"]) == [0, 0, 6]
