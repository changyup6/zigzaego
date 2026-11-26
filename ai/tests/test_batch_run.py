"""야간 예측 배치 로직 (S15P31A308-105). DB · TimesFM 없이 돈다 — CI 기본 설치로 실행된다."""

from datetime import date, timedelta

import numpy as np
import pandas as pd
import pytest

from weglee_ai.batch.db import DbConfig
from weglee_ai.batch.predictors import DowAvgPredictor, TimesFmPredictor, to_calendar
from weglee_ai.batch.run import HORIZON_DAYS, ForecastRow, Store, forecast_store, run_batch, target_dates

TODAY = date(2026, 10, 16)  # 금


def _sales(rows):
    return pd.DataFrame(rows, columns=["sold_date", "menu_id", "channel", "qty"])


def _four_weeks(menu_id=1, channel="HALL", qty=10.0):
    return [(TODAY - timedelta(days=k), menu_id, channel, qty) for k in range(1, 29)]


class FakeRepo:
    def __init__(self, sales_by_store, closed=None, broken=(), existing=None):
        self.sales_by_store = sales_by_store
        self.closed = closed or {}
        self.broken = set(broken)
        # 가게별 forecast 표 흉내. existing 으로 어제 만든 행을 미리 넣어 둘 수 있다
        self.table: dict[int, list[ForecastRow]] = {k: list(v) for k, v in (existing or {}).items()}
        self.saved: dict[int, list[ForecastRow]] = {}

    def stores(self):
        return [Store(sid, "Asia/Seoul") for sid in self.sales_by_store]

    def sales(self, store_id, before):
        if store_id in self.broken:
            raise RuntimeError("DB 오류 흉내")
        return _sales([r for r in self.sales_by_store[store_id] if r[0] < before])

    def closed_days(self, store_id, start, end):
        return {d for d in self.closed.get(store_id, set()) if start <= d <= end}

    def replace_forecasts(self, store_id, models, start, end, rows):
        kept = [r for r in self.table.get(store_id, []) if not (r.model in models and start <= r.target_date <= end)]
        self.table[store_id] = kept + list(rows)
        self.saved[store_id] = list(rows)


class ConstPredictor:
    def __init__(self, name, value):
        self.name, self.value = name, value

    def predict(self, series, targets):
        return [[self.value] * len(targets) for _ in series]


class BrokenPredictor:
    name = "timesfm"

    def predict(self, series, targets):
        raise RuntimeError("모델 로드 실패 흉내")


def test_오늘부터_7일_중_휴무일은_뺀다():
    closed = {TODAY + timedelta(days=2)}
    days = target_dates(TODAY, closed)
    assert days[0] == TODAY and len(days) == HORIZON_DAYS - 1 and TODAY + timedelta(days=2) not in days


def test_메뉴_채널마다_예측하고_음수는_0_소수_3자리():
    sales = _sales(_four_weeks(1, "HALL") + _four_weeks(1, "DELIVERY") + _four_weeks(2, "HALL"))
    rows, failed = forecast_store(sales, set(), TODAY, [ConstPredictor("dow_avg", -1.0), ConstPredictor("x", 2.34567)])
    assert failed == []
    keys = {(r.menu_id, r.channel) for r in rows}
    assert keys == {(1, "HALL"), (1, "DELIVERY"), (2, "HALL")}
    assert all(isinstance(r.menu_id, int) for r in rows)
    assert {r.predicted_qty for r in rows if r.model == "dow_avg"} == {0.0}
    assert {r.predicted_qty for r in rows if r.model == "x"} == {2.346}
    assert len(rows) == 3 * HORIZON_DAYS * 2


def test_한_가게가_실패해도_다른_가게는_저장된다():
    repo = FakeRepo({1: _four_weeks(), 2: _four_weeks(), 3: _four_weeks()}, broken={2})
    summary = run_batch(repo, [ConstPredictor("dow_avg", 5.0)], lambda store: TODAY)
    assert summary.stores_ok == 2 and summary.stores_failed == [2]
    assert set(repo.saved) == {1, 3}
    assert summary.rows == 2 * HORIZON_DAYS


def test_TimesFM이_실패하면_요일_평균만_저장하고_실패를_기록한다():
    repo = FakeRepo({1: _four_weeks()})
    summary = run_batch(repo, [BrokenPredictor(), DowAvgPredictor()], lambda store: TODAY)
    assert summary.stores_ok == 1 and summary.model_failures == [(1, "timesfm")]
    assert {r.model for r in repo.saved[1]} == {"dow_avg"}
    assert all(r.predicted_qty == 10.0 for r in repo.saved[1])


def test_모든_예측기가_실패하면_그_가게는_실패():
    repo = FakeRepo({1: _four_weeks()})
    summary = run_batch(repo, [BrokenPredictor()], lambda store: TODAY)
    assert summary.stores_failed == [1] and 1 not in repo.saved


def test_판매가_없는_가게는_빈_저장():
    repo = FakeRepo({1: []})
    summary = run_batch(repo, [DowAvgPredictor()], lambda store: TODAY)
    assert summary.stores_ok == 1 and repo.saved[1] == []


