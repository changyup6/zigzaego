"""이미 있는 가게(팀 테스트 계정)에 합성 이력 덧붙이기 (S15P31A308-243).

떠 온 CSV → 가게 틀(template_from_existing) → generate → seed SQL(build_existing_seed_sql, --into-existing).
DB 에 넣어 보는 경계(두 번 넣기 · 없는 가게 · 다른 가게 무변경)는 로컬 Docker 확인 스크립트가 본다. 여기는 SQL 모양과 틀 규칙.
"""

import re
import runpy
import sys
from dataclasses import fields
from pathlib import Path

import pandas as pd
import pytest

from weglee_ai.sample_data import generate
from weglee_ai.seed_sql import build_existing_seed_sql
from weglee_ai.store_templates import (
    DONKATSU,
    EXISTING_MENU_DAILY_BUDGET,
    EXISTING_MENU_MAX_BASE,
    template_from_existing,
)

ROOT = Path(__file__).resolve().parents[1]
SAMPLE_SCRIPT = ROOT / "scripts" / "make_sample_data.py"
SEED_SCRIPT = ROOT / "scripts" / "make_seed_sql.py"


def _csv(path: Path, text: str) -> None:
    path.write_text(text.strip() + "\n", encoding="utf-8")


@pytest.fixture()
def dump_dir(tmp_path):
    """스테이징에서 떠 온 모양 그대로의 작은 가게. 비활성 메뉴 1 · 레시피 없는 메뉴 1 · 빈 값 많은 거래처 1."""
    d = tmp_path / "dump"
    d.mkdir()
    _csv(d / "menu.csv", """
id,name,kind,active,price,source
1,로스카츠,MAIN,t,15000.00,NAVER
2,정성한상세트,SET,t,37500.00,NAVER
3,감자고로케(추가메뉴),OPTION,t,5000.00,NAVER
4,단종 메뉴,MAIN,f,9000.00,NAVER
5,콜라 사이다,MAIN,t,2000.00,NAVER
""")
    _csv(d / "ingredient.csv", """
id,name,unit,kind,storage_type,shelf_life_days,sourcing_type,price_sensitive,safety_days
2,돼지 등심,G,BOM,CHILLED,5,GENERIC,f,1.00
3,빵가루,G,BOM,AMBIENT,180,GENERIC,t,1.00
4,파,G,BOM,FRESH,5,GENERIC,f,1.00
5,모짜렐라 치즈,G,BOM,CHILLED,30,GENERIC,f,1.00
6,탄산음료,EA,ONE_TO_ONE,AMBIENT,180,GENERIC,t,1.00
7,생맥주,ML,BOM,CHILLED,30,GENERIC,f,1.00
8,닭고기,G,BOM,CHILLED,3,GENERIC,f,1.00
9,닭다리살,G,BOM,CHILLED,3,GENERIC,f,1.00
""")
    _csv(d / "recipe.csv", """
id,menu_id,menu_name,ingredient_id,ingredient_name,qty_per_serving
1,1,로스카츠,2,돼지 등심,180.0000
2,1,로스카츠,3,빵가루,20.0000
3,2,정성한상세트,2,돼지 등심,180.0000
4,2,정성한상세트,4,파,10.0000
5,2,정성한상세트,5,모짜렐라 치즈,30.0000
6,4,단종 메뉴,7,생맥주,500.0000
7,5,콜라 사이다,6,탄산음료,1.0000
8,2,정성한상세트,8,닭고기,120.0000
9,1,로스카츠,9,닭다리살,50.0000
""")
    _csv(d / "supplier.csv", """
id,store_id,name,channel,lead_time_days,order_cycle_days,cutoff_time,delivery_days,min_order_amount,message_template,email,shipping_fee,free_shipping_min
1,2,횡성한우,,,,,,,,,,
""")
    (d / "store.csv").write_text("", encoding="utf-8")  # 스테이징에서 뜨다 실패한 모양 (빈 파일)
    return d


def _write(data, out: Path) -> Path:
    out.mkdir(parents=True, exist_ok=True)
    for f in fields(data):
        getattr(data, f.name).to_csv(out / f"{f.name}.csv", index=False, encoding="utf-8-sig")
    return out


@pytest.fixture()
def sample_dir(dump_dir, tmp_path):
    return _write(generate(end_date="2026-11-02", days=60, template=template_from_existing(dump_dir)), tmp_path / "gen")


# ---- 가게 틀: 떠 온 그대로 + 정해진 규칙 ----


