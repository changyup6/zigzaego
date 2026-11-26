"""시연 가게 자동 연결 (S15P31A308-272, S15P31A308-346).

시연 계정(이메일 하나)이 온보딩으로 메뉴 · 재료를 저장하는 순간, 그 가게에 2년치 합성 판매 · 발주 · 실사 이력을 붙인다.
    python scripts/demo_attach.py --env staging --email 시연이메일 [--watch] [--interval 5] [--timeout 3600] [--end YYYY-MM-DD] [--dry-run]
    python scripts/demo_attach.py --env prod --confirm-prod --email 시연이메일 [--watch] [--end YYYY-MM-DD]
    python scripts/demo_attach.py --email 시연이메일 --forever [--end YYYY-MM-DD]   # 다시 가입할 때마다 붙인다 (S15P31A308-337)

안전 조건: 명시적 환경 선택(--env staging|prod, 운영 기본 비활성: WEGLEE_ALLOW_PROD=1 및 쓰기 시 --confirm-prod 필요) · 시연 이메일 하나 · 판매 0 인 가게만.
DB 접근은 run_psql 한 곳(staging: ssh → kubectl exec, prod: AWS SSM → 실제 CNPG primary 파드).
--prepare-template 만 생성기를 부르고 일반 연결은 검증된 캐시만 쓴다.
    python scripts/demo_attach.py --prepare-template SOURCE_STORE_ID --template-dir data/demo-template
필수: BE V22 demo_preparation 상태 계약. DATA_READY 뒤 발주 추천/READY 는 BE 작업자가 마친다.
종료 코드: 0 끝남(붙였거나 이미 이력 있음) · 1 ssh/ssm/psql/생성 실패 · 2 계정/가게/인자/가드 오류 · 3 온보딩 전(--watch 아님) · 4 timeout.
"""

import argparse
import base64
import datetime as dt
import gzip
import hashlib
import hmac
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

from weglee_ai.demo_template import (
    TOKEN,
    TemplateError,
    fingerprint,
    load_template,
    render_copy,
    schema_sql,
    semantic_sql,
    sha256,
    validate_forecast,
)

AI_ROOT = Path(__file__).resolve().parents[1]
NAMESPACE = "staging"  # staging 기본 고정. 환경 선택은 --env 로만 바꾼다
ENV_CHOICES = ("staging", "prod")
NAMESPACE_BY_ENV = {"staging": "staging", "prod": "prod"}
SSH_HOST_ENV = "WEGLEE_STAGING_SSH_HOST"
PEM_ENV = "WEGLEE_STAGING_PEM"
PROD_ALLOW_ENV = "WEGLEE_ALLOW_PROD"
PROD_SSM_NODE_ENV = "WEGLEE_PROD_SSM_NODE"
PROD_NODE_PREFIX = "zigzaego-node-"
AWS_PROFILE_DEFAULT = "zigzaego"
AWS_REGION_DEFAULT = "ap-northeast-2"
SSM_CHUNK_SIZE = 20000
WORKDIR_ENV = "WEGLEE_DEMO_WORKDIR"
DAYS = 730
SEED = 15
MAX_FAILURES = 3

EXIT_OK, EXIT_FAIL, EXIT_NO_ACCOUNT, EXIT_NOT_ONBOARDED, EXIT_TIMEOUT = 0, 1, 2, 3, 4

_TARGET_ENV = "staging"



DUMP_QUERIES = {
    "store": "SELECT id, name, display_name, timezone FROM store WHERE id = {sid}",
    "menu": "SELECT id, name, kind, active, price, source FROM menu WHERE store_id = {sid} ORDER BY id",
    "ingredient": (
        "SELECT id, name, unit, kind, storage_type, shelf_life_days, sourcing_type, price_sensitive, safety_days "
        "FROM ingredient WHERE store_id = {sid} ORDER BY id"
    ),
    "recipe": (
        "SELECT r.id, r.menu_id, m.name AS menu_name, r.ingredient_id, i.name AS ingredient_name, r.qty_per_serving "
        "FROM recipe r JOIN menu m ON m.id = r.menu_id JOIN ingredient i ON i.id = r.ingredient_id "
        "WHERE r.store_id = {sid} ORDER BY r.id"
    ),

}


class AttachError(Exception):
    """ssh · psql · 생성 실패."""


def sql_quote(text: str) -> str:
    return "'" + text.replace("'", "''") + "'"


def _email_tag(email: str) -> str:
    return hashlib.sha256(email.strip().encode("utf-8")).hexdigest()[:8]


def _sanitize_error(text: str, email: str | None = None) -> str:
    cleaned = text
    if email and email.strip():
        cleaned = cleaned.replace(email.strip(), "<redacted-email>")
    cleaned = re.sub(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}", "<redacted-email>", cleaned)
    return cleaned.strip()[-300:]


