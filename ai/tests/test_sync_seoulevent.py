import importlib.util
from pathlib import Path

spec = importlib.util.spec_from_file_location("sync_seoulevent", Path(__file__).resolve().parents[1] / "scripts/sync_seoulevent.py")
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)


def test_rewrite_imports_and_ref_paths():
    src = ('from pathlib import Path\n\nimport pandas as pd\n\nfrom seoulevent.build import geo\nimport seoulevent.model as m\n\n'
           'REF = Path("ref")\nSTATIONS = Path(__file__).resolve().parents[3] / "ref" / "stations.csv"\n'
           'text = "python -m seoulevent.model.forecast"\nkeep = "data/seoulevent.csv"\n')
    out = sync.rewrite(src, "history_effects.py")
    assert "from weglee_ai.seoulevent.build import geo" in out and "import weglee_ai.seoulevent.model as m" in out
    assert "-m weglee_ai.seoulevent.model.forecast" in out  # 실행 안내 문구도
    assert 'REF = config.REF_DIR' in out and 'STATIONS = config.REF_DIR / "stations.csv"' in out
    assert "import pandas as pd\n\nfrom weglee_ai.seoulevent import config\n" in out  # ref 를 쓰게 되면 config import
    assert "data/seoulevent.csv" in out  # 경로 안의 이름은 그대로


def test_rewrite_adds_ref_dir_to_config_once():
    src = "import os\nfrom pathlib import Path\n\nfrom dotenv import load_dotenv\n\n\ndef load():\n    pass\n"
    out = sync.rewrite(src, "config.py")
    assert out.count("REF_DIR = Path(__file__).resolve().parent / \"ref\"") == 1
    assert sync.rewrite(out, "config.py") == out  # 두 번 돌려도 같다


def test_strip_prints_keeps_errors_and_blocks():
    import ast
    src = ('import sys\n\n\ndef log(msg):\n    print(f"[t] {msg}",\n          flush=True)\n\n\n'
           'def main(x):\n    print("시작")  # 진행\n    if not x:\n        print("키 없음", file=sys.stderr)\n        return 2\n'
           '    for i in x:\n        print(i)\n    y = 1; print(y)\n    if x: print("한 줄")\n    return y\n')
    out = sync.strip_prints(src)
    ast.parse(out)  # 지운 뒤에도 파이썬 코드
    assert "file=sys.stderr" in out and "시작" not in out and "한 줄" not in out
    assert "def log(msg):\n    pass\n" in out  # 블록이 비면 pass
    assert "for i in x:\n        pass\n" in out and "y = 1\n" in out and "if x: pass\n" in out
    assert sync.strip_prints(out) == out  # 두 번 돌려도 같다


def test_sync_preserves_experiment_results_and_error_diagnostics(tmp_path, monkeypatch, capsys):
    crawler = tmp_path / "crawler"
    src = crawler / "src/seoulevent"
    (src / "model").mkdir(parents=True)
    (crawler / "ref").mkdir()
    (crawler / "tests").mkdir()
    (src / "model/experiment.py").write_text('print("precision 0.7")\n', encoding="utf-8")
    (src / "worker.py").write_text('import sys\nprint("progress")\nprint("missing input", file=sys.stderr)\n', encoding="utf-8")
    pkg, tests = tmp_path / "pkg", tmp_path / "tests"
    monkeypatch.setattr(sync, "PKG", pkg)
    monkeypatch.setattr(sync, "TESTS", tests)
    monkeypatch.setattr(sync, "KEEP", {})
    sync.sync(crawler)
    capsys.readouterr()
    exec(compile((pkg / "model/experiment.py").read_text(), "experiment.py", "exec"), {})
    exec(compile((pkg / "worker.py").read_text(), "worker.py", "exec"), {})
    out = capsys.readouterr()
    assert "precision 0.7" in out.out and "progress" not in out.out
    assert "missing input" in out.err
