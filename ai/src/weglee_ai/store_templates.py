"""합성 데이터 생성기의 가게 틀 (S15P31A308-240).

틀 하나 = 업종 하나의 메뉴판 · 재료 · 레시피 · 거래처 · 계절 성향. sample_data.generate(template=...) 가 고른 틀로 판매 · 입고를 만든다.
공휴일 · 비 · 성장 · 노이즈 · 휴무 · 발주 규칙은 모든 틀 공통이라 sample_data.py 에 있다.

**모든 값은 일반 상식에 기댄 가상값이다.** 실존 가게 · 업체의 메뉴 · 판매량 · 단가가 아니다. 거래처 이름도 지어낸 것이다.
donkatsu 는 S15P31A308-52 · 162 의 돈까스집 상수를 그대로 옮긴 것이라 기본 출력이 예전과 바이트 단위로 같다.
재료 이름 · 단위 · 보관은 ai/seed/ingredient_catalog.csv 에 같은 이름이 있으면 그 값과 맞췄다 (tests/test_store_templates.py 가 확인).
template_from_existing 은 이미 있는 가게를 DB 에서 떠 온 CSV 로 틀을 만든다 (S15P31A308-243, 아래 EXISTING_* 규칙).

형식 (donkatsu 를 기준으로 처음 정한 모양):
- menus: (메뉴명, 평일 평균 판매량, 배달 비중, 판매가(원)). 평균은 기간 마지막 날 수준이고, 판매가는 menus.csv 에만 나간다
- menu_month_factor: 메뉴별 월 배수. month_factor(모든 메뉴 공통)에 곱한다. 없는 달은 1.0
- weekend_factor: 금 · 토 배수. delivery_sunday_factor: 배달만 일요일에 곱하는 배수
- ingredients: (재료명, 단위, 분류, 보관, 거래처, 상품명, 포장 이름, 포장당 기준 단위량, MOQ(포장), 단가(원/포장), 유통기한(일))
  분류: BOM = 메뉴 판매 × 1인분, ONE_TO_ONE = 사 온 그대로 판다, CYCLE = 입고 기록으로만 (설계 4.1절)
- suppliers: (거래처명, 채널, 리드타임(일), 발주 주기(일), 최소 주문금액(원))
- recipes: 메뉴 1인분당 재료량 (재료 기준 단위). 주기형 재료는 넣지 않는다 (DECISIONS #26)
- cycle_usage: 주기형 재료의 실제 소비. 재료명 → (소비가 따라가는 메뉴, 그릇당 평균 소비량). 서비스는 이 값을 모른다
"""

import math
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd


@dataclass(frozen=True)
class StoreTemplate:
    key: str
    label: str  # 업종 한글 이름. 여러 가게 모드의 가게 이름("샘플 치킨 3호점")에 쓴다
    menus: tuple
    menu_month_factor: dict
    month_factor: dict
    weekend_factor: float
    delivery_sunday_factor: float
    ingredients: tuple
    suppliers: tuple
    recipes: dict
    cycle_usage: dict
    # 발주 방식 (sample_data ORDER_POLICY_*). EXPECTED = 다가올 날들의 기대 소비로 목표 재고.
    # PERIOD = 기간 평균 하나 (donkatsu 만 — 기본 출력을 예전과 바이트 단위로 같게 두려고)
    order_policy: str = "EXPECTED"

    @property
    def delivery_share(self) -> float:
        """틀의 배달 비중 = 메뉴 배달 비중을 평일 평균 판매량으로 가중 평균한 값 (비 · 공휴일로 오르기 전)."""
        total = sum(base for _, base, _, _ in self.menus)
        return sum(base * share for _, base, share, _ in self.menus) / total


DONKATSU = StoreTemplate(
    key="donkatsu",
    label="돈까스",
    menus=(
        ("등심돈까스", 40, 0.4, 11000),
        ("안심돈까스", 25, 0.4, 12000),
        ("치즈돈까스", 20, 0.5, 12500),
        ("생선까스", 12, 0.3, 10500),
        ("돈까스정식", 18, 0.1, 13000),  # 배달이 적은 메뉴 — 배달 0개인 날이 생긴다
        ("카레돈까스", 15, 0.4, 11500),
        ("우동", 14, 0.3, 8000),
        ("콜라", 30, 0.5, 2000),
    ),
    # 우동은 겨울에 늘고 여름에 준다, 콜라는 반대
    menu_month_factor={
        "우동": {1: 1.45, 2: 1.35, 3: 1.1, 6: 0.8, 7: 0.65, 8: 0.65, 9: 0.85, 11: 1.2, 12: 1.4},
        "콜라": {1: 0.85, 2: 0.85, 6: 1.15, 7: 1.3, 8: 1.3, 12: 0.9},
    },
    # 여름에 조금 줄고 연말(12월)에 는다
    month_factor={1: 1.05, 2: 0.95, 3: 1.0, 4: 1.0, 5: 1.02, 6: 0.97, 7: 0.93, 8: 0.92, 9: 1.0, 10: 1.02, 11: 1.03, 12: 1.15},
    weekend_factor=1.4,
    delivery_sunday_factor=1.2,  # 배달은 일요일에도 는다. 홀과 요일 모양을 다르게 하려는 값
    ingredients=(
        ("돼지고기 등심", "G", "BOM", "CHILLED", "한빛축산", "돈등심 5kg", "팩", 5000, 1, 55000, 5),
        ("돼지고기 안심", "G", "BOM", "CHILLED", "한빛축산", "돈안심 5kg", "팩", 5000, 1, 60000, 5),
        ("동태살", "G", "BOM", "FROZEN", "한빛축산", "냉동 동태살 2kg", "팩", 2000, 1, 24000, 90),
        ("모짜렐라 치즈", "G", "BOM", "CHILLED", "푸른식자재", "모짜렐라 2.5kg", "봉", 2500, 1, 32000, 30),
        ("빵가루", "G", "BOM", "AMBIENT", "푸른식자재", "습식 빵가루 2kg", "봉", 2000, 1, 9000, 30),
        ("계란", "EA", "BOM", "CHILLED", "푸른식자재", "계란 30구", "판", 30, 1, 8500, 14),
        ("양배추", "G", "BOM", "FRESH", "새벽청과", "양배추 8kg", "망", 8000, 1, 12000, 7),
        ("쌀", "G", "BOM", "AMBIENT", "새벽청과", "쌀 20kg", "포", 20000, 1, 62000, 180),
        ("면", "G", "BOM", "CHILLED", "새벽청과", "생우동면 3kg", "박스", 3000, 1, 9600, 7),
        ("콜라", "EA", "ONE_TO_ONE", "AMBIENT", "푸른식자재", "콜라 355ml 24캔", "박스", 24, 1, 18000, 365),
        ("식용유", "ML", "CYCLE", "AMBIENT", "푸른식자재", "대두유 18L", "통", 18000, 1, 52000, 365),
        ("카레 블록", "G", "CYCLE", "AMBIENT", "푸른식자재", "카레 블록 1kg", "봉", 1000, 1, 11000, 365),
    ),
    suppliers=(
        ("한빛축산", "KAKAO", 1, 2, 100000),
        ("푸른식자재", "KAKAO", 2, 3, 50000),
        ("새벽청과", "KAKAO", 1, 1, 30000),
    ),
    recipes={
        "등심돈까스": {"돼지고기 등심": 150, "빵가루": 40, "계란": 0.5, "양배추": 50, "쌀": 200},
        "안심돈까스": {"돼지고기 안심": 140, "빵가루": 40, "계란": 0.5, "양배추": 50, "쌀": 200},
        "치즈돈까스": {"돼지고기 등심": 120, "모짜렐라 치즈": 60, "빵가루": 50, "계란": 0.5, "양배추": 50, "쌀": 200},
        "생선까스": {"동태살": 150, "빵가루": 40, "계란": 0.5, "양배추": 50, "쌀": 200},
        "돈까스정식": {"돼지고기 등심": 150, "빵가루": 40, "계란": 1, "양배추": 80, "쌀": 250},
        "카레돈까스": {"돼지고기 등심": 120, "빵가루": 40, "계란": 0.5, "쌀": 250},
        "우동": {"면": 250},
        "콜라": {"콜라": 1},
    },
    cycle_usage={
        "식용유": (["등심돈까스", "안심돈까스", "치즈돈까스", "생선까스", "돈까스정식", "카레돈까스"], 30),
        "카레 블록": (["카레돈까스"], 30),
    },
    order_policy="PERIOD",
)

