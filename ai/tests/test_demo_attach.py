"""시연 가게 자동 연결 스크립트 (S15P31A308-272). ssh/psql 은 전부 가짜 — 스테이징에 접속하지 않는다."""

import importlib.util
import os
import sys
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "demo_attach.py"
_spec = importlib.util.spec_from_file_location("demo_attach", SCRIPT)
da = importlib.util.module_from_spec(_spec)
sys.modules["demo_attach"] = da
_spec.loader.exec_module(da)

EMAIL = "demo+soonnam@wiggly.local"


class FakeDb:
    """run_psql 대용. states 는 count_state 가 차례로 돌려줄 (메뉴, 재료, 판매) — 마지막 값이 계속 반복된다."""

    def __init__(self, ids=(7,), states=((3, 5, 0),), fail_times=0, apply_fail=False):
        self.apply_fail = apply_fail
        self.ids = list(ids)
        self.states = list(states)
        self.fail_times = fail_times
        self.calls = []  # (sql, stdin)
        self.applied = 0
        self._n = 0

    def __call__(self, sql=None, stdin=None):
        self.calls.append((sql, stdin))
        if self.fail_times:
            self.fail_times -= 1
            raise da.AttachError("가짜 ssh 실패")
        if stdin is not None:
            self.applied += 1
            if self.apply_fail:
                raise da.AttachError("가짜 적용 실패")
            return ""
        if "UPDATE demo_preparation" in sql:
            return ''
        if "SELECT status, attempt" in sql:
            return 'PREPARING|1'

        if "FROM account a" in sql:
            return "\n".join(str(i) for i in self.ids) + ("\n" if self.ids else "")
        if "FROM purchase_order" in sql:
            return "100|20|30|2026-11-05\n"
        if "FROM menu WHERE" in sql and "FROM pos_sale" in sql:
            st = self.states[min(self._n, len(self.states) - 1)]
            self._n += 1
            if len(st) == 3:  # (메뉴, 재료, 판매) -> 레시피 수는 재료 수와 같게
                st = (st[0], st[1], st[1], st[2], 0, 0)
            return "|".join(str(x) for x in st) + "\n"
        raise AssertionError(f"예상 밖 SQL: {sql}")


FAKE_HOST = "ubuntu@example.invalid"


@pytest.fixture(autouse=True)
def staging_env(monkeypatch):
    monkeypatch.setenv("WEGLEE_STAGING_SSH_HOST", FAKE_HOST)
    monkeypatch.setenv("WEGLEE_STAGING_PEM", "/tmp/test.pem")


@pytest.fixture()
def env(monkeypatch, tmp_path):
    monkeypatch.setenv("WEGLEE_DEMO_WORKDIR", str(tmp_path / "work"))
    sleeps = []
    clock = {"t": 0.0}
    monkeypatch.setattr(da, "_sleep", lambda s: (sleeps.append(s), clock.__setitem__("t", clock["t"] + s)))
    monkeypatch.setattr(da, "_now", lambda: clock["t"])
    built = []

    def fake_build(sid, end, run_dir, **kwargs):
        built.append((sid, end))
        p = tmp_path / "seed_existing.sql"
        p.write_text("SELECT 1;\n", encoding="utf-8")
        return p

    monkeypatch.setattr(da, "build_sql", fake_build)

    def install(db):
        monkeypatch.setattr(da, "run_psql", db)

    return type("E", (), {"sleeps": sleeps, "built": built, "install": staticmethod(install), "work": tmp_path / "work",
                          "monkeypatch": monkeypatch})


def run(env, db, *extra, email=EMAIL):
    env.install(db)
    return da.main(["--email", email, "--today", "2026-11-06", "--end", "2026-11-05", *extra])


def test_menu_zero_waits_and_never_applies(env):
    db = FakeDb(states=((0, 0, 0),))
    rc = run(env, db, "--watch", "--interval", "5", "--timeout", "20")
    assert rc == da.EXIT_TIMEOUT
    assert db.applied == 0 and env.built == []
    assert len(env.sleeps) >= 3


