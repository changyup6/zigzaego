"""샘플 CSV 폴더 → 빈 DB 에 넣는 seed.sql (S15P31A308-53).

make_sample_data.py 가 만든 CSV(기본 ai/data/sample)를 읽어 INSERT 문만 든 SQL 한 파일을 만든다.
DB 에 직접 붙지 않는다. 적용: psql -v ON_ERROR_STOP=1 -f seed.sql

- 전제: Flyway 마이그레이션(V12 까지)이 적용된 **빈 DB**. 가게가 이미 있으면 첫 문장에서 일부러 실패한다(전체가 한 트랜잭션이라 아무것도 안 들어간다)
- id: CSV 의 id 는 넣지 않는다. 모든 PK 는 IDENTITY 가 매기고, 다른 표는 이름(자연키)으로 서브쿼리 조회한다.
  그래서 시드 뒤 앱이 INSERT 해도 시퀀스가 겹치지 않는다
- 가게 1개 + 데모 계정 1개(OWNER). menus.base_qty · delivery_share 는 생성용 값이라 버린다
- true_stock 은 첫 날짜 하루치만 stock_count 에 넣는다(추정 재고 출발점)
- 매입 문서 kind 는 CSV 값을 쓰지 않고 실사일 기준으로 다시 정한다(아래 PURCHASE_DOC_* 주석)
- 끝(COMMIT 직전)에 건수 검증 DO 블록을 둔다. 이름 조회 JOIN 이 행을 조용히 떨구면 전체가 되돌려진다

여러 가게 · --append (S15P31A308-240):
- 폴더에 stores.csv(make_sample_data.py --stores/--template 출력)가 있으면 가게마다 store · 계정 · OWNER · 데이터를 한 파일 · 한 트랜잭션에.
  계정은 demo+가게키@wiggly.local, 비밀번호는 위 데모 비밀번호 하나. 메뉴 · 재료 · 거래처 이름 조회는 모두 그 가게(st) 범위다
- append=True: 빈 DB 검사 대신 "넣으려는 가게 이름 · 계정 이메일이 이미 있으면 실패". 기존 가게는 건드리지 않는다
- 여러 가게 · append 의 검증 블록은 표 전체가 아니라 가게마다 센다

발주 이력 (S15P31A308-242): 매입 문서(거래처 · 날짜별 1장)마다 보내고 입고 끝난 발주서 1장 · 문서 줄마다 품목 1줄 (_order_sql)

이미 있는 가게 (S15P31A308-243, build_existing_seed_sql): 가게 · 계정 · 메뉴 · 재료 · 레시피는 넣지 않고 그 가게에 이력만 더한다

재료 카테고리 (S15P31A308-273 · 274): 가게마다 기본 11종(ingredient_category)을 넣고, 재료의 category_id 는 샘플 폴더의
ingredient_categories.csv(백엔드 자동 분류 백업과 같은 열)가 있으면 그 값, 없으면 기타. 보관 위치(storage_location, V9)는 백엔드 기본값 규칙과 같다
"""

import math
import os
from pathlib import Path

import pandas as pd

STORE_NAME = "샘플 돈까스"
DEMO_EMAIL = "demo@wiggly.local"
DEMO_NAME = "데모 사장님"
DEMO_PASSWORD_HASH_ENV = "WEGLEE_DEMO_PASSWORD_HASH"


def demo_password_hash() -> str:
    """데모 계정의 BCrypt 해시($2a$, 60자)를 환경변수에서 읽는다. 없거나 모양이 다르면 변수 이름을 담아 멈춘다."""
    value = os.environ.get(DEMO_PASSWORD_HASH_ENV, "")
    if not (value.startswith("$2a$") and len(value) == 60):
        raise ValueError(f"환경변수 {DEMO_PASSWORD_HASH_ENV} 에 $2a$ 로 시작하는 60자 BCrypt 해시가 필요하다")
    return value


# CSV 에 없는 NOT NULL 칸의 기본값. 백엔드 테스트 데이터(ConsumptionTestData)와 같게 둔다
MENU_SOURCE = "POS"  # menu.source — 판매 기록에서 온 메뉴
MENU_KIND = "MAIN"  # 샘플에는 세트 · 옵션이 없다
INGREDIENT_PRICE_SENSITIVE = False
INGREDIENT_SOURCING_TYPE = "GENERIC"
# 재료 카테고리 (S15P31A308-273 · 274). 가게마다 기본 11종을 넣는다 — 정본은 백엔드 DefaultIngredientCategory, 여기는 복사본(바뀌면 같이).
# 재료의 카테고리는 샘플 폴더(여러 가게면 stores.csv 옆)에 ingredient_categories.csv 가 있으면 그 값, 없으면 '기타'(OTHER).
# 그 파일은 백엔드 자동 분류(IngredientCategoryBackup)가 INGREDIENT_CATEGORY_BACKUP_DIR 에 쓰는 ingredient_categories_latest.csv 와 열이 같다 —
# store_name,ingredient_name,category_code,category_name,category_source. LLM 이 붙인 카테고리를 DB 를 새로 만들 때 다시 부르지 않고 되살린다
INGREDIENT_CATEGORIES = [
    ("MEAT", "육류", 1), ("SEAFOOD", "수산", 2), ("VEGETABLE", "채소", 3), ("FRUIT", "과일", 4),
    ("EGG_DAIRY", "계란·유제품", 5), ("GRAIN_NOODLE", "곡물·면·떡", 6), ("TOFU_BEAN", "두부·콩", 7),
    ("SAUCE", "양념·소스", 8), ("PROCESSED", "가공식품", 9), ("BEVERAGE", "음료", 10), ("OTHER", "기타", 99),
]
INGREDIENT_CATEGORY_OTHER = "OTHER"
INGREDIENT_CATEGORY_SOURCE = "AUTO"  # ingredient.category_source — 사장님이 고른 것(OWNER)은 파일 값으로만 들어온다
CATEGORIES_FILE = "ingredient_categories.csv"
CATEGORIES_FILE_FROM_SERVER = "ingredient_categories_latest.csv"  # 백엔드가 쓰는 이름 그대로 복사해 둬도 읽는다
# 샘플 매입 문서는 사장님 확인을 끝낸 기록으로 넣는다 (문서 parse_status · 줄 status 모두 CONFIRMED).
# kind 는 V1 스키마 뜻(purchase_doc.kind 주석)대로 첫 재고 실사일과 견줘 시드 단계에서 다시 정한다:
#   doc_date >  실사일 → RECEIVE (실사 뒤 들어온 입고. 확인된 줄 중 재료가 연결된 줄이 추정 재고에 더해진다, DECISIONS #41)
#   doc_date <= 실사일 → PAST    (실사 재고에 이미 들어 있다. 재고에 안 더하고 가격 · 주기형 속도에만 쓴다)
# 샘플 생성기(sample_data.py)는 실사일을 모르고 kind 를 정해서 CSV 의 kind 는 버린다.
# 시드는 문서마다 입고 끝난 발주서를 넣고 문서를 그 발주서에 잇는다(purchase_order_id, _order_sql). 그 발주 줄은 모두 마지막 실사
# 앞에 입고돼 추정 재고에 안 더해지고, 주기형 입고 기록은 문서 줄 대신 같은 날 · 같은 양의 발주 줄로 읽힌다
PURCHASE_DOC_PARSE_STATUS = "CONFIRMED"
PURCHASE_DOC_LINE_STATUS = "CONFIRMED"
# true_stock.qty 는 그날 마감 재고(그날 소비 · 입고를 다 반영한 뒤 남은 양)라 그날 끝 시각(가게 시간대)으로 기록한다.
# 그래서 실사일 당일 문서도 이미 반영된 것으로 보고 PAST 로 둔다
STOCK_COUNT_TIME = "23:59:59+09"
# 마지막 날 실사는 그다음 날 0시로 기록한다 (_screen_sql 주석)
LAST_STOCK_COUNT_TIME = "00:00:00+09"
# 샘플 판매는 포스 엑셀 합계를 흉내 낸 것이라 pos_sale.source 를 기본값에 기대지 않고 적어 둔다
POS_SALE_SOURCE = "EXCEL"
STORES_FILE = "stores.csv"  # 여러 가게 출력의 가게 목록 (make_sample_data.py)


def _raise_text(text: str) -> str:
    """RAISE 형식 문자열 안에 넣을 글자. 작은따옴표는 두 번, % 는 %% 로(% 는 RAISE 의 자리 표시라 그대로 두면 인자 수가 어긋난다)."""
    return str(text).replace("'", "''").replace("%", "%%")


def _lit(value) -> str:
    """파이썬 값 → SQL 리터럴. 문자열은 작은따옴표를 두 번 써서 이스케이프한다."""
    if value is None or (isinstance(value, float) and math.isnan(value)):
        return "NULL"
    if isinstance(value, bool):
        return "TRUE" if value else "FALSE"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        return str(int(value)) if value.is_integer() else repr(value)
    return "'" + str(value).replace("'", "''") + "'"


def _values(rows: list[tuple]) -> str:
    """VALUES 튜플을 한 줄에 하나씩. 테스트가 "    (" 로 시작하는 줄을 세어 행 수를 확인한다."""
    return ",\n".join("    (" + ", ".join(_lit(v) for v in row) + ")" for row in rows)