# 분식: 배달 약 0.45. 어묵 · 라면은 겨울에, 쫄면은 여름에 는다
BUNSIK = StoreTemplate(
    key="bunsik", label="분식",
    menus=(("떡볶이", 45, 0.5, 5000), ("김밥", 50, 0.35, 3500), ("라면", 25, 0.3, 4500), ("순대", 18, 0.5, 5000),
           ("모듬튀김", 20, 0.55, 5000), ("어묵", 22, 0.3, 3000), ("쫄면", 12, 0.45, 6000), ("사이다", 15, 0.6, 2000)),
    menu_month_factor={
        "어묵": {1: 1.5, 2: 1.4, 3: 1.1, 6: 0.7, 7: 0.55, 8: 0.55, 9: 0.8, 11: 1.3, 12: 1.5},
        "라면": {1: 1.2, 2: 1.15, 7: 0.85, 8: 0.85, 12: 1.2},
        "쫄면": {1: 0.6, 2: 0.65, 5: 1.2, 6: 1.4, 7: 1.6, 8: 1.6, 9: 1.2, 12: 0.6},
        "사이다": {6: 1.15, 7: 1.3, 8: 1.3},
    },
    month_factor={1: 0.95, 2: 0.92, 3: 1.05, 4: 1.03, 5: 1.02, 6: 1.0, 7: 0.95, 8: 0.94, 9: 1.03, 10: 1.03, 11: 1.02, 12: 1.0},
    weekend_factor=1.3, delivery_sunday_factor=1.2,
    ingredients=(
        ("떡볶이 떡", "G", "BOM", "CHILLED", "한마음식품", "밀떡 3kg", "봉", 3000, 1, 9000, 7),
        ("어묵", "G", "BOM", "CHILLED", "한마음식품", "사각어묵 1kg", "봉", 1000, 1, 6000, 14),
        ("고추장", "G", "BOM", "AMBIENT", "한마음식품", "고추장 3kg", "통", 3000, 1, 15000, 180),
        ("순대", "G", "BOM", "CHILLED", "한마음식품", "찰순대 2kg", "봉", 2000, 1, 12000, 7),
        ("냉동 튀김", "G", "BOM", "FROZEN", "한마음식품", "냉동 모듬튀김 3kg", "봉", 3000, 1, 21000, 180),
        ("쫄면 사리", "G", "BOM", "CHILLED", "한마음식품", "쫄면 2kg", "봉", 2000, 1, 5000, 14),
        ("쌀", "G", "BOM", "AMBIENT", "새싹농산", "쌀 20kg", "포", 20000, 1, 62000, 180),
        ("김밥김", "EA", "BOM", "AMBIENT", "새싹농산", "김밥김 100매", "봉", 100, 1, 9000, 180),
        ("단무지", "G", "BOM", "CHILLED", "새싹농산", "김밥 단무지 2.5kg", "봉", 2500, 1, 6500, 30),
        ("계란", "EA", "BOM", "CHILLED", "새싹농산", "계란 30구", "판", 30, 1, 8500, 14),
        ("라면 사리", "EA", "BOM", "AMBIENT", "새싹농산", "라면 사리 40개", "박스", 40, 1, 20000, 180),
        ("사이다", "EA", "ONE_TO_ONE", "AMBIENT", "동네도매", "사이다 355ml 24캔", "박스", 24, 1, 17000, 365),
        ("식용유", "ML", "CYCLE", "AMBIENT", "동네도매", "대두유 18L", "통", 18000, 1, 52000, 365),
        ("참기름", "ML", "CYCLE", "AMBIENT", "동네도매", "참기름 1.8L", "병", 1800, 1, 25000, 180),
    ),
    suppliers=(("한마음식품", "KAKAO", 1, 2, 50000), ("새싹농산", "KAKAO", 1, 3, 40000), ("동네도매", "KAKAO", 2, 3, 30000)),
    recipes={
        "떡볶이": {"떡볶이 떡": 200, "어묵": 50, "고추장": 30}, "김밥": {"쌀": 120, "김밥김": 1, "단무지": 20, "계란": 0.3},
        "라면": {"라면 사리": 1, "계란": 0.5}, "순대": {"순대": 250}, "모듬튀김": {"냉동 튀김": 200}, "어묵": {"어묵": 120},
        "쫄면": {"쫄면 사리": 200, "고추장": 20, "계란": 0.5}, "사이다": {"사이다": 1},
    },
    cycle_usage={"식용유": (["모듬튀김"], 40), "참기름": (["김밥"], 3)},
)