def _shell_quote(s: str) -> str:
    if s and all(c.isalnum() or c in "-_./=:" for c in s):
        return s
    return "'" + s.replace("'", "'\\''") + "'"


def missing_env(env_name: str = "staging") -> list[str]:
    if env_name == "prod":
        return [] if os.environ.get(PROD_ALLOW_ENV) == "1" else [PROD_ALLOW_ENV]
    return [n for n in (SSH_HOST_ENV, PEM_ENV) if not os.environ.get(n)]


def check_env_guard(env_name: str, *, namespace_arg: str | None, dry_run: bool, confirm_prod: bool,
                    prepare_template_id: int | None, forever_mode: bool = False) -> str | None:
    if env_name not in NAMESPACE_BY_ENV:
        return f"오류: 지원하지 않는 환경: {env_name}"
    expected_ns = NAMESPACE_BY_ENV[env_name]
    if namespace_arg is not None and namespace_arg != expected_ns:
        return f"오류: 대상 네임스페이스 불일치(--env {env_name} != --namespace {namespace_arg})"
    if dry_run and forever_mode:
        return "오류: --dry-run 과 --forever 는 함께 쓸 수 없다"
    if env_name == "prod":
        if os.environ.get(PROD_ALLOW_ENV) != "1":
            return f"오류: prod 경로는 기본 비활성이다({PROD_ALLOW_ENV}=1 필요)"
        if prepare_template_id is None and not dry_run and not confirm_prod:
            return "오류: prod 쓰기 실행에는 --confirm-prod 승인이 필요하다(점검만 하려면 --dry-run)"
    return None


def _aws_base_args() -> list[str]:
    profile = os.environ.get("AWS_PROFILE") or AWS_PROFILE_DEFAULT
    region = os.environ.get("AWS_REGION") or AWS_REGION_DEFAULT
    return ["aws", "--profile", profile, "--region", region]


def _find_prod_nodes() -> list[tuple[str, str]]:
    cmd = [
        *_aws_base_args(), "ec2", "describe-instances",
        "--filters", "Name=instance-state-name,Values=running", f"Name=tag:Name,Values={PROD_NODE_PREFIX}*",
        "--query", "Reservations[].Instances[].[Tags[?Key=='Name']|[0].Value,InstanceId]",
        "--output", "text",
    ]
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired) as e:
        raise AttachError(f"prod 노드 조회 실패: {e}") from e
    if p.returncode != 0:
        raise AttachError(f"prod 노드 조회 실패(코드 {p.returncode}): {_sanitize_error(p.stderr)}")
    nodes = []
    for line in p.stdout.splitlines():
        parts = [x.strip() for x in line.split("\t") if x.strip()]
        if len(parts) == 2:
            nodes.append((parts[0], parts[1]))
    nodes.sort(key=lambda item: item[0])
    if not nodes:
        raise AttachError("실행 중인 운영 노드(zigzaego-node-*)를 찾지 못했다")
    return nodes


def _select_prod_node(nodes: list[tuple[str, str]]) -> str:
    requested = (os.environ.get(PROD_SSM_NODE_ENV) or "").strip()
    if not requested:
        return nodes[0][1]
    target_name = requested if requested.startswith(PROD_NODE_PREFIX) else f"{PROD_NODE_PREFIX}{requested}"
    for name, instance_id in nodes:
        if requested == instance_id or target_name == name:
            return instance_id
    raise AttachError(f"지정한 운영 노드를 찾지 못했다: {requested}")


def _ssm_run(instance_id: str, script: str) -> str:
    b64 = base64.b64encode(script.encode("utf-8")).decode("ascii")
    payload = json.dumps({"commands": [f"echo {b64} | base64 -d | bash"]})
    with tempfile.NamedTemporaryFile("w", suffix=".json", encoding="ascii", delete=False) as tf:
        tf.write(payload)
        param_path = tf.name
    try:
        send_cmd = [
            *_aws_base_args(), "ssm", "send-command",
            "--instance-ids", instance_id,
            "--document-name", "AWS-RunShellScript",
            "--timeout-seconds", "300",
            "--parameters", f"file://{param_path}",
            "--query", "Command.CommandId",
            "--output", "text",
        ]
        try:
            send = subprocess.run(send_cmd, capture_output=True, text=True, timeout=60)
        except (OSError, subprocess.TimeoutExpired) as e:
            raise AttachError(f"SSM send-command 실패: {e}") from e
        cid = send.stdout.strip()
        if send.returncode != 0 or not cid:
            raise AttachError(f"SSM send-command 실패(코드 {send.returncode}): {_sanitize_error(send.stderr)}")
        try:
            subprocess.run(
                [*_aws_base_args(), "ssm", "wait", "command-executed", "--command-id", cid, "--instance-id", instance_id],
                capture_output=True, text=True, timeout=310,
            )
            inv = subprocess.run(
                [*_aws_base_args(), "ssm", "get-command-invocation", "--command-id", cid, "--instance-id", instance_id, "--output", "json"],
                capture_output=True, text=True, timeout=30,
            )
        except (OSError, subprocess.TimeoutExpired) as e:
            raise AttachError(f"SSM 결과 조회 실패: {e}") from e
        if inv.returncode != 0:
            raise AttachError(f"SSM 결과 조회 실패(코드 {inv.returncode}): {_sanitize_error(inv.stderr)}")
        result = json.loads(inv.stdout)
        status = result.get("Status")
        code = int(result.get("ResponseCode") if result.get("ResponseCode") is not None else 1)
        if status != "Success" or code != 0:
            err = _sanitize_error(result.get("StandardErrorContent") or "")
            raise AttachError(f"SSM 실행 실패(status={status}, code={code}): {err}")
        return result.get("StandardOutputContent") or ""
    finally:
        try:
            os.remove(param_path)
        except OSError:
            pass