def _insert_sql(
    table: str,
    columns: list[str],
    select: str,
    alias: list[str],
    rows: list[tuple],
    joins: str = "",
    store_name: str = STORE_NAME,
    store_select: str | None = None,
    where: str = "",
) -> str:
    """INSERT INTO table (columns) SELECT <select> FROM (VALUES …) AS v(alias) <joins> <where>; — st 는 store_name 가게 하나.

    store_select 를 주면 가게를 그 SELECT 로 찾는다(--into-existing 의 가게 id 지정). where 는 끝에 그대로 붙는다(기본 없음).
    """
    if not rows:
        return f"-- {table}: 0 rows\n"
    store = store_select or f"SELECT id FROM store WHERE name = {_lit(store_name)}"
    return (
        f"INSERT INTO {table} ({', '.join(columns)})\n"
        f"SELECT {select}\n"
        f"FROM (VALUES\n{_values(rows)}\n) AS v({', '.join(alias)})\n"
        f"CROSS JOIN ({store}) AS st{joins}{where};\n"
    )


def _doc_kind(doc_date, stock_date) -> str:
    """첫 재고 실사일보다 뒤 문서는 RECEIVE, 같은 날이거나 앞이면 PAST."""
    return "RECEIVE" if pd.Timestamp(doc_date) > pd.Timestamp(stock_date) else "PAST"


def _verify_block(expected: dict[str, int], line_nulls: dict[str, int]) -> str:
    """넣은 뒤 표별 행 수 · 매입 줄의 빈 연결 수가 생성 시점에 센 값과 같은지 확인하는 DO 블록.

    빈 DB 에만 넣으므로(첫 문장이 확인) 표 전체 count 가 곧 시드가 넣은 행 수다.
    """
    checks = [
        f"  SELECT count(*) INTO n FROM {table}; IF n <> {count} THEN"
        f" RAISE EXCEPTION 'seed 검증 실패: {table} % 행 (기대 {count})', n; END IF;"
        for table, count in expected.items()
    ]
    checks += [
        f"  SELECT count(*) INTO n FROM purchase_doc_line WHERE {col} IS NULL; IF n <> {count} THEN"
        f" RAISE EXCEPTION 'seed 검증 실패: purchase_doc_line.{col} NULL % 행 (기대 {count}) — 이름 조회 실패', n; END IF;"
        for col, count in line_nulls.items()
    ]
    return (
        "-- seed 검증: 이름으로 찾는 JOIN 이 행을 떨구거나 연결을 비우면 여기서 멈춘다 (트랜잭션 안이라 전체가 되돌려진다)\n"
        "DO $$\nDECLARE n bigint;\nBEGIN\n" + "\n".join(checks) + "\nEND $$;\n"
    )


def _py(value):
    """pandas/numpy 스칼라 → 파이썬 기본 타입."""
    if pd.isna(value):
        return None
    return value.item() if hasattr(value, "item") else value


def _records(df: pd.DataFrame, columns: list[str]) -> list[tuple]:
    return [tuple(_py(v) for v in row) for row in df[columns].itertuples(index=False, name=None)]


def _read(sample_dir: Path, name: str) -> pd.DataFrame:
    return pd.read_csv(sample_dir / f"{name}.csv", encoding="utf-8-sig", keep_default_na=True)


def _lookup(mapping: dict, key, what: str):
    if key not in mapping:
        raise ValueError(f"{what}: {key!r} 를 찾을 수 없다")
    return mapping[key]


def _verify_stores_block(stores: list[tuple[str, str, dict[str, int], dict[str, int]]]) -> str:
    """여러 가게 · --append 용 검증 DO 블록. 표 전체가 아니라 가게마다(store_id) 센다 — 다른 가게가 있어도 맞게.

    stores: [(가게 이름, 계정 이메일, 표별 기대 행 수, 매입 줄 빈 연결 기대 수)]
    """
    checks = []
    for name, email, expected, line_nulls in stores:
        sid = f"(SELECT id FROM store WHERE name = {_lit(name)})"
        who = _raise_text(email)
        for table, count in expected.items():
            if table == "store":
                where = f"name = {_lit(name)}"
            elif table == "account":
                where = f"email = {_lit(email)}"
            else:
                where = f"store_id = {sid}"
            checks.append(
                f"  SELECT count(*) INTO n FROM {table} WHERE {where}; IF n <> {count} THEN"
                f" RAISE EXCEPTION 'seed 검증 실패: {who} {table} % 행 (기대 {count})', n; END IF;"
            )
        for col, count in line_nulls.items():
            checks.append(
                f"  SELECT count(*) INTO n FROM purchase_doc_line WHERE store_id = {sid} AND {col} IS NULL; IF n <> {count} THEN"
                f" RAISE EXCEPTION 'seed 검증 실패: {who} purchase_doc_line.{col} NULL % 행 (기대 {count}) — 이름 조회 실패', n; END IF;"
            )
    return (
        "-- seed 검증: 가게마다 이름으로 찾는 JOIN 이 행을 떨구거나 연결을 비우면 여기서 멈춘다 (트랜잭션 안이라 전체가 되돌려진다)\n"
        "DO $$\nDECLARE n bigint;\nBEGIN\n" + "\n".join(checks) + "\nEND $$;\n"
    )


def demo_email(store_key: str) -> str:
    """여러 가게 모드의 가게별 데모 계정 이메일."""
    return f"demo+{store_key}@wiggly.local"


EMPTY_DB_CHECK = (
    "DO $$ BEGIN IF EXISTS (SELECT 1 FROM store) THEN"
    " RAISE EXCEPTION 'seed.sql 은 빈 DB(Flyway 마이그레이션만 적용)에만 넣는다 — store 표에 이미 행이 있다'; END IF; END $$;"
)


def build_seed_sql(sample_dir, append: bool = False) -> str:
    """CSV 폴더 → seed.sql 문자열. CSV 끼리 이름 · id 가 안 맞으면 ValueError.

    - 폴더에 stores.csv 가 있으면 여러 가게: 가게마다 store · 계정(demo+가게키@wiggly.local) · OWNER 와 그 가게 데이터를 한 트랜잭션에
    - append=True 면 "빈 DB" 대신 "넣으려는 가게 이름 · 계정 이메일이 DB 에 없음" 을 검사한다 (기존 가게는 건드리지 않는다)
    - stores.csv 가 없고 append=False 면 예전(S15P31A308-53)과 같은 단일 가게 출력
    """
    d = Path(sample_dir)
    if (d / STORES_FILE).exists():
        stores = _read(d, "stores")
        for col in ("store_key", "store_name"):
            dup = sorted(stores.loc[stores[col].duplicated(), col])
            if dup:
                raise ValueError(f"stores.csv: {col} 가 겹친다 {dup}")
        targets = [(d / s.store_key, s.store_name, demo_email(s.store_key), s.store_key) for s in stores.itertuples()]
        source = f"{d.name}/{STORES_FILE} 의 가게 {len(targets)}곳"
    elif append:
        targets = [(d, STORE_NAME, DEMO_EMAIL, None)]
        source = f"{d.name}/*.csv"
    else:
        parts, expected, line_nulls = _store_sql(d, STORE_NAME, DEMO_EMAIL)
        out = [
            "-- 샘플 데이터 시드 (ai/scripts/make_seed_sql.py 가 만든다. 손으로 고치지 않는다)",
            f"-- 원본: {d.name}/*.csv · 전제: Flyway 마이그레이션(V12 까지)이 적용된 빈 DB",
            f"-- 데모 계정: {DEMO_EMAIL} / 비밀번호는 WEGLEE_DEMO_PASSWORD 로 공유",
            "BEGIN;",
            "",
            "-- 빈 DB 가 아니면 멈춘다 (전체가 한 트랜잭션이라 아무것도 안 들어간다)",
            EMPTY_DB_CHECK,
            "",
            *parts,
            _verify_block(expected, line_nulls),
            # 화면 충족용 문장은 위(S15P31A308-53 출력)를 한 줄도 바꾸지 않고 COMMIT 앞에 더한다 (S15P31A308-240)
            *_screen_sql(d, STORE_NAME, STORE_NAME_CHECK_LABEL),
            *_order_sql(d, STORE_NAME, STORE_NAME_CHECK_LABEL),
            "COMMIT;",
        ]
        return "\n".join(out) + "\n"

    names = [name for _, name, _, _ in targets]
    emails = [email for _, _, email, _ in targets]
    if append:
        precheck = [
            "-- --append: 넣으려는 가게 이름 · 계정 이메일이 이미 있으면 멈춘다 (다른 가게는 있어도 되고 건드리지 않는다)",
            f"DO $$ BEGIN IF EXISTS (SELECT 1 FROM store WHERE name IN ({', '.join(_lit(n) for n in names)}))"
            f" OR EXISTS (SELECT 1 FROM account WHERE email IN ({', '.join(_lit(e) for e in emails)})) THEN"
            " RAISE EXCEPTION 'seed.sql(--append): 넣으려는 가게 이름 또는 계정 이메일이 이미 있다'; END IF; END $$;",
        ]
        premise = "--append: 넣으려는 가게 이름 · 계정 이메일이 DB 에 없어야 한다"
    else:
        precheck = ["-- 빈 DB 가 아니면 멈춘다 (전체가 한 트랜잭션이라 아무것도 안 들어간다)", EMPTY_DB_CHECK]
        premise = "전제: Flyway 마이그레이션만 적용된 빈 DB"
    out = [
        "-- 샘플 데이터 시드 (ai/scripts/make_seed_sql.py 가 만든다. 손으로 고치지 않는다)",
        f"-- 원본: {source} · {premise}",
        f"-- 데모 계정: 가게마다 하나(OWNER) — {', '.join(emails)} / 비밀번호는 WEGLEE_DEMO_PASSWORD 로 공유",
        "BEGIN;",
        "",
        *precheck,
        "",
    ]
    checks = []
    for folder, name, email, key in targets:
        try:
            parts, expected, line_nulls = _store_sql(folder, name, email)
        except ValueError as e:
            raise ValueError(f"{key or d.name}: {e}") from e
        out.append(f"-- ===== 가게 {key or ''} ({email}) =====")
        out.extend(parts)
        checks.append((name, email, expected, line_nulls))
    out.append(_verify_stores_block(checks))
    for folder, name, email, _ in targets:
        out.extend(_screen_sql(folder, name, email))
        out.extend(_order_sql(folder, name, email))
    out.append("COMMIT;")
    return "\n".join(out) + "\n"


