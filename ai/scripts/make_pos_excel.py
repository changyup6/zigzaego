"""합성 판매 CSV(make_sample_data.py 결과) → POS 벤더 export 모양의 .xlsx (S15P31A308-163).

2년치 합성 판매를 POS export 모양 엑셀로 내보낸다. 기본 출력 ai/data/pos_excel/ (커밋하지 않는다).
POS 업로드 · 파서(S15P31A308-38)는 10/27 폐기돼 지금 develop 에 이 파일을 읽는 파서는 없다. 업로드 · 파서가 다시 생기면
그 시험 자료로 쓰고, 샘플 형식을 그 파서에 맞춘다.

uv run python scripts/make_pos_excel.py [--in data/sample] [--out data/pos_excel] [--vendor sample|toss|all]
    [--sizes month,year,two_years] [--start 2026-09-01 --end 2026-09-30] [--store-name 가상 가게] [--seed 15]

벤더 형식
- sample: 폐기된 38 가지(be/feat/pos-upload-S15P31A308-38)의 PosVendor.SAMPLE 형식. 첫 시트, 머리글
  sold_date,menu_name,qty,channel(+amount), 채널 HALL · DELIVERY, 날짜는 엑셀 날짜 셀.
  amount = qty × menus.csv price (그 파서는 모르는 열을 무시했다).
- toss: 토스플레이스 매출 리포트 엑셀(docs/data/POS_export_형식_조사.md). 확인된 것은 "상품 주문" 시트(첫 시트 아님)와
  주문기준일자 · 상품명 · 카테고리 · 옵션 · 주문채널 열, 주문 건 단위 행뿐이다.
  가정(실물 파일 확인 전): 수량 · 금액 · 주문번호 · 주문상태 열 이름, 홀 표기 "홀", 상품 1개당 1행(수량 1),
  배달앱 비중, 합계 행, 취소 표기(주문상태 "취소"), 앞 시트 "매출 요약". 시트 제목 줄에도 "가정" 으로 적는다.

파일마다 정상본과 일부러 깨진 본(_broken)을 쓴다. 깨진 본: 제목 줄 때문에 머리글이 몇 줄 아래(10줄 이내), 빈 줄,
합계 행, 취소 건(sample = 같은 행 +k / −k 쌍, toss = 주문상태 취소 행). 취소를 빼면 합계는 원본과 같다.
미매핑: seed 로 고른 메뉴 몇 개의 POS 표기를 안쪽 공백 · "(L)" 로 바꾼다(앞뒤 공백은 38 가지 파서가 지웠으므로 쓰지 않는다).
같은 입력 · 같은 --seed 면 셀 값이 같다.
"""

from __future__ import annotations

import argparse
import sys
import unicodedata
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd
from openpyxl import Workbook

AI_DIR = Path(__file__).resolve().parents[1]
DEFAULT_IN = AI_DIR / "data" / "sample"
DEFAULT_OUT = AI_DIR / "data" / "pos_excel"
DEFAULT_STORE = "가상 가게"
DEFAULT_SEED = 15

VENDORS = ("sample", "toss")
SIZE_OFFSETS = {"month": pd.DateOffset(months=1), "year": pd.DateOffset(years=1), "two_years": pd.DateOffset(years=2)}

SAMPLE_COLUMNS = ["sold_date", "menu_name", "qty", "channel", "amount"]

TOSS_SHEET = "상품 주문"
TOSS_SUMMARY_SHEET = "매출 요약"  # 가정: "상품 주문" 앞에 오는 시트
# 확인: 주문기준일자 · 상품명 · 카테고리 · 옵션 · 주문채널 / 가정: 주문번호 · 수량 · 금액 · 주문상태
TOSS_COLUMNS = ["주문기준일자", "주문번호", "상품명", "카테고리", "옵션", "수량", "금액", "주문채널", "주문상태"]
TOSS_HALL = "홀"  # 가정: 홀 · 포장 구분 열은 확인 안 됨. 홀 하나로 쓴다
TOSS_APPS = ("배민", "쿠팡이츠", "요기요")  # 확인: 배달 3사별로 나뉨
TOSS_APP_SHARE = (0.6, 0.25, 0.15)  # 가정: 배달 행을 앱별로 나누는 비중
TOSS_DONE, TOSS_CANCEL = "완료", "취소"  # 가정: 취소 표기
TOSS_CATEGORY_DEFAULT = "미분류"  # menus.csv 에 category 열이 없을 때
TOSS_ASSUMED = (
    "가정: 수량 · 금액 · 주문번호 · 주문상태 열 이름, 홀 표기, 상품 1개당 1행, 합계 행, 취소 표기는 실물 파일 확인 전 가정"
)
TOTAL_LABEL = "합계"