def test_menu_zero_without_watch_exits_3(env):
    db = FakeDb(states=((0, 0, 0),))
    assert run(env, db) == 3
    assert db.applied == 0 and env.sleeps == []


def test_existing_sales_means_no_write(env, capsys):
    db = FakeDb(states=((3, 5, 33817),))
    assert run(env, db) == 1
    assert db.applied == 0 and env.built == []
    assert "이미 이력 있음, 쓰지 않음" in capsys.readouterr().out


def test_no_account_exits_2(env):
    db = FakeDb(ids=())
    assert run(env, db, email="nobody-272@wiggly.local") == 2
    assert db.applied == 0 and env.built == []


def test_two_stores_refused(env):
    db = FakeDb(ids=(7, 8))
    assert run(env, db) == 2
    assert db.applied == 0


def test_applies_once_after_two_stable_rounds(env, capsys):
    db = FakeDb(states=((3, 5, 0),))
    assert run(env, db, "--watch") == 0
    assert db.applied == 1
    assert env.built == [(7, "2026-11-05")]
    assert len(env.sleeps) == 1  # 첫 바퀴에선 한 번 더 기다린다
    assert "마지막 판매일 2026-11-05" in capsys.readouterr().out


def test_unstable_counts_wait_more(env):
    # 메뉴 저장 직후 재료가 아직 안 들어옴: (3,0) -> (3,4) -> (3,5) -> (3,5) 에서 적용
    db = FakeDb(states=((3, 0, 0), (3, 4, 0), (3, 5, 0), (3, 5, 0)))
    assert run(env, db, "--watch") == 0
    assert db.applied == 1
    assert len(env.sleeps) == 3


def test_not_watch_also_waits_one_round_then_applies(env):
    db = FakeDb(states=((3, 5, 0),))
    assert run(env, db) == 0
    assert db.applied == 1 and len(env.sleeps) == 1


def test_dry_run_never_applies(env, capsys):
    db = FakeDb(states=((3, 5, 0),))
    assert run(env, db, "--dry-run") == 0
    assert db.applied == 0
    assert env.built == [(7, "2026-11-05")]
    assert "dry-run" in capsys.readouterr().out


def test_three_consecutive_failures_exit_1(env):
    db = FakeDb(fail_times=99)
    assert run(env, db, "--watch") == 1
    assert len([c for c in db.calls if c[0] and "UPDATE demo_preparation" not in c[0]]) == 3 and db.applied == 0


def test_failure_then_recovery_continues(env):
    db = FakeDb(fail_times=2, states=((3, 5, 0),))
    assert run(env, db, "--watch") == 0
    assert db.applied == 1


def test_failure_without_watch_exits_1(env):
    db = FakeDb(fail_times=1)
    assert run(env, db) == 1
    assert len(db.calls) == 1


def test_timeout_exit_4(env):
    db = FakeDb(states=((0, 0, 0),))
    assert run(env, db, "--watch", "--interval", "10", "--timeout", "25") == 4


def test_every_ssh_command_is_staging_only(monkeypatch):
    seen = []

    class P:
        returncode = 0
        stdout = b""
        stderr = b""

    monkeypatch.setattr(da.subprocess, "run", lambda argv, **kw: (seen.append(argv), P())[1])
    da.run_psql("SELECT 1")
    da.run_psql(stdin="SELECT 2;")
    assert len(seen) == 2
    for argv in seen:
        remote = argv[-1]
        assert "-n staging " in remote and "prod" not in " ".join(argv)
        assert argv[0] == "ssh" and "BatchMode=yes" in argv and FAKE_HOST in argv
        assert "-d zigzaego" in remote and "ON_ERROR_STOP=1" in remote
    assert "-f -" in seen[1][-1]


@pytest.mark.parametrize("missing", ["WEGLEE_STAGING_SSH_HOST", "WEGLEE_STAGING_PEM"])
@pytest.mark.parametrize("extra", [[], ["--dry-run"]])
def test_missing_env_exits_2_before_ssh(monkeypatch, capsys, missing, extra):
    monkeypatch.delenv(missing)
    called = []
    monkeypatch.setattr(da.subprocess, "run", lambda *a, **k: called.append(a))
    assert da.main(["--email", EMAIL, "--end", "2026-11-05", *extra]) == 2
    assert missing in capsys.readouterr().err and not called


