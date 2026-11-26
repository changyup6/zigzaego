"""실데이터가 오기 전까지 쓰는 샘플 데이터. 3주 계획 9절 · 설계 4.1절 (S15P31A308-52, 2년치 S15P31A308-162).

메뉴 8개 · 재료 12개(BOM · 1:1 · 주기형) · 거래처 3곳 · 730일치 일별 판매(배달 · 홀) · 입고 영수증.
판매 기대값 = 평일 평균 × 월별 배수(메뉴별) × 성장 추세 × 공휴일 · 행사 배수 × 요일 배수(채널별).
비 오는 날 · 공휴일엔 배달 비중이 오른다. 여기에 정규 노이즈를 얹고, 월 2회 임의 휴무 + 설 · 추석 당일 휴무.
레시피는 돈까스집 기준 가상값. 단위는 DB 기준 단위 G · ML · EA (DECISIONS #33, 스키마 V1 ingredient.unit).
모든 이름 · 수치는 가상이다. 실존 업체명을 쓰지 않는다 (PRODUCT.md Evidence on Hand).

업종별 메뉴판 · 재료 · 레시피 · 거래처 · 계절 성향은 가게 틀(store_templates.py)에 있다 (S15P31A308-240).
기본 틀은 위 돈까스집(donkatsu)이고, generate(template=..., scale=...) 로 다른 업종 · 규모를 만든다.
공휴일 · 비 · 성장 · 노이즈 · 휴무 · 발주 규칙은 모든 틀 공통으로 이 파일에 있다. 기간은 공휴일 표 범위(2021~2026) 안에서 1~5년.

seed.sql(seed_sql.py)이 읽는 CSV 외에 생성 정답 4개를 함께 낸다. 서비스 입력이 아니다:
events(공휴일 · 행사와 배수) · weather(비 여부) · true_demand(노이즈 전 기대값) · true_usage(재료 실제 소비).
"""

import math
from dataclasses import dataclass

import numpy as np
import pandas as pd

from weglee_ai.store_templates import DEFAULT_TEMPLATE, DONKATSU, StoreTemplate, get_template

DAYS = 730
END_DATE = "2026-10-11"
# 아래 대문자 상수(WEEKEND_FACTOR · MENUS · INGREDIENTS · RECIPES …)는 기본 틀(donkatsu) 값을 그대로 내보낸 것이다.
# 생성은 틀 값을 쓴다. 다른 모듈(synthetic_backtest 등)이 이 이름으로 import 하므로 남겨 둔다
WEEKEND_FACTOR = DONKATSU.weekend_factor  # 금·토
DELIVERY_SUNDAY_FACTOR = DONKATSU.delivery_sunday_factor
NOISE_CV = 0.15  # 일 판매량 표준편차 = 평균 × 0.15
LOW_MEAN = 5  # 평균이 이보다 작은 (메뉴, 채널)은 포아송 — 안 팔리는 날(0개)이 생긴다
CLOSED_PER_MONTH = 2
PRICE_CV = 0.03  # 입고 단가가 기준 단가에서 흔들리는 폭

# 기본 틀(donkatsu)의 메뉴 · 월 배수. 형식은 store_templates.py 머리 주석
MENUS = DONKATSU.menus
MONTH_FACTOR = DONKATSU.month_factor
MENU_MONTH_FACTOR = DONKATSU.menu_month_factor
# 완만한 성장 추세. 마지막 날이 1.0 이고 1년 앞으로 갈 때마다 1 / (1 + 이 값)
GROWTH_PER_YEAR = 0.06

# 비 오는 날. 생성용으로만 쓰는 합성 데이터의 정답 잡음이다 — 서비스 입력이 아니다(날씨 변수는 서비스 범위 밖, DECISIONS #30).
# 시드로 날마다 뽑는다. 확률은 실험 저장소 합성기와 같게 7 · 8월 0.45, 나머지 0.22
RAIN_P = 0.22
RAIN_P_SUMMER = 0.45
RAIN_SUMMER_MONTHS = (7, 8)
# 비 · 공휴일엔 홀 몫의 이 비율이 배달로 옮겨 간다 (하루 판매 합계는 그대로). 둘 다면 차례로 적용
RAIN_DELIVERY_PULL = 0.25
HOLIDAY_DELIVERY_PULL = 0.15

# 공휴일 · 행사 배수 (그날 모든 메뉴 공통 판매 배수). 채널 비중 이동은 위 PULL 값으로 따로 준다
HOLIDAY_FACTOR = 1.1  # 법정 공휴일 · 대체 · 임시 공휴일
LONG_HOLIDAY_FACTOR = 0.6  # 설 · 추석 연휴 중 당일이 아닌 날 (귀성으로 손님이 준다)
# 설 · 추석 당일은 휴무 (HOLIDAY_CLOSED, 배수 0, closed_days 에 들어간다)

# 공휴일 표가 덮는 범위. 생성 기간이 이 밖으로 나가면 ValueError (공휴일이 빠진 기간이 조용히 생기지 않게)
HOLIDAY_TABLE_START = "2021-01-01"
HOLIDAY_TABLE_END = "2026-12-31"