DECORATE_SHARE = 0.25  # 꾸밀 메뉴 비율
BROKEN_BLANKS = 3
BROKEN_CANCELS = 3


class InputError(Exception):
    """입력 · 기간이 잘못됨. main 이 이유 한 줄과 0 이 아닌 종료 코드로 바꾼다."""


# ---------- 입력 ----------


def load_inputs(in_dir) -> tuple[pd.DataFrame, pd.DataFrame]:
    in_dir = Path(in_dir)
    for name in ("pos_sales.csv", "menus.csv"):
        if not (in_dir / name).is_file():
            raise InputError(f"입력 파일이 없습니다: {in_dir / name}")
    sales = pd.read_csv(in_dir / "pos_sales.csv", encoding="utf-8-sig")
    menus = pd.read_csv(in_dir / "menus.csv", encoding="utf-8-sig")
    sales["sold_date"] = pd.to_datetime(sales["sold_date"]).dt.date
    sales["qty"] = sales["qty"].astype(int)
    if "pos_name" not in menus.columns:
        menus["pos_name"] = menus["name"]
    priced = menus.dropna(subset=["price"])
    known = set(priced["name"]) | set(priced["pos_name"])
    missing = sorted(set(sales["menu_name"]) - known)
    if missing:
        raise InputError(f"가격이 없는 메뉴: {', '.join(missing)} (menus.csv price)")
    return sales, menus


def _menu_maps(menus: pd.DataFrame) -> tuple[dict, dict, dict]:
    """판매 메뉴 이름 → (POS 표기, 가격, 카테고리)"""
    pos, price, category = {}, {}, {}
    has_category = "category" in menus.columns
    for r in menus.dropna(subset=["price"]).itertuples():
        for key in (r.name, r.pos_name):
            pos[key] = r.pos_name
            price[key] = int(r.price)
            category[key] = r.category if has_category and isinstance(r.category, str) else TOSS_CATEGORY_DEFAULT
    return pos, price, category


# ---------- 기간 ----------


def period_bounds(sizes, data_start, data_end) -> list[tuple[str, date, date]]:
    """크기 묶음 → (이름, 시작, 끝). 끝 날에서 거꾸로 1개월 · 1년 · 2년, 데이터 시작보다 앞이면 자른다."""
    first, last = pd.Timestamp(data_start), pd.Timestamp(data_end)
    out = []
    for size in sizes:
        if size not in SIZE_OFFSETS:
            raise InputError(f"모르는 크기: {size} (month, year, two_years)")
        start = max(last - SIZE_OFFSETS[size] + pd.Timedelta(days=1), first)
        out.append((size, start.date(), last.date()))
    return out


def _parse_date(text: str, flag: str) -> date:
    try:
        return date.fromisoformat(text)
    except ValueError:
        raise InputError(f"{flag} 날짜 형식이 아닙니다(YYYY-MM-DD): {text}") from None


def resolve_periods(sales: pd.DataFrame, sizes, start=None, end=None) -> list[tuple[str, date, date]]:
    data_start, data_end = sales["sold_date"].min(), sales["sold_date"].max()
    end_d = _parse_date(end, "--end") if end else data_end
    if start:
        start_d = _parse_date(start, "--start")
        if start_d > end_d:
            raise InputError(f"--start({start_d})가 --end({end_d})보다 늦습니다")
        periods = [(f"{start_d}_{end_d}", start_d, end_d)]
    else:
        periods = period_bounds(sizes, data_start, end_d)
    for _label, s, e in periods:
        if not ((sales["sold_date"] >= s) & (sales["sold_date"] <= e)).any():
            raise InputError(f"기간 {s} ~ {e} 에 판매 행이 없습니다 (데이터 {data_start} ~ {data_end})")
    return periods