def test_namespace_not_overridable_by_env(monkeypatch):
    monkeypatch.setenv("WEGLEE_NAMESPACE", "prod")
    monkeypatch.setenv("WEGLEE_STAGING_PEM", "/tmp/other.pem")
    cmd = da.ssh_command(["-c", "SELECT 1"])
    assert "-n staging " in cmd[-1] and "prod" not in cmd[-1]
    assert cmd[2] == "/tmp/other.pem"


def test_email_quote_escaped(env):
    db = FakeDb(ids=())
    run(env, db, email="o'brien'; DROP TABLE account;--@x.local")
    sql = db.calls[0][0]
    assert "'o''brien''; DROP TABLE account;--@x.local'" in sql
    assert da.sql_quote("a'b") == "'a''b'"


def test_shell_quote_keeps_remote_command_one_argument():
    cmd = da.ssh_command(["-c", "SELECT 'x y'"])
    assert cmd[-1].endswith("-c 'SELECT '\\''x y'\\'''")


def test_apply_three_consecutive_failures_exit_1_with_exactly_three_applies(env):
    db = FakeDb(states=((3, 5, 0),), apply_fail=True)
    assert run(env, db, "--watch", "--timeout", "600") == 1
    assert db.applied == 3
    assert len(env.built) == 3


def test_build_three_consecutive_failures_exit_1(env):
    n = []

    def bad_build(sid, end, run_dir, **kwargs):
        n.append(1)
        raise da.AttachError("가짜 생성 실패")

    env.monkeypatch.setattr(da, "build_sql", bad_build)
    db = FakeDb(states=((3, 5, 0),))
    assert run(env, db, "--watch", "--timeout", "600") == 1
    assert len(n) == 3 and db.applied == 0


@pytest.mark.parametrize("sp,sc", [(2, 0), (0, 1), (3, 4)])
def test_supplier_product_or_stock_count_present_exits_1_no_write(env, sp, sc, capsys):
    db = FakeDb(states=((3, 5, 3, 0, sp, sc),))
    assert run(env, db, "--watch") == 1
    assert db.applied == 0 and env.built == []
    assert "243 적용 조건 아님" in capsys.readouterr().err


def test_lock_file_blocks_second_run(env):
    db = FakeDb(states=((3, 5, 0),))
    lock = da._Lock(env.work, EMAIL)
    assert lock.acquire()
    try:
        assert run(env, db) == 1
        assert db.applied == 0 and env.built == []
        assert lock.path.exists()  # 남의 잠금은 지우지 않는다
    finally:
        lock.release()
    assert run(env, FakeDb(states=((3, 5, 0),))) == 0
    after = da._Lock(env.work, EMAIL)
    assert after.acquire()  # 끝난 뒤 바로 잡을 수 있다
    after.release()


_CHILD = r"""
import importlib.util, sys, time
from pathlib import Path
spec = importlib.util.spec_from_file_location("demo_attach", sys.argv[1])
da = importlib.util.module_from_spec(spec)
sys.modules["demo_attach"] = da
spec.loader.exec_module(da)
lock = da._Lock(Path(sys.argv[2]), sys.argv[3])
start, release = Path(sys.argv[4]), Path(sys.argv[5])
print("ready", flush=True)
deadline = time.time() + 20
while not start.exists() and time.time() < deadline:
    pass
print("held" if lock.acquire() else "busy", flush=True)
while not release.exists() and time.time() < deadline:
    time.sleep(0.01)
"""


def _spawn(tmp_path, start, release):
    import subprocess
    import tempfile

    # 앞선 Python 이 아직 파일을 읽고 있어도 다음 spawn 이 덮어쓰지 않는다.
    with tempfile.NamedTemporaryFile("w", dir=tmp_path, prefix="child_lock-", suffix=".py", encoding="utf-8", delete=False) as script:
        script.write(_CHILD)
    return subprocess.Popen(
        [sys.executable, script.name, str(SCRIPT), str(tmp_path / "work"), EMAIL, str(start), str(release)],
        stdout=subprocess.PIPE, text=True,
    )