CHINESE = StoreTemplate(
    key="chinese", label="중식",
    menus=(("짜장면", 50, 0.6, 7000), ("짬뽕", 35, 0.6, 9000), ("탕수육", 15, 0.7, 18000), ("볶음밥", 18, 0.55, 8500),
           ("군만두", 12, 0.6, 6000), ("짬뽕밥", 10, 0.5, 9500), ("콜라", 20, 0.7, 2000)),
    menu_month_factor={
        "짬뽕": {1: 1.2, 2: 1.15, 3: 1.05, 6: 0.9, 7: 0.8, 8: 0.8, 11: 1.1, 12: 1.2},
        "짬뽕밥": {1: 1.15, 2: 1.1, 7: 0.85, 8: 0.85, 12: 1.15},
        "콜라": {6: 1.15, 7: 1.3, 8: 1.3, 12: 0.9},
    },
    month_factor={1: 1.03, 2: 0.97, 3: 1.0, 4: 0.98, 5: 1.0, 6: 0.97, 7: 0.95, 8: 0.95, 9: 1.0, 10: 1.02, 11: 1.03, 12: 1.1},
    weekend_factor=1.35, delivery_sunday_factor=1.3,
    ingredients=(
        ("중화면", "G", "BOM", "CHILLED", "대륙식자재", "중화면 5kg", "박스", 5000, 1, 12000, 7),
        ("춘장", "G", "BOM", "AMBIENT", "대륙식자재", "춘장 2kg", "통", 2000, 1, 9000, 180),
        ("오징어", "G", "BOM", "FROZEN", "대륙식자재", "손질 오징어 2kg", "팩", 2000, 1, 22000, 180),
        ("홍합", "G", "BOM", "FROZEN", "대륙식자재", "냉동 홍합 1kg", "팩", 1000, 1, 6000, 180),
        ("냉동 만두", "G", "BOM", "FROZEN", "대륙식자재", "냉동 군만두 2.7kg", "봉", 2700, 1, 15000, 180),
        ("튀김가루", "G", "BOM", "AMBIENT", "대륙식자재", "튀김가루 10kg", "포", 10000, 1, 25000, 180),
        ("콜라", "EA", "ONE_TO_ONE", "AMBIENT", "대륙식자재", "콜라 355ml 24캔", "박스", 24, 1, 18000, 365),
        ("식용유", "ML", "CYCLE", "AMBIENT", "대륙식자재", "대두유 18L", "통", 18000, 1, 52000, 365),
        ("고춧가루", "G", "CYCLE", "AMBIENT", "대륙식자재", "고춧가루 3kg", "봉", 3000, 1, 50000, 180),
        ("돼지고기", "G", "BOM", "CHILLED", "우리축산", "돼지 앞다리살 5kg", "팩", 5000, 1, 45000, 3),
        ("양파", "G", "BOM", "FRESH", "초록청과", "양파 15kg", "망", 15000, 1, 18000, 7),
        ("쌀", "G", "BOM", "AMBIENT", "초록청과", "쌀 20kg", "포", 20000, 1, 62000, 180),
        ("계란", "EA", "BOM", "CHILLED", "초록청과", "계란 30구", "판", 30, 1, 8500, 21),
    ),
    suppliers=(("대륙식자재", "KAKAO", 1, 2, 60000), ("우리축산", "KAKAO", 1, 2, 80000), ("초록청과", "KAKAO", 1, 1, 30000)),
    recipes={
        "짜장면": {"중화면": 250, "춘장": 40, "돼지고기": 40, "양파": 80},
        "짬뽕": {"중화면": 250, "돼지고기": 30, "오징어": 40, "홍합": 60, "양파": 60},
        "탕수육": {"돼지고기": 300, "튀김가루": 100, "양파": 50},
        "볶음밥": {"쌀": 250, "계란": 1, "돼지고기": 30, "양파": 30, "춘장": 20},
        "군만두": {"냉동 만두": 250},
        "짬뽕밥": {"쌀": 250, "돼지고기": 30, "오징어": 40, "홍합": 60, "양파": 60},
        "콜라": {"콜라": 1},
    },
    cycle_usage={"식용유": (["탕수육", "군만두", "볶음밥"], 40), "고춧가루": (["짬뽕", "짬뽕밥"], 10)},
)