def _slice(sales: pd.DataFrame, start: date, end: date) -> pd.DataFrame:
    return sales[(sales["sold_date"] >= start) & (sales["sold_date"] <= end)]


# ---------- 미매핑 ----------


def _norm(name: str) -> str:
    """폐기된 38 가지의 PosNameNormalizer 와 같은 규칙: NFC + 앞뒤 공백 제거."""
    return unicodedata.normalize("NFC", name).strip()


def decorate_names(names, seed: int) -> dict[str, str]:
    """seed 로 메뉴 몇 개를 골라 POS 표기를 바꾼다(안쪽 공백 또는 "(L)"). 원래 이름 → 꾸민 이름.

    꾸민 이름이 정규화(NFC + 앞뒤 공백) 뒤 다른 메뉴 이름이나 이미 꾸민 이름과 겹치면 다른 꾸밈을 쓰고,
    둘 다 겹치면 그 메뉴는 건너뛰고 다음 메뉴를 고른다 — 겹치면 미매핑이 아니라 남의 메뉴로 잡힌다.
    """
    names = list(dict.fromkeys(names))
    if not names:
        return {}
    rng = np.random.default_rng([seed, 1])
    k = min(max(1, round(len(names) * DECORATE_SHARE)), max(1, len(names) - 1))
    taken = {_norm(n) for n in names}
    out = {}
    for i in rng.permutation(len(names)):
        if len(out) == k:
            break
        name = names[i]
        mid = len(name) // 2
        spaced = f"{name[:mid]} {name[mid:]}" if len(name) >= 2 else None
        options = [spaced, f"{name}(L)"] if rng.random() < 0.5 else [f"{name}(L)", spaced]
        for candidate in options:
            if candidate is not None and _norm(candidate) not in taken:
                out[name] = candidate
                taken.add(_norm(candidate))
                break
    return {n: out[n] for n in names if n in out}


# ---------- 시트 행 ----------


class _Context:
    def __init__(self, menus: pd.DataFrame, seed: int, store_name: str):
        self.pos, self.price, self.category = _menu_maps(menus)
        self.decorated = decorate_names(menus["pos_name"].tolist(), seed)
        self.seed = seed
        self.store_name = store_name

    def shown(self, menu_name: str) -> str:
        pos = self.pos[menu_name]
        return self.decorated.get(pos, pos)


def _insert_blanks(rows: list[list], rng: np.random.Generator, width: int) -> list[list]:
    if not rows:
        return rows
    spots = sorted(rng.choice(len(rows), size=min(BROKEN_BLANKS, len(rows)), replace=False), reverse=True)
    for i in spots:
        rows.insert(int(i) + 1, [None] * width)
    return rows


def sample_sheets(sales: pd.DataFrame, ctx: _Context, start: date, end: date, broken: bool) -> dict[str, list[list]]:
    body = [
        [r.sold_date, ctx.shown(r.menu_name), int(r.qty), r.channel, int(r.qty) * ctx.price[r.menu_name]]
        for r in sales.itertuples()
    ]
    if not broken:
        return {"sales": [SAMPLE_COLUMNS, *body]}
    rng = np.random.default_rng([ctx.seed, 3])
    sold = [i for i, r in enumerate(body) if r[2] > 0]
    for i in sorted(rng.choice(sold, size=min(BROKEN_CANCELS, len(sold)), replace=False), reverse=True):
        d, name, _, channel, _ = body[i]
        unit = body[i][4] // body[i][2]
        # 잘못 찍은 판매 +1 과 그 취소 −1 — 파서가 상계하면 원본과 같다
        body[i + 1 : i + 1] = [[d, name, 1, channel, unit], [d, name, -1, channel, -unit]]
    body = _insert_blanks(body, rng, len(SAMPLE_COLUMNS))
    total = [TOTAL_LABEL, None, sum(r[2] or 0 for r in body), None, sum(r[4] or 0 for r in body)]
    title = [
        ["판매 내역 (합성 · 깨진 파일)"],
        ["가게", ctx.store_name],
        ["기간", f"{start} ~ {end}"],
        [None],
    ]
    return {"sales": [*title, SAMPLE_COLUMNS, *body, [None] * len(SAMPLE_COLUMNS), total]}