def test_second_lock_in_same_process_is_refused_until_release(env):
    first, second = da._Lock(env.work, EMAIL), da._Lock(env.work, EMAIL)
    assert first.acquire()
    assert not second.acquire()
    first.release()
    assert second.acquire()
    second.release()


def test_lock_freed_when_holder_process_is_killed(env, tmp_path):
    start, release = tmp_path / "start", tmp_path / "release"
    start.write_text("go")
    child = _spawn(tmp_path, start, release)
    try:
        assert child.stdout.readline().strip() == "ready"
        assert child.stdout.readline().strip() == "held"
        parent = da._Lock(env.work, EMAIL)
        assert not parent.acquire()  # 살아 있는 동안은 못 잡는다
        child.kill()
        child.wait()
        assert parent.acquire()  # 강제 종료 즉시 풀린다
        parent.release()
    finally:
        child.kill()
        child.wait()
        child.stdout.close()


def test_two_real_processes_exactly_one_gets_lock_20_times(env, tmp_path):
    results = []
    for i in range(20):
        start, release = tmp_path / f"start{i}", tmp_path / f"release{i}"
        kids = [_spawn(tmp_path, start, release) for _ in range(2)]
        try:
            for k in kids:
                assert k.stdout.readline().strip() == "ready"
            start.write_text("go")
            got = [k.stdout.readline().strip() for k in kids]
        finally:
            release.write_text("done")
            for k in kids:
                k.wait(timeout=30)
                k.stdout.close()
        results.append(got.count("held"))
        assert sorted(got) == ["busy", "held"], f"round {i}: {got}"
    print("20회 결과:", results)


def test_run_refuses_while_lock_held_and_keeps_it(env):
    db = FakeDb(states=((3, 5, 0),))
    holder = da._Lock(env.work, EMAIL)
    assert holder.acquire()
    try:
        assert run(env, db) == 1
        assert db.applied == 0 and env.built == []
        assert holder.path.exists()
        assert not da._Lock(env.work, EMAIL).acquire()  # 여전히 holder 가 쥐고 있다
    finally:
        holder.release()
    again = da._Lock(env.work, EMAIL)
    assert again.acquire()
    again.release()


def test_summary_failure_after_apply_still_ok(env, monkeypatch):
    db = FakeDb(states=((3, 5, 0),))
    monkeypatch.setattr(da, "result_summary", lambda sid: (_ for _ in ()).throw(da.AttachError("x")))
    assert run(env, db) == 0
    assert db.applied == 1


def test_lock_released_on_failure(env):
    db = FakeDb(states=((3, 5, 0),), apply_fail=True)
    assert run(env, db) == 1
    after = da._Lock(env.work, EMAIL)
    assert after.acquire()
    after.release()


def test_recipe_count_change_waits_more(env):
    db = FakeDb(states=((3, 5, 2, 0, 0, 0), (3, 5, 6, 0, 0, 0), (3, 5, 9, 0, 0, 0), (3, 5, 9, 0, 0, 0)))
    assert run(env, db, "--watch") == 0
    assert db.applied == 1 and len(env.sleeps) == 3


def test_bad_interval_and_end_exit_2(env):
    db = FakeDb()
    env.install(db)
    assert da.main(["--email", EMAIL, "--interval", "0"]) == 2
    assert da.main(["--email", EMAIL, "--end", "20261105"]) == 2
    assert da.main(["--email", EMAIL, "--end", "2026-1-5"]) == 2
    assert db.calls == []


def test_cp949_stdout_does_not_crash(tmp_path):
    import subprocess
    code = (
        "import sys, importlib.util;"
        f"s=importlib.util.spec_from_file_location('d', r'{SCRIPT}'); m=importlib.util.module_from_spec(s); s.loader.exec_module(m);"
        "m.run_psql=lambda sql=None, stdin=None: '';"  # 계정 없음 경로
        "sys.exit(m.main(['--email','x@y.z','--today','2026-11-06','--end','2026-11-05']))"
    )
    env = {k: v for k, v in os.environ.items() if k != "PYTHONUTF8"}
    env["PYTHONIOENCODING"] = "cp949"
    env["WEGLEE_DEMO_WORKDIR"] = str(tmp_path)
    p = subprocess.run([sys.executable, "-c", code], capture_output=True, env=env)
    assert p.returncode == 2, p.stderr
    assert b"UnicodeEncodeError" not in p.stderr