KOREAN = StoreTemplate(
    key="korean", label="한식",
    menus=(("김치찌개", 40, 0.25, 9000), ("된장찌개", 30, 0.2, 9000), ("제육볶음", 35, 0.3, 10000), ("순두부찌개", 25, 0.25, 9500),
           ("비빔밥", 20, 0.2, 9000), ("소불고기", 15, 0.25, 12000), ("사이다", 10, 0.4, 2000)),
    menu_month_factor={
        "김치찌개": {1: 1.2, 2: 1.15, 7: 0.85, 8: 0.85, 12: 1.2},
        "순두부찌개": {1: 1.25, 2: 1.2, 6: 0.85, 7: 0.8, 8: 0.8, 12: 1.2},
        "비빔밥": {1: 0.8, 2: 0.85, 5: 1.1, 6: 1.25, 7: 1.3, 8: 1.3, 12: 0.8},
    },
    month_factor={1: 0.97, 2: 0.95, 3: 1.02, 4: 1.02, 5: 1.0, 6: 1.0, 7: 0.97, 8: 0.93, 9: 1.0, 10: 1.02, 11: 1.03, 12: 1.0},
    weekend_factor=0.8, delivery_sunday_factor=1.1,
    ingredients=(
        ("김치", "G", "BOM", "CHILLED", "한결식품", "포기김치 10kg", "박스", 10000, 1, 35000, 30),
        ("된장", "G", "BOM", "AMBIENT", "한결식품", "재래된장 3kg", "통", 3000, 1, 21000, 180),
        ("고추장", "G", "BOM", "AMBIENT", "한결식품", "고추장 3kg", "통", 3000, 1, 15000, 180),
        ("사이다", "EA", "ONE_TO_ONE", "AMBIENT", "한결식품", "사이다 355ml 24캔", "박스", 24, 1, 17000, 365),
        ("참기름", "ML", "CYCLE", "AMBIENT", "한결식품", "참기름 1.8L", "병", 1800, 1, 25000, 180),
        ("고춧가루", "G", "CYCLE", "AMBIENT", "한결식품", "고춧가루 1kg", "봉", 1000, 1, 18000, 180),
        ("돼지고기", "G", "BOM", "CHILLED", "바른축산", "돼지 앞다리살 5kg", "팩", 5000, 1, 45000, 3),
        ("불고기", "G", "BOM", "CHILLED", "바른축산", "소불고기 3kg", "팩", 3000, 1, 42000, 3),
        ("쌀", "G", "BOM", "AMBIENT", "들녘청과", "쌀 20kg", "포", 20000, 1, 62000, 180),
        ("두부", "G", "BOM", "CHILLED", "들녘청과", "찌개두부 3kg", "판", 3000, 1, 9000, 7),
        ("순두부", "G", "BOM", "CHILLED", "들녘청과", "순두부 400g 10개", "박스", 4000, 1, 10000, 7),
        ("양파", "G", "BOM", "FRESH", "들녘청과", "양파 15kg", "망", 15000, 1, 18000, 7),
        ("애호박", "G", "BOM", "FRESH", "들녘청과", "애호박 5kg", "박스", 5000, 1, 15000, 5),
        ("콩나물", "G", "BOM", "FRESH", "들녘청과", "콩나물 4kg", "봉", 4000, 1, 6000, 3),
        ("계란", "EA", "BOM", "CHILLED", "들녘청과", "계란 30구", "판", 30, 1, 8500, 21),
    ),
    suppliers=(("한결식품", "KAKAO", 2, 3, 50000), ("바른축산", "KAKAO", 1, 2, 80000), ("들녘청과", "KAKAO", 1, 1, 30000)),
    recipes={
        "김치찌개": {"김치": 150, "돼지고기": 60, "두부": 80, "쌀": 200},
        "된장찌개": {"된장": 40, "두부": 80, "애호박": 50, "양파": 30, "쌀": 200},
        "제육볶음": {"돼지고기": 180, "고추장": 30, "양파": 60, "쌀": 200},
        "순두부찌개": {"순두부": 350, "계란": 1, "양파": 20, "쌀": 200},
        "비빔밥": {"쌀": 220, "콩나물": 50, "애호박": 30, "계란": 1, "고추장": 20},
        "소불고기": {"불고기": 180, "양파": 50, "쌀": 200},
        "사이다": {"사이다": 1},
    },
    cycle_usage={"참기름": (["비빔밥", "소불고기"], 5), "고춧가루": (["김치찌개", "제육볶음", "순두부찌개"], 5)},
)

CHICKEN = StoreTemplate(
    key="chicken", label="치킨",
    menus=(("후라이드치킨", 30, 0.8, 19000), ("양념치킨", 25, 0.8, 20000), ("간장치킨", 12, 0.8, 21000),
           ("반반치킨", 20, 0.85, 20000), ("치즈볼", 15, 0.85, 5000), ("콜라", 35, 0.85, 2500)),
    menu_month_factor={"콜라": {1: 0.92, 2: 0.92, 6: 1.1, 7: 1.2, 8: 1.2}, "양념치킨": {12: 1.15}},
    month_factor={1: 1.0, 2: 0.95, 3: 0.95, 4: 0.97, 5: 1.0, 6: 1.03, 7: 1.08, 8: 1.08, 9: 1.0, 10: 0.98, 11: 0.98, 12: 1.12},
    weekend_factor=1.6, delivery_sunday_factor=1.4,
    ingredients=(
        ("닭고기", "G", "BOM", "CHILLED", "하늘계육", "닭 절단육 10kg", "박스", 10000, 1, 58000, 3),
        ("튀김가루", "G", "BOM", "AMBIENT", "맛나소스", "치킨 파우더 10kg", "포", 10000, 1, 30000, 180),
        ("양념 소스", "G", "BOM", "AMBIENT", "맛나소스", "양념치킨 소스 5kg", "통", 5000, 1, 25000, 180),
        ("간장", "ML", "BOM", "AMBIENT", "맛나소스", "간장 소스 13L", "통", 13000, 1, 30000, 365),
        ("단무지", "G", "BOM", "CHILLED", "맛나소스", "치킨무 3kg", "봉", 3000, 1, 6000, 30),
        ("냉동 치즈볼", "EA", "BOM", "FROZEN", "맛나소스", "냉동 치즈볼 50개", "봉", 50, 1, 15000, 180),
        ("콜라", "EA", "ONE_TO_ONE", "AMBIENT", "하늘계육", "콜라 1.25L 12병", "박스", 12, 1, 24000, 365),
        ("식용유", "ML", "CYCLE", "AMBIENT", "바로음료", "대두유 18L", "통", 18000, 1, 52000, 365),
        ("포장 박스", "EA", "CYCLE", "AMBIENT", "바로음료", "치킨 포장 박스 100개", "박스", 100, 1, 25000, 365),
    ),
    suppliers=(("하늘계육", "KAKAO", 1, 1, 100000), ("맛나소스", "KAKAO", 2, 4, 50000), ("바로음료", "WEB", 2, 7, 40000)),
    recipes={
        "후라이드치킨": {"닭고기": 1000, "튀김가루": 150, "단무지": 100},
        "양념치킨": {"닭고기": 1000, "튀김가루": 150, "양념 소스": 120, "단무지": 100},
        "간장치킨": {"닭고기": 1000, "튀김가루": 150, "간장": 80, "단무지": 100},
        "반반치킨": {"닭고기": 1000, "튀김가루": 150, "양념 소스": 60, "단무지": 100},
        "치즈볼": {"냉동 치즈볼": 5},
        "콜라": {"콜라": 1},
    },
    cycle_usage={
        "식용유": (["후라이드치킨", "양념치킨", "간장치킨", "반반치킨", "치즈볼"], 60),
        "포장 박스": (["후라이드치킨", "양념치킨", "간장치킨", "반반치킨"], 0.85),
    },
)