def _toss_lines(sales: pd.DataFrame, ctx: _Context) -> list[list]:
    """판매 행 → 주문 건 단위 행(상품 1개당 1행). 날짜마다 같은 채널 줄을 1~3개씩 묶어 주문번호를 붙인다."""
    rng = np.random.default_rng([ctx.seed, 2])
    rows = []
    for day, day_sales in sales[sales["qty"] > 0].groupby("sold_date", sort=True):
        by_channel: dict[str, list[str]] = {}
        for r in day_sales.itertuples():
            if r.channel == "HALL":
                by_channel.setdefault(TOSS_HALL, []).extend([r.menu_name] * int(r.qty))
            else:
                apps = rng.choice(len(TOSS_APPS), size=int(r.qty), p=TOSS_APP_SHARE)
                for a in apps:
                    by_channel.setdefault(TOSS_APPS[a], []).append(r.menu_name)
        orders = []
        for channel in (TOSS_HALL, *TOSS_APPS):
            items = by_channel.get(channel, [])
            order = rng.permutation(len(items))
            i = 0
            while i < len(items):
                n = int(rng.integers(1, 4))
                orders.append((channel, [items[j] for j in order[i : i + n]]))
                i += n
        stamp = day.strftime("%Y%m%d")
        for no, oi in enumerate(rng.permutation(len(orders)), 1):
            channel, items = orders[oi]
            for menu in items:
                rows.append(
                    [day, f"{stamp}-{no:04d}", ctx.shown(menu), ctx.category[menu], None, 1, ctx.price[menu],
                     channel, TOSS_DONE]
                )
    return rows


def toss_sheets(sales: pd.DataFrame, ctx: _Context, start: date, end: date, broken: bool) -> dict[str, list[list]]:
    body = _toss_lines(sales, ctx)
    summary_rows = []
    for channel in (TOSS_HALL, *TOSS_APPS):
        part = [r for r in body if r[7] == channel]
        summary_rows.append([channel, sum(r[5] for r in part), sum(r[6] for r in part)])
    summary = [[f"{TOSS_SUMMARY_SHEET} · {ctx.store_name} · {start} ~ {end} ({TOSS_ASSUMED})"], ["주문채널", "수량", "금액"],
               *summary_rows]
    if not broken:
        title = [[f"{TOSS_SHEET} · {ctx.store_name} · {start} ~ {end}"], [TOSS_ASSUMED]]
        return {TOSS_SUMMARY_SHEET: summary, TOSS_SHEET: [*title, TOSS_COLUMNS, *body]}
    rng = np.random.default_rng([ctx.seed, 3])
    done_qty, done_amount = sum(r[5] for r in body), sum(r[6] for r in body)
    picks = sorted(rng.choice(len(body), size=min(BROKEN_CANCELS, len(body)), replace=False), reverse=True)
    for c, i in enumerate(picks, 1):
        row = list(body[i])
        row[1] = f"{row[0].strftime('%Y%m%d')}-C{c:03d}"
        row[8] = TOSS_CANCEL
        body.insert(int(i) + 1, row)
    body = _insert_blanks(body, rng, len(TOSS_COLUMNS))
    total = [TOTAL_LABEL, None, None, None, None, done_qty, done_amount, None, None]
    title = [
        [f"{TOSS_SHEET} (합성 · 깨진 파일)"],
        ["가게", ctx.store_name],
        ["조회 기간", f"{start} ~ {end}"],
        [TOSS_ASSUMED],
        [None],
    ]
    return {TOSS_SUMMARY_SHEET: summary, TOSS_SHEET: [*title, TOSS_COLUMNS, *body, [None] * len(TOSS_COLUMNS), total]}


SHEET_BUILDERS = {"sample": sample_sheets, "toss": toss_sheets}