# 단일 가게 출력의 화면 충족 검증 메시지에 쓰는 이름 (여러 가게는 계정 이메일)
STORE_NAME_CHECK_LABEL = DEMO_EMAIL
# 거래처 마감 시각 · 배송 요일. 생성기의 발주 주기(일)에 맞춘 요일 (화면 표시용, 발주 계산은 order_cycle_days 를 쓴다)
SUPPLIER_CUTOFF_TIME = "18:00"
DELIVERY_DAYS_BY_CYCLE = {
    1: "MON,TUE,WED,THU,FRI,SAT,SUN",
    2: "MON,WED,FRI",
    3: "MON,THU",
    4: "MON,FRI",
}
DELIVERY_DAYS_DEFAULT = "MON"  # 주기 5일 이상 = 주 1회


def _screen_sql(d: Path, store_name: str, label: str) -> list[str]:
    """seed 가게로 로그인했을 때 발주가 되고 화면이 비지 않게 더 넣는 문장 + 그 검증 DO 블록 (S15P31A308-240).

    근거: 로컬 실측 위글리/데이터/240_화면충족 — 대표 상품이 없어 추천이 전부 SUPPLIER_UNSET · 발주 409,
    menu.price null, price_history 0줄, 레시피 × 판매 누적이 실제 소비보다 조금 많아 추정 재고 0 · needsCount.
    - menu.price = menus.csv price
    - ingredient.primary_supplier_product_id = 그 재료의 거래처 상품(첫 줄), unit_status = LOCKED (입고 기록이 있는 가게)
    - supplier.cutoff_time · delivery_days (발주 주기에 맞춘 요일)
    - price_history: 매입 문서 줄마다 source DOC, observed_at = 문서 날짜(가게 시간대 0시), 포장 1개 단가, 단위 = 포장 이름
    - stock_count: 마지막 날 true_stock 을 한 번 더 (가게가 최근에 실사한 모습). 매입 문서 kind 기준은 그대로 첫 실사일
    모든 문장은 그 가게(store_name) 범위다. 이미 넣은 행은 바꾸지 않고 비어 있던 칸만 채운다.
    """
    menus = _read(d, "menus")
    ingredients = _read(d, "ingredients")
    suppliers = _read(d, "suppliers")
    supplier_products = _read(d, "supplier_products")
    lines = _read(d, "purchase_doc_lines")
    true_stock = _read(d, "true_stock")
    st = f"(SELECT id FROM store WHERE name = {_lit(store_name)})"
    supplier_name = dict(zip(suppliers["supplier_id"], suppliers["name"], strict=False))
    ingredient_name = dict(zip(ingredients["ingredient_id"], ingredients["name"], strict=False))

    out = ["-- ===== 화면 충족 (S15P31A308-240): 판매가 · 대표 상품 · 거래처 요일 · 단가 이력 · 마지막 날 실사 ====="]
    checks: list[tuple[str, str, int]] = []  # (설명, count 할 FROM … WHERE …, 기대 수)

    priced = [(n, _py(p)) for n, p in zip(menus["name"], menus["price"], strict=False) if not pd.isna(p)] if "price" in menus.columns else []
    if priced:
        out.append(
            "UPDATE menu AS m SET price = v.price::numeric\n"
            f"FROM (VALUES\n{_values(priced)}\n) AS v(name, price)\n"
            f"WHERE m.store_id = {st} AND m.name = v.name;\n"
        )
    checks.append(("menu.price NULL", f"menu WHERE store_id = {st} AND price IS NULL", len(menus) - len(priced)))

    primary = []
    for p in supplier_products.drop_duplicates("ingredient_id").itertuples():
        primary.append(
            (
                _lookup(ingredient_name, p.ingredient_id, "supplier_products.ingredient_id"),
                _lookup(supplier_name, p.supplier_id, "supplier_products.supplier_id"),
                p.product_name,
            )
        )
    if primary:
        out.append(
            "UPDATE ingredient AS i SET primary_supplier_product_id = sp.id, unit_status = 'LOCKED'\n"
            f"FROM (VALUES\n{_values(primary)}\n) AS v(ingredient_name, supplier_name, product_name)\n"
            f"JOIN supplier s ON s.store_id = {st} AND s.name = v.supplier_name\n"
            "JOIN supplier_product sp ON sp.supplier_id = s.id AND sp.product_name = v.product_name\n"
            f"WHERE i.store_id = {st} AND i.name = v.ingredient_name;\n"
        )
    no_product = len(ingredients) - len(primary)
    checks.append(("ingredient.primary_supplier_product_id NULL",
                   f"ingredient WHERE store_id = {st} AND primary_supplier_product_id IS NULL", no_product))
    checks.append(("ingredient.unit_status LOCKED 아님", f"ingredient WHERE store_id = {st} AND unit_status <> 'LOCKED'", no_product))

    days = [
        (s.name, SUPPLIER_CUTOFF_TIME, DELIVERY_DAYS_BY_CYCLE.get(int(s.order_cycle_days), DELIVERY_DAYS_DEFAULT))
        for s in suppliers.itertuples()
        if not pd.isna(s.order_cycle_days)
    ]
    if days:
        out.append(
            "UPDATE supplier AS s SET cutoff_time = v.cutoff_time::time, delivery_days = string_to_array(v.delivery_days, ',')\n"
            f"FROM (VALUES\n{_values(days)}\n) AS v(name, cutoff_time, delivery_days)\n"
            f"WHERE s.store_id = {st} AND s.name = v.name;\n"
        )
    checks.append(("supplier.delivery_days NULL", f"supplier WHERE store_id = {st} AND delivery_days IS NULL", len(suppliers) - len(days)))

    priced_lines = int((lines["supplier_product_id"].notna() & (lines["unit_price"].fillna(0) > 0)).sum())
    out.append(
        "-- 매입 문서 줄마다 단가 이력 1행 (화면 단가 추이 · 가격 지수는 RECEIVE · DOC · MANUAL 만 읽는다)\n"
        "INSERT INTO price_history (store_id, supplier_product_id, price, unit, observed_at, source, doc_line_id)\n"
        "SELECT l.store_id, l.supplier_product_id, l.unit_price, COALESCE(l.unit, 'PACK'),"
        " pd.doc_date::timestamp AT TIME ZONE st.timezone, 'DOC', l.id\n"
        "FROM purchase_doc_line l\n"
        f"JOIN store st ON st.id = l.store_id AND st.id = {st}\n"
        "JOIN purchase_doc pd ON pd.id = l.doc_id AND pd.store_id = st.id\n"
        "WHERE l.supplier_product_id IS NOT NULL AND l.unit_price > 0\n"
        "ORDER BY pd.doc_date, pd.id, l.line_no;\n"
    )
    checks.append(("price_history DOC", f"price_history WHERE store_id = {st} AND source = 'DOC'", priced_lines))

    first_day, last_day = true_stock["stock_date"].min(), true_stock["stock_date"].max()
    n_first = int((true_stock["stock_date"] == first_day).sum())
    last_rows = []
    if last_day != first_day:
        # 마지막 날 마감 재고에는 그날 입고가 이미 들어 있다. 백엔드는 실사일(가게 시간대 날짜) 당일 이후 RECEIVE 문서를 더하므로
        # 실사 시각을 다음 날 0시로 둬 그날 문서를 한 번 더 더하지 않게 한다
        last_counted_at = f"{pd.Timestamp(last_day) + pd.Timedelta(days=1):%Y-%m-%d} {LAST_STOCK_COUNT_TIME}"
        last_rows = [
            (_lookup(ingredient_name, s.ingredient_id, "true_stock.ingredient_id"), last_counted_at, _py(s.qty))
            for s in true_stock[true_stock["stock_date"] == last_day].itertuples()
        ]
        out.append(
            "-- 마지막 날 마감 재고를 실사로 한 번 더 (레시피 × 판매 누적과 실제 소비의 작은 차이가 추정 재고를 0 으로 끌어내리지 않게)\n"
            + _insert_sql(
                "stock_count",
                ["store_id", "ingredient_id", "counted_at", "qty"],
                "st.id, i.id, v.counted_at::timestamptz, v.qty::numeric",
                ["ingredient_name", "counted_at", "qty"],
                last_rows,
                "\nJOIN ingredient i ON i.store_id = st.id AND i.name = v.ingredient_name",
                store_name,
            )
        )
    checks.append(("stock_count", f"stock_count WHERE store_id = {st}", n_first + len(last_rows)))

    who = _raise_text(label)
    body = [
        f"  SELECT count(*) INTO n FROM {where}; IF n <> {count} THEN"
        f" RAISE EXCEPTION 'seed 화면 충족 검증 실패: {who} {_raise_text(what)} % 행 (기대 {count})', n; END IF;"
        for what, where, count in checks
    ]
    out.append(
        "-- seed 화면 충족 검증: 위 UPDATE 가 행을 못 찾았거나 이력이 덜 들어가면 멈춘다 (트랜잭션 안이라 전체가 되돌려진다)\n"
        "DO $$\nDECLARE n bigint;\nBEGIN\n" + "\n".join(body) + "\nEND $$;\n"
    )
    return out