CAFE = StoreTemplate(
    key="cafe", label="카페",
    menus=(("아메리카노", 80, 0.12, 4000), ("카페라떼", 40, 0.15, 4500), ("아이스티", 20, 0.15, 4000),
           ("치즈케이크", 12, 0.2, 6000), ("쿠키", 15, 0.15, 2500), ("샌드위치", 10, 0.25, 6500)),
    menu_month_factor={
        "아이스티": {1: 0.7, 2: 0.75, 3: 0.85, 4: 1.0, 5: 1.15, 6: 1.3, 7: 1.4, 8: 1.4, 9: 1.2, 10: 0.95, 11: 0.8, 12: 0.7},
        "아메리카노": {6: 1.08, 7: 1.12, 8: 1.12},
        "카페라떼": {1: 1.1, 2: 1.08, 12: 1.12},
    },
    month_factor={1: 0.95, 2: 0.93, 3: 1.0, 4: 1.03, 5: 1.05, 6: 1.03, 7: 1.02, 8: 1.0, 9: 1.0, 10: 1.02, 11: 0.98, 12: 1.05},
    weekend_factor=1.3, delivery_sunday_factor=1.1,
    ingredients=(
        ("원두", "G", "BOM", "AMBIENT", "커피상회", "원두 1kg", "봉", 1000, 1, 22000, 90),
        ("아이스티 분말", "G", "BOM", "AMBIENT", "커피상회", "아이스티 분말 1kg", "봉", 1000, 1, 9000, 365),
        ("일회용 컵", "EA", "CYCLE", "AMBIENT", "커피상회", "일회용 컵 1000개", "박스", 1000, 1, 60000, 365),
        ("바닐라 시럽", "ML", "CYCLE", "AMBIENT", "커피상회", "바닐라 시럽 1L", "병", 1000, 1, 12000, 365),
        ("우유", "ML", "BOM", "CHILLED", "아침유업", "우유 1L 12팩", "박스", 12000, 1, 26000, 7),
        ("치즈케이크", "EA", "ONE_TO_ONE", "FROZEN", "베이커리도매", "냉동 치즈케이크 12조각", "박스", 12, 1, 30000, 180),
        ("쿠키 생지", "EA", "BOM", "FROZEN", "베이커리도매", "냉동 쿠키 생지 60개", "박스", 60, 1, 36000, 180),
        ("식빵", "EA", "BOM", "AMBIENT", "베이커리도매", "식빵 12장", "봉", 12, 1, 3000, 3),
        ("슬라이스 햄", "G", "BOM", "CHILLED", "베이커리도매", "슬라이스 햄 1kg", "팩", 1000, 1, 12000, 14),
        ("슬라이스 치즈", "EA", "BOM", "CHILLED", "베이커리도매", "체다 슬라이스 100매", "봉", 100, 1, 15000, 30),
        ("양상추", "G", "BOM", "FRESH", "베이커리도매", "양상추 1kg", "봉", 1000, 1, 4000, 3),
    ),
    suppliers=(("커피상회", "KAKAO", 2, 3, 50000), ("아침유업", "KAKAO", 1, 1, 30000), ("베이커리도매", "KAKAO", 1, 3, 40000)),
    recipes={
        "아메리카노": {"원두": 18}, "카페라떼": {"원두": 18, "우유": 200}, "아이스티": {"아이스티 분말": 30},
        "치즈케이크": {"치즈케이크": 1}, "쿠키": {"쿠키 생지": 1},
        "샌드위치": {"식빵": 2, "슬라이스 햄": 30, "슬라이스 치즈": 1, "양상추": 30},
    },
    cycle_usage={"일회용 컵": (["아메리카노", "카페라떼", "아이스티"], 0.6), "바닐라 시럽": (["카페라떼"], 10)},
)