def test_explicit_template_preparation_only_calls_generation(monkeypatch, tmp_path):
    calls = []
    monkeypatch.setattr(da, 'prepare_template', lambda *a: calls.append(a))
    assert da.main(['--prepare-template', '7', '--template-dir', str(tmp_path / 'cache')]) == 0
    assert calls == [(7, '2026-11-11', tmp_path / 'cache', None, None)]



def test_forever_attaches_again_after_resignup(env, capsys):
    """다시 가입해 가게 id 가 바뀌면 새 가게에 또 붙이고, 같은 가게는 다시 건드리지 않는다 (S15P31A308-337)."""
    db = FakeDb(ids=(7,))

    class Stop(Exception):
        pass

    idle = {"n": 0}

    def sleep(sec):
        if env.built and env.built[-1][0] == 7 and idle["n"] == 2:
            db.ids = [8]  # 다시 가입
        idle["n"] += 1
        if idle["n"] > 12:
            raise Stop

    env.monkeypatch.setattr(da, "_sleep", sleep)
    env.install(db)
    with pytest.raises(Stop):
        da.main(["--email", EMAIL, "--end", "2026-11-11", "--forever"])
    assert [sid for sid, _ in env.built] == [7, 8]
    assert db.applied == 2
    assert "DATA_READY" in capsys.readouterr().out


def test_spawn_keeps_each_child_program_until_it_is_read(tmp_path, monkeypatch):
    import subprocess

    gate = tmp_path / "read-script"
    popen = subprocess.Popen
    delayed = (
        'import runpy, sys, time\n'
        'from pathlib import Path\n'
        'gate = Path(sys.argv[1])\n'
        'print("waiting", flush=True)\n'
        'deadline = time.monotonic() + 20\n'
        'while not gate.exists() and time.monotonic() < deadline:\n'
        '    time.sleep(0.01)\n'
        'sys.argv = sys.argv[2:]\n'
        'runpy.run_path(sys.argv[0], run_name="__main__")\n'
    )

    def launch(args, **kwargs):
        return popen([args[0], "-c", delayed, str(gate), *args[1:]], **kwargs)

    monkeypatch.setattr(subprocess, "Popen", launch)
    start, release = tmp_path / "start", tmp_path / "release"
    children = []
    try:
        first = _spawn(tmp_path, start, release)
        children.append(first)
        assert first.stdout.readline().strip() == "waiting"
        # 첫 Python 이 파일을 읽기 전에 다음 spawn 이 다른 프로그램을 쓴다.
        monkeypatch.setattr(sys.modules[__name__], "_CHILD", _CHILD.replace('print("ready",', 'print("second-ready",'))
        second = _spawn(tmp_path, start, release)
        children.append(second)
        assert second.stdout.readline().strip() == "waiting"
        gate.write_text("go")
        assert first.stdout.readline().strip() == "ready"
        assert second.stdout.readline().strip() == "second-ready"
        start.write_text("go")
        assert sorted(child.stdout.readline().strip() for child in children) == ["busy", "held"]
    finally:
        gate.write_text("go")
        start.write_text("go")
        release.write_text("done")
        for child in children:
            child.wait(timeout=30)
            child.stdout.close()


def test_prod_disabled_by_default_and_blocks_writes(monkeypatch, capsys):
    monkeypatch.delenv("WEGLEE_ALLOW_PROD", raising=False)
    called = []
    monkeypatch.setattr(da.subprocess, "run", lambda *a, **k: called.append(a))
    assert da.main(["--env", "prod", "--email", EMAIL]) == 2
    assert da.main(["--env", "prod", "--confirm-prod", "--email", EMAIL]) == 2
    assert da.main(["--env", "prod", "--dry-run", "--email", EMAIL]) == 2
    assert not called
    assert EMAIL not in capsys.readouterr().err


