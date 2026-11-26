"""샘플 CSV 폴더 → seed.sql. 기본 입력 · 출력 ai/data/sample/ (커밋하지 않는다).

uv run python scripts/make_seed_sql.py [--sample-dir data/sample] [--out data/sample/seed.sql] [--append]
적용: psql -v ON_ERROR_STOP=1 -f seed.sql  (Flyway 마이그레이션(V12 까지)이 적용된 빈 DB)

- --sample-dir 에 stores.csv 가 있으면 여러 가게를 한 파일 · 한 트랜잭션에 넣는다 (S15P31A308-240)
- --append: 빈 DB 대신 "넣으려는 가게 이름 · 계정 이메일이 DB 에 없음" 만 검사한다. 기본 출력 이름은 seed_append.sql
- --into-existing --store-name 이름 | --store-id id: 이미 있는 가게에 이력만 더한다 (make_sample_data.py --from-existing 출력용,
  S15P31A308-243). 기본 출력 이름은 seed_existing.sql. 가게를 안 주면 한 줄 오류와 종료 코드 2
"""

import argparse
import sys
from pathlib import Path

from weglee_ai.seed_sql import build_existing_seed_sql, build_seed_sql

SAMPLE_DIR = Path(__file__).resolve().parents[1] / "data" / "sample"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sample-dir", default=str(SAMPLE_DIR), help="make_sample_data.py 출력 폴더")
    parser.add_argument("--out", default=None, help="기본: <sample-dir>/seed.sql (--append 면 seed_append.sql)")
    parser.add_argument("--append", action="store_true", help="이미 다른 가게가 있는 DB 에 새 가게만 더 넣는다")
    parser.add_argument("--into-existing", action="store_true", help="이미 있는 가게에 판매 · 입고 · 실사 이력만 더한다")
    parser.add_argument("--store-name", default=None, help="--into-existing 대상 가게 이름")
    parser.add_argument("--store-id", type=int, default=None, help="--into-existing 대상 가게 id")
    args = parser.parse_args()

    sample_dir = Path(args.sample_dir)
    if args.into_existing:
        if args.append:
            print("오류: --into-existing 과 --append 는 같이 쓰지 않는다", file=sys.stderr)
            sys.exit(2)
        try:
            sql = build_existing_seed_sql(sample_dir, store_name=args.store_name, store_id=args.store_id)
        except ValueError as e:
            print(f"오류: {e}", file=sys.stderr)
            sys.exit(2)
        out = Path(args.out) if args.out else sample_dir / "seed_existing.sql"
    else:
        out = Path(args.out) if args.out else sample_dir / ("seed_append.sql" if args.append else "seed.sql")
        try:
            sql = build_seed_sql(sample_dir, append=args.append)
        except ValueError as e:
            print(f"오류: {e}", file=sys.stderr)
            sys.exit(2)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(sql, encoding="utf-8", newline="\n")
    print(f"out: {out} ({sql.count(chr(10))} lines)")


if __name__ == "__main__":
    main()
