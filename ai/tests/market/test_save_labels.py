import importlib.util
import sys
from pathlib import Path

import duckdb
import pytest

SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "market" / "classify_products.py"


@pytest.fixture(scope="module")
def cp():
    spec = importlib.util.spec_from_file_location("classify_products", SCRIPT)
    mod = importlib.util.module_from_spec(spec)
    sys.modules["classify_products"] = mod
    spec.loader.exec_module(mod)
    return mod


def _row(name, version="v1"):
    return {"product_name": name, "item_group_code": "EGG", "form": "RAW", "qty": 30.0, "qty_unit": "EA", "confidence": 0.9,
            "reason": "r", "label_version": version}


def test_save_is_atomic_and_keeps_previous_file_on_failure(cp, tmp_path):
    cp.save_labels([_row("a"), _row("b")], tmp_path)
    assert duckdb.sql(f"SELECT count(*) FROM '{(tmp_path / 'labels.parquet').as_posix()}'").fetchall()[0][0] == 2
    assert not list(tmp_path.glob("*.tmp"))
    # 쓰다가 실패(값 형식 오류) → 이전 파일 그대로, 임시 파일 없음
    bad = _row("c")
    bad["confidence"] = object()
    with pytest.raises(Exception):  # noqa: B017
        cp.save_labels([_row("a"), bad], tmp_path)
    assert duckdb.sql(f"SELECT count(*) FROM '{(tmp_path / 'labels.parquet').as_posix()}'").fetchall()[0][0] == 2
    assert not list(tmp_path.glob("*.tmp"))
    assert cp.load_labels(tmp_path / "labels.parquet")[0]["product_name"] == "a"


def test_keeping_error_does_not_mask_the_original_exception(cp, tmp_path):
    bad = _row("c")
    bad["confidence"] = object()
    with pytest.raises(KeyError):  # 저장 실패(형식 오류)가 아니라 원래 예외(KeyError)가 올라온다
        try:
            raise KeyError("원래 오류")
        finally:
            cp.save_labels_keeping_error([bad], tmp_path)
    with pytest.raises(Exception, match="(?i)(conversion|invalid|type)"):  # 올라가는 예외가 없으면 저장 실패를 그대로 올린다
        cp.save_labels_keeping_error([bad], tmp_path)