PASTA = StoreTemplate(
    key="pasta", label="파스타",
    menus=(("토마토 파스타", 25, 0.35, 13000), ("크림 파스타", 25, 0.4, 14000), ("알리오올리오", 15, 0.3, 12000),
           ("로제 파스타", 20, 0.4, 14500), ("버섯 리조또", 12, 0.35, 15000), ("리코타 샐러드", 10, 0.3, 11000), ("콜라", 15, 0.4, 2500)),
    menu_month_factor={
        "리코타 샐러드": {1: 0.7, 2: 0.75, 5: 1.2, 6: 1.35, 7: 1.4, 8: 1.4, 9: 1.15, 12: 0.7},
        "크림 파스타": {1: 1.15, 2: 1.1, 7: 0.85, 8: 0.85, 12: 1.15},
    },
    month_factor={1: 0.97, 2: 0.98, 3: 1.0, 4: 1.0, 5: 1.08, 6: 0.98, 7: 0.95, 8: 0.95, 9: 1.0, 10: 1.02, 11: 1.0, 12: 1.15},
    weekend_factor=1.45, delivery_sunday_factor=1.2,
    ingredients=(
        ("파스타면", "G", "BOM", "AMBIENT", "이탈리아상사", "스파게티면 5kg", "봉", 5000, 1, 15000, 365),
        ("토마토 소스", "G", "BOM", "AMBIENT", "이탈리아상사", "토마토 소스 3kg", "캔", 3000, 1, 12000, 365),
        ("파르메산 치즈", "G", "BOM", "CHILLED", "이탈리아상사", "파르메산 치즈 1kg", "팩", 1000, 1, 30000, 30),
        ("콜라", "EA", "ONE_TO_ONE", "AMBIENT", "이탈리아상사", "콜라 355ml 24캔", "박스", 24, 1, 18000, 365),
        ("올리브유", "ML", "CYCLE", "AMBIENT", "이탈리아상사", "올리브유 3L", "통", 3000, 1, 35000, 365),
        ("소금", "G", "CYCLE", "AMBIENT", "이탈리아상사", "꽃소금 3kg", "봉", 3000, 1, 5000, 365),
        ("크림 소스", "ML", "BOM", "CHILLED", "신선유통", "크림 소스 2L", "팩", 2000, 1, 14000, 14),
        ("베이컨", "G", "BOM", "CHILLED", "신선유통", "베이컨 1kg", "팩", 1000, 1, 13000, 14),
        ("새우", "EA", "BOM", "FROZEN", "신선유통", "칵테일새우 100마리", "봉", 100, 1, 18000, 180),
        ("리코타 치즈", "G", "BOM", "CHILLED", "신선유통", "리코타 치즈 1kg", "팩", 1000, 1, 18000, 14),
        ("쌀", "G", "BOM", "AMBIENT", "신선유통", "쌀 10kg", "포", 10000, 1, 32000, 180),
        ("마늘", "G", "BOM", "FRESH", "아침청과", "깐마늘 1kg", "봉", 1000, 1, 9000, 7),
        ("양송이버섯", "G", "BOM", "FRESH", "아침청과", "양송이 2kg", "박스", 2000, 1, 16000, 3),
        ("양상추", "G", "BOM", "FRESH", "아침청과", "로메인 1kg", "봉", 1000, 1, 6000, 3),
        ("방울토마토", "EA", "BOM", "FRESH", "아침청과", "방울토마토 1kg", "팩", 80, 1, 7000, 5),
    ),
    suppliers=(("이탈리아상사", "KAKAO", 2, 7, 60000), ("신선유통", "KAKAO", 1, 2, 40000), ("아침청과", "KAKAO", 1, 1, 30000)),
    recipes={
        "토마토 파스타": {"파스타면": 110, "토마토 소스": 150, "마늘": 10, "새우": 4},
        "크림 파스타": {"파스타면": 110, "크림 소스": 150, "베이컨": 40, "양송이버섯": 20, "마늘": 10},
        "알리오올리오": {"파스타면": 110, "마늘": 25, "파르메산 치즈": 5},
        "로제 파스타": {"파스타면": 110, "토마토 소스": 80, "크림 소스": 80, "베이컨": 30, "새우": 5},
        "버섯 리조또": {"쌀": 120, "크림 소스": 120, "양송이버섯": 60, "파르메산 치즈": 10},
        "리코타 샐러드": {"양상추": 80, "방울토마토": 5, "리코타 치즈": 40},
        "콜라": {"콜라": 1},
    },
    cycle_usage={
        "올리브유": (["토마토 파스타", "크림 파스타", "알리오올리오", "로제 파스타", "버섯 리조또"], 15),
        "소금": (["토마토 파스타", "크림 파스타", "알리오올리오", "로제 파스타"], 5),
    },
)

# 순서가 곧 --template all 의 순서다 (가게 i 는 이 순서를 차례로 돌려 받는다)
TEMPLATES: dict[str, StoreTemplate] = {t.key: t for t in (DONKATSU, BUNSIK, CHINESE, KOREAN, CHICKEN, CAFE, PASTA)}
DEFAULT_TEMPLATE = "donkatsu"
SCALE_RANGE = (0.6, 1.8)  # 여러 가게 모드의 가게 규모 배수 범위


def get_template(template) -> StoreTemplate:
    """틀 key(str) 또는 StoreTemplate → StoreTemplate. 모르는 key 면 ValueError."""
    if isinstance(template, StoreTemplate):
        return template
    if template not in TEMPLATES:
        raise ValueError(f"모르는 가게 틀: {template!r} (가능: {', '.join(TEMPLATES)})")
    return TEMPLATES[template]


def parse_template_keys(text: str) -> list[str]:
    """--template 값(틀 key, 쉼표 목록, all) → 틀 key 목록. 빈 값 · 모르는 key 면 ValueError."""
    if text.strip() == "all":
        return list(TEMPLATES)
    keys = [k.strip() for k in text.split(",") if k.strip()]
    if not keys:
        raise ValueError(f"가게 틀이 비었다: {text!r}")
    for k in keys:
        get_template(k)
    return keys


