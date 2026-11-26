#!/usr/bin/env bash
# 여러 가게 seed.sql · --append 를 실제 Postgres 에 넣어 본다 (S15P31A308-240). Docker 필요, pytest 에 넣지 않는다.
#
#   cd ai && WEGLEE_DEMO_PASSWORD_HASH=<$2a$ BCrypt 해시 60자> bash scripts/check_seed_multi.sh   (환경변수 필수)
#
# 1) 빈 DB(V1~V4) 에 가게 7곳(틀 7종 · 365일) seed.sql → store 7 · account 7 · 가게마다 pos_sale 행 수 = 그 가게 pos_sales.csv 행 수
# 2) 같은 DB 에 같은 가게들의 --append 파일 → 이름 중복으로 실패하고 행 수 그대로
# 3) 새 빈 DB 에 단일 가게(돈까스) seed.sql → 여러 가게 --append 파일 → 성공, 가게 8곳
# 기대 출력 세 줄:
#   stores=7 accounts=7 pos_sale_match=7/7
#   append_dup=FAILED rows_unchanged
#   append_onto_single=OK stores=8
#
# PYTHON 으로 파이썬 명령을 바꿀 수 있다 (기본 python). PYTHONPATH 가 비어 있으면 ai/src 를 쓴다.
# 가게는 계정 이메일(ASCII)로 찾는다 — 한글 가게 이름을 docker 명령 인자로 넘기지 않으려고.
set -euo pipefail

AI="$(cd "$(dirname "$0")/.." && pwd)"
MIGRATIONS="$AI/../backend/src/main/resources/db/migration"
PYTHON="${PYTHON:-python}"
export PYTHONPATH="${PYTHONPATH:-$AI/src}"
WORK="$(mktemp -d)"
NAME="weglee-seed-check-$$"

cleanup() {
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

# 1. 샘플 · seed.sql 만들기
$PYTHON "$AI/scripts/make_sample_data.py" --out "$WORK/multi" --template all --stores 7 --days 365 >/dev/null
$PYTHON "$AI/scripts/make_seed_sql.py" --sample-dir "$WORK/multi" --out "$WORK/multi.sql" >/dev/null
$PYTHON "$AI/scripts/make_seed_sql.py" --sample-dir "$WORK/multi" --append --out "$WORK/multi_append.sql" >/dev/null
$PYTHON "$AI/scripts/make_sample_data.py" --out "$WORK/single" >/dev/null
$PYTHON "$AI/scripts/make_seed_sql.py" --sample-dir "$WORK/single" --out "$WORK/single.sql" >/dev/null

# 2. Postgres 16
docker run -d --name "$NAME" -e POSTGRES_PASSWORD=check -e PGCLIENTENCODING=UTF8 postgres:16 >/dev/null
for _ in $(seq 1 60); do
  docker exec "$NAME" pg_isready -U postgres -q 2>/dev/null && break
  sleep 1
done
sleep 2

psql_in() {  # psql_in DB < file
  docker exec -i -e PGCLIENTENCODING=UTF8 "$NAME" psql -U postgres -d "$1" -v ON_ERROR_STOP=1 -q -X
}
scalar() {  # scalar DB "SQL"
  echo "$2" | docker exec -i "$NAME" psql -U postgres -d "$1" -v ON_ERROR_STOP=1 -q -X -t -A
}
fresh_db() {
  echo "CREATE DATABASE $1;" | psql_in postgres
  for f in "$MIGRATIONS"/V*__*.sql; do
    psql_in "$1" < "$f" >/dev/null
  done
}
snapshot() {
  scalar "$1" "SELECT (SELECT count(*) FROM store) || ',' || (SELECT count(*) FROM account) || ',' || (SELECT count(*) FROM menu)
    || ',' || (SELECT count(*) FROM ingredient) || ',' || (SELECT count(*) FROM pos_sale) || ',' || (SELECT count(*) FROM purchase_doc_line)
    || ',' || (SELECT count(*) FROM stock_count);"
}

# 3. 빈 DB 에 여러 가게
fresh_db multi
psql_in multi < "$WORK/multi.sql" >/dev/null
stores=$(scalar multi "SELECT count(*) FROM store;")
accounts=$(scalar multi "SELECT count(*) FROM account;")
match=0
total=0
while IFS=, read -r key _rest; do
  key="${key#$'\xef\xbb\xbf'}"
  [ "$key" = "store_key" ] && continue
  total=$((total + 1))
  want=$(($(wc -l < "$WORK/multi/$key/pos_sales.csv") - 1))
  got=$(scalar multi "SELECT count(*) FROM pos_sale p JOIN store_member m ON m.store_id = p.store_id
    JOIN account a ON a.id = m.account_id WHERE a.email = 'demo+$key@wiggly.local';")
  [ "$want" = "$got" ] && match=$((match + 1))
done < "$WORK/multi/stores.csv"
echo "stores=$stores accounts=$accounts pos_sale_match=$match/$total"

# 4. 같은 가게들 --append 다시 → 실패 · 행 수 그대로
before=$(snapshot multi)
if psql_in multi < "$WORK/multi_append.sql" >/dev/null 2>"$WORK/dup.err"; then
  result="append_dup=SUCCEEDED"
else
  result="append_dup=FAILED"
fi
after=$(snapshot multi)
if [ "$before" = "$after" ]; then echo "$result rows_unchanged"; else echo "$result rows_changed ($before -> $after)"; fi

# 5. 새 빈 DB: 단일 가게 → 여러 가게 --append
fresh_db onto
psql_in onto < "$WORK/single.sql" >/dev/null
if psql_in onto < "$WORK/multi_append.sql" >/dev/null 2>"$WORK/onto.err"; then
  echo "append_onto_single=OK stores=$(scalar onto "SELECT count(*) FROM store;")"
else
  echo "append_onto_single=FAILED $(head -1 "$WORK/onto.err")"
fi