def test_prod_write_requires_confirm_prod_while_dry_run_allowed(monkeypatch, env):
    monkeypatch.setenv("WEGLEE_ALLOW_PROD", "1")
    called = []
    monkeypatch.setattr(da.subprocess, "run", lambda *a, **k: called.append(a))
    assert da.main(["--env", "prod", "--email", EMAIL]) == 2
    assert not called

    monkeypatch.setattr(da, "fetch_prod_demo_email_hash", lambda: da.hashlib.sha256(EMAIL.encode()).hexdigest())
    db = FakeDb(states=((3, 5, 0),))
    env.install(db)
    assert da.main(["--env", "prod", "--dry-run", "--today", "2026-11-06", "--end", "2026-11-05", "--email", EMAIL]) == 0
    assert db.applied == 0 and len(env.built) == 1


@pytest.mark.parametrize("env_name,ns_arg", [("staging", "prod"), ("prod", "staging"), ("prod", "kube-system")])
def test_namespace_mismatch_rejected_zero_writes(monkeypatch, env_name, ns_arg):
    monkeypatch.setenv("WEGLEE_ALLOW_PROD", "1")
    called = []
    monkeypatch.setattr(da.subprocess, "run", lambda *a, **k: called.append(a))
    assert da.main(["--env", env_name, "--namespace", ns_arg, "--confirm-prod", "--email", EMAIL]) == 2
    assert not called


def test_prod_rejects_general_or_archived_account_and_redacts_email(monkeypatch, capsys):
    monkeypatch.setenv("WEGLEE_ALLOW_PROD", "1")
    secret_email = "official-demo@example.invalid"
    monkeypatch.setattr(da, "fetch_prod_demo_email_hash", lambda: da.hashlib.sha256(secret_email.encode()).hexdigest())
    called = []
    monkeypatch.setattr(da, "run_psql", lambda *a, **k: called.append((a, k)))

    normal = "real-owner@restaurant.example.invalid"
    assert da.main(["--env", "prod", "--confirm-prod", "--email", normal]) == da.EXIT_NO_ACCOUNT
    assert da.main(["--env", "prod", "--confirm-prod", "--email", f"archived-9-{secret_email}"]) == da.EXIT_NO_ACCOUNT
    assert not called
    err = capsys.readouterr().err
    assert normal not in err and secret_email not in err


def test_prod_rejects_missing_app_secret_demo_email(monkeypatch, capsys):
    monkeypatch.setenv("WEGLEE_ALLOW_PROD", "1")
    monkeypatch.setattr(da, "fetch_prod_demo_email_hash", lambda: (_ for _ in ()).throw(da.AttachError("DEMO_ACCOUNT_NOT_CONFIGURED")))
    called = []
    monkeypatch.setattr(da, "run_psql", lambda *a, **k: called.append((a, k)))
    assert da.main(["--env", "prod", "--confirm-prod", "--email", EMAIL]) == da.EXIT_FAIL
    assert not called
    assert EMAIL not in capsys.readouterr().err