def resolve_prod_primary(instance_id: str) -> str:
    """실행 중인 3노드 운영 CNPG 클러스터에서 현재 단일 primary 파드 이름을 확인한다."""
    script = (
        "set -euo pipefail\n"
        "pods=$(sudo -n k3s kubectl -n prod get pod -l cnpg.io/cluster=postgres,role=primary "
        "-o jsonpath='{range .items[*]}{.metadata.name}{\"\\n\"}{end}' | awk 'NF')\n"
        "cur=$(sudo -n k3s kubectl -n prod get cluster postgres -o jsonpath='{.status.currentPrimary}')\n"
        "printf '%s\\n---\\n%s\\n' \"$pods\" \"$cur\"\n"
    )
    out = _ssm_run(instance_id, script)
    if "\n---\n" not in out and not out.startswith("---\n"):
        raise AttachError("운영 DB primary 조회 응답 형식이 올바르지 않다")
    head, tail = (out.split("---", 1)[0], out.split("---", 1)[1])
    pods = [line.strip() for line in head.splitlines() if line.strip()]
    cur = tail.strip()
    if len(pods) != 1 or not cur or pods[0] != cur or not re.fullmatch(r"postgres-[0-9]+", pods[0]):
        raise AttachError(f"운영 DB primary가 단일 안정 상태가 아님(pods={pods}, currentPrimary={cur or 'none'})")
    return pods[0]


def fetch_prod_demo_email_hash(instance_id: str | None = None) -> str:
    """운영 Secret/app-secrets 의 DEMO_ACCOUNT_EMAIL SHA-256 해시만 조회한다(평문 비밀값·이메일 미노출)."""
    nid = instance_id or _select_prod_node(_find_prod_nodes())
    script = (
        "set -euo pipefail\n"
        "val=$(sudo -n k3s kubectl -n prod get secret app-secrets -o jsonpath='{.data.DEMO_ACCOUNT_EMAIL}' 2>/dev/null || true)\n"
        "[ -n \"$val\" ] || { echo 'MISSING'; exit 0; }\n"
        "decoded=$(printf '%s' \"$val\" | base64 -d | tr -d '\\r\\n' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')\n"
        "[ -n \"$decoded\" ] || { echo 'MISSING'; exit 0; }\n"
        "printf '%s' \"$decoded\" | sha256sum | awk '{print $1}'\n"
    )
    digest = _ssm_run(nid, script).strip()
    if digest == "MISSING" or not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise AttachError("DEMO_ACCOUNT_NOT_CONFIGURED")
    return digest


def verify_prod_demo_email(email: str) -> bool:
    clean = email.strip()
    if not clean or clean.startswith("archived-"):
        return False
    expected = fetch_prod_demo_email_hash()
    actual = hashlib.sha256(clean.encode("utf-8")).hexdigest()
    return hmac.compare_digest(actual, expected)


def ssh_command(psql_args: list[str]) -> list[str]:
    """ssh 한 번의 argv. 원격 명령은 문자열 하나(원격 셸이 읽는다)."""
    host, pem = os.environ[SSH_HOST_ENV], os.environ[PEM_ENV]
    remote = (
        f"sudo -n kubectl -n {NAMESPACE} exec -i postgres-1 -c postgres -- "
        "psql -U postgres -d zigzaego -v ON_ERROR_STOP=1 -At"
    )
    for a in psql_args:
        remote += " " + _shell_quote(a)
    return ["ssh", "-i", pem, "-o", "BatchMode=yes", host, remote]


