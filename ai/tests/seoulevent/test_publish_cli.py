from types import SimpleNamespace

from weglee_ai.seoulevent.model import publish


def test_missing_forecasts_explain_failure_on_stderr(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(publish.config, "load", lambda: SimpleNamespace(data_dir=tmp_path))

    assert publish.main([]) == 1

    output = capsys.readouterr()
    assert output.out == ""
    assert "예측표" in output.err and str(tmp_path / "out/forecast") in output.err
    assert not (tmp_path / "out/forecast/forecast_merged.csv").exists()