def test_휴무일은_요일_평균으로_메워_날짜를_잇는다():
    # 월 10 · 화 휴무 · 수 30, 다음 주 월 20 · 화 40
    s = pd.Series([10.0, 30.0, 20.0, 40.0],
                  index=pd.to_datetime(["2026-10-05", "2026-10-07", "2026-10-12", "2026-10-13"]))
    arr = to_calendar(s, date(2026, 10, 13))
    assert len(arr) == 9  # 10/05 ~ 10/13
    assert arr[1] == 40.0  # 10/06 화 휴무 → 화요일 평균(40)
    assert arr[3] == 25.0  # 10/08 목 — 목요일 기록 없음 → 전체 평균


class FakeModel:
    """forecast(horizon) → 계열 i 의 h 번째 값 = 100*i + h."""

    def forecast(self, horizon, inputs):
        self.inputs = inputs
        point = np.array([[100 * i + h for h in range(horizon)] for i in range(len(inputs))], dtype=float)
        return point, None


def test_TimesFM_예측값을_계열별_마지막_판매일_기준으로_날짜에_맞춘다():
    p = TimesFmPredictor()
    p._model = FakeModel()
    fresh = pd.Series([1.0] * 3, index=pd.date_range("2026-10-13", periods=3))  # 마지막 10/15 → 10/16 = h0
    stale = pd.Series([1.0] * 3, index=pd.date_range("2026-10-11", periods=3))  # 마지막 10/13 → 10/16 = h2
    values = p.predict([fresh, stale], [TODAY, TODAY + timedelta(days=1)])
    assert values == [[0.0, 1.0], [102.0, 103.0]]


def test_TimesFM_판매가_오래_끊긴_계열만_건너뛰고_나머지는_예측한다():
    # 배달을 넉 달 쉰 계열 하나 때문에 홀 예측까지 빠지면 안 된다
    p = TimesFmPredictor()
    p._model = FakeModel()
    old = pd.Series([1.0] * 3, index=pd.date_range("2026-01-01", periods=3))
    fresh = pd.Series([1.0] * 3, index=pd.date_range("2026-10-13", periods=3))
    values = p.predict([old, fresh], [TODAY])
    assert values[0] is None and values[1] == [0.0]
    assert len(p._model.inputs) == 1  # 모델에는 쓸 수 있는 계열만 넣는다


def test_건너뛴_계열은_저장하지_않는다():
    class SkipFirst:
        name = "timesfm"

        def predict(self, series, targets):
            return [None] + [[3.0] * len(targets) for _ in series[1:]]

    sales = _sales(_four_weeks(1, "DELIVERY") + _four_weeks(1, "HALL"))
    rows, failed = forecast_store(sales, set(), TODAY, [SkipFirst()])
    assert failed == [] and {r.channel for r in rows} == {"HALL"}


def test_TimesFM이_실패한_날_어제_만든_오늘자_timesfm_값은_지운다():
    # 남아 있으면 Spring 이 "오늘자 timesfm 있음"으로 보고 어제 예측을 쓴다
    stale = [ForecastRow(1, "HALL", TODAY + timedelta(days=k), "timesfm", 99.0) for k in range(HORIZON_DAYS)]
    outside = ForecastRow(1, "HALL", TODAY - timedelta(days=1), "timesfm", 99.0)  # 대상 기간 밖은 남긴다
    repo = FakeRepo({1: _four_weeks()}, existing={1: stale + [outside]})
    run_batch(repo, [BrokenPredictor(), DowAvgPredictor()], lambda store: TODAY)
    timesfm = [r for r in repo.table[1] if r.model == "timesfm"]
    assert timesfm == [outside]
    assert {r.model for r in repo.table[1] if r.target_date >= TODAY} == {"dow_avg"}


def test_NaN_예측값은_그_예측기_실패로_본다():
    rows, failed = forecast_store(_sales(_four_weeks()), set(), TODAY, [ConstPredictor("timesfm", float("nan"))])
    assert failed == ["timesfm"] and rows == []


def test_DB_URL은_백엔드와_같은_JDBC_형식을_읽는다():
    c = DbConfig.from_env({"DB_URL": "jdbc:postgresql://postgres:5432/zigzaego", "DB_USERNAME": "z", "DB_PASSWORD": "p"})
    assert (c.host, c.port, c.dbname, c.user, c.password) == ("postgres", 5432, "zigzaego", "z", "p")
    assert DbConfig.from_env({"DB_USERNAME": "z"}).host == "localhost"  # application.yaml 기본값과 같다
    with pytest.raises(ValueError):
        DbConfig.from_env({"DB_URL": "mysql://x/y", "DB_USERNAME": "z"})
    with pytest.raises(ValueError):
        DbConfig.from_env({"DB_URL": "jdbc:postgresql://h:1/d"})


def test_DB_URL의_sslmode는_넘기고_모르는_파라미터는_거부한다():
    # Spring 은 SSL 인데 배치만 평문으로 붙는 일이 없게
    c = DbConfig.from_env({"DB_URL": "jdbc:postgresql://h:5432/d?sslmode=require", "DB_USERNAME": "z"})
    assert c.sslmode == "require"
    with pytest.raises(ValueError, match="ssl"):
        DbConfig.from_env({"DB_URL": "jdbc:postgresql://h:5432/d?ssl=true", "DB_USERNAME": "z"})


def test_DB_URL_오류_메시지에_쿼리_값을_넣지_않는다():
    with pytest.raises(ValueError) as e:
        DbConfig.from_env({"DB_URL": "jdbc:postgresql://[::1]:5432/d?password=secret", "DB_USERNAME": "z"})
    assert "secret" not in str(e.value)