# 2021-01 ~ 2026-12 공휴일 (날짜, 이름, 종류, 배수). 날짜는 생성 기간 안의 것만 events.csv 에 나간다.
# 종류: HOLIDAY(영업, 배수 적용 · 배달 비중 상승) · HOLIDAY_CLOSED(휴무)
# 2021 ~ 2024-10 은 S15P31A308-240 에서 더했다(대체 · 임시 공휴일 · 선거일 포함). 설 · 추석 대체공휴일은 연휴 배수
HOLIDAYS = [
    ("2021-01-01", "신정", "HOLIDAY", HOLIDAY_FACTOR),
    ("2021-02-11", "설날 연휴", "HOLIDAY", LONG_HOLIDAY_FACTOR),
    ("2021-02-12", "설날", "HOLIDAY_CLOSED", 0.0),
    ("2021-02-13", "설날 연휴", "HOLIDAY", LONG_HOLIDAY_FACTOR),
    ("2021-03-01", "삼일절", "HOLIDAY", HOLIDAY_FACTOR),
    ("2021-05-05", "어린이날", "HOLIDAY", HOLIDAY_FACTOR),
    ("2021-05-19", "부처님오신날", "HOLIDAY", HOLIDAY_FACTOR),
    ("2021-06-06", "현충일", "HOLIDAY", HOLIDAY_FACTOR),
    ("2021-08-15", "광복절", "HOLIDAY", HOLIDAY_FACTOR),
    ("2021-08-16", "대체공휴일(광복절)", "HOLIDAY", HOLIDAY_FACTOR),
    ("2021-09-20", "추석 연휴", "HOLIDAY", LONG_HOLIDAY_FACTOR),
    ("2021-09-21", "추석", "HOLIDAY_CLOSED", 0.0),
    ("2021-09-22", "추석 연휴", "HOLIDAY", LONG_HOLIDAY_FACTOR),
    ("2021-10-03", "개천절", "HOLIDAY", HOLIDAY_FACTOR),
    ("2021-10-04", "대체공휴일(개천절)", "HOLIDAY", HOLIDAY_FACTOR),
    ("2021-10-09", "한글날", "HOLIDAY", HOLIDAY_FACTOR),
    ("2021-10-11", "대체공휴일(한글날)", "HOLIDAY", HOLIDAY_FACTOR),
    ("2021-12-25", "성탄절", "HOLIDAY", HOLIDAY_FACTOR),
    ("2022-01-01", "신정", "HOLIDAY", HOLIDAY_FACTOR),
    ("2022-01-31", "설날 연휴", "HOLIDAY", LONG_HOLIDAY_FACTOR),
    ("2022-02-01", "설날", "HOLIDAY_CLOSED", 0.0),
    ("2022-02-02", "설날 연휴", "HOLIDAY", LONG_HOLIDAY_FACTOR),
    ("2022-03-01", "삼일절", "HOLIDAY", HOLIDAY_FACTOR),
    ("2022-03-09", "대통령 선거일", "HOLIDAY", HOLIDAY_FACTOR),
    ("2022-05-05", "어린이날", "HOLIDAY", HOLIDAY_FACTOR),
    ("2022-05-08", "부처님오신날", "HOLIDAY", HOLIDAY_FACTOR),
    ("2022-06-01", "지방선거일", "HOLIDAY", HOLIDAY_FACTOR),
    ("2022-06-06", "현충일", "HOLIDAY", HOLIDAY_FACTOR),
    ("2022-08-15", "광복절", "HOLIDAY", HOLIDAY_FACTOR),
    ("2022-09-09", "추석 연휴", "HOLIDAY", LONG_HOLIDAY_FACTOR),
    ("2022-09-10", "추석", "HOLIDAY_CLOSED", 0.0),
    ("2022-09-11", "추석 연휴", "HOLIDAY", LONG_HOLIDAY_FACTOR),
    ("2022-09-12", "대체공휴일(추석)", "HOLIDAY", LONG_HOLIDAY_FACTOR),
    ("2022-10-03", "개천절", "HOLIDAY", HOLIDAY_FACTOR),
    ("2022-10-09", "한글날", "HOLIDAY", HOLIDAY_FACTOR),
    ("2022-10-10", "대체공휴일(한글날)", "HOLIDAY", HOLIDAY_FACTOR),
    ("2022-12-25", "성탄절", "HOLIDAY", HOLIDAY_FACTOR),
    ("2023-01-01", "신정", "HOLIDAY", HOLIDAY_FACTOR),
    ("2023-01-21", "설날 연휴", "HOLIDAY", LONG_HOLIDAY_FACTOR),
    ("2023-01-22", "설날", "HOLIDAY_CLOSED", 0.0),
    ("2023-01-23", "설날 연휴", "HOLIDAY", LONG_HOLIDAY_FACTOR),
    ("2023-01-24", "대체공휴일(설날)", "HOLIDAY", LONG_HOLIDAY_FACTOR),
    ("2023-03-01", "삼일절", "HOLIDAY", HOLIDAY_FACTOR),
    ("2023-05-05", "어린이날", "HOLIDAY", HOLIDAY_FACTOR),
    ("2023-05-27", "부처님오신날", "HOLIDAY", HOLIDAY_FACTOR),
    ("2023-05-29", "대체공휴일(부처님오신날)", "HOLIDAY", HOLIDAY_FACTOR),
    ("2023-06-06", "현충일", "HOLIDAY", HOLIDAY_FACTOR),
    ("2023-08-15", "광복절", "HOLIDAY", HOLIDAY_FACTOR),
    ("2023-09-28", "추석 연휴", "HOLIDAY", LONG_HOLIDAY_FACTOR),
    ("2023-09-29", "추석", "HOLIDAY_CLOSED", 0.0),
    ("2023-09-30", "추석 연휴", "HOLIDAY", LONG_HOLIDAY_FACTOR),
    ("2023-10-02", "임시공휴일(추석 연휴)", "HOLIDAY", LONG_HOLIDAY_FACTOR),
    ("2023-10-03", "개천절", "HOLIDAY", HOLIDAY_FACTOR),
    ("2023-10-09", "한글날", "HOLIDAY", HOLIDAY_FACTOR),
    ("2023-12-25", "성탄절", "HOLIDAY", HOLIDAY_FACTOR),
    ("2024-01-01", "신정", "HOLIDAY", HOLIDAY_FACTOR),
    ("2024-02-09", "설날 연휴", "HOLIDAY", LONG_HOLIDAY_FACTOR),
    ("2024-02-10", "설날", "HOLIDAY_CLOSED", 0.0),
    ("2024-02-11", "설날 연휴", "HOLIDAY", LONG_HOLIDAY_FACTOR),
    ("2024-02-12", "대체공휴일(설날)", "HOLIDAY", LONG_HOLIDAY_FACTOR),
    ("2024-03-01", "삼일절", "HOLIDAY", HOLIDAY_FACTOR),
    ("2024-04-10", "국회의원 선거일", "HOLIDAY", HOLIDAY_FACTOR),
    ("2024-05-05", "어린이날", "HOLIDAY", HOLIDAY_FACTOR),
    ("2024-05-06", "대체공휴일(어린이날)", "HOLIDAY", HOLIDAY_FACTOR),
    ("2024-05-15", "부처님오신날", "HOLIDAY", HOLIDAY_FACTOR),
    ("2024-06-06", "현충일", "HOLIDAY", HOLIDAY_FACTOR),
    ("2024-08-15", "광복절", "HOLIDAY", HOLIDAY_FACTOR),
    ("2024-09-16", "추석 연휴", "HOLIDAY", LONG_HOLIDAY_FACTOR),
    ("2024-09-17", "추석", "HOLIDAY_CLOSED", 0.0),
    ("2024-09-18", "추석 연휴", "HOLIDAY", LONG_HOLIDAY_FACTOR),
    ("2024-10-01", "임시공휴일(국군의날)", "HOLIDAY", HOLIDAY_FACTOR),
    ("2024-10-03", "개천절", "HOLIDAY", HOLIDAY_FACTOR),
    ("2024-10-09", "한글날", "HOLIDAY", HOLIDAY_FACTOR),
    # 여기부터는 S15P31A308-162 표 그대로 (기본 730일 출력의 범위)
    ("2024-12-25", "성탄절", "HOLIDAY", HOLIDAY_FACTOR),
    ("2025-01-01", "신정", "HOLIDAY", HOLIDAY_FACTOR),
    ("2025-01-27", "임시공휴일(설 연휴)", "HOLIDAY", LONG_HOLIDAY_FACTOR),
    ("2025-01-28", "설날 연휴", "HOLIDAY", LONG_HOLIDAY_FACTOR),
    ("2025-01-29", "설날", "HOLIDAY_CLOSED", 0.0),
    ("2025-01-30", "설날 연휴", "HOLIDAY", LONG_HOLIDAY_FACTOR),
    ("2025-03-01", "삼일절", "HOLIDAY", HOLIDAY_FACTOR),
    ("2025-03-03", "대체공휴일(삼일절)", "HOLIDAY", HOLIDAY_FACTOR),
    ("2025-05-05", "어린이날 · 부처님오신날", "HOLIDAY", HOLIDAY_FACTOR),
    ("2025-05-06", "대체공휴일(어린이날 · 부처님오신날)", "HOLIDAY", HOLIDAY_FACTOR),
    ("2025-06-03", "대통령 선거일", "HOLIDAY", HOLIDAY_FACTOR),
    ("2025-06-06", "현충일", "HOLIDAY", HOLIDAY_FACTOR),
    ("2025-08-15", "광복절", "HOLIDAY", HOLIDAY_FACTOR),
    ("2025-10-03", "개천절", "HOLIDAY", HOLIDAY_FACTOR),
    ("2025-10-05", "추석 연휴", "HOLIDAY", LONG_HOLIDAY_FACTOR),
    ("2025-10-06", "추석", "HOLIDAY_CLOSED", 0.0),
    ("2025-10-07", "추석 연휴", "HOLIDAY", LONG_HOLIDAY_FACTOR),
    ("2025-10-08", "대체공휴일(추석)", "HOLIDAY", LONG_HOLIDAY_FACTOR),
    ("2025-10-09", "한글날", "HOLIDAY", HOLIDAY_FACTOR),
    ("2025-12-25", "성탄절", "HOLIDAY", HOLIDAY_FACTOR),
    ("2026-01-01", "신정", "HOLIDAY", HOLIDAY_FACTOR),
    ("2026-02-16", "설날 연휴", "HOLIDAY", LONG_HOLIDAY_FACTOR),
    ("2026-02-17", "설날", "HOLIDAY_CLOSED", 0.0),
    ("2026-02-18", "설날 연휴", "HOLIDAY", LONG_HOLIDAY_FACTOR),
    ("2026-03-01", "삼일절", "HOLIDAY", HOLIDAY_FACTOR),
    ("2026-03-02", "대체공휴일(삼일절)", "HOLIDAY", HOLIDAY_FACTOR),
    ("2026-05-05", "어린이날", "HOLIDAY", HOLIDAY_FACTOR),
    ("2026-05-24", "부처님오신날", "HOLIDAY", HOLIDAY_FACTOR),
    ("2026-05-25", "대체공휴일(부처님오신날)", "HOLIDAY", HOLIDAY_FACTOR),
    ("2026-06-03", "지방선거일", "HOLIDAY", HOLIDAY_FACTOR),
    ("2026-06-06", "현충일", "HOLIDAY", HOLIDAY_FACTOR),
    ("2026-08-15", "광복절", "HOLIDAY", HOLIDAY_FACTOR),
    ("2026-08-17", "대체공휴일(광복절)", "HOLIDAY", HOLIDAY_FACTOR),
    ("2026-09-24", "추석 연휴", "HOLIDAY", LONG_HOLIDAY_FACTOR),
    ("2026-09-25", "추석", "HOLIDAY_CLOSED", 0.0),
    ("2026-09-26", "추석 연휴", "HOLIDAY", LONG_HOLIDAY_FACTOR),
    ("2026-10-03", "개천절", "HOLIDAY", HOLIDAY_FACTOR),
    ("2026-10-05", "대체공휴일(개천절)", "HOLIDAY", HOLIDAY_FACTOR),
    ("2026-10-09", "한글날", "HOLIDAY", HOLIDAY_FACTOR),
    ("2026-12-25", "성탄절", "HOLIDAY", HOLIDAY_FACTOR),
]

