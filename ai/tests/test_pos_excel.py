"""합성 POS 엑셀(scripts/make_pos_excel.py) 검사 (S15P31A308-163).

작은 기간(45일)을 162 생성기로 만들어 한 달 파일을 쓰고, openpyxl 로 다시 읽어 원본 pos_sales.csv 와 맞춘다.
2년 크기는 행 수 계산만 본다(실제 2년 파일 생성은 스크립트 실행으로 확인).
"""

import importlib.util
import shutil
import unicodedata
from collections import Counter
from datetime import date, datetime
from pathlib import Path

import pandas as pd
import pytest
from openpyxl import load_workbook

from weglee_ai.sample_data import generate

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "make_pos_excel.py"
PARSER_MAX_ROWS = 200_000  # 파서 38 의 행 상한 (모든 시트 합)
HEADER_SCAN_ROWS = 10  # 파서 38 HeaderLocator.SCAN_ROWS
SEED = 7


def _load_module():
    spec = importlib.util.spec_from_file_location("make_pos_excel", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


mpe = _load_module()


def _write_inputs(folder: Path, days: int) -> Path:
    data = generate(days=days)
    folder.mkdir(parents=True, exist_ok=True)
    data.pos_sales.to_csv(folder / "pos_sales.csv", index=False, encoding="utf-8-sig")
    data.menus.to_csv(folder / "menus.csv", index=False, encoding="utf-8-sig")
    return folder


@pytest.fixture(scope="module")
def inputs(tmp_path_factory) -> Path:
    return _write_inputs(tmp_path_factory.mktemp("in"), days=45)


@pytest.fixture(scope="module")
def built(inputs, tmp_path_factory) -> Path:
    out = tmp_path_factory.mktemp("out")
    mpe.build(in_dir=inputs, out_dir=out, vendors=["sample", "toss"], sizes=["month"], seed=SEED)
    return out


def _source(inputs: Path) -> pd.DataFrame:
    sales = pd.read_csv(inputs / "pos_sales.csv", encoding="utf-8-sig")
    sales["sold_date"] = pd.to_datetime(sales["sold_date"]).dt.date
    return sales


def _menus(inputs: Path) -> pd.DataFrame:
    return pd.read_csv(inputs / "menus.csv", encoding="utf-8-sig")


def _month_bounds(inputs: Path) -> tuple[date, date]:
    end = _source(inputs)["sold_date"].max()
    start = (pd.Timestamp(end) - pd.DateOffset(months=1) + pd.Timedelta(days=1)).date()
    return start, end


def _as_date(value):
    return value.date() if isinstance(value, datetime) else value


def _rows(path: Path, sheet: str | None = None) -> list[list]:
    wb = load_workbook(path)
    ws = wb[sheet] if sheet else wb.worksheets[0]
    return [list(r) for r in ws.iter_rows(values_only=True)]


def _cells(path: Path, sheet: str | None = None):
    wb = load_workbook(path)
    ws = wb[sheet] if sheet else wb.worksheets[0]
    return list(ws.iter_rows())


def _header_index(rows: list[list], required: list[str]) -> int:
    for i, row in enumerate(rows[:HEADER_SCAN_ROWS]):
        names = {str(c).strip().lower() for c in row if c is not None}
        if set(required) <= names:
            return i
    raise AssertionError(f"머리글 없음: {required}")


def _blank(row) -> bool:
    return all(c is None or str(c).strip() == "" for c in row)


def _is_total(row) -> bool:
    return any(isinstance(c, str) and c.strip() in {"합계", "총계", "소계"} for c in row)


def _undecorate(inputs: Path) -> dict[str, str]:
    names = _menus(inputs)["pos_name"].tolist()
    back = {v: k for k, v in mpe.decorate_names(names, SEED).items()}
    return {n: n for n in names} | back


def _read_sample(path: Path, inputs: Path) -> Counter:
    """파서 38 SAMPLE 처럼 읽는다: 첫 시트, 앞 10줄 안 머리글, 빈 줄 · 합계 줄 버림, 음수 qty 는 상계."""
    rows = _rows(path)
    h = _header_index(rows, ["sold_date", "menu_name", "qty"])
    col = {str(c).lower(): i for i, c in enumerate(rows[h]) if c is not None}
    undo = _undecorate(inputs)
    total = Counter()
    for row in rows[h + 1 :]:
        if _blank(row) or _is_total(row):
            continue
        name = unicodedata.normalize("NFC", row[col["menu_name"]]).strip()
        key = (_as_date(row[col["sold_date"]]), undo[name], row[col["channel"]])
        total[key] += row[col["qty"]]
    return total


def _read_toss(path: Path, inputs: Path) -> Counter:
    rows = _rows(path, mpe.TOSS_SHEET)
    h = _header_index(rows, ["주문기준일자", "상품명", "수량"])
    col = {str(c): i for i, c in enumerate(rows[h]) if c is not None}
    undo = _undecorate(inputs)
    total = Counter()
    for row in rows[h + 1 :]:
        if _blank(row) or _is_total(row) or row[col["주문상태"]] == "취소":
            continue
        name = unicodedata.normalize("NFC", row[col["상품명"]]).strip()
        channel = "HALL" if row[col["주문채널"]] == "홀" else "DELIVERY"
        total[(_as_date(row[col["주문기준일자"]]), undo[name], channel)] += row[col["수량"]]
    return total


def _expected(inputs: Path, start: date, end: date) -> Counter:
    s = _source(inputs)
    s = s[(s["sold_date"] >= start) & (s["sold_date"] <= end) & (s["qty"] != 0)]
    return Counter({(r.sold_date, r.menu_name, r.channel): int(r.qty) for r in s.itertuples()})


# ---------- 합계 일치 ----------


@pytest.mark.parametrize("vendor", ["sample", "toss"])
@pytest.mark.parametrize("broken", [False, True], ids=["normal", "broken"])
def test_total_matches_source(built, inputs, vendor, broken):
    path = built / mpe.file_name(vendor, "month", broken)
    got = _read_sample(path, inputs) if vendor == "sample" else _read_toss(path, inputs)
    got = Counter({k: v for k, v in got.items() if v != 0})
    assert got == _expected(inputs, *_month_bounds(inputs))


# ---------- 열 · 시트 · 금액 ----------


def test_sample_columns_and_channels(built):
    path = built / mpe.file_name("sample", "month", False)
    cells = _cells(path)
    header = [c.value for c in cells[0]]
    assert header[:4] == ["sold_date", "menu_name", "qty", "channel"]
    date_col = header.index("sold_date")
    channel_col = header.index("channel")
    body = cells[1:]
    assert body
    assert {r[channel_col].value for r in body} <= {"HALL", "DELIVERY"}
    assert all(r[date_col].is_date for r in body)


def test_toss_sheet_and_columns(built):
    path = built / mpe.file_name("toss", "month", False)
    wb = load_workbook(path)
    assert mpe.TOSS_SHEET == "상품 주문"
    assert "상품 주문" in wb.sheetnames
    assert wb.sheetnames[0] != "상품 주문"
    rows = _rows(path, "상품 주문")
    h = _header_index(rows, ["주문기준일자", "상품명", "카테고리", "옵션", "주문채널"])
    above = " ".join(str(c) for r in rows[:h] for c in r if c is not None)
    assert "가정" in above
    date_col = rows[h].index("주문기준일자")
    body = _cells(path, "상품 주문")[h + 1 :]
    assert body
    assert all(r[date_col].is_date for r in body)


def test_toss_sheet_delivery_split_into_three_apps(built):
    rows = _rows(built / mpe.file_name("toss", "month", False), mpe.TOSS_SHEET)
    h = _header_index(rows, ["주문채널"])
    channel = rows[h].index("주문채널")
    assert {r[channel] for r in rows[h + 1 :]} == {"홀", "배민", "쿠팡이츠", "요기요"}


@pytest.mark.parametrize("vendor", ["sample", "toss"])
@pytest.mark.parametrize("broken", [False, True], ids=["normal", "broken"])
def test_amount_equals_qty_times_price(built, inputs, vendor, broken):
    undo = _undecorate(inputs)
    price = dict(zip(_menus(inputs)["pos_name"], _menus(inputs)["price"], strict=False))
    if vendor == "sample":
        rows = _rows(built / mpe.file_name(vendor, "month", broken))
        h = _header_index(rows, ["sold_date", "menu_name", "qty", "amount"])
        name_col, qty_col, amount_col = (rows[h].index(c) for c in ("menu_name", "qty", "amount"))
    else:
        rows = _rows(built / mpe.file_name(vendor, "month", broken), mpe.TOSS_SHEET)
        h = _header_index(rows, ["상품명", "수량", "금액"])
        name_col, qty_col, amount_col = (rows[h].index(c) for c in ("상품명", "수량", "금액"))
    checked = 0
    for r in rows[h + 1 :]:
        if _blank(r) or _is_total(r):
            continue
        assert r[amount_col] == r[qty_col] * price[undo[r[name_col]]]
        checked += 1
    assert checked > 0


# ---------- 미매핑 ----------


def test_unmapped_names_fixed_by_seed_and_survive_normalize(inputs):
    names = _menus(inputs)["pos_name"].tolist()
    a = mpe.decorate_names(names, SEED)
    b = mpe.decorate_names(names, SEED)
    assert a == b
    assert 1 <= len(a) < len(names)
    for original, decorated in a.items():
        assert unicodedata.normalize("NFC", decorated).strip() != original


@pytest.mark.parametrize("seed", range(40))
def test_unmapped_names_never_collide_with_other_menus(seed):
    names = ["우동", "우동(L)", "우 동", "콜라", "콜라(L)", "콜 라", "라면", " 라 면 "]
    norm = {unicodedata.normalize("NFC", n).strip() for n in names}
    decorated = mpe.decorate_names(names, seed)
    assert decorated == mpe.decorate_names(names, seed)
    for original, d in decorated.items():
        assert unicodedata.normalize("NFC", d).strip() not in norm, (seed, original, d)


def test_unmapped_names_appear_in_files(built, inputs):
    decorated = set(mpe.decorate_names(_menus(inputs)["pos_name"].tolist(), SEED).values())
    rows = _rows(built / mpe.file_name("sample", "month", False))
    assert decorated <= {r[1] for r in rows[1:]}
    toss = _rows(built / mpe.file_name("toss", "month", False), mpe.TOSS_SHEET)
    col = toss[_header_index(toss, ["상품명"])].index("상품명")
    assert decorated <= {r[col] for r in toss}


# ---------- 깨진 파일 ----------


@pytest.mark.parametrize("vendor", ["sample", "toss"])
def test_broken_file_has_all_defects(built, vendor):
    if vendor == "sample":
        rows = _rows(built / mpe.file_name(vendor, "month", True))
        h = _header_index(rows, ["sold_date", "menu_name", "qty"])
        qty = rows[h].index("qty")
        cancelled = [r for r in rows[h + 1 :] if isinstance(r[qty], int) and r[qty] < 0]
    else:
        rows = _rows(built / mpe.file_name(vendor, "month", True), mpe.TOSS_SHEET)
        h = _header_index(rows, ["주문기준일자", "상품명", "수량"])
        status = rows[h].index("주문상태")
        cancelled = [r for r in rows[h + 1 :] if r[status] == "취소"]
    assert 0 < h < HEADER_SCAN_ROWS, "머리글이 몇 줄 아래(10줄 이내)"
    assert any(_blank(r) for r in rows[h + 1 :]), "빈 줄"
    assert any(_is_total(r) for r in rows[h + 1 :]), "합계 행"
    assert cancelled, "취소 건"


def test_normal_sample_has_no_broken_defects(built):
    rows = _rows(built / mpe.file_name("sample", "month", False))
    assert _header_index(rows, ["sold_date", "menu_name", "qty"]) == 0
    assert not any(_blank(r) or _is_total(r) for r in rows[1:])
    assert all(r[2] >= 0 for r in rows[1:])


# ---------- 경계: 빈 기간 · 잘못된 입력 ----------


def _run_main(args: list[str], capsys) -> tuple[int, str]:
    code = mpe.main(args)
    return code, capsys.readouterr().err


def _no_xlsx(folder: Path) -> bool:
    return not folder.exists() or not list(folder.glob("*.xlsx"))


def test_empty_period_outside_data(inputs, tmp_path, capsys):
    out = tmp_path / "out"
    code, err = _run_main(["--in", str(inputs), "--out", str(out), "--start", "2000-01-01", "--end", "2000-01-31"], capsys)
    assert code != 0
    assert len(err.strip().splitlines()) == 1 and "판매" in err
    assert _no_xlsx(out)


def test_invalid_start_after_end(inputs, tmp_path, capsys):
    out = tmp_path / "out"
    end = _source(inputs)["sold_date"].max()
    code, err = _run_main(
        ["--in", str(inputs), "--out", str(out), "--start", end.isoformat(), "--end", "2000-01-01"], capsys
    )
    assert code != 0
    assert len(err.strip().splitlines()) == 1 and "--start" in err
    assert _no_xlsx(out)


@pytest.mark.parametrize("missing", ["pos_sales.csv", "menus.csv"])
def test_invalid_missing_input_csv(inputs, tmp_path, capsys, missing):
    src = tmp_path / "in"
    shutil.copytree(inputs, src)
    (src / missing).unlink()
    out = tmp_path / "out"
    code, err = _run_main(["--in", str(src), "--out", str(out)], capsys)
    assert code != 0
    assert len(err.strip().splitlines()) == 1 and missing in err
    assert _no_xlsx(out)


@pytest.mark.parametrize("how", ["blank_price", "menu_missing"])
def test_invalid_menu_without_price(inputs, tmp_path, capsys, how):
    src = tmp_path / "in"
    shutil.copytree(inputs, src)
    menus = _menus(src)
    victim = menus["pos_name"].iloc[0]
    if how == "blank_price":
        menus["price"] = menus["price"].astype("object")
        menus.loc[0, "price"] = None
    else:
        menus = menus.iloc[1:]
    menus.to_csv(src / "menus.csv", index=False, encoding="utf-8-sig")
    out = tmp_path / "out"
    code, err = _run_main(["--in", str(src), "--out", str(out)], capsys)
    assert code != 0
    assert len(err.strip().splitlines()) == 1 and victim in err
    assert _no_xlsx(out)


@pytest.mark.parametrize("flag", [["--sizes", ","], ["--sizes", ""]])
def test_invalid_empty_sizes(inputs, tmp_path, capsys, flag):
    out = tmp_path / "out"
    code, err = _run_main(["--in", str(inputs), "--out", str(out), *flag], capsys)
    assert code != 0
    assert len(err.strip().splitlines()) == 1 and "--sizes" in err
    assert not out.exists() or not any(out.iterdir())


def test_invalid_empty_vendors(inputs, tmp_path):
    with pytest.raises(mpe.InputError, match="vendor"):
        mpe.build(in_dir=inputs, out_dir=tmp_path / "out", vendors=[], sizes=["month"], seed=SEED)
    assert not (tmp_path / "out").exists()


# ---------- 크기 · 기간 ----------


def test_period_start_end_clip_dates(inputs, tmp_path):
    days = sorted(_source(inputs)["sold_date"].unique())
    start, end = days[10], days[25]
    files = mpe.build(
        in_dir=inputs, out_dir=tmp_path, vendors=["sample", "toss"], sizes=["month"], seed=SEED,
        start=start.isoformat(), end=end.isoformat(),
    )
    assert len(files) == 4
    for path in files:
        if path.name.startswith("sample"):
            rows = _rows(path)
            h = _header_index(rows, ["sold_date"])
            col = rows[h].index("sold_date")
        else:
            rows = _rows(path, mpe.TOSS_SHEET)
            h = _header_index(rows, ["주문기준일자"])
            col = rows[h].index("주문기준일자")
        dates = {_as_date(r[col]) for r in rows[h + 1 :] if not _blank(r) and not _is_total(r)}
        assert min(dates) == start and max(dates) == end, path.name


def test_size_bounds_month_year_two_years():
    bounds = mpe.period_bounds(["month", "year", "two_years"], date(2024, 10, 12), date(2026, 10, 11))
    days = {label: (e - s).days + 1 for label, s, e in bounds}
    assert days == {"month": 30, "year": 365, "two_years": 730}


def test_size_two_years_toss_rows_under_parser_limit(tmp_path):
    folder = _write_inputs(tmp_path / "full", days=730)
    sales, menus = mpe.load_inputs(folder)
    ((_, s, e),) = mpe.period_bounds(["two_years"], sales["sold_date"].min(), sales["sold_date"].max())
    counts = mpe.row_counts(sales, menus, s, e, seed=SEED)
    assert counts["sample"] <= PARSER_MAX_ROWS
    assert 100_000 < counts["toss"] <= PARSER_MAX_ROWS
    assert counts["toss_broken"] <= PARSER_MAX_ROWS


def test_size_month_files_written(built):
    for vendor in ("sample", "toss"):
        for broken in (False, True):
            assert (built / mpe.file_name(vendor, "month", broken)).stat().st_size > 0


# ---------- 재현성 ----------


def test_reproducible_same_seed_same_cells(inputs, tmp_path):
    a = mpe.build(in_dir=inputs, out_dir=tmp_path / "a", vendors=["sample", "toss"], sizes=["month"], seed=SEED)
    b = mpe.build(in_dir=inputs, out_dir=tmp_path / "b", vendors=["sample", "toss"], sizes=["month"], seed=SEED)
    assert [p.name for p in a] == [p.name for p in b]
    for pa, pb in zip(a, b, strict=False):
        wa, wb = load_workbook(pa), load_workbook(pb)
        assert wa.sheetnames == wb.sheetnames
        for name in wa.sheetnames:
            assert _rows(pa, name) == _rows(pb, name), (pa.name, name)