def _ssm_run_psql(sql: str | None = None, stdin: str | None = None) -> str:
    instance_id = _select_prod_node(_find_prod_nodes())
    primary_pod = resolve_prod_primary(instance_id)
    if sql is not None:
        q_b64 = base64.b64encode(sql.encode("utf-8")).decode("ascii")
        script = (
            "set -euo pipefail\n"
            f"q=$(printf '%s' '{q_b64}' | base64 -d)\n"
            f"sudo -n k3s kubectl -n prod exec -i {primary_pod} -c postgres -- "
            "psql -U postgres -d zigzaego -v ON_ERROR_STOP=1 -At -c \"$q\"\n"
        )
        return _ssm_run(instance_id, script)
    assert stdin is not None
    gz = gzip.compress(stdin.encode("utf-8"))
    b64 = base64.b64encode(gz).decode("ascii")
    if len(b64) <= SSM_CHUNK_SIZE:
        script = (
            "set -euo pipefail\n"
            f"printf '%s' '{b64}' | base64 -d | gzip -dc | "
            f"sudo -n k3s kubectl -n prod exec -i {primary_pod} -c postgres -- "
            "psql -U postgres -d zigzaego -v ON_ERROR_STOP=1 -At -f -\n"
        )
        return _ssm_run(instance_id, script)
    gz_sha = hashlib.sha256(gz).hexdigest()
    remote_dir = _ssm_run(instance_id, "set -euo pipefail\nd=$(mktemp -d /tmp/demo-attach.XXXXXX)\nchmod 700 \"$d\"\necho \"$d\"\n").strip()
    if not re.fullmatch(r"/tmp/demo-attach\.[A-Za-z0-9]+", remote_dir):
        raise AttachError("prod 임시 작업 폴더 생성 검증 실패")
    try:
        for i in range(0, len(b64), SSM_CHUNK_SIZE):
            chunk = b64[i:i + SSM_CHUNK_SIZE]
            _ssm_run(instance_id, f"set -euo pipefail\nprintf '%s' '{chunk}' >> {remote_dir}/in.b64\n")
        apply_script = (
            "set -euo pipefail\n"
            f"trap 'rm -rf \"{remote_dir}\"' EXIT\n"
            f"base64 -d \"{remote_dir}/in.b64\" > \"{remote_dir}/in.gz\"\n"
            f"got=$(sha256sum \"{remote_dir}/in.gz\" | awk '{{print $1}}')\n"
            f"[ \"$got\" = \"{gz_sha}\" ] || {{ echo 'DEMO_TRANSFER_CHECKSUM_MISMATCH' >&2; exit 1; }}\n"
            f"gzip -dc \"{remote_dir}/in.gz\" | sudo -n k3s kubectl -n prod exec -i {primary_pod} -c postgres -- "
            "psql -U postgres -d zigzaego -v ON_ERROR_STOP=1 -At -f -\n"
        )
        return _ssm_run(instance_id, apply_script)
    finally:
        try:
            _ssm_run(instance_id, f"rm -rf \"{remote_dir}\"")
        except AttachError:
            pass


def run_psql(sql: str | None = None, stdin: str | None = None) -> str:
    """DB 를 부르는 유일한 곳. sql 이면 -c, stdin 이면 -f - 로 적용. 표준출력 문자열을 돌려준다. 실패하면 AttachError."""
    if (sql is None) == (stdin is None):
        raise ValueError("sql 과 stdin 중 하나만")
    if _TARGET_ENV == "prod":
        return _ssm_run_psql(sql=sql, stdin=stdin)
    args = ["-c", sql] if sql is not None else ["-f", "-"]
    try:
        p = subprocess.run(
            ssh_command(args), input=stdin.encode("utf-8") if stdin is not None else None,
            capture_output=True, timeout=300,
        )
    except (OSError, subprocess.TimeoutExpired) as e:
        raise AttachError(f"ssh 실행 실패: {e}") from e
    if p.returncode != 0:
        raise AttachError(f"ssh/psql 실패(코드 {p.returncode}): {_sanitize_error(p.stderr.decode('utf-8', 'replace'))}")
    return p.stdout.decode("utf-8")


def _now() -> float:
    return time.monotonic()


def _sleep(sec: float) -> None:
    time.sleep(sec)


def find_store_ids(email: str) -> list[int]:
    out = run_psql(
        "SELECT sm.store_id FROM account a JOIN store_member sm ON sm.account_id = a.id AND sm.role = 'OWNER' "
        f"WHERE a.email = {sql_quote(email)} ORDER BY sm.store_id"
    )
    return [int(x) for x in out.split()]


def count_state(store_id: int) -> tuple[int, int, int, int, int, int]:
    """(메뉴, 재료, 레시피, 판매, 거래처상품, 실사) 행 수."""
    out = run_psql(
        f"SELECT (SELECT count(*) FROM menu WHERE store_id = {store_id}), "
        f"(SELECT count(*) FROM ingredient WHERE store_id = {store_id}), "
        f"(SELECT count(*) FROM recipe WHERE store_id = {store_id}), "
        f"(SELECT count(*) FROM pos_sale WHERE store_id = {store_id}), "
        f"(SELECT count(*) FROM supplier_product WHERE store_id = {store_id}), "
        f"(SELECT count(*) FROM stock_count WHERE store_id = {store_id})"
    )
    return tuple(int(x) for x in out.strip().split("|"))  # type: ignore[return-value]