# 가게 근처 가상 지역 행사 (날짜, 이름, 배수). 이름 · 날짜 모두 가상이다. 배달 비중은 바꾸지 않는다
# 2021 ~ 2024-10 은 2025년과 같은 모양(봄 축제 이틀 · 5월 장터 · 9월 체육대회 · 12월 경품)으로 더했다. 2024-10-12 이후는 예전 표 그대로
LOCAL_EVENTS = [
    ("2021-04-03", "벚꽃길 걷기 축제", 1.4),
    ("2021-04-04", "벚꽃길 걷기 축제", 1.4),
    ("2021-05-15", "동네 플리마켓", 1.3),
    ("2021-09-25", "구민 체육대회", 1.35),
    ("2021-12-11", "상가 연말 경품 행사", 1.25),
    ("2022-04-02", "벚꽃길 걷기 축제", 1.4),
    ("2022-04-03", "벚꽃길 걷기 축제", 1.4),
    ("2022-05-14", "동네 플리마켓", 1.3),
    ("2022-09-24", "구민 체육대회", 1.35),
    ("2022-12-10", "상가 연말 경품 행사", 1.25),
    ("2023-04-01", "벚꽃길 걷기 축제", 1.4),
    ("2023-04-02", "벚꽃길 걷기 축제", 1.4),
    ("2023-05-20", "동네 플리마켓", 1.3),
    ("2023-09-16", "구민 체육대회", 1.35),
    ("2023-12-16", "상가 연말 경품 행사", 1.25),
    ("2024-04-06", "벚꽃길 걷기 축제", 1.4),
    ("2024-04-07", "벚꽃길 걷기 축제", 1.4),
    ("2024-05-18", "동네 플리마켓", 1.3),
    ("2024-09-28", "구민 체육대회", 1.35),
    ("2025-04-05", "벚꽃길 걷기 축제", 1.4),
    ("2025-04-06", "벚꽃길 걷기 축제", 1.4),
    ("2025-05-17", "동네 플리마켓", 1.3),
    ("2025-09-20", "구민 체육대회", 1.35),
    ("2025-12-13", "상가 연말 경품 행사", 1.25),
    ("2026-04-11", "벚꽃길 걷기 축제", 1.4),
    ("2026-04-12", "벚꽃길 걷기 축제", 1.4),
    ("2026-05-16", "동네 플리마켓", 1.3),
    ("2026-08-29", "여름밤 야시장", 1.3),
]

