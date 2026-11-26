import importlib.util
import sys
from pathlib import Path

import pytest

from weglee_ai.market.taxonomy import Taxonomy

SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "market" / "compare_search.py"


@pytest.fixture(scope="module")
def cs():
    spec = importlib.util.spec_from_file_location("compare_search", SCRIPT)
    mod = importlib.util.module_from_spec(spec)
    sys.modules["compare_search"] = mod
    spec.loader.exec_module(mod)
    return mod


@pytest.fixture(scope="module")
def tax():
    return Taxonomy.load()


def test_prefix_method_skips_brackets_and_uses_aliases(cs, tax):
    f = cs.prefix_method(tax)
    assert f("[KF365] 달걀 30구", "계란") and f("★특가★ 계란 30알", "계란")
    assert not f("새우 계란 볶음밥", "계란") and not f("국내산 대파 1단", "대파")
    assert f("흙대파 1단", "대파")  # 별칭(흙대파)도 앞말로 본다


def test_llm_method_uses_group_code_form_and_confidence(cs, tax):
    labels = {"a": ("EGG", "RAW", 0.9), "b": ("EGG", "PROCESSED", 0.9), "c": ("EGG", "RAW", 0.3), "d": ("EGG_LIQUID", "RAW", 0.9)}
    f = cs.llm_method(tax, labels)
    assert f("a", "계란") and not f("b", "계란") and not f("c", "계란") and not f("d", "계란") and f("d", "액란")
    assert not f("zzz", "계란") and not f("a", "없는품목")


def test_evaluate_precision_recall_and_leaks(cs):
    rows = [
        # 사이트 A: 1위 후보가 가공품(0), 2위가 진짜(1)
        {"query": "계란", "site": "A", "rank": "1", "product_name": "계란찜", "truth": "0", "note": ""},
        {"query": "계란", "site": "A", "rank": "2", "product_name": "계란 30구", "truth": "1", "note": ""},
        # 사이트 B: 진짜 상품이 없다 → '없음'이 정답
        {"query": "계란", "site": "B", "rank": "1", "product_name": "구운란", "truth": "0", "note": ""},
        # 사이트 C: 정답 없는 줄 → 뺀다
        {"query": "계란", "site": "C", "rank": "1", "product_name": "?", "truth": "", "note": ""},
    ]
    good = lambda name, q: name == "계란 30구"  # noqa: E731
    leaky = lambda name, q: True  # noqa: E731
    stats, leaks = cs.evaluate(rows, {"good": good, "leaky": leaky})
    g, lk = stats["good"], stats["leaky"]
    assert g["cells"] == 2 and g["unlabeled"] == 1 and g["ok"] == 2 and g["shown"] == 1 and g["shown_ok"] == 1
    assert g["positives"] == 1 and g["best_hit"] == 1 and leaks["good"] == []
    assert lk["ok"] == 0 and lk["shown"] == 2 and lk["shown_ok"] == 0 and len(leaks["leaky"]) == 2


def test_report_renders_table(cs):
    stats = {"m": {"cells": 10, "shown": 8, "shown_ok": 6, "positives": 7, "best_hit": 5, "ok": 7, "unlabeled": 0}}
    md = cs.report(stats, {"m": []}, {"m": 0.123}, 3, {"m": "메모"})
    assert "| m | 7/10 (70%) | 75% | 71% | 8 | 0.12초 | 메모 |" in md
