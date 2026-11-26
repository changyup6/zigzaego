"""동등성 검정(7일 블록 부트스트랩 90% 구간, 동등 범위 ±10%) 시험. 합성 데이터만 쓴다."""
import importlib.util
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

from weglee_ai.timesfm_eval import adoption as ta
from weglee_ai.timesfm_eval import equivalence as eq

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "timesfm_eval" / "run_equivalence.py"


def _base_err(n=84, seed=0):
    rng = np.random.default_rng(seed)
    dow = np.tile([1.0, 0.9, 0.95, 1.0, 1.2, 1.5, 1.4], n // 7 + 1)[:n]
    return 100.0 * dow * rng.uniform(0.9, 1.1, n)


def test_same_errors_is_equivalent():
    b = _base_err()
    r = eq.equivalence_test(b.copy(), b)
    assert r.verdict == eq.V_SAME
    assert r.mean_diff == 0.0 and r.ci_low == 0.0 and r.ci_high == 0.0


def test_model_20pct_better():
    b = _base_err()
    rng = np.random.default_rng(1)
    m = b * 0.8 * rng.uniform(0.97, 1.03, len(b))
    r = eq.equivalence_test(m, b)
    assert r.verdict == eq.V_BETTER
    assert r.rel_ci_high < -0.10
    assert r.rel_mean_diff == pytest.approx(-0.2, abs=0.02)


def test_model_20pct_worse():
    b = _base_err()
    rng = np.random.default_rng(2)
    m = b * 1.2 * rng.uniform(0.97, 1.03, len(b))
    r = eq.equivalence_test(m, b)
    assert r.verdict == eq.V_WORSE
    assert r.rel_ci_low > 0.10


def test_noisy_is_undecided():
    b = _base_err(n=28)
    rng = np.random.default_rng(3)
    m = b * rng.uniform(0.3, 1.7, len(b))  # 평균은 비슷하지만 날마다 크게 흔들림
    r = eq.equivalence_test(m, b)
    assert r.verdict == eq.V_UNKNOWN
    assert r.rel_ci_low < -0.10 or r.rel_ci_high > 0.10


def test_too_short_is_undecided():
    b = _base_err(n=5)
    r = eq.equivalence_test(b.copy(), b)  # 7일 블록 하나도 못 만든다
    assert r.verdict == eq.V_UNKNOWN
    assert np.isnan(r.ci_low) and np.isnan(r.ci_high)
    assert r.n_days == 5


def test_seed_reproducible():
    b = _base_err()
    m = b * np.random.default_rng(4).uniform(0.7, 1.3, len(b))
    r1 = eq.equivalence_test(m, b, seed=7)
    r2 = eq.equivalence_test(m, b, seed=7)
    assert r1 == r2
    assert (r1.block, r1.n_boot, r1.seed) == (7, eq.N_BOOT, 7)
    r3 = eq.equivalence_test(m, b, seed=8)
    assert (r3.ci_low, r3.ci_high) != (r1.ci_low, r1.ci_high)


def test_empty_input_raises():
    with pytest.raises(ValueError, match="비어"):
        eq.equivalence_test(np.array([]), np.array([]))
    with pytest.raises(ValueError, match="비어"):
        eq.daily_errors(pd.DataFrame(columns=["date", "series", "actual", "timesfm", "baseline"]),
                        model="timesfm", base="baseline")


def test_zero_baseline_error_raises():
    with pytest.raises(ValueError, match="0"):
        eq.equivalence_test(np.ones(14), np.zeros(14))


def test_length_mismatch_raises():
    with pytest.raises(ValueError, match="길이"):
        eq.equivalence_test(np.ones(14), np.ones(13))


def test_blocks_are_7_consecutive_days():
    rng = np.random.default_rng(0)
    idx = eq.block_indices(30, block=7, rng=rng)
    assert len(idx) == 30
    full = idx[: (30 // 7) * 7].reshape(-1, 7)
    assert (np.diff(full, axis=1) == 1).all()  # 블록 안은 연속한 7일
    tail = idx[(30 // 7) * 7:]
    assert (np.diff(tail) == 1).all()  # 마지막 잘린 블록도 연속
    assert idx.min() >= 0 and idx.max() <= 29


def test_daily_errors_sums_over_series():
    df = pd.DataFrame({
        "date": pd.to_datetime(["2024-01-01", "2024-01-01", "2024-01-02"]),
        "series": ["a", "b", "a"],
        "actual": [10.0, 5.0, 8.0],
        "timesfm": [12.0, 5.0, 4.0],
        "baseline": [9.0, 7.0, 8.0],
    })
    d = eq.daily_errors(df, model="timesfm", base="baseline")
    assert list(d["model_err"]) == [2.0, 4.0]
    assert list(d["base_err"]) == [3.0, 0.0]
    assert list(d["diff"]) == [-1.0, 4.0]


# ---------------------------------------------------------------- 판정 경계 보강
def _pred(model_vals, actual=10.0, base=5.0):
    dates = pd.to_datetime(["2024-01-01", "2024-01-01", "2024-01-02", "2024-01-02"])
    return pd.DataFrame({"date": dates, "series": ["a", "b", "a", "b"], "actual": actual,
                         "timesfm": model_vals, "baseline": base})


def test_nan_prediction_raises():
    df = _pred([np.nan, 10.0, np.nan, 10.0])
    with pytest.raises(ValueError, match="2줄"):
        eq.daily_errors(df, model="timesfm", base="baseline")


def test_nan_actual_or_base_raises():
    df = _pred([10.0] * 4)
    df.loc[0, "actual"] = np.nan
    with pytest.raises(ValueError, match="1줄"):
        eq.daily_errors(df, model="timesfm", base="baseline")
    df = _pred([10.0] * 4)
    df.loc[3, "baseline"] = np.nan
    with pytest.raises(ValueError, match="1줄"):
        eq.daily_errors(df, model="timesfm", base="baseline")


@pytest.mark.parametrize("n", [7, 13])
def test_short_7_to_13_days_is_undecided(n):
    b = _base_err(n=n)
    r = eq.equivalence_test(b * 0.5, b)  # 50% 나아도 묶음 2개가 안 되면 판정하지 않는다
    assert r.verdict == eq.V_UNKNOWN
    assert np.isnan(r.ci_low) and np.isnan(r.ci_high)


def test_14_days_is_judged():
    b = _base_err(n=14)
    r = eq.equivalence_test(b * 0.5, b)
    assert r.verdict == eq.V_BETTER
    assert r.ci_high > r.ci_low


@pytest.mark.parametrize("lo,hi,expected", [
    (-0.10, 0.10, "same"),        # 경계 정확히 ±10% 는 '안'
    (-0.20, -0.10, "unknown"),    # 위끝이 정확히 −10% 면 '낫다' 아님(엄격히 작아야)
    (0.10, 0.20, "unknown"),      # 아래끝이 정확히 +10% 면 '못하다' 아님
    (-0.20, -0.1000001, "better"),
    (0.1000001, 0.20, "worse"),
    (-0.1000001, 0.0, "unknown"),
])
def test_judge_boundaries(lo, hi, expected):
    want = {"same": eq.V_SAME, "better": eq.V_BETTER, "worse": eq.V_WORSE, "unknown": eq.V_UNKNOWN}[expected]
    assert eq._judge(lo, hi, 0.10) == want


def test_uneven_dates_per_series():
    """계열마다 날짜 수가 다르면 그날 있는 계열만 더한다."""
    df = pd.DataFrame({
        "date": pd.to_datetime(["2024-01-01", "2024-01-02", "2024-01-03", "2024-01-02"]),
        "series": ["a", "a", "a", "b"],
        "actual": [10.0, 10.0, 10.0, 4.0],
        "timesfm": [11.0, 12.0, 13.0, 0.0],
        "baseline": [10.0, 10.0, 10.0, 1.0],
    })
    d = eq.daily_errors(df, model="timesfm", base="baseline")
    assert list(d["date"].dt.day) == [1, 2, 3]
    assert list(d["model_err"]) == [1.0, 6.0, 3.0]
    assert list(d["base_err"]) == [0.0, 3.0, 0.0]


# ---------------------------------------------------------------- 경계: 리포트 안 절 찾기·바꿔 넣기
S, E = eq.REPORT_START, eq.REPORT_END


@pytest.mark.parametrize("body,expected", [
    ("", None),
    (f"{S} 반쪽", None),
    (f"{E} 뒤집힘 {S}", None),
    (f"{S}{E}", f"{S}{E}"),                                   # 빈 절
    (f"{S} A {E} {S} B {E} {S} C {E}", f"{S} C {E}"),         # 여럿이면 마지막
    (f"{S} half\nmid\n{S} B {E}", f"{S} B {E}"),              # 앞의 짝 없는 시작 표시는 절이 아니다
    (f"OLD {E} X {S} SEC {E}", f"{S} SEC {E}"),               # 앞의 짝 없는 끝 표시도
])
def test_edge_find_report_section(body, expected):
    span = eq.find_report_section(body)
    assert (body[span[0]:span[1]] if span else None) == expected


def test_edge_write_section_replaces_only_paired_section(tmp_path):
    spec = importlib.util.spec_from_file_location("run_equivalence", SCRIPT)
    r = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(r)
    report = tmp_path / "report.md"
    report.write_text(f"H\n{S} half\nmid\n{S} B {E}\n", encoding="utf-8")
    r.write_section(f"{S} NEW {E}", report)
    assert report.read_text(encoding="utf-8") == f"H\n{S} half\nmid\n{S} NEW {E}\n"  # 짝 없는 시작 표시 사이 본문은 그대로
    report.write_text("표시 없음", encoding="utf-8")
    r.write_section(f"{S} NEW {E}", report)
    assert report.read_text(encoding="utf-8") == f"표시 없음\n\n{S} NEW {E}\n"  # 없으면 맨 끝에 붙인다


# ---------------------------------------------------------------- 연기 시험: 스크립트가 끝까지 돈다
def _synthetic_predictions(n_days, series, seed):
    rng = np.random.default_rng(seed)
    rows = []
    for method, hists in ((ta.METHOD_WINDOW, ["1주", "2주"]), (ta.METHOD_ACCUM, ["1주", "전체"])):
        for hist in hists:
            for s in series:
                a = rng.integers(5, 30, n_days).astype(float)
                rows.append(pd.DataFrame({
                    "series": s, "date": pd.date_range("2024-03-01", periods=n_days), "actual": a,
                    "baseline": a + rng.normal(0, 5, n_days), "timesfm": a + rng.normal(0, 4, n_days),
                    "dataset": "x", "method": method, "mode": ta.MODE_SINGLE, "history": hist}))
    return pd.concat(rows, ignore_index=True)


def test_smoke_run_equivalence_no_report(tmp_path, capsys):
    """합성 예측표(Recruit 형식 + FreshRetail 형식·0 비율 구간)로 main 을 끝까지 돌린다. --no-report 라 리포트는 안 쓴다."""
    spec = importlib.util.spec_from_file_location("run_equivalence", SCRIPT)
    r = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(r)
    _synthetic_predictions(42, ["air_a", "air_b"], 0).to_csv(tmp_path / "recruit_predictions.csv", index=False)
    fresh = ["s1_p1", "s1_p2", "s2_p1"]
    _synthetic_predictions(28, fresh, 1).to_csv(tmp_path / "freshretail_predictions.csv", index=False)
    pd.DataFrame({"series": fresh, "zero_bin": ["0~10%", "10~30%", "30%+"]}).to_csv(
        tmp_path / "freshretail_zero_bins.csv", index=False)
    before = sorted(p.name for p in tmp_path.iterdir())

    assert r.main(["--pred-dir", str(tmp_path), "--no-report"]) == 0

    out = capsys.readouterr().out
    assert "block=7 n_boot=2000 seed=42" in out
    t = r.table(tmp_path)
    # Recruit: 전체 묶음 × 4 (방식·이력), FreshRetail: (전체 + 구간 3) × 4
    assert len(t) == 4 + 4 * 4
    assert set(t["verdict"]) <= {eq.V_SAME, eq.V_BETTER, eq.V_WORSE, eq.V_UNKNOWN}
    assert set(t.loc[t["dataset"] == "Recruit", "n_days"]) == {42}
    assert sorted(p.name for p in tmp_path.iterdir()) == before  # 예측표 폴더는 읽기만
    # 절 쓰기: 표시 주석 사이만 바꾸고 앞 내용은 둔다
    report = tmp_path / "report.md"
    report.write_text("앞 내용\n\n" + eq.REPORT_START + "\n옛 절\n" + eq.REPORT_END + "\n", encoding="utf-8")
    r.write_section(r.section(t), report)
    text = report.read_text(encoding="utf-8")
    assert text.startswith("앞 내용") and "옛 절" not in text and text.count(eq.REPORT_START) == 1


# ---------------------------------------------------------------- 판정 이름: 허용 범위 안 열세·우세
@pytest.mark.parametrize("lo,hi,expected", [
    (0.031, 0.098, "tol_worse"),     # Recruit 2주 예: ±10% 안이지만 0 을 포함하지 않음(더 틀림)
    (-0.08, -0.02, "tol_better"),    # ±10% 안, 0 미포함(덜 틀림)
    (-0.03, 0.02, "same"),           # 0 포함
    (0.0, 0.05, "same"),             # 아래끝이 정확히 0 → 0 포함
    (-0.05, 0.0, "same"),            # 위끝이 정확히 0 → 0 포함
    (1e-9, 0.10, "tol_worse"),
    (-0.10, -1e-9, "tol_better"),
])
def test_judge_tolerance_names(lo, hi, expected):
    want = {"same": eq.V_SAME, "tol_worse": eq.V_TOL_WORSE, "tol_better": eq.V_TOL_BETTER}[expected]
    assert eq._judge(lo, hi, 0.10) == want
    assert eq.V_TOL_WORSE == "허용 범위 내(열세)" and eq.V_TOL_BETTER == "허용 범위 내(우세)"


# ---------------------------------------------------------------- 탐색적: 블록 8개 미만
@pytest.mark.parametrize("n,exploratory", [(14, True), (42, True), (55, True), (56, False), (84, False)])
def test_exploratory_under_8_blocks(n, exploratory):
    b = _base_err(n=n)
    r = eq.equivalence_test(b * 0.5, b)
    assert eq.MIN_BLOCKS_CONFIRM == 8
    assert r.exploratory is exploratory
    assert r.verdict == eq.V_BETTER  # 판정 자체는 그대로 내고, 표시만 탐색적


def _load_runner():
    spec = importlib.util.spec_from_file_location("run_equivalence", SCRIPT)
    r = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(r)
    return r


# ---------------------------------------------------------------- 겹치는 줄 합치기 (M3) · mode 칸 · --pred-dir 리포트 (덤 버그)
def test_overlap_12w_equal_to_all_merged_into_one_row(tmp_path):
    r = _load_runner()
    p = _synthetic_predictions(42, ["air_a", "air_b"], 0)
    same = p[(p["method"] == ta.METHOD_ACCUM) & (p["history"] == "전체")].assign(history="12주")
    pd.concat([p, same]).to_csv(tmp_path / "recruit_predictions.csv", index=False)
    _synthetic_predictions(28, ["s1"], 1).to_csv(tmp_path / "freshretail_predictions.csv", index=False)
    t = r.table(tmp_path)
    rec = t[(t["dataset"] == "Recruit") & (t["method"] == ta.METHOD_ACCUM)]
    assert list(rec["history"]) == ["1주", "12주(=전체)"]  # 같은 예측표인 12주·전체는 한 줄
    text = r.section(t)
    assert f"판정 {len(t)}개" in text and "다중 비교" in text and "부분집합" in text
    assert "허용 범위 내(우세)" in text and "[−8%, −2%]" in text and "[−3%, +2%]" in text


def test_overlap_different_12w_and_all_kept_apart(tmp_path):
    r = _load_runner()
    p = _synthetic_predictions(42, ["air_a"], 0)
    diff = p[(p["method"] == ta.METHOD_ACCUM) & (p["history"] == "전체")].assign(history="12주")
    diff["timesfm"] = diff["timesfm"] + 1.0
    pd.concat([p, diff]).to_csv(tmp_path / "recruit_predictions.csv", index=False)
    _synthetic_predictions(28, ["s1"], 1).to_csv(tmp_path / "freshretail_predictions.csv", index=False)
    rec = r.table(tmp_path)
    assert set(rec.loc[(rec["dataset"] == "Recruit") & (rec["method"] == ta.METHOD_ACCUM), "history"]) == {"1주", "12주", "전체"}


def test_mode_column_separates_channel_rows(tmp_path):
    r = _load_runner()
    p = _synthetic_predictions(28, ["m1"], 0)
    both = pd.concat([p.assign(mode=ta.MODE_SPLIT), p.assign(mode=ta.MODE_MERGED, timesfm=p["timesfm"] + 2)])
    both.to_csv(tmp_path / "recruit_predictions.csv", index=False)
    _synthetic_predictions(28, ["s1"], 1).to_csv(tmp_path / "freshretail_predictions.csv", index=False)
    t = r.table(tmp_path)
    text = r.section(t)
    assert all(x.startswith("| 데이터 | 채널 방식 |") for x in text.split("\n") if x.startswith("| 데이터"))
    rows = [x for x in text.split("\n") if x.startswith("| Recruit")]
    assert any(ta.MODE_SPLIT in x for x in rows) and any(ta.MODE_MERGED in x for x in rows)


def test_pred_dir_report_written_in_that_folder(tmp_path):
    r = _load_runner()
    _synthetic_predictions(42, ["air_a"], 0).to_csv(tmp_path / "recruit_predictions.csv", index=False)
    _synthetic_predictions(28, ["s1"], 1).to_csv(tmp_path / "freshretail_predictions.csv", index=False)
    r.REPORT = tmp_path / "기본_리포트_자리_아님.md"  # 기본 경로로 쓰면 이 파일이 생긴다
    assert r.main(["--pred-dir", str(tmp_path)]) == 0
    assert (tmp_path / "timesfm_adoption.md").exists()
    assert not r.REPORT.exists()