# 기본 틀(donkatsu)의 재료 · 거래처 · 레시피 · 주기형 소비. 형식은 store_templates.py 머리 주석
INGREDIENTS = DONKATSU.ingredients
SUPPLIERS = DONKATSU.suppliers
RECIPES = DONKATSU.recipes
CYCLE_USAGE = DONKATSU.cycle_usage
CYCLE_USAGE_CV = 0.25  # 주기형은 양이 일정하지 않다

# 발주 주기(R)마다 "R + 리드타임(L) + 1일치 + 안전재고"까지 채운다. 안전재고 = SAFETY_Z × σ × √(R + L)
REORDER_BUFFER_DAYS = 1
SAFETY_Z = 3
TARGET_WINDOW_DAYS = 730  # 목표 재고를 잴 때 보는 최근 기간 (_purchase_docs, PERIOD 방식)
# 발주 방식 (가게 틀의 order_policy). PERIOD = 기간 평균 · σ 하나로 고정된 목표 (donkatsu — 기본 출력을 예전과 바이트 단위로 같게).
# EXPECTED = 다가올 날들의 기대 소비 합 + 잔차 안전재고 (새 틀 6종)
ORDER_POLICY_PERIOD = "PERIOD"
ORDER_POLICY_EXPECTED = "EXPECTED"
CYCLE_BATCH_CYCLES = 12  # 주기형 재료 한 번 주문에 더 얹는 발주 주기 수 (모든 틀). 백엔드 추정 음수 비율 실측: 0→72%, 4→20%, 12→9% (S15P31A308-240)
EXPECTED_SAFETY_Z = 4  # 3 이면 378회 스윕에서 주기형(올리브유) 잡음으로 1회 음수 (S15P31A308-240)


@dataclass
class SampleData:
    menus: pd.DataFrame
    ingredients: pd.DataFrame
    suppliers: pd.DataFrame
    supplier_products: pd.DataFrame
    recipes: pd.DataFrame
    pos_sales: pd.DataFrame
    closed_days: pd.DataFrame
    purchase_docs: pd.DataFrame
    purchase_doc_lines: pd.DataFrame
    true_stock: pd.DataFrame
    # 아래는 생성 정답. seed_sql 은 읽지 않는다 (서비스 입력이 아니다)
    events: pd.DataFrame
    weather: pd.DataFrame
    true_demand: pd.DataFrame
    true_usage: pd.DataFrame