def _mock_aws_3node_ssm(monkeypatch, primary_ref, *, email_for_secret=EMAIL, chunk_sql_seen=None):
    import base64
    import json as _json

    nodes = "zigzaego-node-0\ti-000\nzigzaego-node-1\ti-111\nzigzaego-node-2\ti-222\n"
    invocations = {}
    exec_pods = []
    remote_tmp = "/tmp/demo-attach.Test12"
    b64_buf = []

    class Res:
        def __init__(self, rc=0, out="", err=""):
            self.returncode = rc
            self.stdout = out
            self.stderr = err

    def fake_run(argv, **kwargs):
        if argv[0] != "aws":
            raise AssertionError(f"unexpected cmd: {argv[0]}")
        if argv[5:7] == ["ec2", "describe-instances"]:
            return Res(out=nodes)
        if argv[5:7] == ["ssm", "send-command"]:
            pfile = next(a.split("file://", 1)[1] for a in argv if a.startswith("file://"))
            cmd_line = _json.loads(Path(pfile).read_text(encoding="ascii"))["commands"][0]
            b64 = cmd_line.split("echo ", 1)[1].split(" | base64 -d", 1)[0]
            script = base64.b64decode(b64).decode("utf-8")
            cid = f"cmd-{len(invocations) + 1}"
            pod = primary_ref["pod"]
            if "get pod -l cnpg.io/cluster=postgres,role=primary" in script:
                out = f"{pod}\n---\n{primary_ref.get('current', pod)}\n"
                invocations[cid] = {"Status": "Success", "ResponseCode": 0, "StandardOutputContent": out, "StandardErrorContent": ""}
            elif "get secret app-secrets" in script:
                digest = da.hashlib.sha256(email_for_secret.encode("utf-8")).hexdigest() if email_for_secret else "MISSING"
                invocations[cid] = {"Status": "Success", "ResponseCode": 0, "StandardOutputContent": f"{digest}\n", "StandardErrorContent": ""}
            elif "mktemp -d" in script:
                invocations[cid] = {"Status": "Success", "ResponseCode": 0, "StandardOutputContent": f"{remote_tmp}\n", "StandardErrorContent": ""}
            elif "/in.b64" in script and ">>" in script:
                b64_buf.append(script.split("printf '%s' '", 1)[1].split("'", 1)[0])
                invocations[cid] = {"Status": "Success", "ResponseCode": 0, "StandardOutputContent": "", "StandardErrorContent": ""}
            elif "kubectl -n prod exec -i" in script:
                target_pod = script.split("exec -i ", 1)[1].split(" ", 1)[0]
                exec_pods.append(target_pod)
                if chunk_sql_seen is not None and "psql -U postgres -d zigzaego -v ON_ERROR_STOP=1 -At -f -" in script and b64_buf:
                    import gzip
                    chunk_sql_seen.append(gzip.decompress(base64.b64decode("".join(b64_buf))).decode("utf-8"))
                    b64_buf.clear()
                out = primary_ref["on_exec"](script, target_pod)
                invocations[cid] = {"Status": "Success", "ResponseCode": 0, "StandardOutputContent": out, "StandardErrorContent": ""}
            elif f"rm -rf \"{remote_tmp}\"" in script:
                invocations[cid] = {"Status": "Success", "ResponseCode": 0, "StandardOutputContent": "", "StandardErrorContent": ""}
            else:
                raise AssertionError(f"unhandled SSM script:\n{script}")
            return Res(out=f"{cid}\n")
        if argv[5:7] == ["ssm", "wait"]:
            return Res()
        if argv[5:7] == ["ssm", "get-command-invocation"]:
            cid = argv[argv.index("--command-id") + 1]
            return Res(out=_json.dumps(invocations[cid]))
        raise AssertionError(f"unhandled aws args: {argv}")

    monkeypatch.setattr(da.subprocess, "run", fake_run)
    return exec_pods


def test_prod_3node_primary_failover_selects_active_primary(monkeypatch):
    state = {"pod": "postgres-2"}

    def on_exec(script, pod):
        return f"active:{pod}\n"

    state["on_exec"] = on_exec
    pods = _mock_aws_3node_ssm(monkeypatch, state)
    assert da._ssm_run_psql("SELECT 1").strip() == "active:postgres-2"
    state["pod"] = "postgres-3"
    assert da._ssm_run_psql("SELECT 1").strip() == "active:postgres-3"
    assert pods == ["postgres-2", "postgres-3"]


def test_prod_split_or_unstable_primary_aborts_before_exec(monkeypatch):
    state = {"pod": "postgres-2", "current": "postgres-1", "on_exec": lambda s, p: "should-not-run"}
    pods = _mock_aws_3node_ssm(monkeypatch, state)
    with pytest.raises(da.AttachError, match="단일 안정 상태"):
        da._ssm_run_psql("SELECT 1")
    assert pods == []