def test_template_keeps_dump_menus_ingredients_recipes(dump_dir):
    t = template_from_existing(dump_dir)
    # 비활성 메뉴(단종 메뉴)는 판매를 만들지 않는다 — 틀 메뉴에서 빠진다. 레시피 없는 메뉴(감자고로케)는 남는다
    assert [m[0] for m in t.menus] == ["로스카츠", "정성한상세트", "감자고로케(추가메뉴)", "콜라 사이다"]
    assert [m[3] for m in t.menus] == [15000, 37500, 5000, 2000]
    # 재료는 전부(비활성 메뉴에만 쓰이는 생맥주도) 이름 · 단위 · 종류 · 보관 그대로
    got = [(i[0], i[1], i[2], i[3]) for i in t.ingredients]
    assert got == [
        ("돼지 등심", "G", "BOM", "CHILLED"), ("빵가루", "G", "BOM", "AMBIENT"), ("파", "G", "BOM", "FRESH"),
        ("모짜렐라 치즈", "G", "BOM", "CHILLED"), ("탄산음료", "EA", "ONE_TO_ONE", "AMBIENT"), ("생맥주", "ML", "BOM", "CHILLED"),
        ("닭고기", "G", "BOM", "CHILLED"), ("닭다리살", "G", "BOM", "CHILLED"),
    ]
    assert t.recipes == {
        "로스카츠": {"돼지 등심": 180, "빵가루": 20, "닭다리살": 50},
        "정성한상세트": {"돼지 등심": 180, "파": 10, "모짜렐라 치즈": 30, "닭고기": 120},
        "콜라 사이다": {"탄산음료": 1},
    }
    assert t.cycle_usage == {} and t.order_policy == "EXPECTED"


def test_template_menu_base_by_price_and_kind(dump_dir):
    t = template_from_existing(dump_dir)
    kinds = {"로스카츠": "MAIN", "정성한상세트": "SET", "감자고로케(추가메뉴)": "OPTION", "콜라 사이다": "MAIN"}
    for name, base, share, price in t.menus:
        want = min(EXISTING_MENU_MAX_BASE, EXISTING_MENU_DAILY_BUDGET[kinds[name]] / price)
        assert base == pytest.approx(round(want, 1)), name
        assert share == pytest.approx(DONKATSU.delivery_share)  # 배달 비중 = 돈까스 틀 값
    assert set(t.month_factor.values()) == {1.0} and t.menu_month_factor == {}  # 계절 배수 1
    assert (t.weekend_factor, t.delivery_sunday_factor) == (DONKATSU.weekend_factor, DONKATSU.delivery_sunday_factor)


def test_template_products_estimated(dump_dir):
    t = template_from_existing(dump_dir)
    by_name = {i[0]: i for i in t.ingredients}
    for name, _unit, _kind, _storage, supplier, product, pack_unit, per_pack, moq, price, shelf in t.ingredients:
        assert supplier and product and pack_unit, name
        assert per_pack > 0 and moq >= 1 and price > 0 and shelf > 0, name
    # 틀에 같은 재료가 있으면(빵가루 · 모짜렐라 치즈) 그 포장을 쓴다
    assert by_name["빵가루"][5:10] == ("습식 빵가루 2kg", "봉", 2000, 1, 9000)
    assert by_name["모짜렐라 치즈"][5:10] == ("모짜렐라 2.5kg", "봉", 2500, 1, 32000)
    # 닭다리살은 카탈로그에서 닭고기의 별칭이라 닭고기와 같은 틀 포장을 찾는다 — 같은 거래처 안 상품명이 겹치면 재료 이름을 붙인다
    assert by_name["닭고기"][5] == "닭 절단육 10kg" and by_name["닭다리살"][5] == "닭다리살 닭 절단육 10kg"
    # 고기는 축산 거래처, 신선은 청과 거래처
    assert by_name["돼지 등심"][4] == "한빛축산" and by_name["파"][4] == "새벽청과"
    # 빈 값(리드타임 · 발주 주기)이 있는 떠 온 거래처에는 상품을 걸지 않는다 — 걸면 추천이 SUPPLIER_UNSET 이 된다
    assert "횡성한우" not in {i[4] for i in t.ingredients}
    assert {i[4] for i in t.ingredients} == {s[0] for s in t.suppliers}
    products = [(i[4], i[5]) for i in t.ingredients]
    assert len(set(products)) == len(products)