def _run_script(script: str, *args: str) -> None:
    env = dict(os.environ, PYTHONUTF8="1")
    env["PYTHONPATH"] = os.pathsep.join([str(AI_ROOT / "src"), env.get("PYTHONPATH", "")]).rstrip(os.pathsep)
    p = subprocess.run([sys.executable, str(AI_ROOT / "scripts" / script), *args], capture_output=True, env=env)
    if p.returncode != 0:
        raise AttachError(f"{script} 실패(코드 {p.returncode}): {p.stderr.decode('utf-8', 'replace').strip()[-300:]}")


def snapshot(store_id: int) -> dict:
    return json.loads(run_psql(f"SELECT jsonb_build_object('semantic', {semantic_sql(store_id)}, 'schema', {schema_sql()})"))


def prepare_template(store_id: int, end: str, folder: Path, forecast_store_id: int | None = None,
                     market_sql: Path | None = None) -> None:
    """Explicit read-only DB preparation. Generation is never called by attach."""
    if folder.exists():
        raise AttachError("DEMO_TEMPLATE_EXISTS: choose a new immutable template directory")
    started = _now()
    folder.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix='.demo-template-', dir=folder.parent))
    try:
        before = snapshot(store_id)
        dump_dir, gen_dir = temporary / 'dump', temporary / 'gen'
        dump_dir.mkdir()
        for name in ('store', 'menu', 'ingredient', 'recipe'):
            text = run_psql(f"COPY ({DUMP_QUERIES[name].format(sid=store_id)}) TO STDOUT WITH CSV HEADER")
            (dump_dir / f'{name}.csv').write_text(text, encoding='utf-8', newline='')
        # A fresh signup has no transaction suppliers. Build deterministic synthetic suppliers,
        # rather than importing contacts or depending on an archived store's transaction state.
        (dump_dir / 'supplier.csv').write_text('id,name,channel,lead_time_days,order_cycle_days,min_order_amount\n')
        (dump_dir / 'purchase_doc.csv').write_text('id,doc_type,kind,doc_date,parse_status,supplier_id\n')
        _run_script("make_sample_data.py", "--from-existing", str(dump_dir), "--end", end, "--days", str(DAYS),
                    "--seed", str(SEED), "--out", str(gen_dir))
        source_sql = gen_dir / 'seed_existing.sql'
        sentinel = '9223372036854775807'
        _run_script("make_seed_sql.py", "--sample-dir", str(gen_dir), "--into-existing", "--store-id", sentinel,
                    "--out", str(source_sql))
        sql = source_sql.read_text(encoding='utf-8').replace(sentinel, TOKEN)
        sql = sql.replace('BEGIN;', '', 1)
        sql = sql[:sql.rfind('COMMIT;')]
        (temporary / 'history.sql').write_text(sql, encoding='utf-8')
        if snapshot(store_id) != before:
            raise AttachError('DEMO_SOURCE_CHANGED')
        today = dt.date.fromisoformat(end) + dt.timedelta(days=1)
        monday = today - dt.timedelta(days=today.weekday())
        source = int(forecast_store_id or store_id)
        forecast = json.loads(run_psql(
            "SELECT row_to_json(w) FROM (SELECT week_start,model,pct,reason,days,factors,analysis FROM sales_forecast_week "
            f"WHERE store_id={source} AND week_start={sql_quote(monday.isoformat())}::date) w"
        ).strip() or 'null')
        validate_forecast(forecast, today.isoformat())
        import pandas as pd
        def read(name):
            return pd.read_csv(gen_dir / f'{name}.csv')
        sales, docs, lines, stock = read('pos_sales'), read('purchase_docs'), read('purchase_doc_lines'), read('true_stock')
        counts = {'pos_sale': len(sales), 'supplier_product': len(read('supplier_products')), 'purchase_doc': len(docs),
                  'purchase_doc_line': len(lines), 'purchase_order': len(docs), 'purchase_order_item': len(lines),
                  'stock_count': int(stock.stock_date.isin([stock.stock_date.min(), stock.stock_date.max()]).sum()),
                  'price_history': int((lines.supplier_product_id.notna() & (lines.unit_price.fillna(0) > 0)).sum())}
        files = {'history.sql': sha256(temporary / 'history.sql')}
        if market_sql:
            shutil.copyfile(market_sql, temporary / 'market.sql')
            files['market.sql'] = sha256(temporary / 'market.sql')
        manifest = dict(version=1, days=DAYS, seed=SEED, start=(today - dt.timedelta(days=DAYS)).isoformat(), end=end,
                        today=today.isoformat(), counts=counts, forecast=forecast, files=files,
                        semantic=before['semantic'], schema=before['schema'],
                        semantic_fingerprint=fingerprint(before['semantic']), schema_fingerprint=fingerprint(before['schema']),
                        generation_seconds=round(_now() - started, 3))
        manifest['template_fingerprint'] = fingerprint(manifest)
        (temporary / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
        temporary.rename(folder)
        print(f"template ready: {folder} generation_seconds={manifest['generation_seconds']} fingerprint={manifest['semantic_fingerprint']}")
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)