def test_prod_end_to_end_attach_after_primary_switch_with_chunked_sql(monkeypatch, tmp_path, capsys):
    monkeypatch.setenv("WEGLEE_ALLOW_PROD", "1")
    monkeypatch.setenv("WEGLEE_DEMO_WORKDIR", str(tmp_path / "work"))
    monkeypatch.setenv("WEGLEE_PROD_SSM_NODE", "1")  # zigzaego-node-1 사용 검증
    monkeypatch.setattr(da, "SSM_CHUNK_SIZE", 120)
    monkeypatch.setattr(da, "_sleep", lambda s: None)

    large_sql = "-- large demo attach payload\n" + "\n".join(
        f"INSERT INTO pos_sale(store_id,sold_date,qty) VALUES (9,'2025-01-01',{i});" for i in range(60)
    ) + "\nCOMMIT;\n"
    monkeypatch.setattr(da, "build_sql", lambda sid, end, run_dir, **kw: (
        run_dir.mkdir(parents=True, exist_ok=True),
        (run_dir / "attach.sql").write_text(large_sql, encoding="utf-8"),
    ) and (run_dir / "attach.sql"))

    round_state = {"cnt": 0}
    transferred = []

    def on_exec(script, pod):
        import base64
        if "psql -U postgres -d zigzaego -v ON_ERROR_STOP=1 -At -f -" in script:
            return ""
        q_b64 = script.split("printf '%s' '", 1)[1].split("'", 1)[0]
        sql = base64.b64decode(q_b64).decode("utf-8")
        if "FROM account a" in sql:
            return "9\n"
        if "SELECT status, attempt" in sql:
            return "PREPARING|2\n"
        if "FROM menu WHERE" in sql:
            round_state["cnt"] += 1
            return "3|5|5|0|0|0\n"
        if "FROM purchase_order" in sql:
            return "10|2|3|2026-11-11\n"
        raise AssertionError(f"unexpected sql: {sql}")

    state = {"pod": "postgres-3", "current": "postgres-3", "on_exec": on_exec}
    exec_pods = _mock_aws_3node_ssm(monkeypatch, state, email_for_secret=EMAIL, chunk_sql_seen=transferred)

    rc = da.main(["--env", "prod", "--confirm-prod", "--watch", "--today", "2026-11-12", "--email", EMAIL])
    assert rc == 0
    assert round_state["cnt"] == 2
    assert exec_pods and all(p == "postgres-3" for p in exec_pods)
    assert transferred == [large_sql]
    out = capsys.readouterr()
    assert "DATA_READY" in out.out
    assert EMAIL not in out.out and EMAIL not in out.err


@pytest.mark.parametrize("env_name", ["staging", "prod"])
def test_dry_run_with_forever_rejected_before_any_call(monkeypatch, env_name):
    monkeypatch.setenv("WEGLEE_ALLOW_PROD", "1")
    called = []
    monkeypatch.setattr(da.subprocess, "run", lambda *a, **k: called.append(a))
    assert da.main(["--env", env_name, "--dry-run", "--forever", "--email", EMAIL]) == 2
    assert not called


def test_chunked_upload_cleanup_on_failure(monkeypatch):
    monkeypatch.setattr(da, "SSM_CHUNK_SIZE", 50)
    monkeypatch.setattr(da, "_find_prod_nodes", lambda: [("zigzaego-node-0", "i-000")])
    monkeypatch.setattr(da, "resolve_prod_primary", lambda nid: "postgres-2")
    scripts = []

    def fake_ssm(instance_id, script):
        scripts.append(script)
        if "mktemp -d" in script:
            return "/tmp/demo-attach.Fail99\n"
        if "/in.b64" in script:
            raise da.AttachError("DEMO_TRANSFER_CHECKSUM_MISMATCH")
        if 'rm -rf "/tmp/demo-attach.Fail99"' in script:
            return ""
        raise AssertionError(f"unexpected script: {script}")

    payload = "\n".join(f"SELECT {i} AS row_{i};" for i in range(60))
    monkeypatch.setattr(da, "_ssm_run", fake_ssm)
    with pytest.raises(da.AttachError, match="DEMO_TRANSFER_CHECKSUM_MISMATCH"):
        da._ssm_run_psql(stdin=payload)
    assert any('rm -rf "/tmp/demo-attach.Fail99"' in s for s in scripts)
