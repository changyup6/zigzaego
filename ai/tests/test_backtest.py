"""예측 백테스팅 (S15P31A308-57, 평가 지표 정의 4절). 손으로 셀 수 있는 작은 계열로 규칙 하나씩 본다."""

from datetime import date

import pandas as pd
import pytest

from weglee_ai.backtest import (
    ALL_CHANNELS,
    LastWeekPredictor,
    backtest,
    common_rows,
    eval_period,
    score,
    to_markdown,
    verdict,
)
from weglee_ai.baseline import DowAvgPredictor
from weglee_ai.metrics import wape

END = pd.Timestamp("2026-10-18")  # 일요일


def _sales(values, channel="DELIVERY", menu="A", end=END):
    days = pd.date_range(end=end, periods=len(values), freq="D")
    return pd.DataFrame({"sold_date": days, "menu_name": menu, "qty": values, "channel": channel})


class Fixed:
    """모든 대상일에 같은 값. skip 에 든 메뉴는 None (예측 못 함). 받은 계열의 마지막 날을 기록한다."""

    def __init__(self, name, value, skip=()):
        self.name, self.value, self.skip = name, value, set(skip)
        self.calls = []

    def predict(self, series, targets):
        self.calls.append(([s.index.max() for s in series], list(targets)))
        return [None if s.name[1] in self.skip else [self.value] * len(targets) for s in series]  # name = (채널, 메뉴)


def test_평가_기간은_마지막_8주():
    sales = _sales([1.0] * 100)
    assert eval_period(sales) == (date(2026, 8, 24), date(2026, 10, 18))
    rows = backtest(sales, [Fixed("m", 1.0)])
    assert rows["target"].min() == pd.Timestamp("2026-08-24") and rows["target"].max() == END


def test_기준일까지의_데이터만_넘긴다():
    model = Fixed("m", 1.0)
    backtest(_sales([1.0] * 70), [model], eval_weeks=1)
    for last_seen, targets in model.calls:
        assert all(seen < pd.Timestamp(min(targets)) for seen in last_seen)


def test_h_는_1에서_7_대상일은_기간_안():
    rows = backtest(_sales([1.0] * 70), [Fixed("m", 1.0)], eval_weeks=1)
    assert sorted(rows["h"].unique()) == list(range(1, 8))
    assert (rows["target"] - rows["origin"]).dt.days.equals(rows["h"])
    # 마지막 날을 대상으로 하는 기준일은 7개 (h = 1~7), 평가 첫날은 1개 (h = 1)
    assert len(rows[rows["target"] == END]) == 7
    assert len(rows[rows["target"] == END - pd.Timedelta(days=6)]) == 1


def test_휴무일은_대상에서_빠지고_영업일_미판매는_0():
    sales = pd.concat([_sales([5.0] * 14, menu="A"), _sales([2.0] * 14, menu="B").iloc[:-1]])
    sales = sales[sales["sold_date"] != END - pd.Timedelta(days=3)]  # 그날은 둘 다 안 팔림 = 휴무
    rows = backtest(sales, [Fixed("m", 1.0)], eval_weeks=1)
    assert END - pd.Timedelta(days=3) not in set(rows["target"])
    b_last = rows[(rows["menu_name"] == "B") & (rows["target"] == END)]
    assert len(b_last) > 0 and (b_last["actual"] == 0).all()  # A 만 팔린 날 B 는 0


def test_감은_지난주_같은_요일_휴무면_그_전_같은_요일():
    s = pd.Series([10.0, 20.0, 30.0], index=pd.to_datetime(["2026-10-05", "2026-10-11", "2026-10-12"]))  # 월 · 일 · 월
    assert LastWeekPredictor().predict([s], [date(2026, 10, 19), date(2026, 10, 18)]) == [[30.0, 20.0]]
    gap = pd.Series([10.0, 99.0], index=pd.to_datetime(["2026-10-05", "2026-10-13"]))  # 10/12(월) 휴무
    assert LastWeekPredictor().predict([gap], [date(2026, 10, 19)]) == [[10.0]]


def test_음수_예측은_0():
    rows = backtest(_sales([1.0] * 70), [Fixed("m", -3.0)], eval_weeks=1)
    assert (rows["predicted"] == 0).all()


def test_점수는_pooled_이고_합계는_채널을_더한_뒤():
    sales = pd.concat([_sales([10.0] * 70, "DELIVERY"), _sales([30.0] * 70, "HALL")])
    rows = backtest(sales, [Fixed("m", 20.0)], eval_weeks=1)
    table = score(rows).set_index(["구간", "채널"])
    assert table.loc[("h=1", "DELIVERY"), "WAPE"] == pytest.approx(1.0)  # |10-20| / 10
    assert table.loc[("h=1", "HALL"), "WAPE"] == pytest.approx(1 / 3)
    assert table.loc[("h=1", ALL_CHANNELS), "WAPE"] == pytest.approx(0.0)  # 40 vs 40
    assert table.loc[("h=1", "DELIVERY"), "행"] == 7


def test_요일_평균은_배치와_같은_식():
    values = [float(i % 7 + 1) for i in range(70)]  # 요일마다 고정 → 요일 평균은 정확히 맞힌다
    rows = backtest(_sales(values), [DowAvgPredictor()], eval_weeks=2)
    assert wape(rows["actual"], rows["predicted"]) == pytest.approx(0.0)


def test_같은_행_비교는_한쪽이_못_한_계열을_다른_쪽에서도_뺀다():
    sales = pd.concat([_sales([1.0] * 70, menu="A"), _sales([1.0] * 70, menu="B")])
    rows = backtest(sales, [Fixed("dow_avg", 1.0), Fixed("timesfm", 1.0, skip={"B"})], eval_weeks=1)
    both = common_rows(rows, ["dow_avg", "timesfm"])
    assert set(both["menu_name"]) == {"A"}
    assert (both.groupby("model").size() == len(rows[rows["model"] == "timesfm"])).all()


@pytest.mark.parametrize(
    ("timesfm", "expected"),
    [(10.0, "다시 쓸 근거 있음"), (12.0, "낫지 않다"), (11.5, "Bias ±10% 초과"), (None, "결과 없음")],
)
def test_판정_한_줄(timesfm, expected):
    # 실제 10. 요일 평균 예측 12 → WAPE 0.2. TimesFM 10 → 0 (근거 있음), 12 → 0.2 같음 (낫지 않음), 11.5 → 0.15 · Bias +15%
    sales = _sales([10.0] * 70)
    models = [Fixed("dow_avg", 12.0)] + ([Fixed("timesfm", timesfm)] if timesfm is not None else [])
    assert expected in verdict(backtest(sales, models, eval_weeks=1))


def test_실제_판매가_0이면_판정_보류():
    rows = backtest(_sales([0.0] * 70), [Fixed("dow_avg", 1.0), Fixed("timesfm", 1.0)], eval_weeks=1)
    assert "판정 보류" in verdict(rows)  # WAPE 가 NaN — '낫지 않다' 로 떨어지지 않는다


def test_md_표():
    md = to_markdown(score(backtest(_sales([10.0] * 70), [Fixed("m", 12.0)], eval_weeks=1)))
    lines = md.splitlines()
    assert lines[0] == "| 구간 | 모델 | 채널 | WAPE | Bias | 행 |"
    assert "| h=1 | m | DELIVERY | 0.200 | +0.200 | 7 |" in lines