def _events(dates: pd.DatetimeIndex) -> pd.DataFrame:
    """기간 안의 공휴일 · 지역 행사. 하루 한 행 (event_date, name, kind, multiplier, scope)."""
    rows = [
        {"event_date": pd.Timestamp(d), "name": n, "kind": k, "multiplier": m, "scope": "NATIONAL"}
        for d, n, k, m in HOLIDAYS
    ] + [
        {"event_date": pd.Timestamp(d), "name": n, "kind": "LOCAL_EVENT", "multiplier": m, "scope": "STORE"}
        for d, n, m in LOCAL_EVENTS
    ]
    ev = pd.DataFrame(rows, columns=["event_date", "name", "kind", "multiplier", "scope"])
    ev = ev[ev["event_date"].between(dates[0], dates[-1])].sort_values("event_date").reset_index(drop=True)
    ev["event_date"] = ev["event_date"].dt.date
    return ev


def _closed_days(dates: pd.DatetimeIndex, events: pd.DataFrame, rng: np.random.Generator) -> set[pd.Timestamp]:
    """설 · 추석 당일(HOLIDAY_CLOSED) + 달마다 CLOSED_PER_MONTH 일 임의 휴무. 임의 휴무는 공휴일 · 행사 날을 피한다."""
    event_days = {pd.Timestamp(d) for d in events["event_date"]}
    closed = {pd.Timestamp(d) for d in events.loc[events["kind"] == "HOLIDAY_CLOSED", "event_date"]}
    for _, month_days in pd.Series(dates, index=dates).groupby([dates.year, dates.month]):
        candidates = np.array([d for d in month_days.to_numpy() if pd.Timestamp(d) not in event_days])
        k = min(CLOSED_PER_MONTH, len(candidates))
        closed.update(pd.Timestamp(d) for d in rng.choice(candidates, size=k, replace=False))
    return closed


def _rain(dates: pd.DatetimeIndex, rng: np.random.Generator) -> pd.Series:
    """날마다 비 여부(0/1). 합성 데이터의 정답 잡음일 뿐 서비스 입력이 아니다 (DECISIONS #30)."""
    p = np.where(dates.month.isin(RAIN_SUMMER_MONTHS), RAIN_P_SUMMER, RAIN_P)
    return pd.Series((rng.random(len(dates)) < p).astype(int), index=dates)


def _expected(
    dates: pd.DatetimeIndex,
    closed: set,
    events: pd.DataFrame,
    rain: pd.Series,
    template: StoreTemplate = DONKATSU,
    scale: float = 1.0,
) -> pd.DataFrame:
    """영업일 × 메뉴 × 채널의 노이즈 전 기대 판매량 (true_demand). scale 은 모든 메뉴 평일 평균에 곱한다."""
    event_of = {pd.Timestamp(e.event_date): (e.kind, e.multiplier) for e in events.itertuples()}
    end = dates[-1]
    rows = []
    for day in dates:
        if day in closed:
            continue
        kind, event_factor = event_of.get(day, (None, 1.0))
        trend = (1 + GROWTH_PER_YEAR) ** ((day - end).days / 365)
        for name, base, delivery_share, _ in template.menus:
            season = template.month_factor[day.month] * template.menu_month_factor.get(name, {}).get(day.month, 1.0)
            # scale 1.0 이면 base * 1.0 은 base 와 같은 float 라 기본 출력이 바뀌지 않는다
            total = base * scale * season * trend * event_factor
            share = delivery_share
            if rain[day]:
                share += (1 - share) * RAIN_DELIVERY_PULL
            if kind == "HOLIDAY":
                share += (1 - share) * HOLIDAY_DELIVERY_PULL
            for channel, ch_share in (("HALL", 1 - share), ("DELIVERY", share)):
                rows.append(
                    {
                        "sold_date": day.date(),
                        "menu_name": name,
                        "channel": channel,
                        "expected_qty": round(total * ch_share * _day_factor(day, channel, template), 4),
                    }
                )
    return pd.DataFrame(rows, columns=["sold_date", "menu_name", "channel", "expected_qty"])


def _day_factor(day: pd.Timestamp, channel: str, template: StoreTemplate = DONKATSU) -> float:
    if day.dayofweek in (4, 5):
        return template.weekend_factor
    if channel == "DELIVERY" and day.dayofweek == 6:
        return template.delivery_sunday_factor
    return 1.0


def _sales(expected: pd.DataFrame, rng: np.random.Generator) -> pd.DataFrame:
    """기대값에 노이즈를 얹는다. 포스 원본처럼 판매가 있는 (날짜, 메뉴, 채널)만 행으로 남긴다. 0개 판매는 행이 없다."""
    rows = []
    for e in expected.itertuples():
        mean = e.expected_qty
        if mean < LOW_MEAN:
            qty = int(rng.poisson(mean))
        else:
            qty = max(0, int(round(rng.normal(mean, mean * NOISE_CV))))
        if qty:
            rows.append({"sold_date": e.sold_date, "menu_name": e.menu_name, "qty": qty, "channel": e.channel})
    return pd.DataFrame(rows, columns=["sold_date", "menu_name", "qty", "channel"])


def _daily_usage(
    sales: pd.DataFrame, dates: pd.DatetimeIndex, rng: np.random.Generator, template: StoreTemplate = DONKATSU
) -> pd.DataFrame:
    """재료별 실제 일 소비량 (index=날짜, columns=재료명). 휴무일은 0."""
    bowls = (
        sales.assign(sold_date=pd.to_datetime(sales["sold_date"]))
        .pivot_table(index="sold_date", columns="menu_name", values="qty", aggfunc="sum", fill_value=0)
        .reindex(index=dates, columns=[m[0] for m in template.menus], fill_value=0)
    )
    usage = pd.DataFrame(0.0, index=dates, columns=[i[0] for i in template.ingredients])
    for menu, items in template.recipes.items():
        for ing, q in items.items():
            usage[ing] += bowls[menu] * q
    for ing, (menus, per_bowl) in template.cycle_usage.items():
        mean = bowls[menus].sum(axis=1) * per_bowl
        usage[ing] = np.maximum(0, rng.normal(mean, mean * CYCLE_USAGE_CV))
    return usage