def test_template_uses_complete_dump_supplier(dump_dir):
    # 떠 온 거래처가 리드타임 · 발주 주기 · 채널을 다 갖고 이름이 축산이면 가상 축산 거래처 대신 그 거래처를 쓴다
    _csv(dump_dir / "supplier.csv", """
id,store_id,name,channel,lead_time_days,order_cycle_days,cutoff_time,delivery_days,min_order_amount,message_template,email,shipping_fee,free_shipping_min
1,2,우리동네축산,KAKAO,1,2,18:00:00,"{MON,WED,FRI}",80000.00,,,,
""")
    t = template_from_existing(dump_dir)
    assert {i[4] for i in t.ingredients if i[0] == "돼지 등심"} == {"우리동네축산"}
    assert ("우리동네축산", "KAKAO", 1, 2, 80000) in t.suppliers
    assert "한빛축산" not in {s[0] for s in t.suppliers}


def test_template_skips_dump_supplier_without_min_order(dump_dir):
    # 리드타임 · 발주 주기 · 채널이 있어도 최소 주문금액이 비면 그 거래처에 상품을 걸지 않는다(0 으로 채우지 않는다)
    _csv(dump_dir / "supplier.csv", """
id,store_id,name,channel,lead_time_days,order_cycle_days,cutoff_time,delivery_days,min_order_amount,message_template,email,shipping_fee,free_shipping_min
1,2,우리동네축산,KAKAO,1,2,18:00:00,"{MON,WED,FRI}",,,,,
""")
    t = template_from_existing(dump_dir)
    assert "우리동네축산" not in {s[0] for s in t.suppliers}
    assert {i[4] for i in t.ingredients if i[0] == "돼지 등심"} == {"한빛축산"}


def test_template_bad_dump_fails(dump_dir):
    _csv(dump_dir / "recipe.csv", """
id,menu_id,menu_name,ingredient_id,ingredient_name,qty_per_serving
1,1,로스카츠,99,없는 재료,10
""")
    with pytest.raises(ValueError, match="없는 재료"):
        template_from_existing(dump_dir)


def test_generate_inactive_menu_no_sales(sample_dir):
    sales = pd.read_csv(sample_dir / "pos_sales.csv")
    assert "단종 메뉴" not in set(sales["menu_name"])
    # 레시피 없는 메뉴도 판매는 만든다
    assert (sales["menu_name"] == "감자고로케(추가메뉴)").sum() > 0


# ---- seed SQL (--into-existing) ----


@pytest.fixture()
def sql(sample_dir):
    return build_existing_seed_sql(sample_dir, store_name="지그재고 테스트")


def test_existing_sql_one_transaction(sql):
    code = [line for line in sql.splitlines() if line.strip() and not line.startswith("--")]
    assert code[0] == "BEGIN;" and code[-1] == "COMMIT;"
    assert sql.count("BEGIN;") == 1 and sql.count("COMMIT;") == 1


def test_existing_sql_does_not_insert_or_change_master_rows(sql):
    for table in ("store", "account", "store_member", "menu", "menu_alias", "menu_component", "ingredient", "recipe"):
        assert f"INSERT INTO {table} " not in sql, table
    for table in ("store", "account", "menu", "recipe", "supplier", "supplier_product"):
        assert not re.search(rf"(?m)^UPDATE {table} ", sql), table
    assert "DELETE " not in sql
    # 재료는 대표 상품이 빈 행만 채운다
    upd = re.findall(r"(?ms)^UPDATE ingredient .*?;\n", sql)
    assert len(upd) == 1 and "primary_supplier_product_id IS NULL" in upd[0]
    # 발주서 연결은 이번에 넣은 문서만 (기존 매입 문서 행은 그대로)
    link = re.findall(r"(?ms)^UPDATE purchase_doc AS d .*?;\n", sql)
    assert len(link) == 1 and "seed_old_doc" in link[0]


def test_existing_sql_prechecks(sql):
    pre = sql[: sql.index("INSERT INTO ")]
    assert "'지그재고 테스트'" in pre
    assert "가게를 찾을 수 없다" in pre or "가게가 하나가 아니다" in pre
    for table in ("pos_sale", "supplier_product", "stock_count"):
        assert re.search(rf"EXISTS \(SELECT 1 FROM {table} WHERE store_id = ", pre), table
    assert "이중 적재" in pre
    # 생성에 쓴 메뉴 · 재료가 그 가게에 이름으로 다 있어야 한다
    assert "menu WHERE store_id" in pre and "ingredient WHERE store_id" in pre