def preparation(store_id: int) -> tuple[str, int]:
    out = run_psql(f"SELECT status, attempt FROM demo_preparation WHERE store_id={store_id}").strip()
    if not out:
        return 'NOT_STARTED', 0
    status, attempt = out.split('|')
    return status, int(attempt)


def fail_preparation(store_id: int, attempt: int, code: str, email: str | None = None) -> None:
    owner = (" AND EXISTS (SELECT 1 FROM account a JOIN store_member sm ON sm.account_id=a.id AND sm.role='OWNER' "
             f"WHERE a.email={sql_quote(email)} AND sm.store_id={store_id})") if email else ''
    run_psql(f"UPDATE demo_preparation SET status='FAILED',phase='ATTACH',error_code={sql_quote(code)},retryable=true,updated_at=now() "
             f"WHERE store_id={store_id} AND status='PREPARING' AND phase='ATTACH' AND attempt={attempt}{owner}")


def build_sql(store_id: int, end: str, run_dir: Path, *, email: str, template_dir: Path, attempt: int) -> Path:
    """Read cached SQL only. Missing/mismatched cache never generates a replacement."""
    manifest, body = load_template(template_dir, end)
    run_dir.mkdir(parents=True, exist_ok=True)
    sql_path = run_dir / 'attach.sql'
    sql_path.write_text(render_copy(body, manifest, store_id, email, attempt), encoding='utf-8')
    return sql_path


def result_summary(store_id: int) -> str:
    out = run_psql(
        f"SELECT (SELECT count(*) FROM pos_sale WHERE store_id = {store_id}), "
        f"(SELECT count(*) FROM purchase_order WHERE store_id = {store_id}), "
        f"(SELECT count(*) FROM stock_count WHERE store_id = {store_id}), "
        f"(SELECT max(sold_date) FROM pos_sale WHERE store_id = {store_id})"
    )
    sales, orders, counts, last = out.strip().split("|")
    return f"판매 {sales}행 · 발주서 {orders}장 · 실사 {counts}행 · 마지막 판매일 {last}"



class _Lock:
    """이메일별 OS 파일 잠금. 같은 이메일로 두 프로세스가 붙이지 못하게 한다.

    잠금 파일을 열어 둔 채 비차단 배타 잠금을 잡는다. 프로세스가 어떻게 죽든 OS 가 풀므로 남은 잠금 판정이 없다.
    파일은 지우지 않는다(지우면 다른 프로세스가 연 파일과 갈라져 경쟁이 생긴다).
    """

    def __init__(self, work_root: Path, email: str):
        self.path = work_root / f".lock-{hashlib.sha1(email.encode('utf-8')).hexdigest()[:16]}"
        self.held = False
        self._fd: int | None = None

    def acquire(self) -> bool:
        if self.held:
            return True
        self.path.parent.mkdir(parents=True, exist_ok=True)
        fd = os.open(str(self.path), os.O_CREAT | os.O_RDWR)
        try:
            if os.name == "nt":
                import msvcrt

                os.lseek(fd, 0, os.SEEK_SET)
                msvcrt.locking(fd, msvcrt.LK_NBLCK, 1)
            else:
                import fcntl

                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            os.close(fd)
            return False
        self._fd = fd
        self.held = True
        return True

    def release(self) -> None:
        if not self.held or self._fd is None:
            return
        fd, self._fd, self.held = self._fd, None, False
        try:
            if os.name == "nt":
                import msvcrt

                os.lseek(fd, 0, os.SEEK_SET)
                msvcrt.locking(fd, msvcrt.LK_UNLCK, 1)
            else:
                import fcntl

                fcntl.flock(fd, fcntl.LOCK_UN)
        except OSError:
            pass
        finally:
            os.close(fd)


def _timed_out(start: float, timeout: float) -> bool:
    if _now() - start > timeout:
        print(f"timeout({timeout:g}초) 넘김", file=sys.stderr)
        return True
    return False


