"""야간 예측 배치 본체 (지라 S15P31A308-105, 설계 5.2).

가게마다: 판매 읽기 → 영업일 표로 정리(baseline.fill_daily) → 예측기마다 오늘부터 7일 → forecast 저장.
한 가게가 실패해도 다음 가게로 간다. 한 예측기가 실패해도 그 가게의 다른 예측기 값은 저장한다
(Spring 은 오늘자 timesfm 값이 없으면 dow_avg 를 쓴다).

저장은 "이번에 돌린 모델들의 대상 기간(오늘 ~ 6일 뒤)을 지우고 새로 넣기"다. 그래서 TimesFM 이 실패하거나
일부 계열을 건너뛴 날, 어제 만든 오늘자 timesfm 값이 남아 Spring 이 오래된 예측을 쓰는 일이 없다.

DB 는 Repository 뒤에 둔다 — 이 파일은 DB 없이 테스트한다. 실제 구현은 db.PostgresRepository.
"""

import logging
import math
import time
from collections.abc import Callable, Sequence
from dataclasses import dataclass, field
from datetime import date, timedelta
from typing import Protocol

import pandas as pd

from weglee_ai.baseline import fill_daily
from weglee_ai.batch.predictors import Predictor

log = logging.getLogger(__name__)

HORIZON_DAYS = 7


@dataclass(frozen=True)
class Store:
    id: int
    timezone: str


@dataclass(frozen=True)
class ForecastRow:
    menu_id: int
    channel: str
    target_date: date
    model: str
    predicted_qty: float


class Repository(Protocol):
    def stores(self) -> list[Store]: ...

    def sales(self, store_id: int, before: date) -> pd.DataFrame:
        """before 전날까지의 판매. columns = sold_date, menu_id, channel, qty. 판매 중지 메뉴는 뺀다."""
        ...

    def closed_days(self, store_id: int, start: date, end: date) -> set[date]: ...

    def replace_forecasts(
        self, store_id: int, models: Sequence[str], start: date, end: date, rows: Sequence[ForecastRow]
    ) -> None:
        """한 트랜잭션에서 models 의 [start, end] 예측을 지우고 rows 를 넣는다 — 다시 돌려도 안전."""
        ...


@dataclass
class Summary:
    stores_ok: int = 0
    stores_failed: list[int] = field(default_factory=list)
    model_failures: list[tuple[int, str]] = field(default_factory=list)
    rows: int = 0


def target_dates(today: date, closed: set[date]) -> list[date]:
    """오늘부터 7일 중 휴무일을 뺀 날. 서비스는 03:00 에 만든 오늘자 값을 그날 쓴다."""
    return [d for d in (today + timedelta(days=k) for k in range(HORIZON_DAYS)) if d not in closed]


def forecast_store(
    sales: pd.DataFrame, closed: set[date], today: date, predictors: Sequence[Predictor]
) -> tuple[list[ForecastRow], list[str]]:
    """한 가게의 예측 행과 실패한 예측기 이름.

    예측 단위는 (메뉴, 채널). 채널 구분이 없는 가게는 NONE 한 계열이다.
    """
    if sales.empty:
        return [], []
    frame = sales.rename(columns={"menu_id": "menu_name"})
    daily = fill_daily(frame)
    keys, series = [], []
    for (channel, menu_id), g in daily.groupby(["channel", "menu_name"]):
        keys.append((int(menu_id), channel))
        series.append(g.set_index("sold_date")["qty"].astype(float).sort_index())
    targets = target_dates(today, closed)
    if not targets:
        return [], []

    rows, failed = [], []
    for predictor in predictors:
        try:
            values = predictor.predict(series, targets)
            if any(v is not None and not all(math.isfinite(x) for x in v) for v in values):
                raise ValueError("NaN · inf 예측값")
        except Exception:
            log.exception("예측기 %s 실패", predictor.name)
            failed.append(predictor.name)
            continue
        for (menu_id, channel), per_day in zip(keys, values, strict=False):
            if per_day is None:
                continue
            for target, value in zip(targets, per_day, strict=False):
                rows.append(ForecastRow(menu_id, channel, target, predictor.name, round(max(0.0, value), 3)))
    return rows, failed


def run_batch(repo: Repository, predictors: Sequence[Predictor], today_of: Callable[[Store], date]) -> Summary:
    """모든 가게를 돈다. today_of(store) = 그 가게 시간대의 오늘."""
    summary = Summary()
    for store in repo.stores():
        started = time.monotonic()
        try:
            today = today_of(store)
            last = today + timedelta(days=HORIZON_DAYS - 1)
            sales = repo.sales(store.id, before=today)
            closed = repo.closed_days(store.id, today, last)
            rows, failed = forecast_store(sales, closed, today, predictors)
            if failed and len(failed) == len(predictors):
                raise RuntimeError(f"모든 예측기 실패: {failed}")
            repo.replace_forecasts(store.id, [p.name for p in predictors], today, last, rows)
        except Exception:
            log.exception("가게 %s 예측 실패 — 다음 가게로", store.id)
            summary.stores_failed.append(store.id)
            continue
        summary.stores_ok += 1
        summary.rows += len(rows)
        summary.model_failures += [(store.id, name) for name in failed]
        log.info("가게 %s: %d행, %.1f초%s", store.id, len(rows), time.monotonic() - started,
                 f" (실패 예측기 {failed})" if failed else "")
    return summary