# 발주 이력 (S15P31A308-242): 주문 시각 = 주문일(문서 날짜 − 거래처 리드타임) 오후, 입고 시각 = 문서 날짜 저녁 (가게 시간대).
# 입고 시각은 첫 실사(그날 23:59:59) · 마지막 실사(다음 날 0시)보다 앞이라 실사 판정이 문서와 같다. 리드타임 0일이어도 주문 뒤다
ORDER_SENT_TIME = "15:00"
ORDER_RECEIVED_TIME = "17:00"
# 리드타임을 모르는 거래처는 1일 (PurchaseOrderService.DEFAULT_LEAD_TIME_DAYS 와 같은 가정)
ORDER_DEFAULT_LEAD_TIME_DAYS = 1


def _order_sql(d: Path, store_name: str, label: str, st_sql: str | None = None, new_only: bool = False) -> list[str]:
    """seed 가게의 "발주 이력" 탭이 비지 않게 지난 발주를 넣는 문장 + 그 검증 DO 블록 (S15P31A308-242).

    - 매입 문서 1장 → 발주서 1장: 같은 거래처, status SENT, sent_at = created_at = 주문일 ORDER_SENT_TIME (가게 시간대)
    - 문서 줄 1개 → 품목 1줄: qty · received_qty = 줄 수량(DB 칸은 포장 개수 — ReceivingService · PurchaseOrderService 와 같다,
      화면은 × base_qty_per_pack 로 기준 단위를 낸다 #42), received_unit_price = 줄 단가(포장 1개), expected_date = 문서 날짜,
      received_at = 문서 날짜 ORDER_RECEIVED_TIME. 추천 연결 · 취소는 없다. 미입고 SENT(추천의 입고 예정)는 만들지 않는다
    - 문서를 그 발주서에 잇는다(purchase_doc.purchase_order_id). 안 이으면 주기형 입고 기록(InventoryEstimateService.CYCLE_RECEIPTS_SQL)이
      문서 줄과 발주 줄을 같은 날 두 번 더해 주기형 추정 재고가 바뀐다. 이으면 문서 줄 대신 같은 날 · 같은 양의 발주 줄이 읽힌다
    - 잇는 조건은 ReceiptConfirmService.linkPurchaseOrder 와 같다: 재고에 들어갈 줄(CONFIRMED · 재료 연결) 전부가 상품 · 양(> 0)이
      있어야 한다. 안 맞는 문서는 발주서를 만들지 않는다(이으면 그 줄이 주기형에서 빠지고, 안 이으면 두 번 더해진다)
    - price_history 는 쓰지 않는다 — 문서 줄의 DOC 행이 이미 있다(영수증 확정이 발주서를 이을 때도 RECEIVE 행을 안 쓴다)
    모든 문장은 그 가게(store_name) 범위다.
    --into-existing(S15P31A308-243): st_sql 로 가게를 찾고, new_only 면 넣기 전부터 있던 매입 문서(seed_old_doc) · 발주서(seed_old_po)는
    잇지도 세지도 않는다 (임시 표는 build_existing_seed_sql 이 맨 앞에 만든다).
    """
    docs = _read(d, "purchase_docs")
    lines = _read(d, "purchase_doc_lines")
    st = st_sql or f"(SELECT id FROM store WHERE name = {_lit(store_name)})"
    new_doc = " AND pd.id NOT IN (SELECT id FROM seed_old_doc)" if new_only else ""
    new_d = " AND d.id NOT IN (SELECT id FROM seed_old_doc)" if new_only else ""
    new_po = " AND id NOT IN (SELECT id FROM seed_old_po)" if new_only else ""
    new_poi = " AND po_id NOT IN (SELECT id FROM seed_old_po)" if new_only else ""
    new_poi_alias = " AND poi.po_id NOT IN (SELECT id FROM seed_old_po)" if new_only else ""
    new_docs = " AND id NOT IN (SELECT id FROM seed_old_doc)" if new_only else ""

    # 아래 SQL 의 문서 · 줄 조건과 같은 규칙으로 기대 건수를 센다 (시드 줄 status 는 모두 CONFIRMED)
    itemable = lines["supplier_product_id"].notna() & lines["ingredient_id"].notna() & (lines["qty"].fillna(0) > 0)
    unlinkable = lines["ingredient_id"].notna() & ~itemable
    order_docs = set(lines.loc[itemable, "doc_id"]) - set(lines.loc[unlinkable, "doc_id"])
    n_items = int((itemable & lines["doc_id"].isin(order_docs)).sum())

    itemable_sql = "l.supplier_product_id IS NOT NULL AND l.ingredient_id IS NOT NULL AND l.qty > 0"
    out = [
        "-- ===== 발주 이력 (S15P31A308-242): 매입 문서마다 보내고 입고 끝난 발주서 1장 · 문서 줄마다 품목 1줄 =====",
        "-- 발주서 → 문서 연결은 (거래처, 주문 시각) 으로 찾는다. 같은 거래처 · 같은 날 문서가 두 장이 없어(_store_sql 확인) 겹치지 않는다\n"
        "WITH src AS (\n"
        "    SELECT pd.id AS doc_id, pd.store_id, pd.supplier_id,\n"
        f"           ((pd.doc_date - COALESCE(s.lead_time_days, {ORDER_DEFAULT_LEAD_TIME_DAYS})) + TIME {_lit(ORDER_SENT_TIME)})"
        " AT TIME ZONE st.timezone AS sent_at\n"
        "    FROM purchase_doc pd\n"
        f"    JOIN store st ON st.id = pd.store_id AND st.id = {st}\n"
        "    JOIN supplier s ON s.id = pd.supplier_id AND s.store_id = st.id\n"
        f"    WHERE pd.doc_date IS NOT NULL{new_doc}\n"
        f"      AND EXISTS (SELECT 1 FROM purchase_doc_line l WHERE l.doc_id = pd.id AND l.store_id = st.id AND {itemable_sql})\n"
        "      AND NOT EXISTS (SELECT 1 FROM purchase_doc_line l WHERE l.doc_id = pd.id AND l.store_id = st.id\n"
        f"                      AND l.status = 'CONFIRMED' AND l.ingredient_id IS NOT NULL AND NOT ({itemable_sql}))\n"
        "), po AS (\n"
        "    INSERT INTO purchase_order (store_id, supplier_id, status, created_at, sent_at)\n"
        "    SELECT store_id, supplier_id, 'SENT', sent_at, sent_at FROM src ORDER BY sent_at, doc_id\n"
        "    RETURNING id, supplier_id, sent_at\n"
        ")\n"
        "UPDATE purchase_doc AS d SET purchase_order_id = po.id\n"
        "FROM src JOIN po ON po.supplier_id = src.supplier_id AND po.sent_at = src.sent_at\n"
        f"WHERE d.id = src.doc_id AND d.store_id = {st}{new_d};\n",
        "INSERT INTO purchase_order_item (store_id, po_id, supplier_product_id, ingredient_id, qty, expected_date,"
        " received_qty, received_at, received_unit_price)\n"
        "SELECT l.store_id, pd.purchase_order_id, l.supplier_product_id, l.ingredient_id, l.qty, pd.doc_date,"
        f" l.qty, (pd.doc_date + TIME {_lit(ORDER_RECEIVED_TIME)}) AT TIME ZONE st.timezone, NULLIF(l.unit_price, 0)\n"
        "FROM purchase_doc_line l\n"
        f"JOIN store st ON st.id = l.store_id AND st.id = {st}\n"
        f"JOIN purchase_doc pd ON pd.id = l.doc_id AND pd.store_id = st.id AND pd.purchase_order_id IS NOT NULL{new_doc}\n"
        f"WHERE {itemable_sql}\n"
        "ORDER BY pd.doc_date, pd.id, l.line_no;\n",
    ]
    checks = [
        ("purchase_order", f"purchase_order WHERE store_id = {st}{new_po}", len(order_docs)),
        ("purchase_order SENT 아님", f"purchase_order WHERE store_id = {st}{new_po} AND (status <> 'SENT' OR sent_at IS NULL)", 0),
        ("purchase_order_item", f"purchase_order_item WHERE store_id = {st}{new_poi}", n_items),
        # 추천의 입고 예정(RecommendationService.ON_ORDER_SQL)에 잡힐 줄이 없다
        ("purchase_order_item 입고 안 됨",
         f"purchase_order_item WHERE store_id = {st}{new_poi} AND (received_at IS NULL OR received_qty IS NULL)", 0),
        # 추정 재고(InventoryEstimateService.RECEIVED_SINCE_COUNT_SQL ①)에 더해질 줄이 없다
        ("purchase_order_item 마지막 실사 뒤 입고",
         f"purchase_order_item poi WHERE poi.store_id = {st}{new_poi_alias} AND poi.received_at >= (SELECT max(sc.counted_at) FROM stock_count sc"
         f" WHERE sc.store_id = {st} AND sc.ingredient_id = poi.ingredient_id)", 0),
        ("purchase_doc 발주서 미연결", f"purchase_doc WHERE store_id = {st}{new_docs} AND purchase_order_id IS NULL", len(docs) - len(order_docs)),
    ]
    who = _raise_text(label)
    body = [
        f"  SELECT count(*) INTO n FROM {where}; IF n <> {count} THEN"
        f" RAISE EXCEPTION '발주 이력 검증 실패: {who} {_raise_text(what)} % 행 (기대 {count})', n; END IF;"
        for what, where, count in checks
    ]
    out.append(
        "-- seed 발주 이력 검증: 발주서 · 품목이 덜 들어가거나 입고 예정 · 실사 뒤 입고가 생기면 멈춘다 (트랜잭션 안이라 전체가 되돌려진다)\n"
        "DO $$\nDECLARE n bigint;\nBEGIN\n" + "\n".join(body) + "\nEND $$;\n"
    )
    return out