def test_existing_sql_rows_match_csv(sample_dir, sql):
    pos = len(pd.read_csv(sample_dir / "pos_sales.csv"))
    sp = len(pd.read_csv(sample_dir / "supplier_products.csv"))
    stmt = sql.split("INSERT INTO pos_sale ", 1)[1].split(";\n", 1)[0]
    assert sum(1 for line in stmt.splitlines() if line.startswith("    (")) == pos
    assert "'단종 메뉴'" not in stmt
    verify = sql[sql.index("-- seed 검증 (--into-existing)"):]
    assert re.search(rf"FROM pos_sale WHERE store_id = [^;]+; IF n <> {pos} THEN", verify)
    assert re.search(rf"FROM supplier_product WHERE store_id = [^;]+; IF n <> {sp} THEN", verify)
    assert verify.index("RAISE EXCEPTION") < sql.index("COMMIT;")


def test_existing_sql_scoped_to_store(sql):
    st = "(SELECT id FROM store WHERE name = '지그재고 테스트')"
    for stmt in re.findall(r"(?m)^(?:WITH|UPDATE|INSERT INTO) [^;]+;", sql):
        assert st in stmt or "CROSS JOIN (SELECT id FROM store WHERE name = '지그재고 테스트') AS st" in stmt, stmt[:80]


def test_existing_sql_store_by_id(sample_dir):
    sql = build_existing_seed_sql(sample_dir, store_id=2)
    assert "(SELECT id FROM store WHERE id = 2)" in sql and "WHERE name =" not in sql.split("BEGIN;", 1)[1].split("INSERT", 1)[0]


def test_existing_sql_percent_in_store_name(sample_dir):
    # 가게 이름의 % 는 RAISE 형식 문자열에서 %% 로 들어가야 한다 — 그대로면 "too few parameters specified for RAISE"
    sql = build_existing_seed_sql(sample_dir, store_name="50% 할인 돈까스")
    raises = [line for line in sql.splitlines() if "RAISE EXCEPTION" in line and "할인 돈까스" in line]
    assert raises and all("50%% 할인 돈까스" in line for line in raises)
    # 가게를 찾는 WHERE 의 리터럴은 % 그대로
    assert "name = '50% 할인 돈까스'" in sql


def test_existing_sql_needs_store(sample_dir):
    with pytest.raises(ValueError, match="가게"):
        build_existing_seed_sql(sample_dir)
    with pytest.raises(ValueError, match="가게"):
        build_existing_seed_sql(sample_dir, store_name="a", store_id=1)


# ---- CLI ----


def _run(monkeypatch, script, *args):
    monkeypatch.setattr(sys, "argv", [str(script), *args])
    runpy.run_path(str(script), run_name="__main__")


def test_cli_from_existing_then_into_existing(dump_dir, tmp_path, monkeypatch):
    out = tmp_path / "out"
    _run(monkeypatch, SAMPLE_SCRIPT, "--from-existing", str(dump_dir), "--end", "2026-11-02", "--days", "40", "--out", str(out))
    assert (out / "pos_sales.csv").exists() and not (out / "stores.csv").exists()
    seed = tmp_path / "seed.sql"
    _run(monkeypatch, SEED_SCRIPT, "--sample-dir", str(out), "--into-existing", "--store-name", "지그재고 테스트", "--out", str(seed))
    assert "INSERT INTO pos_sale " in seed.read_text(encoding="utf-8")
    # 가게를 안 주면 종료 코드 2
    with pytest.raises(SystemExit) as e:
        _run(monkeypatch, SEED_SCRIPT, "--sample-dir", str(out), "--into-existing", "--out", str(tmp_path / "x.sql"))
    assert e.value.code == 2 and not (tmp_path / "x.sql").exists()


@pytest.mark.parametrize("extra", [[], ["--append"]])
def test_cli_missing_demo_hash_exits_2_with_var_name(sample_dir, tmp_path, monkeypatch, capsys, extra):
    monkeypatch.delenv("WEGLEE_DEMO_PASSWORD_HASH")
    out = tmp_path / "x.sql"
    with pytest.raises(SystemExit) as e:
        _run(monkeypatch, SEED_SCRIPT, "--sample-dir", str(sample_dir), "--out", str(out), *extra)
    assert e.value.code == 2 and not out.exists()
    assert "WEGLEE_DEMO_PASSWORD_HASH" in capsys.readouterr().err