# ---- 이미 있는 가게(DB 에서 떠 온 CSV)로 만드는 틀 (S15P31A308-243) ----
# 메뉴 · 재료 · 레시피는 떠 온 그대로 쓴다. 생성에만 쓰는 값은 아래 규칙으로 채운다 (실존 판매량 · 단가가 아니다).
# 메뉴 평일 평균 = min(EXISTING_MENU_MAX_BASE, 종류별 하루 매출 몫(원) / 판매가), 소수 한 자리. 판매가가 없으면 EXISTING_MENU_NO_PRICE_BASE
EXISTING_MENU_DAILY_BUDGET = {"MAIN": 100000, "SET": 60000, "OPTION": 24000}
EXISTING_MENU_MAX_BASE = 20
EXISTING_MENU_NO_PRICE_BASE = 5
# 떠 온 거래처를 쓸 수 없을 때 더하는 가상 거래처 (돈까스 틀의 세 곳). 역할: 고기 · 신선(FRESH) · 그 밖
EXISTING_VIRTUAL_SUPPLIERS = {"MEAT": DONKATSU.suppliers[0], "FRESH": DONKATSU.suppliers[2], "OTHER": DONKATSU.suppliers[1]}
# 떠 온 거래처 이름으로 역할을 고른다 (리드타임 · 발주 주기 · 채널이 다 있는 거래처만 쓴다)
EXISTING_SUPPLIER_ROLE_WORDS = {"MEAT": ("축산", "한우", "정육", "미트", "육가공"), "FRESH": ("청과", "농산", "채소", "야채")}
# 고기 재료: 카탈로그 대표 이름이 이 안이거나 재료 이름에 이 말이 있으면
EXISTING_MEAT_NAMES = frozenset({"돼지고기", "삼겹살", "닭고기", "닭가슴살", "소고기", "불고기", "다진 소고기"})
EXISTING_MEAT_WORDS = ("돼지", "닭", "소고기", "쇠고기", "한우", "등심", "안심", "가브리", "삼겹", "목살")
# 틀에도 카탈로그에도 포장 정보가 없는 재료의 포장 (단위, 보관) → (포장 이름, 포장당 기준 단위량, 포장 단가(원)).
# 상식 기반 가상값. 보관이 없으면(주기형) AMBIENT 로 본다
EXISTING_PACK_DEFAULTS = {
    ("G", "FRESH"): ("박스", 4000, 12000),
    ("G", "CHILLED"): ("팩", 3000, 30000),
    ("G", "FROZEN"): ("봉", 2000, 20000),
    ("G", "AMBIENT"): ("봉", 2000, 12000),
    ("ML", "FRESH"): ("병", 1000, 5000),
    ("ML", "CHILLED"): ("통", 20000, 60000),
    ("ML", "FROZEN"): ("병", 1000, 8000),
    ("ML", "AMBIENT"): ("병", 1000, 15000),
    ("EA", "FRESH"): ("봉", 30, 15000),
    ("EA", "CHILLED"): ("판", 30, 9000),
    ("EA", "FROZEN"): ("봉", 50, 20000),
    ("EA", "AMBIENT"): ("박스", 24, 18000),
}
EXISTING_MOQ = 1
# 유통기한이 없을 때 보관별 기본값 (ai/seed/storage_type_defaults.csv 와 같은 값)
EXISTING_SHELF_DEFAULTS = {"FROZEN": 90, "AMBIENT": 180, "CHILLED": 7, "FRESH": 3}
CATALOG_PATH = Path(__file__).resolve().parents[2] / "seed" / "ingredient_catalog.csv"


def _dump_csv(dump_dir: Path, name: str) -> pd.DataFrame:
    path = dump_dir / f"{name}.csv"
    if not path.exists() or path.stat().st_size == 0:
        return pd.DataFrame()
    return pd.read_csv(path, encoding="utf-8-sig", keep_default_na=True)


def _is_true(value) -> bool:
    """psql CSV 의 t/f · true/false · 1/0 → bool. 빈 값은 True (DB 기본값 active = true)."""
    if value is None or (isinstance(value, float) and math.isnan(value)):
        return True
    return str(value).strip().lower() in ("t", "true", "1", "y", "yes")


def _catalog_names() -> dict[str, str]:
    """카탈로그 이름 · 별칭 → 대표 이름. 카탈로그가 없으면 빈 dict."""
    if not CATALOG_PATH.exists():
        return {}
    out: dict[str, str] = {}
    for row in pd.read_csv(CATALOG_PATH, encoding="utf-8-sig").itertuples():
        out.setdefault(row.name, row.name)
        for alias in str(row.aliases).split(";") if isinstance(row.aliases, str) else []:
            out.setdefault(alias.strip(), row.name)
    return out


def _size_label(qty: float, unit: str) -> str:
    if unit == "G":
        return f"{qty / 1000:g}kg" if qty >= 1000 else f"{qty:g}g"
    if unit == "ML":
        return f"{qty / 1000:g}L" if qty >= 1000 else f"{qty:g}ml"
    return f"{qty:g}개"


def _template_pack(names: list[str], unit: str):
    """틀 7종에서 이름(떠 온 이름 → 카탈로그 대표 이름 순)과 단위가 같은 첫 재료의 (상품명, 포장 이름, 포장당 양, MOQ, 단가)."""
    for name in names:
        for t in TEMPLATES.values():
            for ing in t.ingredients:
                if ing[0] == name and ing[1] == unit:
                    return ing[5], ing[6], ing[7], ing[8], ing[9]
    return None