def _storage_location(kind, storage_type) -> str:
    """재료 보관 위치(V9) 기본값 — 백엔드 StorageLocation.defaultFor 와 같다: 주기형 · 보관 방식 없음 → 창고."""
    if kind == "CYCLE" or storage_type is None:
        return "WAREHOUSE"
    return {"FROZEN": "FREEZER", "CHILLED": "FRIDGE", "FRESH": "FRIDGE", "AMBIENT": "SHELF"}[storage_type]


def read_ingredient_categories(d: Path) -> dict[tuple[str, str], tuple[str, str]]:
    """샘플 폴더(없으면 그 위 폴더)의 ingredient_categories.csv → {(가게 이름, 재료 이름): (카테고리 코드, 출처)}.

    백엔드 IngredientCategoryBackup 이 쓰는 파일과 같은 열. 코드가 기본 11종 밖이거나 비어 있으면(사장님이 만든 카테고리는 시드가
    만들지 않는다) 기타, 출처가 AUTO · OWNER 가 아니면 AUTO. 파일이 없으면 빈 dict — 재료는 전부 기타.
    """
    codes = {code for code, _, _ in INGREDIENT_CATEGORIES}
    for folder in (d, d.parent):
        for name in (CATEGORIES_FILE, CATEGORIES_FILE_FROM_SERVER):
            path = folder / name
            if not path.exists():
                continue
            df = pd.read_csv(path, encoding="utf-8-sig", keep_default_na=False, dtype=str)
            missing = {"store_name", "ingredient_name", "category_code", "category_source"} - set(df.columns)
            if missing:
                raise ValueError(f"{path.name}: 열이 없다 {sorted(missing)}")
            out = {}
            for r in df.itertuples():
                if r.category_code in codes:
                    source = r.category_source if r.category_source in ("AUTO", "OWNER") else INGREDIENT_CATEGORY_SOURCE
                    out[(r.store_name, r.ingredient_name)] = (r.category_code, source)
                else:
                    # 사장님이 만든 카테고리(코드 없음)는 시드가 만들지 않는다 — 기타로 두되 "사장님이 기타를 골랐다" 가 되지 않게 AUTO
                    out[(r.store_name, r.ingredient_name)] = (INGREDIENT_CATEGORY_OTHER, INGREDIENT_CATEGORY_SOURCE)
            return out
    return {}


