"""샘플 데이터를 CSV로 쓴다. 기본 출력 ai/data/sample/ (커밋하지 않는다).

uv run python scripts/make_sample_data.py [--out data/sample] [--end 2026-10-11] [--days 730] [--seed 15]
                                          [--template donkatsu|bunsik,chicken|all] [--stores N] [--list-templates]
같은 인자면 CSV 가 바이트 단위로 같다.
events · weather · true_demand · true_usage 는 생성 정답이다. seed.sql(make_seed_sql.py)은 읽지 않는다.

- 돈까스 틀 하나 · 가게 1곳(기본)이면 --out 에 CSV 를 바로 쓴다 (S15P31A308-162 와 같은 출력)
- 그 밖이면 가게마다 --out/가게키/ 에 CSV 를 쓰고 --out/stores.csv 에 가게 목록을 적는다 (S15P31A308-240).
  가게 i(1부터)는 틀 목록을 차례로 돌려 받고 시드 seed+i, 규모 배수는 default_rng(seed) 로 0.6~1.8
- 모르는 틀 · --stores 0 이하 · --days 0 이하 · 공휴일 표 범위 밖 기간이면 한 줄 오류와 종료 코드 2 (CSV 를 쓰지 않는다)
- --from-existing 폴더: 이미 있는 가게를 DB 에서 떠 온 CSV 로 틀을 만들어 --out 에 바로 쓴다 (S15P31A308-243, ai/README.md)
"""

import argparse
import sys
from dataclasses import fields
from pathlib import Path

import pandas as pd

from weglee_ai.sample_data import DAYS, END_DATE, check_period, generate
from weglee_ai.store_templates import DEFAULT_TEMPLATE, TEMPLATES, parse_template_keys, plan_stores, template_from_existing

STORES_FILE = "stores.csv"


def _write(data, out: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    for f in fields(data):
        df = getattr(data, f.name)
        df.to_csv(out / f"{f.name}.csv", index=False, encoding="utf-8-sig")
        print(f"{f.name:18} {len(df):6} rows")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default=str(Path(__file__).resolve().parents[1] / "data" / "sample"))
    parser.add_argument("--end", default=END_DATE, help="마지막 날짜 (YYYY-MM-DD)")
    parser.add_argument("--days", type=int, default=DAYS, help="기간 일수 (마지막 날 포함). 2021-01-01~2026-12-31 안")
    parser.add_argument("--seed", type=int, default=15)
    parser.add_argument("--template", default=DEFAULT_TEMPLATE, help="가게 틀 key, 쉼표 목록, 또는 all (--list-templates)")
    parser.add_argument("--stores", type=int, default=1, help="만들 가게 수")
    parser.add_argument("--list-templates", action="store_true", help="가게 틀 목록을 보이고 끝낸다")
    parser.add_argument(
        "--from-existing", default=None,
        help="DB 에서 떠 온 가게 CSV 폴더(menu · ingredient · recipe · supplier). 그 메뉴 · 재료 · 레시피로 틀을 만든다 (S15P31A308-243)",
    )
    args = parser.parse_args()

    if args.list_templates:
        for t in TEMPLATES.values():
            kinds = sorted({i[2] for i in t.ingredients})
            print(
                f"{t.key:10} {t.label}  메뉴 {len(t.menus)} · 재료 {len(t.ingredients)}({'/'.join(kinds)}) · "
                f"거래처 {len(t.suppliers)} · 배달 비중 {t.delivery_share:.2f} · 금토 배수 {t.weekend_factor}"
            )
        return

    # 쓰기 전에 전부 검사한다 — 오류면 CSV 를 하나도 쓰지 않는다
    try:
        keys = parse_template_keys(args.template)
        plan = plan_stores(keys, args.stores, args.seed)
        check_period(args.end, args.days)
    except ValueError as e:
        print(f"오류: {e}", file=sys.stderr)
        sys.exit(2)

    out = Path(args.out)
    if args.from_existing:
        # 이미 있는 가게 한 곳 (S15P31A308-243). --template · --stores 는 쓰지 않는다. seed 는 make_seed_sql.py --into-existing
        try:
            template = template_from_existing(args.from_existing)
        except (ValueError, KeyError) as e:
            print(f"오류: {e}", file=sys.stderr)
            sys.exit(2)
        _write(generate(end_date=args.end, days=args.days, seed=args.seed, template=template), out)
        stale = out / STORES_FILE
        if stale.exists():
            stale.unlink()
            print(f"지움: {stale} (예전 여러 가게 목록)")
        print(f"out: {out} (떠 온 가게: 메뉴 {len(template.menus)} · 재료 {len(template.ingredients)} · 거래처 {len(template.suppliers)})")
        return

    if args.stores == 1 and keys == [DEFAULT_TEMPLATE]:
        data = generate(end_date=args.end, days=args.days, seed=args.seed)
        _write(data, out)
        # 같은 폴더에 예전 여러 가게 출력이 남아 있으면 seed.sql 이 여러 가게로 읽는다 — 목록만 지운다
        stale = out / STORES_FILE
        if stale.exists():
            stale.unlink()
            print(f"지움: {stale} (예전 여러 가게 목록)")
        print(f"out: {out}")
        return

    for store in plan:
        data = generate(
            end_date=args.end, days=args.days, seed=store["seed"], template=store["template"], scale=store["scale"]
        )
        folder = out / store["store_key"]
        for f in fields(data):
            folder.mkdir(parents=True, exist_ok=True)
            getattr(data, f.name).to_csv(folder / f"{f.name}.csv", index=False, encoding="utf-8-sig")
        print(f"{store['store_key']:12} scale {store['scale']:.3f} seed {store['seed']:4}  판매 {len(data.pos_sales):6} rows")
    stores = pd.DataFrame(plan, columns=["store_key", "template", "store_name", "scale", "seed"])
    stores.to_csv(out / STORES_FILE, index=False, encoding="utf-8-sig")
    print(f"out: {out} (가게 {len(plan)}곳, {STORES_FILE})")


if __name__ == "__main__":
    main()