def template_from_existing(dump_dir) -> StoreTemplate:
    """DB 에서 떠 온 가게 CSV 폴더(menu · ingredient · recipe · supplier) → 가게 틀.

    - 메뉴 · 재료 · 레시피는 떠 온 그대로(이름 · 단위 · 1인분 양 · 재료 종류 · 보관). 비활성 메뉴는 메뉴 · 레시피에서 뺀다(판매 없음).
      레시피가 없는 메뉴도 판매는 만든다. 재료는 비활성 메뉴에만 쓰여도 모두 남긴다
    - 메뉴 평일 평균: 판매가 · 종류 규칙(EXISTING_MENU_*). 배달 비중 = 돈까스 틀 값, 계절 배수 1, 금 · 토 · 일요일 배달 배수 = 돈까스 틀
    - 거래처 상품: 틀 7종에 같은 재료(떠 온 이름 또는 카탈로그 대표 이름, 같은 단위)가 있으면 그 포장 · 단가, 없으면 (단위, 보관) 기본값
    - 거래처: 재료 역할(고기 · 신선 · 그 밖)마다 떠 온 거래처 중 리드타임 · 발주 주기 · 채널이 다 있고 이름이 그 역할인 곳, 없으면 가상 거래처
    - 주기형(CYCLE) 재료는 소비를 따라갈 메뉴를 몰라 소비 0 (주문도 없다)
    떠 온 CSV 끼리 이름 · id 가 안 맞으면 ValueError.
    """
    d = Path(dump_dir)
    menu = _dump_csv(d, "menu")
    ingredient = _dump_csv(d, "ingredient")
    recipe = _dump_csv(d, "recipe")
    supplier = _dump_csv(d, "supplier")
    if menu.empty or ingredient.empty:
        raise ValueError(f"{d}: menu.csv · ingredient.csv 가 비었다")
    for frame, what in ((menu, "menu"), (ingredient, "ingredient")):
        dup = sorted(frame.loc[frame["name"].duplicated(), "name"])
        if dup:
            raise ValueError(f"{what}.csv: 이름이 겹친다 {dup}")

    active = [m for m in menu.itertuples() if _is_true(getattr(m, "active", None))]
    menus = []
    for m in active:
        kind = m.kind if isinstance(m.kind, str) else "MAIN"
        price = None if pd.isna(m.price) else float(m.price)
        if price and price > 0:
            base = round(min(EXISTING_MENU_MAX_BASE, EXISTING_MENU_DAILY_BUDGET.get(kind, EXISTING_MENU_DAILY_BUDGET["MAIN"]) / price), 1)
        else:
            base = EXISTING_MENU_NO_PRICE_BASE
        menus.append((m.name, base, DONKATSU.delivery_share, int(price) if price and price.is_integer() else price))

    ing_name = dict(zip(ingredient["id"], ingredient["name"], strict=False))
    menu_name = dict(zip(menu["id"], menu["name"], strict=False))
    active_names = {m.name for m in active}
    recipes: dict[str, dict[str, float]] = {}
    for r in recipe.itertuples() if not recipe.empty else []:
        mname = menu_name.get(r.menu_id, getattr(r, "menu_name", None))
        iname = ing_name.get(r.ingredient_id)
        if mname not in menu_name.values():
            raise ValueError(f"recipe.csv: menu.csv 에 없는 메뉴 {mname!r}")
        if iname is None:
            raise ValueError(f"recipe.csv: ingredient.csv 에 없는 재료 {getattr(r, 'ingredient_name', r.ingredient_id)!r}")
        if mname not in active_names:
            continue
        q = float(r.qty_per_serving)
        recipes.setdefault(mname, {})[iname] = int(q) if q.is_integer() else q

    # 떠 온 거래처 중 생성에 쓸 수 있는 곳(리드타임 · 발주 주기 · 채널 · 최소 주문금액이 다 있음)을 역할별로.
    # 하나라도 비면 그 거래처에 상품을 걸지 않는다 — 비운 채 걸면 추천이 SUPPLIER_UNSET, 채우면 기존 행을 바꾼다(계약 243)
    usable: dict[str, tuple] = {}
    for s in supplier.itertuples() if not supplier.empty else []:
        if any(pd.isna(getattr(s, c, None)) for c in ("channel", "lead_time_days", "order_cycle_days", "min_order_amount")):
            continue
        for role, words in EXISTING_SUPPLIER_ROLE_WORDS.items():
            if role not in usable and any(w in s.name for w in words):
                usable[role] = (s.name, s.channel, int(s.lead_time_days), int(s.order_cycle_days), int(s.min_order_amount))
    taken = set(supplier["name"]) if not supplier.empty else set()

    def supplier_for(role: str) -> tuple:
        if role in usable:
            return usable[role]
        s = EXISTING_VIRTUAL_SUPPLIERS[role]
        if s[0] in taken:
            raise ValueError(f"가상 거래처 이름 {s[0]!r} 가 떠 온 거래처와 겹친다 "
                             f"(그 거래처는 리드타임 · 발주 주기 · 채널 · 최소 주문금액 중 빈 칸이 있어 쓸 수 없다)")
        return s

    catalog = _catalog_names()
    ingredients, used_suppliers, products = [], {}, set()
    for i in ingredient.itertuples():
        unit, kind = i.unit, i.kind
        storage = i.storage_type if isinstance(i.storage_type, str) else None
        canonical = catalog.get(i.name)
        if canonical in EXISTING_MEAT_NAMES or any(w in i.name for w in EXISTING_MEAT_WORDS):
            role = "MEAT"
        elif storage == "FRESH":
            role = "FRESH"
        else:
            role = "OTHER"
        s = supplier_for(role)
        used_suppliers[s[0]] = s
        pack = _template_pack([i.name] + ([canonical] if canonical else []), unit)
        if pack is None:
            pack_unit, per_pack, price = EXISTING_PACK_DEFAULTS[(unit, storage or "AMBIENT")]
            pack = (f"{i.name} {_size_label(per_pack, unit)}", pack_unit, per_pack, EXISTING_MOQ, price)
        if (s[0], pack[0]) in products:
            # 같은 거래처 안 상품명은 겹치면 안 된다(DB unique). 별칭으로 같은 틀 포장을 찾은 재료(닭고기 · 닭다리살)는 재료 이름을 붙인다
            pack = (f"{i.name} {pack[0]}", *pack[1:])
        products.add((s[0], pack[0]))
        shelf = getattr(i, "shelf_life_days", None)
        shelf = EXISTING_SHELF_DEFAULTS[storage or "AMBIENT"] if shelf is None or pd.isna(shelf) else int(shelf)
        ingredients.append((i.name, unit, kind, storage, s[0], *pack, shelf))

    return StoreTemplate(
        key="existing",
        label="기존 가게",
        menus=tuple(menus),
        menu_month_factor={},
        month_factor={m: 1.0 for m in range(1, 13)},
        weekend_factor=DONKATSU.weekend_factor,
        delivery_sunday_factor=DONKATSU.delivery_sunday_factor,
        ingredients=tuple(ingredients),
        suppliers=tuple(used_suppliers.values()),
        recipes=recipes,
        cycle_usage={},
        order_policy="EXPECTED",
    )


def existing_store_name(dump_dir) -> str | None:
    """떠 온 store.csv 의 가게 이름. 파일이 없거나 비었으면 None."""
    store = _dump_csv(Path(dump_dir), "store")
    if store.empty or "name" not in store.columns:
        return None
    return str(store["name"].iloc[0])


def plan_stores(keys: list[str], stores: int, seed: int) -> list[dict]:
    """여러 가게 계획. 가게 i(1부터)는 틀 목록을 차례로 돌려 받고, 시드 seed + i, 규모 배수는 default_rng(seed) 로 뽑는다."""
    if stores < 1:
        raise ValueError(f"--stores 는 1 이상이어야 한다: {stores}")
    scales = np.round(np.random.default_rng(seed).uniform(*SCALE_RANGE, size=stores), 3)
    plan = []
    for i in range(1, stores + 1):
        t = get_template(keys[(i - 1) % len(keys)])
        plan.append(
            {
                "store_key": f"{t.key}-{i:02d}",
                "template": t.key,
                "store_name": f"샘플 {t.label} {i}호점",
                "scale": float(scales[i - 1]),
                "seed": seed + i,
            }
        )
    return plan