def _store_sql(d: Path, store_name: str, email: str) -> tuple[list[str], dict[str, int], dict[str, int]]:
    """가게 하나의 INSERT 문들(store · account 부터 stock_count 까지), 표별 기대 행 수, 매입 줄 빈 연결 기대 수."""
    menus = _read(d, "menus")
    ingredients = _read(d, "ingredients")
    suppliers = _read(d, "suppliers")
    supplier_products = _read(d, "supplier_products")
    recipes = _read(d, "recipes")
    pos_sales = _read(d, "pos_sales")
    closed_days = _read(d, "closed_days")
    purchase_docs = _read(d, "purchase_docs")
    purchase_doc_lines = _read(d, "purchase_doc_lines")
    true_stock = _read(d, "true_stock")

    menu_name = dict(zip(menus["menu_id"], menus["name"], strict=False))
    ingredient_name = dict(zip(ingredients["ingredient_id"], ingredients["name"], strict=False))
    supplier_name = dict(zip(suppliers["supplier_id"], suppliers["name"], strict=False))
    product_key = {
        p.supplier_product_id: (_lookup(supplier_name, p.supplier_id, "supplier_products.supplier_id"), p.product_name)
        for p in supplier_products.itertuples()
    }

    # 매입 문서는 자연키가 없다 — (거래처, 문서 날짜)를 키로 쓴다. 겹치면 줄을 어느 문서에 붙일지 모르니 멈춘다
    doc_key = {
        doc.doc_id: (_lookup(supplier_name, doc.supplier_id, "purchase_docs.supplier_id"), doc.doc_date)
        for doc in purchase_docs.itertuples()
    }
    if len(set(doc_key.values())) != len(doc_key):
        raise ValueError("purchase_docs: 같은 (거래처, 날짜) 문서가 두 장 이상이다")

    unknown = sorted(set(pos_sales["menu_name"]) - set(menus["name"]))
    if unknown:
        raise ValueError(f"pos_sales.menu_name: menus 에 없는 메뉴 {unknown}")

    # 표별로 넣을 행 수. 끝의 검증 블록이 DB 에 실제로 들어간 수와 견준다
    expected: dict[str, int] = {"store": 1, "account": 1}

    def _insert(table, columns, select, alias, rows, joins=""):
        expected[table] = expected.get(table, 0) + len(rows)
        return _insert_sql(table, columns, select, alias, rows, joins, store_name)

    first_day = true_stock["stock_date"].min()

    out = [
        f"INSERT INTO store (name, display_name) VALUES\n{_values([(store_name, store_name)])};\n",
        f"INSERT INTO account (email, password_hash, name) VALUES\n{_values([(email, demo_password_hash(), DEMO_NAME)])};\n",
        _insert(
            "store_member",
            ["account_id", "store_id", "role"],
            "a.id, st.id, v.role",
            ["email", "role"],
            [(email, "OWNER")],
            "\nJOIN account a ON a.email = v.email",
        ),
        # 가게가 생기면 기본 카테고리 11종 (백엔드는 가입 때 StoreAccountCreator 가, 기존 가게는 V11 이 넣는다)
        _insert(
            "ingredient_category",
            ["store_id", "code", "name", "sort_order"],
            "st.id, v.code, v.name, v.sort_order::integer",
            ["code", "name", "sort_order"],
            INGREDIENT_CATEGORIES,
        ),
        _insert(
            "menu",
            ["store_id", "name", "kind", "source"],
            "st.id, v.name, v.kind, v.source",
            ["name", "kind", "source"],
            [(n, MENU_KIND, MENU_SOURCE) for n in menus["name"]],
        ),
    ]

    if "pos_name" in menus.columns:
        aliases = _records(menus[menus["pos_name"].notna()], ["name", "pos_name"])
        out.append(
            _insert(
                "menu_alias",
                ["store_id", "menu_id", "pos_name"],
                "st.id, m.id, v.pos_name",
                ["menu_name", "pos_name"],
                aliases,
                "\nJOIN menu m ON m.store_id = st.id AND m.name = v.menu_name",
            )
        )

    categories = read_ingredient_categories(d)
    out.append(
        _insert(
            "ingredient",
            ["store_id", "name", "unit", "kind", "storage_type", "safety_days", "shelf_life_days",
             "price_sensitive", "sourcing_type", "storage_location", "category_id", "category_source"],
            "st.id, v.name, v.unit, v.kind, v.storage_type, v.safety_days::numeric, v.shelf_life_days::integer,"
            " v.price_sensitive, v.sourcing_type, v.storage_location, c.id, v.category_source",
            ["name", "unit", "kind", "storage_type", "safety_days", "shelf_life_days", "price_sensitive", "sourcing_type",
             "storage_location", "category_code", "category_source"],
            [
                (*row, INGREDIENT_PRICE_SENSITIVE, INGREDIENT_SOURCING_TYPE, _storage_location(row[2], row[3]),
                 *categories.get((store_name, row[0]), (INGREDIENT_CATEGORY_OTHER, INGREDIENT_CATEGORY_SOURCE)))
                for row in _records(ingredients, ["name", "unit", "kind", "storage_type", "safety_days", "shelf_life_days"])
            ],
            "\nJOIN ingredient_category c ON c.store_id = st.id AND c.code = v.category_code",
        )
    )
    out.append(
        _insert(
            "supplier",
            ["store_id", "name", "channel", "lead_time_days", "order_cycle_days", "min_order_amount"],
            "st.id, v.name, v.channel, v.lead_time_days::integer, v.order_cycle_days::integer, v.min_order_amount::numeric",
            ["name", "channel", "lead_time_days", "order_cycle_days", "min_order_amount"],
            _records(suppliers, ["name", "channel", "lead_time_days", "order_cycle_days", "min_order_amount"]),
        )
    )
    out.append(
        _insert(
            "supplier_product",
            ["store_id", "supplier_id", "ingredient_id", "product_name", "pack_unit", "base_qty_per_pack", "moq", "price"],
            "st.id, s.id, i.id, v.product_name, v.pack_unit, v.base_qty_per_pack::numeric, v.moq::numeric, v.price::numeric",
            ["supplier_name", "ingredient_name", "product_name", "pack_unit", "base_qty_per_pack", "moq", "price"],
            [
                (
                    _lookup(supplier_name, supplier_id, "supplier_products.supplier_id"),
                    _lookup(ingredient_name, ingredient_id, "supplier_products.ingredient_id"),
                    *rest,
                )
                for supplier_id, ingredient_id, *rest in _records(
                    supplier_products,
                    ["supplier_id", "ingredient_id", "product_name", "pack_unit", "base_qty_per_pack", "moq", "price"],
                )
            ],
            "\nJOIN supplier s ON s.store_id = st.id AND s.name = v.supplier_name"
            "\nJOIN ingredient i ON i.store_id = st.id AND i.name = v.ingredient_name",
        )
    )
    out.append(
        _insert(
            "recipe",
            ["store_id", "menu_id", "ingredient_id", "qty_per_serving"],
            "st.id, m.id, i.id, v.qty::numeric",
            ["menu_name", "ingredient_name", "qty"],
            [
                (
                    _lookup(menu_name, r.menu_id, "recipes.menu_id"),
                    _lookup(ingredient_name, r.ingredient_id, "recipes.ingredient_id"),
                    _py(r.qty_per_serving),
                )
                for r in recipes.itertuples()
            ],
            "\nJOIN menu m ON m.store_id = st.id AND m.name = v.menu_name"
            "\nJOIN ingredient i ON i.store_id = st.id AND i.name = v.ingredient_name",
        )
    )
    # 원래 이름은 raw_menu_name 에 남긴다
    out.append(
        _insert(
            "pos_sale",
            ["store_id", "sold_date", "menu_id", "qty", "raw_menu_name", "channel", "source"],
            f"st.id, v.sold_date::date, m.id, v.qty::numeric, v.menu_name, v.channel, {_lit(POS_SALE_SOURCE)}",
            ["sold_date", "menu_name", "qty", "channel"],
            _records(pos_sales, ["sold_date", "menu_name", "qty", "channel"]),
            "\nJOIN menu m ON m.store_id = st.id AND m.name = v.menu_name",
        )
    )
    out.append(
        _insert(
            "store_closed_day",
            ["store_id", "closed_date"],
            "st.id, v.closed_date::date",
            ["closed_date"],
            _records(closed_days, ["closed_date"]),
        )
    )
    out.append(
        _insert(
            "purchase_doc",
            ["store_id", "supplier_id", "doc_type", "doc_date", "kind", "total_amount", "parse_status"],
            "st.id, s.id, v.doc_type, v.doc_date::date, v.kind, v.total_amount::numeric, v.parse_status",
            ["supplier_name", "doc_type", "doc_date", "kind", "total_amount", "parse_status"],
            [
                (doc_key[doc_id][0], doc_type, doc_date, _doc_kind(doc_date, first_day), total, PURCHASE_DOC_PARSE_STATUS)
                for doc_id, doc_type, doc_date, total in _records(
                    purchase_docs, ["doc_id", "doc_type", "doc_date", "total_amount"]
                )
            ],
            "\nJOIN supplier s ON s.store_id = st.id AND s.name = v.supplier_name",
        )
    )
    line_rows = []
    for line in purchase_doc_lines.itertuples():
        doc_supplier, doc_date = _lookup(doc_key, line.doc_id, "purchase_doc_lines.doc_id")
        sp_supplier, sp_name = (None, None)
        if not pd.isna(line.supplier_product_id):
            sp_supplier, sp_name = _lookup(product_key, line.supplier_product_id, "purchase_doc_lines.supplier_product_id")
        ing = None if pd.isna(line.ingredient_id) else _lookup(ingredient_name, line.ingredient_id, "purchase_doc_lines.ingredient_id")
        line_rows.append(
            (
                doc_supplier,
                doc_date,
                _py(line.line_no),
                _py(line.raw_name),
                _py(line.qty),
                _py(line.unit),
                _py(line.unit_price),
                _py(line.amount),
                sp_supplier,
                sp_name,
                ing,
                PURCHASE_DOC_LINE_STATUS,
            )
        )
    out.append(
        _insert(
            "purchase_doc_line",
            ["store_id", "doc_id", "line_no", "raw_name", "qty", "unit", "unit_price", "amount",
             "supplier_product_id", "ingredient_id", "status"],
            "st.id, pd.id, v.line_no::integer, v.raw_name, v.qty::numeric, v.unit, v.unit_price::numeric, v.amount::numeric,"
            " sp.id, i.id, v.status",
            ["doc_supplier", "doc_date", "line_no", "raw_name", "qty", "unit", "unit_price", "amount",
             "sp_supplier", "sp_name", "ingredient_name", "status"],
            line_rows,
            "\nJOIN supplier ds ON ds.store_id = st.id AND ds.name = v.doc_supplier"
            "\nJOIN purchase_doc pd ON pd.store_id = st.id AND pd.supplier_id = ds.id AND pd.doc_date = v.doc_date::date"
            "\nLEFT JOIN supplier ss ON ss.store_id = st.id AND ss.name = v.sp_supplier"
            "\nLEFT JOIN supplier_product sp ON sp.supplier_id = ss.id AND sp.product_name = v.sp_name"
            "\nLEFT JOIN ingredient i ON i.store_id = st.id AND i.name = v.ingredient_name",
        )
    )

    first_stock = true_stock[true_stock["stock_date"] == first_day]
    out.append(
        _insert(
            "stock_count",
            ["store_id", "ingredient_id", "counted_at", "qty"],
            "st.id, i.id, v.counted_at::timestamptz, v.qty::numeric",
            ["ingredient_name", "counted_at", "qty"],
            [
                (_lookup(ingredient_name, s.ingredient_id, "true_stock.ingredient_id"), f"{s.stock_date} {STOCK_COUNT_TIME}", _py(s.qty))
                for s in first_stock.itertuples()
            ],
            "\nJOIN ingredient i ON i.store_id = st.id AND i.name = v.ingredient_name",
        )
    )
    line_nulls = {
        "supplier_product_id": sum(1 for row in line_rows if row[9] is None),
        "ingredient_id": sum(1 for row in line_rows if row[10] is None),
    }
    return out, expected, line_nulls


# ---- 이미 있는 가게에 이력만 더하기 (--into-existing, S15P31A308-243) ----


def _existing_store(store_name: str | None, store_id: int | None) -> tuple[str, str]:
    """(가게를 찾는 SELECT, 검증 메시지에 쓸 이름). 이름 · id 중 정확히 하나만 받는다."""
    if (store_name is None) == (store_id is None):
        raise ValueError("--into-existing: 가게는 이름(--store-name) 또는 id(--store-id) 하나로 정한다")
    if store_id is not None:
        return f"SELECT id FROM store WHERE id = {int(store_id)}", f"store_id={int(store_id)}"
    return f"SELECT id FROM store WHERE name = {_lit(store_name)}", str(store_name)


def _check_block(title: str, label: str, checks: list[tuple[str, str, int]]) -> str:
    """'SELECT count(*) INTO n FROM <where>; IF n <> <기대>' 검증 DO 블록."""
    who = _raise_text(label)
    body = [
        f"  SELECT count(*) INTO n FROM {where}; IF n <> {count} THEN"
        f" RAISE EXCEPTION '{title}: {who} {_raise_text(what)} % 행 (기대 {count})', n; END IF;"
        for what, where, count in checks
    ]
    return "DO $$\nDECLARE n bigint;\nBEGIN\n" + "\n".join(body) + "\nEND $$;\n"