def row_counts(sales: pd.DataFrame, menus: pd.DataFrame, start: date, end: date, seed: int = DEFAULT_SEED) -> dict:
    """파일마다 모든 시트 행 수 합 (파서 상한은 시트 합으로 센다). 파일은 쓰지 않는다."""
    ctx = _Context(menus, seed, DEFAULT_STORE)
    part = _slice(sales, start, end)
    out = {}
    for vendor, builder in SHEET_BUILDERS.items():
        for broken in (False, True):
            sheets = builder(part, ctx, start, end, broken)
            out[vendor + ("_broken" if broken else "")] = sum(len(r) for r in sheets.values())
    return out


# ---------- 쓰기 ----------


def file_name(vendor: str, label: str, broken: bool) -> str:
    return f"{vendor}_{label}{'_broken' if broken else ''}.xlsx"


def _write(path: Path, sheets: dict[str, list[list]]) -> None:
    wb = Workbook(write_only=True)
    for title, rows in sheets.items():
        ws = wb.create_sheet(title)
        for row in rows:
            ws.append(row)
    wb.save(path)


def build(
    in_dir=DEFAULT_IN,
    out_dir=DEFAULT_OUT,
    vendors=VENDORS,
    sizes=("month", "year", "two_years"),
    seed: int = DEFAULT_SEED,
    start: str | None = None,
    end: str | None = None,
    store_name: str = DEFAULT_STORE,
    report=None,
) -> list[Path]:
    """모든 입력 · 기간을 먼저 확인하고(잘못되면 InputError, 파일 안 씀) 벤더 × 기간 × (정상 · 깨진) 파일을 쓴다."""
    vendors, sizes = list(vendors), list(sizes)
    if not vendors:
        raise InputError("--vendor 가 비었습니다 (sample, toss, all)")
    if unknown := [v for v in vendors if v not in SHEET_BUILDERS]:
        raise InputError(f"모르는 --vendor: {', '.join(unknown)} (sample, toss, all)")
    if not start and not sizes:
        raise InputError("--sizes 가 비었습니다 (month, year, two_years 중 쉼표로)")
    sales, menus = load_inputs(in_dir)
    periods = resolve_periods(sales, sizes, start, end)
    ctx = _Context(menus, seed, store_name)
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    written = []
    for label, s, e in periods:
        part = _slice(sales, s, e)
        for vendor in vendors:
            for broken in (False, True):
                sheets = SHEET_BUILDERS[vendor](part, ctx, s, e, broken)
                path = out_dir / file_name(vendor, label, broken)
                _write(path, sheets)
                written.append(path)
                if report:
                    sizes_text = ", ".join(f"{k} {len(v)}" for k, v in sheets.items())
                    report(f"{path.name:36} {sum(len(v) for v in sheets.values()):7} rows  ({sizes_text})")
    pd.DataFrame({"menu_name": list(ctx.decorated), "pos_name": list(ctx.decorated.values())}).to_csv(
        out_dir / "unmapped_menus.csv", index=False, encoding="utf-8-sig"
    )
    if report:
        report("미매핑(꾸민) 메뉴: " + ", ".join(f"{k} → {v!r}" for k, v in ctx.decorated.items()))
        report(f"out: {out_dir}")
    return written


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--in", dest="in_dir", default=str(DEFAULT_IN))
    parser.add_argument("--out", default=str(DEFAULT_OUT))
    parser.add_argument("--vendor", choices=[*VENDORS, "all"], default="all")
    parser.add_argument("--start", help="시작 날짜 YYYY-MM-DD. 주면 --sizes 대신 이 기간 하나")
    parser.add_argument("--end", help="끝 날짜 YYYY-MM-DD (기본 데이터 마지막 날)")
    parser.add_argument("--store-name", default=DEFAULT_STORE, help="제목 줄에 쓰는 가게 이름")
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    parser.add_argument("--sizes", default="month,year,two_years", help="month,year,two_years 중 쉼표로")
    args = parser.parse_args(argv)
    vendors = list(VENDORS) if args.vendor == "all" else [args.vendor]
    try:
        build(
            in_dir=args.in_dir,
            out_dir=args.out,
            vendors=vendors,
            sizes=[s.strip() for s in args.sizes.split(",") if s.strip()],
            seed=args.seed,
            start=args.start,
            end=args.end,
            store_name=args.store_name,
            report=print,
        )
    except InputError as e:
        print(f"오류: {e}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