def _expected_usage(expected: pd.DataFrame, dates: pd.DatetimeIndex, template: StoreTemplate) -> pd.DataFrame:
    """재료별 기대 일 소비 (index=날짜, columns=재료명). 기대 판매(두 채널 합, 휴무일 0) × 레시피, 주기형은 × 그릇당 평균 소비."""
    bowls = (
        expected.assign(sold_date=pd.to_datetime(expected["sold_date"]))
        .pivot_table(index="sold_date", columns="menu_name", values="expected_qty", aggfunc="sum", fill_value=0.0)
        .reindex(index=dates, columns=[m[0] for m in template.menus], fill_value=0.0)
    )
    out = pd.DataFrame(0.0, index=dates, columns=[i[0] for i in template.ingredients])
    for menu, items in template.recipes.items():
        for ing, q in items.items():
            out[ing] += bowls[menu] * q
    for ing, (menus, per_bowl) in template.cycle_usage.items():
        out[ing] = bowls[menus].sum(axis=1) * per_bowl
    return out


def _purchase_docs(
    usage: pd.DataFrame,
    supplier_products: pd.DataFrame,
    suppliers: pd.DataFrame,
    closed: set,
    rng: np.random.Generator,
    expected_usage: pd.DataFrame | None = None,
    cycle_ingredients: frozenset = frozenset(),
) -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    """거래처마다 발주 주기(R)일에 한 번 주문하고, 리드타임 뒤 입고되는 것으로 영수증을 만든다.

    - 주문은 영업일에만. 도착일이 휴무면 다음 영업일로 미룬다
    - 품목마다 목표(PERIOD 방식, expected_usage 없음) = 평균 소비 × (R + L + 1) + SAFETY_Z × σ × √(R + L).
      EXPECTED 방식(expected_usage 있음) = 주문일부터 R + L + 1 일의 기대 소비 합 + EXPECTED_SAFETY_Z × σ_resid × √(R + L).
      σ_resid = 영업일의 (실제 소비 − 기대 소비) 표준편차. 주문량 = 목표 − (재고 + 배송 중), 포장 단위로 올림
    - 한 번 주문의 합계가 최소 주문금액보다 작으면 가장 빨리 소진되는 품목을 한 포장씩 더한다
    - 거래처 · 입고일마다 매입 문서(거래명세서) 1장. 단가는 기준 단가 ± PRICE_CV

    반환: 매입 문서, 문서 줄, 재료별 실제 마감 재고 (추정 재고 검증용 정답). 열 이름은 스키마 V1 purchase_doc · purchase_doc_line
    """
    # 목표 재고의 평균 · σ 는 마지막 TARGET_WINDOW_DAYS 일의 영업일로 잰다. 기간이 그보다 짧으면(기본 730일 포함) 전체 기간이다.
    # 5년치처럼 길면 성장 추세 탓에 전체 평균이 끝 무렵 소비보다 한참 작아 재고가 음수로 떨어진다 (S15P31A308-240)
    recent = usage.index[-TARGET_WINDOW_DAYS:]
    open_usage = usage.loc[[d for d in recent if d not in closed]]
    avg, sigma = open_usage.mean(), open_usage.std()
    sp = supplier_products.merge(suppliers, on="supplier_id")
    # usage 열 = 틀의 재료 이름을 ingredient_id(1부터) 순서대로 둔 것
    name = {p.supplier_product_id: usage.columns[p.ingredient_id - 1] for p in sp.itertuples()}
    ingredient_of = dict(zip(sp["supplier_product_id"], sp["ingredient_id"], strict=False))

    # 주기형 묶음 주문량(하루 소비 평균 × CYCLE_BATCH_CYCLES, 주문 때 발주 주기를 곱한다). 모든 틀 공통 (돈까스 포함, 2026-11-03 사용자 결정)
    cycle_batch: dict[str, float] = {ing: float(avg[ing]) * CYCLE_BATCH_CYCLES for ing in cycle_ingredients}
    if expected_usage is None:

        def target(p, day) -> float:
            horizon = p.order_cycle_days + p.lead_time_days
            ing = name[p.supplier_product_id]
            return avg[ing] * (horizon + REORDER_BUFFER_DAYS) + SAFETY_Z * sigma[ing] * math.sqrt(horizon)

    else:
        # 생성기는 기대 판매(true_demand)를 이미 안다 — 다가올 R + L + 1 일의 기대 소비를 그대로 더한다.
        # 기간 평균 하나로는 계절 · 메뉴 월 배수가 겹치는 성수기를 못 덮는다
        open_days = [d for d in usage.index if d not in closed]
        sigma_resid = (usage.loc[open_days] - expected_usage.loc[open_days]).std().fillna(0.0)
        avg = expected_usage.loc[open_days].mean()  # 최소 주문금액 채울 품목(fastest) 고르기용
        # 주기형: 주문할 때 목표에 더하는 양 = 하루 기대 소비 평균 × CYCLE_BATCH_CYCLES (× 발주 주기, 주문 때 곱한다).
        # 매 주기 조금씩 채우면 입고 직전 남은 재고가 입고량보다 커져, 백엔드 주기형 추정(마지막 입고량 − 그릇당 × 그 뒤 판매)이
        # 음수 → needsCount 가 된다 (로컬 실측 위글리/데이터/240_화면충족)
        cycle_batch = {ing: float(avg[ing]) * CYCLE_BATCH_CYCLES for ing in cycle_ingredients}
        cum = expected_usage.cumsum().to_numpy()
        pos = {day: i for i, day in enumerate(usage.index)}
        col = {ing: j for j, ing in enumerate(usage.columns)}
        last = len(usage.index) - 1
        is_closed = [d in closed for d in usage.index]

        def next_open(i: int) -> int:
            while i <= last and is_closed[i]:
                i += 1
            return i

        cover_end: dict[tuple[int, int, int], int] = {}

        def covered_until(i: int, cycle: int, lead: int) -> int:
            """주문일 i 에 넣은 물건으로 버텨야 하는 마지막 날: 다음 주문(주기 뒤 첫 영업일)이 휴무를 건너 도착하는 날의 전날
            + REORDER_BUFFER_DAYS. 휴무일 소비는 0 이지만 휴무 때문에 다음 입고가 밀리는 것까지 덮는다."""
            key = (i, cycle, lead)
            if key not in cover_end:
                arrive = next_open(next_open(i + cycle) + lead)
                cover_end[key] = min(last, arrive - 1 + REORDER_BUFFER_DAYS)  # 기간 끝 너머 소비는 재지 않는다
            return cover_end[key]

        def target(p, day) -> float:
            horizon = p.order_cycle_days + p.lead_time_days
            ing = name[p.supplier_product_id]
            i, j = pos[day], col[ing]
            k = covered_until(i, int(p.order_cycle_days), int(p.lead_time_days))
            ahead = cum[k, j] - (cum[i - 1, j] if i else 0.0)
            return ahead + EXPECTED_SAFETY_Z * sigma_resid[ing] * math.sqrt(horizon)

    # 첫날 재고도 목표만큼. 첫날이 휴무라 첫 주문이 늦어져도 첫 입고까지 버틴다
    stock = {p.supplier_product_id: target(p, usage.index[0]) for p in sp.itertuples()}
    pending: dict[int, dict[pd.Timestamp, float]] = {p.supplier_product_id: {} for p in sp.itertuples()}
    last_order: dict[int, pd.Timestamp] = {}
    end = usage.index[-1]
    arrivals: dict[tuple[pd.Timestamp, int], list[dict]] = {}
    stock_rows = []
    by_supplier = [(sid, items.iloc[0], list(items.itertuples())) for sid, items in sp.groupby("supplier_id")]
    product = {p.supplier_product_id: p for p in sp.itertuples()}

    daily = {ing: usage[ing].to_dict() for ing in usage.columns}
    for day in usage.index:
        for pid in stock:
            stock[pid] += pending[pid].pop(day, 0.0)

        for supplier_id, s, items in by_supplier:
            due = supplier_id not in last_order or (day - last_order[supplier_id]).days >= s.order_cycle_days
            if day in closed or not due:
                continue
            last_order[supplier_id] = day
            packs = {}
            for p in items:
                goal = target(p, day)
                position = stock[p.supplier_product_id] + sum(pending[p.supplier_product_id].values())
                if position < goal:
                    need = goal - position
                    if name[p.supplier_product_id] in cycle_batch:
                        # 주기형(EXPECTED 방식): 바닥나기 직전에만 몇 주기치를 한 번에 채운다
                        need += cycle_batch[name[p.supplier_product_id]] * p.order_cycle_days
                    packs[p.supplier_product_id] = max(p.moq, math.ceil(need / p.base_qty_per_pack))
            if not packs:
                continue
            # 최소 주문금액을 채울 품목. 주기형 묶음은 되도록 고르지 않는다 (입고 직전 남은 양이 커지면 백엔드 주기형 추정이 어긋난다)
            pick = [pid for pid in packs if name[pid] not in cycle_batch] or list(packs)
            fastest = max(pick, key=lambda pid: avg[name[pid]] / product[pid].base_qty_per_pack)
            while sum(product[pid].price * n for pid, n in packs.items()) < s.min_order_amount:
                packs[fastest] += 1

            arrive = day + pd.Timedelta(days=int(s.lead_time_days))
            while arrive in closed:
                arrive += pd.Timedelta(days=1)
            for pid, n in packs.items():
                p = product[pid]
                pending[pid][arrive] = pending[pid].get(arrive, 0.0) + n * p.base_qty_per_pack
                if arrive <= end:
                    unit_price = int(round(p.price * rng.normal(1, PRICE_CV), -1))
                    arrivals.setdefault((arrive, supplier_id), []).append(
                        {
                            "raw_name": p.product_name,
                            "qty": n,
                            "unit": p.pack_unit,
                            "unit_price": unit_price,
                            "amount": unit_price * n,
                            "supplier_product_id": pid,
                            "ingredient_id": p.ingredient_id,
                        }
                    )

        for pid in stock:
            stock[pid] -= daily[name[pid]][day]
            stock_rows.append({"stock_date": day.date(), "ingredient_id": ingredient_of[pid], "qty": round(stock[pid], 1)})

    docs, lines = [], []
    for doc_id, (arrive, supplier_id) in enumerate(sorted(arrivals), 1):
        doc_lines = arrivals[(arrive, supplier_id)]
        # 재고 실사 전 기록이므로 전부 과거 기록(PAST). 설계 4.3절
        docs.append(
            {
                "doc_id": doc_id,
                "doc_type": "STATEMENT",
                "doc_date": arrive.date(),
                "kind": "PAST",
                "supplier_id": supplier_id,
                "total_amount": sum(line["amount"] for line in doc_lines),
            }
        )
        for line_no, line in enumerate(doc_lines, 1):
            lines.append({"doc_id": doc_id, "line_no": line_no, **line})
    return (
        pd.DataFrame(docs, columns=["doc_id", "doc_type", "doc_date", "kind", "supplier_id", "total_amount"]),
        pd.DataFrame(
            lines,
            columns=["doc_id", "line_no", "raw_name", "qty", "unit", "unit_price", "amount", "supplier_product_id", "ingredient_id"],
        ),
        pd.DataFrame(stock_rows, columns=["stock_date", "ingredient_id", "qty"]),
    )