def attach(email: str, *, watch: bool, interval: float, timeout: float, end: str, dry_run: bool, work_root: Path,
           template_dir: Path) -> int:
    start = _now()
    failures = 0  # 연속 실패. 붙이기 시도가 성공하거나 쓰지 않음으로 끝날 때, 또는 기다리는 바퀴가 성공할 때만 0
    prev: tuple[int, int, int] | None = None  # 직전 바퀴의 (메뉴, 재료, 레시피) 수 - 메뉴>0 일 때만
    first = True
    while True:
        if not first:
            if _timed_out(start, timeout):
                return EXIT_TIMEOUT
            _sleep(interval)
            if _timed_out(start, timeout):
                return EXIT_TIMEOUT
        first = False
        lock = _Lock(work_root, email)
        sid, attempt = None, 0
        try:
            ids = find_store_ids(email)
            if len(ids) != 1:
                tag = _email_tag(email)
                msg = (f"계정 또는 OWNER 가게를 못 찾음(ref={tag})" if not ids
                       else f"OWNER 가게가 {len(ids)}곳이라 쓰지 않음(ref={tag})")
                print(msg, file=sys.stderr)
                return EXIT_NO_ACCOUNT
            sid = ids[0]
            status, attempt = preparation(sid)
            if status in ('DATA_READY', 'READY'):
                print(f"가게 {sid}: 이미 준비된 이력, 쓰지 않음 ({status})")
                return EXIT_OK
            if status != 'PREPARING':
                if not watch or status == 'FAILED':
                    return EXIT_NOT_ONBOARDED if status == 'NOT_STARTED' else EXIT_FAIL
                continue
            menus, ings, recipes, sales, sprods, scounts = count_state(sid)
            if sales > 0:
                print(f"가게 {sid}: 이미 이력 있음, 쓰지 않음 (판매 {sales}행)")
                raise AttachError("DEMO_PARTIAL_HISTORY")
            if sprods > 0 or scounts > 0:
                print(f"243 적용 조건 아님(거래처상품 {sprods} · 실사 {scounts} 있음), 쓰지 않음", file=sys.stderr)
                raise AttachError("DEMO_PARTIAL_HISTORY")
            if menus == 0:
                prev = None
                failures = 0
                if not watch:
                    print(f"가게 {sid}: 온보딩 전 (메뉴 0)")
                    return EXIT_NOT_ONBOARDED
                print(f"가게 {sid}: 온보딩 전, {interval:g}초 뒤 다시")
                continue
            if prev != (menus, ings, recipes):
                prev = (menus, ings, recipes)
                failures = 0
                print(f"가게 {sid}: 메뉴 {menus} · 재료 {ings} · 레시피 {recipes} - 저장이 끝났는지 한 바퀴 더 확인")
                continue
            # 세 수가 두 바퀴 연속 같음 -> 붙이기 시도
            if not lock.acquire():
                print(f"다른 실행이 붙이는 중이라 쓰지 않음: {lock.path}", file=sys.stderr)
                return EXIT_FAIL
            run_dir = work_root / dt.datetime.now().strftime("%Y%m%d-%H%M%S")
            sql_path = build_sql(sid, end, run_dir, email=email, template_dir=template_dir, attempt=attempt)
            copy_started = _now()
            if dry_run:
                print(f"dry-run: SQL 만 만들고 적용하지 않음 -> {sql_path}")
                return EXIT_OK
            run_psql(stdin=sql_path.read_text(encoding="utf-8"))
            try:
                summary = result_summary(sid)
            except (AttachError, ValueError) as e:  # 쓰기는 끝났다 - 요약만 못 읽음
                summary = f"요약 조회 실패({_sanitize_error(str(e), email)})"
            print(f"가게 {sid}: DATA_READY - {summary} · copy_seconds={_now() - copy_started:.3f}")
            return EXIT_OK
        except (AttachError, TemplateError) as e:
            if sid is not None and attempt and not dry_run:
                code = str(e) if isinstance(e, TemplateError) else next(iter(re.findall(r'DEMO_[A-Z_]+', str(e))), 'DEMO_ATTACH_FAILED')
                try:
                    fail_preparation(sid, attempt, code, email)
                except AttachError:
                    pass
            failures += 1
            print(f"오류({failures}/{MAX_FAILURES}): {_sanitize_error(str(e), email)}", file=sys.stderr)
            if not watch or failures >= MAX_FAILURES:
                return EXIT_FAIL
        finally:
            lock.release()


def forever(email: str, *, interval: float, end: str, work_root: Path, template_dir: Path) -> int:
    """끝내지 않고 지켜본다. 그 이메일의 가게가 바뀔 때마다(다시 가입) 온보딩을 기다렸다가 붙인다. Ctrl+C 로 끝낸다."""
    done: list[int] | None = None
    while True:
        try:
            ids = find_store_ids(email)
            if ids != done:
                code = attach(email, watch=True, interval=interval, timeout=float("inf"), end=end, dry_run=False,
                              work_root=work_root, template_dir=template_dir)
                if code == EXIT_OK:
                    done = ids
                else:
                    print(f"붙이지 못함(코드 {code}), {interval:g}초 뒤 다시", file=sys.stderr)
        except AttachError as e:
            print(f"오류: {_sanitize_error(str(e), email)}, {interval:g}초 뒤 다시", file=sys.stderr)
        _sleep(interval)