def build_existing_seed_sql(sample_dir, store_name: str | None = None, store_id: int | None = None) -> str:
    """이미 있는 가게(이름 또는 id)에 판매 · 휴무일 · 거래처(없는 것만) · 거래처 상품 · 매입 문서 · 발주 이력 · 단가 이력 ·
    첫날 · 마지막 날 실사 · 대표 상품 · unit_status 를 넣는 seed.sql (S15P31A308-243).

    sample_dir 은 make_sample_data.py --from-existing 출력(그 가게에서 떠 온 메뉴 · 재료 · 레시피로 만든 틀의 생성 결과).
    - 가게 · 계정 · 메뉴 · 재료 · 레시피는 넣지 않는다. 메뉴 · 재료 · 거래처는 그 가게 안에서 이름으로 찾는다
    - 첫 문장이 확인한다: 가게가 정확히 1곳, 그 가게에 판매 · 거래처 상품 · 실사가 없음(이중 적재 방지), 생성에 쓴 메뉴 · 재료가 다 있음
    - 기존 메뉴 · 재료 · 레시피 · 거래처 · 매입 문서 행의 값은 바꾸지 않는다. 예외: 재료의 대표 상품이 비어 있으면 대표 상품 · unit_status=LOCKED
    - 넣기 전부터 있던 매입 문서 · 발주서는 임시 표(seed_old_doc · seed_old_po)에 적어 두고 잇거나 세지 않는다
    - 끝에 건수 검증 블록. 전체가 한 트랜잭션
    CSV 끼리 이름 · id 가 안 맞거나 가게를 안 주면 ValueError.
    """
    d = Path(sample_dir)
    select, label = _existing_store(store_name, store_id)
    st = f"({select})"
    menus = _read(d, "menus")
    ingredients = _read(d, "ingredients")
    suppliers = _read(d, "suppliers")
    supplier_products = _read(d, "supplier_products")
    pos_sales = _read(d, "pos_sales")
    closed_days = _read(d, "closed_days")
    purchase_docs = _read(d, "purchase_docs")
    purchase_doc_lines = _read(d, "purchase_doc_lines")
    true_stock = _read(d, "true_stock")

    ingredient_name = dict(zip(ingredients["ingredient_id"], ingredients["name"], strict=False))
    supplier_name = dict(zip(suppliers["supplier_id"], suppliers["name"], strict=False))
    product_key = {
        p.supplier_product_id: (_lookup(supplier_name, p.supplier_id, "supplier_products.supplier_id"), p.product_name)
        for p in supplier_products.itertuples()
    }
    doc_key = {
        doc.doc_id: (_lookup(supplier_name, doc.supplier_id, "purchase_docs.supplier_id"), doc.doc_date)
        for doc in purchase_docs.itertuples()
    }
    if len(set(doc_key.values())) != len(doc_key):
        raise ValueError("purchase_docs: 같은 (거래처, 날짜) 문서가 두 장 이상이다")
    unknown = sorted(set(pos_sales["menu_name"]) - set(menus["name"]))
    if unknown:
        raise ValueError(f"pos_sales.menu_name: menus 에 없는 메뉴 {unknown}")

    def ins(table, columns, sel, alias, rows, joins="", where=""):
        return _insert_sql(table, columns, sel, alias, rows, joins, store_select=select, where=where)

    def names(values) -> str:
        return ", ".join(_lit(_py(v)) for v in values)

    menu_names, ing_names, sup_names = list(menus["name"]), list(ingredients["name"]), list(suppliers["name"])
    who = _raise_text(label)
    first_day, last_day = true_stock["stock_date"].min(), true_stock["stock_date"].max()
    old_doc = "pd.id NOT IN (SELECT id FROM seed_old_doc)"
    store_where = select.split(" WHERE ", 1)[1]

    out = [
        "-- 이미 있는 가게에 합성 이력 덧붙이기 (ai/scripts/make_seed_sql.py --into-existing 가 만든다. 손으로 고치지 않는다)",
        f"-- 원본: {d.name}/*.csv · 대상 가게: {label} (가게 · 계정 · 메뉴 · 재료 · 레시피는 넣지 않는다)",
        "BEGIN;",
        "",
        "-- 대상 가게가 정확히 1곳이고, 판매 · 거래처 상품 · 실사가 없고(이중 적재 방지), 생성에 쓴 메뉴 · 재료가 다 있어야 한다",
        "DO $$\nDECLARE n bigint;\nBEGIN\n"
        f"  SELECT count(*) INTO n FROM store WHERE {store_where};\n"
        f"  IF n = 0 THEN RAISE EXCEPTION 'seed.sql(--into-existing): 가게를 찾을 수 없다 — {who}'; END IF;\n"
        f"  IF n > 1 THEN RAISE EXCEPTION 'seed.sql(--into-existing): 가게가 하나가 아니다 — {who} % 곳', n; END IF;\n"
        f"  IF EXISTS (SELECT 1 FROM pos_sale WHERE store_id = {st}) OR EXISTS (SELECT 1 FROM supplier_product WHERE store_id = {st})"
        f" OR EXISTS (SELECT 1 FROM stock_count WHERE store_id = {st}) THEN\n"
        f"    RAISE EXCEPTION 'seed.sql(--into-existing): {who} 에 이미 판매 · 거래처 상품 · 실사가 있다 (이중 적재 방지)'; END IF;\n"
        f"  SELECT count(*) INTO n FROM menu WHERE store_id = {st} AND name IN ({names(menu_names)});\n"
        f"  IF n <> {len(menu_names)} THEN RAISE EXCEPTION"
        f" 'seed.sql(--into-existing): {who} 에 생성에 쓴 메뉴가 % / {len(menu_names)} 개만 있다', n; END IF;\n"
        f"  SELECT count(*) INTO n FROM ingredient WHERE store_id = {st} AND name IN ({names(ing_names)});\n"
        f"  IF n <> {len(ing_names)} THEN RAISE EXCEPTION"
        f" 'seed.sql(--into-existing): {who} 에 생성에 쓴 재료가 % / {len(ing_names)} 개만 있다', n; END IF;\n"
        "END $$;",
        "",
        "-- 넣기 전부터 있던 매입 문서 · 발주서 (발주서 연결 · 검증에서 뺀다)",
        f"CREATE TEMP TABLE seed_old_doc ON COMMIT DROP AS SELECT id FROM purchase_doc WHERE store_id = {st};",
        f"CREATE TEMP TABLE seed_old_po ON COMMIT DROP AS SELECT id FROM purchase_order WHERE store_id = {st};",
        "",
        "-- 거래처: 그 가게에 같은 이름이 없는 것만 (있는 거래처 행은 그대로 둔다). 마감 시각 · 배송 요일은 발주 주기에 맞춘 값",
        ins(
            "supplier",
            ["store_id", "name", "channel", "lead_time_days", "order_cycle_days", "min_order_amount", "cutoff_time", "delivery_days"],
            "st.id, v.name, v.channel, v.lead_time_days::integer, v.order_cycle_days::integer, v.min_order_amount::numeric,"
            " v.cutoff_time::time, string_to_array(v.delivery_days, ',')",
            ["name", "channel", "lead_time_days", "order_cycle_days", "min_order_amount", "cutoff_time", "delivery_days"],
            [
                (*row, SUPPLIER_CUTOFF_TIME, DELIVERY_DAYS_BY_CYCLE.get(int(row[3]), DELIVERY_DAYS_DEFAULT))
                for row in _records(suppliers, ["name", "channel", "lead_time_days", "order_cycle_days", "min_order_amount"])
            ],
            where="\nWHERE NOT EXISTS (SELECT 1 FROM supplier x WHERE x.store_id = st.id AND x.name = v.name)",
        ),
        ins(
            "supplier_product",
            ["store_id", "supplier_id", "ingredient_id", "product_name", "pack_unit", "base_qty_per_pack", "moq", "price"],
            "st.id, s.id, i.id, v.product_name, v.pack_unit, v.base_qty_per_pack::numeric, v.moq::numeric, v.price::numeric",
            ["supplier_name", "ingredient_name", "product_name", "pack_unit", "base_qty_per_pack", "moq", "price"],
            [
                (
                    _lookup(supplier_name, supplier_id, "supplier_products.supplier_id"),
                    _lookup(ingredient_name, ingredient_id, "supplier_products.ingredient_id"),
                    *rest,
                )
                for supplier_id, ingredient_id, *rest in _records(
                    supplier_products,
                    ["supplier_id", "ingredient_id", "product_name", "pack_unit", "base_qty_per_pack", "moq", "price"],
                )
            ],
            "\nJOIN supplier s ON s.store_id = st.id AND s.name = v.supplier_name"
            "\nJOIN ingredient i ON i.store_id = st.id AND i.name = v.ingredient_name",
        ),
        ins(
            "pos_sale",
            ["store_id", "sold_date", "menu_id", "qty", "raw_menu_name", "channel", "source"],
            f"st.id, v.sold_date::date, m.id, v.qty::numeric, v.menu_name, v.channel, {_lit(POS_SALE_SOURCE)}",
            ["sold_date", "menu_name", "qty", "channel"],
            _records(pos_sales, ["sold_date", "menu_name", "qty", "channel"]),
            "\nJOIN menu m ON m.store_id = st.id AND m.name = v.menu_name",
        ),
        "-- 휴무일: 그 가게에 이미 있는 날은 건너뛴다",
        ins(
            "store_closed_day",
            ["store_id", "closed_date"],
            "st.id, v.closed_date::date",
            ["closed_date"],
            _records(closed_days, ["closed_date"]),
            where="\nWHERE NOT EXISTS (SELECT 1 FROM store_closed_day x WHERE x.store_id = st.id AND x.closed_date = v.closed_date::date)",
        ),
        ins(
            "purchase_doc",
            ["store_id", "supplier_id", "doc_type", "doc_date", "kind", "total_amount", "parse_status"],
            "st.id, s.id, v.doc_type, v.doc_date::date, v.kind, v.total_amount::numeric, v.parse_status",
            ["supplier_name", "doc_type", "doc_date", "kind", "total_amount", "parse_status"],
            [
                (doc_key[doc_id][0], doc_type, doc_date, _doc_kind(doc_date, first_day), total, PURCHASE_DOC_PARSE_STATUS)
                for doc_id, doc_type, doc_date, total in _records(purchase_docs, ["doc_id", "doc_type", "doc_date", "total_amount"])
            ],
            "\nJOIN supplier s ON s.store_id = st.id AND s.name = v.supplier_name",
        ),
    ]
    line_rows = []
    for line in purchase_doc_lines.itertuples():
        doc_supplier, doc_date = _lookup(doc_key, line.doc_id, "purchase_doc_lines.doc_id")
        sp_supplier, sp_name = (None, None)
        if not pd.isna(line.supplier_product_id):
            sp_supplier, sp_name = _lookup(product_key, line.supplier_product_id, "purchase_doc_lines.supplier_product_id")
        ing = None if pd.isna(line.ingredient_id) else _lookup(ingredient_name, line.ingredient_id, "purchase_doc_lines.ingredient_id")
        line_rows.append((doc_supplier, doc_date, _py(line.line_no), _py(line.raw_name), _py(line.qty), _py(line.unit),
                          _py(line.unit_price), _py(line.amount), sp_supplier, sp_name, ing, PURCHASE_DOC_LINE_STATUS))
    out.append(
        ins(
            "purchase_doc_line",
            ["store_id", "doc_id", "line_no", "raw_name", "qty", "unit", "unit_price", "amount",
             "supplier_product_id", "ingredient_id", "status"],
            "st.id, pd.id, v.line_no::integer, v.raw_name, v.qty::numeric, v.unit, v.unit_price::numeric, v.amount::numeric,"
            " sp.id, i.id, v.status",
            ["doc_supplier", "doc_date", "line_no", "raw_name", "qty", "unit", "unit_price", "amount",
             "sp_supplier", "sp_name", "ingredient_name", "status"],
            line_rows,
            "\nJOIN supplier ds ON ds.store_id = st.id AND ds.name = v.doc_supplier"
            f"\nJOIN purchase_doc pd ON pd.store_id = st.id AND pd.supplier_id = ds.id AND pd.doc_date = v.doc_date::date AND {old_doc}"
            "\nLEFT JOIN supplier ss ON ss.store_id = st.id AND ss.name = v.sp_supplier"
            "\nLEFT JOIN supplier_product sp ON sp.supplier_id = ss.id AND sp.product_name = v.sp_name"
            "\nLEFT JOIN ingredient i ON i.store_id = st.id AND i.name = v.ingredient_name",
        )
    )
    first_rows = [
        (_lookup(ingredient_name, s.ingredient_id, "true_stock.ingredient_id"), f"{s.stock_date} {STOCK_COUNT_TIME}", _py(s.qty))
        for s in true_stock[true_stock["stock_date"] == first_day].itertuples()
    ]
    stock_join = "\nJOIN ingredient i ON i.store_id = st.id AND i.name = v.ingredient_name"
    out.append(ins("stock_count", ["store_id", "ingredient_id", "counted_at", "qty"],
                   "st.id, i.id, v.counted_at::timestamptz, v.qty::numeric", ["ingredient_name", "counted_at", "qty"],
                   first_rows, stock_join))

    new_lines = f"purchase_doc_line l WHERE l.store_id = {st} AND l.doc_id NOT IN (SELECT id FROM seed_old_doc)"
    closed_where = (f"store_closed_day WHERE store_id = {st} AND closed_date IN ({names(closed_days['closed_date'])})"
                    if len(closed_days) else f"store_closed_day WHERE store_id = {st} AND false")
    out.append("-- seed 검증 (--into-existing): 이름으로 찾는 JOIN 이 행을 떨구거나 연결을 비우면 멈춘다 (트랜잭션 안이라 전체가 되돌려진다)")
    out.append(_check_block("seed 검증 실패", label, [
        ("supplier", f"supplier WHERE store_id = {st} AND name IN ({names(sup_names)})", len(sup_names)),
        ("supplier_product", f"supplier_product WHERE store_id = {st}", len(supplier_products)),
        ("pos_sale", f"pos_sale WHERE store_id = {st}", len(pos_sales)),
        ("store_closed_day", closed_where, len(closed_days)),
        ("purchase_doc", f"purchase_doc WHERE store_id = {st} AND id NOT IN (SELECT id FROM seed_old_doc)", len(purchase_docs)),
        ("purchase_doc_line", new_lines, len(line_rows)),
        ("purchase_doc_line.supplier_product_id NULL", f"{new_lines} AND l.supplier_product_id IS NULL",
         sum(1 for row in line_rows if row[9] is None)),
        ("purchase_doc_line.ingredient_id NULL", f"{new_lines} AND l.ingredient_id IS NULL",
         sum(1 for row in line_rows if row[10] is None)),
        ("stock_count", f"stock_count WHERE store_id = {st}", len(first_rows)),
    ]))

    # 화면 충족 (S15P31A308-240 과 같은 목적): 대표 상품 · 단가 이력 · 마지막 날 실사. 메뉴 판매가 · 기존 거래처 요일은 그대로 둔다
    out.append("-- ===== 화면 충족 (--into-existing): 대표 상품(빈 재료만) · 단가 이력 · 마지막 날 실사 =====")
    primary = [
        (_lookup(ingredient_name, p.ingredient_id, "supplier_products.ingredient_id"),
         _lookup(supplier_name, p.supplier_id, "supplier_products.supplier_id"), p.product_name)
        for p in supplier_products.drop_duplicates("ingredient_id").itertuples()
    ]
    if primary:
        out.append(
            "UPDATE ingredient AS i SET primary_supplier_product_id = sp.id, unit_status = 'LOCKED'\n"
            f"FROM (VALUES\n{_values(primary)}\n) AS v(ingredient_name, supplier_name, product_name)\n"
            f"JOIN supplier s ON s.store_id = {st} AND s.name = v.supplier_name\n"
            "JOIN supplier_product sp ON sp.supplier_id = s.id AND sp.product_name = v.product_name\n"
            f"WHERE i.store_id = {st} AND i.name = v.ingredient_name AND i.primary_supplier_product_id IS NULL;\n"
        )
    lines_df = purchase_doc_lines
    priced_lines = int((lines_df["supplier_product_id"].notna() & (lines_df["unit_price"].fillna(0) > 0)).sum())
    out.append(
        "-- 이번에 넣은 매입 문서 줄마다 단가 이력 1행\n"
        "INSERT INTO price_history (store_id, supplier_product_id, price, unit, observed_at, source, doc_line_id)\n"
        "SELECT l.store_id, l.supplier_product_id, l.unit_price, COALESCE(l.unit, 'PACK'),"
        " pd.doc_date::timestamp AT TIME ZONE st.timezone, 'DOC', l.id\n"
        "FROM purchase_doc_line l\n"
        f"JOIN store st ON st.id = l.store_id AND st.id = {st}\n"
        f"JOIN purchase_doc pd ON pd.id = l.doc_id AND pd.store_id = st.id AND {old_doc}\n"
        "WHERE l.supplier_product_id IS NOT NULL AND l.unit_price > 0\n"
        "ORDER BY pd.doc_date, pd.id, l.line_no;\n"
    )
    last_rows = []
    if last_day != first_day:
        last_counted_at = f"{pd.Timestamp(last_day) + pd.Timedelta(days=1):%Y-%m-%d} {LAST_STOCK_COUNT_TIME}"
        last_rows = [
            (_lookup(ingredient_name, s.ingredient_id, "true_stock.ingredient_id"), last_counted_at, _py(s.qty))
            for s in true_stock[true_stock["stock_date"] == last_day].itertuples()
        ]
        out.append("-- 마지막 날 마감 재고를 다음 날 0시 실사로 (_screen_sql 과 같은 이유)\n"
                   + ins("stock_count", ["store_id", "ingredient_id", "counted_at", "qty"],
                         "st.id, i.id, v.counted_at::timestamptz, v.qty::numeric", ["ingredient_name", "counted_at", "qty"],
                         last_rows, stock_join))
    out.append("-- seed 화면 충족 검증 (--into-existing)")
    out.append(_check_block("seed 화면 충족 검증 실패", label, [
        ("ingredient.primary_supplier_product_id NULL",
         f"ingredient WHERE store_id = {st} AND name IN ({names(ing_names)}) AND primary_supplier_product_id IS NULL",
         len(ing_names) - len(primary)),
        # 추천의 SUPPLIER_UNSET: 대표 상품 거래처에 리드타임 · 발주 주기가 없으면 뜬다
        ("supplier 리드타임 · 발주 주기 NULL",
         f"supplier WHERE store_id = {st} AND name IN ({names(sup_names)}) AND (lead_time_days IS NULL OR order_cycle_days IS NULL)", 0),
        ("price_history DOC", f"price_history WHERE store_id = {st} AND source = 'DOC'", priced_lines),
        ("stock_count", f"stock_count WHERE store_id = {st}", len(first_rows) + len(last_rows)),
    ]))
    out.extend(_order_sql(d, label, label, st_sql=st, new_only=True))
    out.append("COMMIT;")
    return "\n".join(out) + "\n"