def check_period(end_date=END_DATE, days: int = DAYS) -> pd.DatetimeIndex:
    """생성 기간(마지막 날 포함 days 일). days < 1 이거나 공휴일 표 범위 밖이면 ValueError."""
    if days < 1:
        raise ValueError(f"기간은 1일 이상이어야 한다: --days {days}")
    dates = pd.date_range(end=pd.Timestamp(end_date), periods=days, freq="D")
    if dates[0] < pd.Timestamp(HOLIDAY_TABLE_START) or dates[-1] > pd.Timestamp(HOLIDAY_TABLE_END):
        raise ValueError(
            f"공휴일 표 범위({HOLIDAY_TABLE_START}~{HOLIDAY_TABLE_END}) 밖: {dates[0].date()}~{dates[-1].date()}"
        )
    return dates


def generate(
    end_date=END_DATE, days: int = DAYS, seed: int = 15, template=DEFAULT_TEMPLATE, scale: float = 1.0
) -> SampleData:
    """가게 틀(key 또는 StoreTemplate) 하나로 days 일치 샘플을 만든다. scale 은 모든 메뉴 평일 평균에 곱한다.

    인자를 주지 않으면 S15P31A308-162 의 돈까스집 출력과 바이트 단위로 같다.
    """
    t = get_template(template)
    if scale <= 0:
        raise ValueError(f"scale 은 0 보다 커야 한다: {scale}")
    dates = check_period(end_date, days)
    rng = np.random.default_rng(seed)
    events = _events(dates)
    closed = _closed_days(dates, events, rng)
    rain = _rain(dates, rng)
    expected = _expected(dates, closed, events, rain, t, scale)
    sales = _sales(expected, rng)

    menus = pd.DataFrame(
        [
            # base_qty 는 생성용 값. 규모 배수를 곱한 가게면 곱한 값을 적는다 (scale 1.0 이면 정수 그대로)
            {"menu_id": i, "name": n, "pos_name": n, "base_qty": b if scale == 1.0 else round(b * scale, 3),
             "delivery_share": s, "price": price}
            for i, (n, b, s, price) in enumerate(t.menus, 1)
        ]
    )
    suppliers = pd.DataFrame(
        [
            {"supplier_id": i, "name": n, "channel": c, "lead_time_days": lt, "order_cycle_days": r, "min_order_amount": moa}
            for i, (n, c, lt, r, moa) in enumerate(t.suppliers, 1)
        ]
    )
    supplier_id = dict(zip(suppliers["name"], suppliers["supplier_id"], strict=False))
    ingredients = pd.DataFrame(
        [
            {
                "ingredient_id": i,
                "name": n,
                "unit": u,
                "kind": kind,
                "storage_type": storage,
                "safety_days": 1,
                "shelf_life_days": shelf,
            }
            for i, (n, u, kind, storage, *_, shelf) in enumerate(t.ingredients, 1)
        ]
    )
    ingredient_id = dict(zip(ingredients["name"], ingredients["ingredient_id"], strict=False))
    supplier_products = pd.DataFrame(
        [
            {
                "supplier_product_id": i,
                "supplier_id": supplier_id[s],
                "ingredient_id": ingredient_id[n],
                "product_name": p,
                "pack_unit": pack_unit,
                "base_qty_per_pack": per_pack,
                "moq": moq,
                "price": price,
            }
            for i, (n, _, _, _, s, p, pack_unit, per_pack, moq, price, _) in enumerate(t.ingredients, 1)
        ]
    )
    menu_id = dict(zip(menus["name"], menus["menu_id"], strict=False))
    recipes = pd.DataFrame(
        [
            {"menu_id": menu_id[m], "ingredient_id": ingredient_id[ing], "qty_per_serving": q}
            for m, items in t.recipes.items()
            for ing, q in items.items()
        ]
    )
    usage = _daily_usage(sales, dates, rng, t)
    expected_usage = _expected_usage(expected, dates, t) if t.order_policy == ORDER_POLICY_EXPECTED else None
    purchase_docs, purchase_doc_lines, true_stock = _purchase_docs(
        usage, supplier_products, suppliers, closed, rng, expected_usage,
        frozenset(i[0] for i in t.ingredients if i[2] == "CYCLE"),
    )
    true_usage = (
        usage.rename(columns=ingredient_id)
        .stack()
        .round(4)
        .rename_axis(["usage_date", "ingredient_id"])
        .rename("qty")
        .reset_index()
    )
    true_usage["usage_date"] = true_usage["usage_date"].dt.date

    return SampleData(
        menus=menus,
        ingredients=ingredients,
        suppliers=suppliers,
        supplier_products=supplier_products,
        recipes=recipes,
        pos_sales=sales,
        closed_days=pd.DataFrame({"closed_date": sorted(d.date() for d in closed)}),
        purchase_docs=purchase_docs,
        purchase_doc_lines=purchase_doc_lines,
        true_stock=true_stock,
        events=events,
        weather=pd.DataFrame({"date": [d.date() for d in dates], "rain": rain.to_numpy()}),
        true_demand=expected,
        true_usage=true_usage,
    )