def main(argv: list[str] | None = None) -> int:
    global _TARGET_ENV
    for stream in (sys.stdout, sys.stderr):  # cp949 콘솔에서 한글 아닌 글자로 죽지 않게
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--env", choices=ENV_CHOICES, default="staging", help="대상 환경 (staging 고정 SSH 또는 prod AWS SSM)")
    p.add_argument("--namespace", default=None, help="대상 네임스페이스 교차 확인용 (--env 와 일치해야 함)")
    p.add_argument("--confirm-prod", action="store_true", help="prod 실제 쓰기 승인 (--env prod 쓰기 시 필수)")
    p.add_argument("--email", default=None, help="시연 계정 이메일")
    p.add_argument("--watch", action="store_true", help="온보딩이 끝날 때까지 --interval 초마다 다시 확인")
    p.add_argument("--interval", type=float, default=1)
    p.add_argument("--timeout", type=float, default=3600)
    p.add_argument("--end", default=None, help="마지막 판매일 YYYY-MM-DD (기본: --today 전날)")
    p.add_argument("--forever", action="store_true", help="끝내지 않고, 다시 가입할 때마다 새 가게에 붙인다")
    p.add_argument("--dry-run", action="store_true", help="SQL 파일까지만 만들고 적용하지 않는다")
    p.add_argument('--template-dir', type=Path, default=AI_ROOT / 'data' / 'demo-template')
    p.add_argument('--prepare-template', type=int, metavar='SOURCE_STORE_ID', help='Generate cache explicitly, never attach')
    p.add_argument('--forecast-store-id', type=int, help='Read-only source for the required fixed-week forecast')
    p.add_argument('--market-sql', type=Path, help='Optional portable target-scoped market fixture SQL')
    p.add_argument('--today', default='2026-11-12', help='Must match DEMO_ACCOUNT_TODAY')
    args = p.parse_args(argv)
    if args.interval < 1:
        print("오류: --interval 은 1 이상", file=sys.stderr)
        return 2
    guard_err = check_env_guard(
        args.env, namespace_arg=args.namespace, dry_run=args.dry_run,
        confirm_prod=args.confirm_prod, prepare_template_id=args.prepare_template,
        forever_mode=args.forever,
    )
    if guard_err:
        print(guard_err, file=sys.stderr)
        return 2
    missing = missing_env(args.env)
    if missing:
        print(f"오류: 환경변수가 없다: {', '.join(missing)}", file=sys.stderr)
        return 2
    try:
        expected_end = (dt.date.fromisoformat(args.today) - dt.timedelta(days=1)).isoformat()
    except ValueError:
        return EXIT_NO_ACCOUNT
    end = args.end or expected_end
    try:
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", end):
            raise ValueError(end)
        dt.date.fromisoformat(end)
    except ValueError:
        print(f"오류: --end 는 YYYY-MM-DD: {args.end}", file=sys.stderr)
        return 2
    if end != expected_end:
        print('오류: --end must equal --today minus one day', file=sys.stderr)
        return EXIT_NO_ACCOUNT
    previous_env = _TARGET_ENV
    _TARGET_ENV = args.env
    try:
        if args.prepare_template is not None:
            if args.prepare_template <= 0:
                print("오류: --prepare-template 는 양의 정수여야 한다", file=sys.stderr)
                return 2
            try:
                prepare_template(args.prepare_template, end, args.template_dir, args.forecast_store_id, args.market_sql)
                return EXIT_OK
            except (AttachError, TemplateError, OSError, ValueError) as exc:
                print(f'template preparation failed: {_sanitize_error(str(exc))}', file=sys.stderr)
                return EXIT_FAIL
        if not args.email or not args.email.strip() or args.email.strip().startswith("archived-"):
            return EXIT_NO_ACCOUNT
        if args.env == "prod":
            try:
                if not verify_prod_demo_email(args.email):
                    print("오류: 지정된 운영 시연 계정이 아니므로 쓰기를 거부한다", file=sys.stderr)
                    return EXIT_NO_ACCOUNT
            except AttachError as exc:
                print(f"오류: 운영 시연 계정 검증 실패: {_sanitize_error(str(exc), args.email)}", file=sys.stderr)
                return EXIT_FAIL
        work_root = Path(os.environ.get(WORKDIR_ENV) or Path(tempfile.gettempdir()) / "weglee_demo_attach")
        if args.forever:
            return forever(args.email, interval=args.interval, end=end, work_root=work_root, template_dir=args.template_dir)
        return attach(args.email, watch=args.watch, interval=args.interval, timeout=args.timeout, end=end,
                      dry_run=args.dry_run, work_root=work_root, template_dir=args.template_dir)
    finally:
        _TARGET_ENV = previous_env


if __name__ == "__main__":
    sys.exit(main())
