"""Portable demo artifact contract; no remote service is contacted."""
import copy
import json

import pytest

from weglee_ai import demo_template as template


def test_semantic_fingerprint_ignores_numeric_scale():
    left = {"menu": [["A", "MAIN", True, 1000]], "ingredient": [["I", "G", "BOM"]], "recipe": [["A", "I", 1.0]]}
    right = copy.deepcopy(left)
    right["recipe"][0][-1] = 1
    assert template.fingerprint(left) == template.fingerprint(right)
    right["ingredient"][0][1] = "ML"
    assert template.fingerprint(left) != template.fingerprint(right)


def test_cache_missing_fails_without_running_generator(tmp_path):
    with pytest.raises(template.TemplateError, match="DEMO_TEMPLATE_MISSING"):
        template.load_template(tmp_path, "2026-11-11")


def test_cache_tamper_rejected_before_copy(tmp_path):
    (tmp_path / "history.sql").write_text("SELECT 1;")
    manifest = {"version": 1, "end": "2026-11-11", "days": 730, "seed": 15, "files": {"history.sql": "wrong"}}
    (tmp_path / "manifest.json").write_text(json.dumps(manifest))
    with pytest.raises(template.TemplateError, match="DEMO_TEMPLATE_CORRUPT"):
        template.load_template(tmp_path, "2026-11-11")


def test_rendered_copy_is_atomic_scoped_and_requires_attempt():
    manifest = {"semantic": {}, "schema": [], "forecast": {"week_start": "2026-11-09", "model": "fixture", "pct": 120,
                "reason": "event", "days": [], "factors": [], "analysis": None},
                "counts": {"pos_sale": 2}, "start": "2024-11-12", "end": "2026-11-11"}
    sql = template.render_copy("INSERT INTO pos_sale SELECT __DEMO_STORE_ID__;", manifest, 42, "demo@example.invalid", 3)
    assert sql.count("BEGIN;") == 1 and sql.count("COMMIT;") == 1
    assert "IF pg_is_in_recovery()" in sql
    assert "__DEMO_STORE_ID__" not in sql
    assert "FOR UPDATE" in sql and "attempt = 3" in sql
    assert "DATA_READY" in sql and "RECOMMENDATION" in sql
    assert sql.index("INSERT INTO sales_forecast_week") < sql.index("COMMIT;")
    assert "a.email = 'demo@example.invalid'" in sql
    assert "\n\\if :demo_done" in sql


def test_forecast_missing_or_wrong_week_is_explicit_failure():
    with pytest.raises(template.TemplateError, match='DEMO_FORECAST_MISSING'):
        template.validate_forecast(None, '2026-11-12')
    with pytest.raises(template.TemplateError, match='DEMO_FORECAST_MISSING'):
        template.validate_forecast({'week_start': '2026-11-02', 'days': []}, '2026-11-12')
