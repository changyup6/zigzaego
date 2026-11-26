"""TimesFM 채택 검증 실험의 시험. TimesFM 은 내려받지 않는다 — 가짜 예측기를 주입한다.

-k 로 고른다: service(서비스 입력과 같음), leak(누수), open_day(영업일 0 채우기), channel(채널 분리·합산), edge(경계값),
window(고정 창), robust(안전장치), smoke(스크립트 끝까지).
"""
import importlib.util
import sys
import types
from contextlib import nullcontext
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

from weglee_ai import metrics
from weglee_ai.baseline import forecast_day
from weglee_ai.timesfm_eval import adoption as ta

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "timesfm_eval" / "run_adoption.py"


def _series(name, start, values, drop=()):
    d = pd.date_range(start, periods=len(values))
    df = pd.DataFrame({"date": d, "series": name, "qty": np.asarray(values, float)})
    return df[~df["date"].isin(pd.to_datetime(list(drop)))].reset_index(drop=True)


# 가짜 예측기는 (contexts, horizons) 를 받아 각 context 의 horizons[i] 일 뒤 값을 돌려준다(horizons 는 무시해도 된다).
def fake_mean7(contexts, horizons=None):
    """가짜 TimesFM: context 마지막 7개 평균 + 0.01 × 길이 (값과 길이 둘 다에 반응)."""
    return np.array([float(np.mean(c[-7:])) + 0.01 * len(c) for c in contexts])


def fake_len(contexts, horizons=None):
    return np.array([float(len(c)) for c in contexts])


def fake_max(contexts, horizons=None):
    return np.array([float(np.max(c)) for c in contexts])


def fake_negative(contexts, horizons=None):
    return np.full(len(contexts), -5.0)


TEST_START = pd.Timestamp("2024-03-01")
TEST_END = pd.Timestamp("2024-03-14")


def _base_df():
    rng = np.random.default_rng(0)
    a = _series("A", "2024-01-01", rng.integers(5, 30, 74), drop=["2024-02-10", "2024-03-05", "2024-03-06"])  # t-1 휴무: 보간 누수도 잡는다
    b = _series("B", "2024-01-15", rng.integers(0, 10, 60))
    return pd.concat([a, b], ignore_index=True)


# ---------------------------------------------------------------- 누수
@pytest.mark.parametrize("weeks", [1, 4, None])
def test_leak_future_values_do_not_change_prediction_both_models(weeks):
    df = _base_df()
    t = pd.Timestamp("2024-03-07")
    r1 = ta.rolling_eval(df, TEST_START, TEST_END, weeks, fake_mean7).pred
    df2 = df.copy()
    df2.loc[df2["date"] >= t, "qty"] = 9999.0  # t 날과 그 뒤 값을 바꾼다
    r2 = ta.rolling_eval(df2, TEST_START, TEST_END, weeks, fake_mean7).pred
    k = ["series", "date"]
    m = r1.merge(r2, on=k, suffixes=("_1", "_2"))
    upto = m[m["date"] <= t]
    assert len(upto) > 0
    np.testing.assert_allclose(upto["baseline_1"], upto["baseline_2"])
    np.testing.assert_allclose(upto["timesfm_1"], upto["timesfm_2"])
    # 반대로 t 이후 날의 예측은 바뀌어야 한다(시험이 아무것도 안 잡는 게 아님을 확인)
    later = m[m["date"] > t + pd.Timedelta(days=1)]
    assert (later["timesfm_1"] != later["timesfm_2"]).any()


def test_leak_timesfm_context_ends_day_before_target():
    df = _series("A", "2024-02-01", np.arange(1, 44), drop=["2024-02-29", "2024-03-03"])
    seen = []

    def recorder(contexts, horizons):
        seen.extend((c.copy(), h) for c, h in zip(contexts, horizons, strict=False))
        return fake_len(contexts)

    pred = ta.rolling_eval(df, TEST_START, TEST_END, None, recorder).pred
    first = df["date"].min()
    recorded = set(df["date"])
    for (c, h), (_, r) in zip(seen, pred.iterrows(), strict=False):
        last = r["date"] - pd.Timedelta(days=h)
        # context = 첫 기록일 ~ t 이전 마지막 기록일 → t 날 값은 들어가지 않는다. 그 사이 날은 모두 휴무
        assert h >= 1 and last in recorded
        assert all(r["date"] - pd.Timedelta(days=k) not in recorded for k in range(1, h))
        assert r["timesfm"] == len(c) == (last - first).days + 1
        assert c[-1] == (last - first).days + 1  # 값은 날짜 순 1,2,3… → 마지막 칸 = 마지막 기록일의 값


def test_leak_baseline_is_team_formula():
    # 기준선 = 팀 baseline.forecast_day 에 그 계열의 기록(휴무일 행 없음)을 그대로 넣은 값.
    # (baseline.rolling_forecast 는 fill_daily 로 다른 계열이 판 날을 0 으로 채우므로 여기 비교 대상이 아니다)
    df = _base_df()
    pred = ta.rolling_eval(df, TEST_START, TEST_END, None, fake_mean7).pred
    hist = {n: g.set_index("date")["qty"].sort_index() for n, g in df.groupby("series")}
    expect = [max(forecast_day(hist[r.series], r.date), 0.0) for r in pred.itertuples(index=False)]
    # 채점 칸 = 시험 기간에 기록이 있는 (계열, 날짜) 와 하나하나 같다 (휴무일 행이 생기거나 기록일이 빠지면 실패)
    recorded = [(n, d) for n, s in hist.items() for d in s.index if TEST_START <= d <= TEST_END]
    assert sorted(zip(pred["series"], pred["date"], strict=False)) == sorted(recorded)
    np.testing.assert_allclose(pred["baseline"], expect)


# ---------------------------------------------------------------- 채널
def _channel_df():
    a = _series("M", "2024-02-01", [10, 0, 0, 0, 0, 0, 0] * 6 + [10] * 2)
    a["channel"] = "hall"
    b = _series("M", "2024-02-01", [0, 0, 0, 7, 0, 0, 0] * 6 + [0] * 2)
    b["channel"] = "delivery"
    return pd.concat([a, b], ignore_index=True)


def test_channel_split_and_merged_both_scored():
    df = _channel_df()
    modes = ta.evaluate_modes(df, TEST_START, TEST_END, None, fake_max)
    assert set(modes) == {ta.MODE_SPLIT, ta.MODE_MERGED}
    split, merged = modes[ta.MODE_SPLIT].pred, modes[ta.MODE_MERGED].pred
    assert len(split) == len(merged) > 0
    assert set(split["series"]) == set(merged["series"]) == {"M"}
    # 가짜 = 최댓값: 나눠 예측 후 합산 = 10 + 7 = 17, 합쳐서 예측 = max(합계) = 10
    assert (split["timesfm"] == 17).all()
    assert (merged["timesfm"] == 10).all()
    # 실제값은 두 방식 모두 채널 합
    np.testing.assert_allclose(split["actual"].to_numpy(), merged["actual"].to_numpy())


def test_channel_summary_has_both_modes():
    modes = ta.evaluate_modes(_channel_df(), TEST_START, TEST_END, 4, fake_max)
    s = {k: ta.summarize(v) for k, v in modes.items()}
    assert s[ta.MODE_SPLIT]["n_series"] == s[ta.MODE_MERGED]["n_series"] == 1


# ---------------------------------------------------------------- 경계
def test_edge_zero_row_series_skipped_and_counted():
    df = _base_df()
    only_test = _series("NEW", "2024-03-01", [5] * 14)  # 시험 전 기록 0줄
    nan_only = _series("NAN", "2024-01-01", [np.nan] * 74)  # 수량이 전부 빈 값 → 0줄
    res = ta.rolling_eval(pd.concat([df, only_test, nan_only]), TEST_START, TEST_END, None, fake_mean7)
    assert set(res.skipped) == {"NEW", "NAN"}
    assert set(res.pred["series"]) == {"A", "B"}
    assert ta.summarize(res)["n_skipped"] == 2


def test_edge_zero_actual_sum_excluded_from_series_wape_but_in_total():
    df = pd.concat([_base_df(), _series("Z", "2024-01-01", [3] * 60 + [0] * 14)], ignore_index=True)
    res = ta.rolling_eval(df, TEST_START, TEST_END, None, fake_mean7)
    s = ta.summarize(res)
    assert s["n_wape_excluded"] == 1
    per = ta.per_series(res.pred)
    assert per.loc[per["series"] == "Z", "baseline_wape"].isna().all()
    assert s["n_series"] == 3
    assert s["n_series_wape"] == 2
    # 전체 WAPE 에는 Z 의 오차(예측 3, 실제 0)가 들어간다
    no_z = ta.summarize(ta.rolling_eval(_base_df(), TEST_START, TEST_END, None, fake_mean7))
    assert s["baseline_wape"] > no_z["baseline_wape"]


def test_edge_service_context_closed_days_and_horizon():
    # 월 10, (화 휴무), 수 20, 토 40 → 화 = 화요일 기록 없음 → 전체 평균 70/3. 맨 뒤(일·월) 휴무는 메우지 않고 예측 길이 3
    s = pd.Series([10.0, 20.0, 40.0], index=pd.to_datetime(["2024-03-04", "2024-03-06", "2024-03-09"]))
    ctx, h = ta.service_context(s, pd.Timestamp("2024-03-12"))
    np.testing.assert_allclose(ctx, [10, 70 / 3, 20, 70 / 3, 70 / 3, 40], rtol=1e-6)
    assert h == 3


def test_edge_negative_prediction_clipped_to_zero():
    res = ta.rolling_eval(_base_df(), TEST_START, TEST_END, None, fake_negative)
    assert (res.pred["timesfm"] == 0).all()
    assert (res.pred["baseline"] >= 0).all()


def test_edge_no_channel_column_is_single_series():
    df = _base_df()
    assert "channel" not in df.columns
    modes = ta.evaluate_modes(df, TEST_START, TEST_END, 2, fake_mean7)
    assert list(modes) == [ta.MODE_SINGLE]
    assert set(modes[ta.MODE_SINGLE].pred["series"]) == {"A", "B"}


def test_edge_insufficient_days_gives_cannot_judge():
    s = {"baseline_wape": 0.5, "timesfm_wape": 0.1, "timesfm_bias": 0.0, "win_rate": 1.0, "mean_days": 6.9}
    assert ta.verdict(s) == (ta.V_UNKNOWN, ta.V_UNKNOWN)


def test_edge_verdict_boundaries():
    base = {"baseline_wape": 0.40, "timesfm_wape": 0.38, "timesfm_bias": 0.10, "win_rate": 0.5, "mean_days": 7}
    # |Bias| = 10% 는 통과, 개선율 5% 와 승률 50% 도 '이상' 이라 통과
    assert ta.verdict(base) == (ta.V_YES, ta.V_YES)
    assert ta.verdict({**base, "timesfm_bias": -0.1001}) == (ta.V_NO, ta.V_NO)
    assert ta.verdict({**base, "timesfm_wape": 0.3801}) == (ta.V_YES, ta.V_NO)  # 개선율 < 5%
    assert ta.verdict({**base, "win_rate": 0.49}) == (ta.V_YES, ta.V_NO)
    assert ta.verdict({**base, "timesfm_wape": 0.40}) == (ta.V_NO, ta.V_NO)  # 같으면 '<' 아님


def test_edge_report_has_skip_and_exclusion_line():
    res = ta.rolling_eval(pd.concat([_base_df(), _series("NEW", "2024-03-01", [5] * 14)]),
                          TEST_START, TEST_END, None, fake_mean7)
    line = ta.skip_line(ta.summarize(res))
    assert line == "건너뛴 계열 1개 / WAPE 제외 계열 0개"


def test_edge_metrics_match_team_definition():
    a, p = [3, 0, 5], [2, 1, 9]
    assert ta.wape(a, p) == metrics.wape(a, p)
    assert ta.bias(a, p) == metrics.bias(a, p)


# ---------------------------------------------------------------- 고정 창 (window)
@pytest.mark.parametrize("weeks", ta.WINDOW_WEEKS)
def test_window_inputs_never_exceed_k_weeks_both_models(weeks):
    df = _base_df()
    seen_base = []

    def base_recorder(history, target):
        seen_base.append((pd.Timestamp(target), history.index.min(), history.index.max(), len(history)))
        return forecast_day(history, target)

    res = ta.rolling_eval(df, TEST_START, TEST_END, weeks, fake_len, window=True, baseline_fn=base_recorder)
    assert len(res.pred) > 0
    # TimesFM: 가짜 = context 길이 → 7k 이하
    assert (res.pred["timesfm"] <= 7 * weeks).all()
    assert (res.pred["timesfm"] > 0).all()
    # 기준선: 받은 기록이 [t-7k, t) 안에만 있다
    for t, lo, hi, n in seen_base:
        assert n > 0
        assert lo >= t - pd.Timedelta(days=7 * weeks)
        assert hi < t


@pytest.mark.parametrize("weeks", ta.WINDOW_WEEKS)
def test_window_leak_future_values_do_not_change_prediction(weeks):
    df = _base_df()
    t = pd.Timestamp("2024-03-07")
    r1 = ta.rolling_eval(df, TEST_START, TEST_END, weeks, fake_mean7, window=True).pred
    df2 = df.copy()
    df2.loc[df2["date"] >= t, "qty"] = 9999.0
    r2 = ta.rolling_eval(df2, TEST_START, TEST_END, weeks, fake_mean7, window=True).pred
    m = r1.merge(r2, on=["series", "date"], suffixes=("_1", "_2"))
    upto = m[m["date"] <= t]
    assert len(upto) > 0
    np.testing.assert_allclose(upto["baseline_1"], upto["baseline_2"])
    np.testing.assert_allclose(upto["timesfm_1"], upto["timesfm_2"])
    later = m[m["date"] > t + pd.Timedelta(days=1)]
    assert (later["timesfm_1"] != later["timesfm_2"]).any()


def test_window_interpolation_uses_only_records_inside_window():
    # 창 1주: 창 밖(t-7 이전) 값 1000 은 보간에도 끌려오면 안 된다. 창 안 첫 기록 전 빈 날은 창 안의 가장 가까운 값.
    df = _series("A", "2024-02-01", [1000.0] * 22 + [5.0] * 21, drop=["2024-02-23", "2024-02-24"])
    res = ta.rolling_eval(df, TEST_START, TEST_END, 1, fake_max, window=True)
    assert len(res.pred) > 0
    assert (res.pred["timesfm"] == 5.0).all()


def test_window_differs_from_accumulating():
    df = _base_df()
    acc = ta.rolling_eval(df, TEST_START, TEST_END, 1, fake_len).pred
    win = ta.rolling_eval(df, TEST_START, TEST_END, 1, fake_len, window=True).pred
    assert acc["timesfm"].max() > 7 and win["timesfm"].max() <= 7


# ---------------------------------------------------------------- 채널: 배달 영업일(다른 메뉴 배달이 팔린 날)에 행이 없으면 0
def test_channel_sparse_delivery_row_missing_means_zero_not_closed():
    hall = _series("M", "2024-01-01", [10.0] * 74)
    hall["channel"] = "hall"
    d = pd.date_range("2024-01-01", periods=74)
    deliv = pd.DataFrame({"date": d[d.weekday == 3], "series": "M", "qty": 7.0, "channel": "delivery"})
    other = _series("N", "2024-01-01", [3.0] * 74)  # 다른 메뉴 N 은 배달이 매일 팔린다 → 배달 채널은 매일 영업
    other["channel"] = "delivery"
    df = ta.fill_open_days(pd.concat([hall, deliv, other], ignore_index=True))
    m_deliv = df[(df["series"] == "M") & (df["channel"] == "delivery")]
    assert len(m_deliv) == 74 and (m_deliv["qty"] == 0).sum() == 74 - len(deliv)
    df = df[df["series"] == "M"]

    def last(cs, hs=None):
        return np.array([float(c[-1]) for c in cs])

    for window in (False, True):
        modes = ta.evaluate_modes(df, TEST_START, TEST_END, 4, last, window=window)
        s = {k: ta.summarize(v) for k, v in modes.items()}
        assert abs(s[ta.MODE_SPLIT]["timesfm_bias"]) <= 0.01, s[ta.MODE_SPLIT]
        assert abs(s[ta.MODE_MERGED]["timesfm_bias"]) <= 0.01
        assert len(modes[ta.MODE_SPLIT].pred) == len(modes[ta.MODE_MERGED].pred) == 14


# ---------------------------------------------------------------- 안전장치 (robust)
class FakeModel:
    """가짜 TimesFM 모델: compile 의 max_context 보다 긴 입력은 실제처럼 앞을 자른다. 예측 = 마지막 3개 평균 + 길이."""

    def __init__(self):
        self.max_context = None
        self.n_inputs = 0

    def compile(self, cfg):
        self.max_context = cfg.max_context

    def forecast(self, horizon, inputs):
        self.n_inputs += len(inputs)
        out = []
        for c in inputs:
            c = np.asarray(c, float)[-self.max_context:]
            out.append([float(np.mean(c[-3:])) + len(c) + 1000.0 * k for k in range(horizon)])  # k 일 뒤(0부터)
        return np.array(out), None


@pytest.fixture
def fake_torch_timesfm(monkeypatch):
    """torch · timesfm 은 batch 그룹에만 있다. 예측기 묶음·캐시 시험은 가짜 모듈로 돌린다(실제 모델은 안 부른다)."""
    torch = types.ModuleType("torch")
    torch.inference_mode = nullcontext
    timesfm = types.ModuleType("timesfm")
    timesfm.ForecastConfig = lambda **kw: types.SimpleNamespace(**kw)
    monkeypatch.setitem(sys.modules, "torch", torch)
    monkeypatch.setitem(sys.modules, "timesfm", timesfm)


def _contexts():
    rng = np.random.default_rng(3)
    base = [rng.integers(0, 20, n).astype(np.float32) for n in [1, 5, 31, 32, 33, 64, 65, 100, 7]]
    return base + [base[4].copy(), base[1].copy()]  # 같은 입력 두 개(캐시 확인용)


@pytest.mark.parametrize("batch", [1, 2, 3, 128])
def test_robust_predictor_same_output_for_any_batch_size(batch, fake_torch_timesfm):
    ctx = _contexts()
    expect = np.array([float(np.mean(c[-3:])) + len(c) for c in ctx])
    p = ta.TimesFMPredictor(model=FakeModel(), batch_size=batch)
    np.testing.assert_allclose(p(ctx), expect)  # 길이순 정렬·묶음 뒤에도 원래 순서로, 잘리지 않고


def test_robust_predictor_horizon_picks_step_and_is_in_cache_key(fake_torch_timesfm):
    model = FakeModel()
    p = ta.TimesFMPredictor(model=model, batch_size=3)
    ctx = _contexts()
    hs = [1, 3, 2, 1, 5, 1, 2, 4, 1, 3, 2]  # 같은 맥락 두 개(마지막 둘)는 원본과 예측 길이가 같다
    base = np.array([float(np.mean(c[-3:])) + len(c) for c in ctx])
    np.testing.assert_allclose(p(ctx, hs), base + 1000.0 * (np.array(hs) - 1))
    n = model.n_inputs
    np.testing.assert_allclose(p([ctx[0]], [2]), [base[0] + 1000.0])  # 같은 맥락이라도 예측 길이가 다르면 새로 계산
    assert model.n_inputs == n + 1


def test_robust_real_model_same_context_in_short_and_long_batch():
    """리뷰 L8: 캐시 키는 (맥락, 예측 길이)뿐이다. 같은 맥락이 짧은 묶음(max_context 32)과 긴 묶음(512)에
    들어가도 값이 같아야 캐시가 맞다. 다르면 캐시 키에 max_context 를 넣어야 한다."""
    pytest.importorskip("timesfm")
    pytest.importorskip("torch")
    rng = np.random.default_rng(5)
    ctx = (20 + 5 * np.sin(np.arange(28) * 2 * np.pi / 7) + rng.normal(0, 1, 28)).astype(np.float32)
    long_ctx = (20 + rng.normal(0, 3, 500)).astype(np.float32)
    short = ta.TimesFMPredictor(batch_size=4)
    a = short([ctx], [3])
    long = ta.TimesFMPredictor(batch_size=4)
    long.model = short.model  # 체크포인트는 한 번만 불러온다
    b = long([ctx, long_ctx], [3, 1])  # 같은 묶음에 긴 입력 → max_context 512 로 컴파일
    assert short._ctx == 32 and long._ctx == 512
    np.testing.assert_allclose(b[0], a[0], rtol=1e-3, atol=1e-3)


def test_robust_round_context():
    assert [ta.round_context(m) for m in (1, 31, 32, 33, 64, 65, 480)] == [32, 32, 32, 64, 64, 96, 480]
    for m in range(1, 300):
        assert ta.round_context(m) >= m and ta.round_context(m) % 32 == 0


def test_robust_predictor_cache_skips_duplicates(fake_torch_timesfm):
    model = FakeModel()
    p = ta.TimesFMPredictor(model=model, batch_size=4)
    ctx = _contexts()
    first = p(ctx)
    n = model.n_inputs
    assert n == len({c.tobytes() for c in ctx}) == len(ctx) - 2
    again = p(list(reversed(ctx)))
    assert model.n_inputs == n  # 두 번째는 전부 저장된 값
    np.testing.assert_allclose(again, first[::-1])


def test_robust_zero_ratio_bin_boundaries():
    def s(name, zeros):  # 시험 전 10일 중 0 인 날 수
        return _series(name, "2024-02-20", [0.0] * zeros + [1.0] * (10 - zeros) + [1.0] * 5)

    df = pd.concat([s("z0", 0), s("z10", 1), s("z20", 2), s("z30", 3), s("z100", 10)])
    b = ta.zero_ratio_bins(df, pd.Timestamp("2024-03-01"))
    assert b["z0"] == "0~10%"
    assert b["z10"] == "10~30%"  # 정확히 10% → 10~30%
    assert b["z20"] == "10~30%"
    assert b["z30"] == "30%+"  # 정확히 30% → 30%+
    assert b["z100"] == "30%+"


def _load_script(out_dir: Path | None = None):
    spec = importlib.util.spec_from_file_location("run_adoption", SCRIPT)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    if out_dir is not None:  # 시험이 저장소 ai/outputs 를 건드리지 않게
        mod.OUT = out_dir
        mod.REPORT = out_dir / "timesfm_adoption.md"
        mod.FIG = out_dir / "figures" / "timesfm_adoption.png"
    return mod


def test_robust_load_recruit_from_nested_zip(tmp_path):
    import io
    import zipfile

    r = _load_script()
    rows = []
    for sid, start in [("air_a", "2016-01-01"), ("air_b", "2016-03-01"), ("air_c", "2016-12-01")]:
        for d in pd.date_range(start, "2017-04-22"):
            rows.append((sid, d.strftime("%Y-%m-%d"), 10))
    rows.append(("air_d", "2016-01-01", 3))  # 시험 기간 기록 없음 → 자격 없음
    csv = pd.DataFrame(rows, columns=["air_store_id", "visit_date", "visitors"]).to_csv(index=False)
    inner = io.BytesIO()
    with zipfile.ZipFile(inner, "w") as z:
        z.writestr("air_visit_data.csv", csv)
    outer = tmp_path / "recruit.zip"
    with zipfile.ZipFile(outer, "w") as z:
        z.writestr("air_visit_data.csv.zip", inner.getvalue())
    df, start, end, info = r.load_recruit(outer)
    # air_c 는 시험 전 기록 101일 < 300 → 제외, air_d 는 시험 기간 기록 없음 → 제외
    assert sorted(df["series"].unique()) == ["air_a", "air_b"]
    assert info["n_eligible"] == 2 and info["n_picked"] == 2 and info["n_all_series"] == 4
    assert (start, end) == (pd.Timestamp("2017-03-12"), pd.Timestamp("2017-04-22"))
    assert df["date"].min() == pd.Timestamp("2016-01-01") and df["date"].max() == pd.Timestamp("2017-04-22")


def test_robust_load_freshretail_from_small_parquet(tmp_path, monkeypatch):
    # pyarrow 는 팀 의존성에 없다 — parquet 파일 대신 같은 칸의 작은 합성 표를 read_parquet 자리에 끼운다
    r = _load_script()
    dates = pd.date_range("2024-03-28", periods=90).strftime("%Y-%m-%d")
    rows = [(s, p, d, 1.0) for s in range(3) for p in (10, 20) for d in dates]
    table = pd.DataFrame(rows, columns=["store_id", "product_id", "dt", "sale_amount"])
    path = tmp_path / "train.parquet"
    seen = {}

    def fake_read_parquet(p, columns=None):
        seen["path"], seen["columns"] = Path(p), columns
        return table[columns] if columns else table

    monkeypatch.setattr(r.pd, "read_parquet", fake_read_parquet)
    df, start, end, info = r.load_freshretail(path)
    assert seen == {"path": path, "columns": ["store_id", "product_id", "dt", "sale_amount"]}
    assert df["series"].nunique() == 6 and info["n_picked"] == 6 and info["n_all_series"] == 6
    assert len(df) == 6 * 90
    assert end == pd.Timestamp("2024-06-25")
    assert start == pd.Timestamp("2024-05-29")  # 마지막 28일
    assert (end - start).days + 1 == 28


# ---------------------------------------------------------------- 살아남은 변이를 잡는 시험
def test_robust_win_rate_tie_is_not_timesfm_win():
    d = pd.to_datetime(["2024-03-01", "2024-03-02"])
    pred = pd.DataFrame({"series": ["A", "A", "B", "B"], "date": list(d) * 2,
                         "actual": [10.0, 10.0, 10.0, 10.0],
                         "baseline": [8.0, 12.0, 5.0, 5.0],
                         "timesfm": [12.0, 8.0, 9.0, 9.0]})  # A: 동점(WAPE 0.2 = 0.2), B: TimesFM 승
    s = ta.summarize(pred, {})
    assert s["n_series_wape"] == 2
    assert s["win_rate"] == 0.5


def test_window_timesfm_context_starts_at_first_record_inside_window():
    # 창 1주, t = 03-01 → 창 [02-23, 03-01). 창 안 기록은 02-26 ~ 02-29 뿐 → 입력 길이 4 (창 첫날부터 7 이 아님)
    df = _series("A", "2024-02-01", [3.0] * 44, drop=["2024-02-23", "2024-02-24", "2024-02-25"])
    res = ta.rolling_eval(df, TEST_START, TEST_END, 1, fake_len, window=True)
    first_day = res.pred[res.pred["date"] == TEST_START]
    assert first_day["timesfm"].tolist() == [4.0]


def test_robust_report_survives_old_format_summary(tmp_path):
    r = _load_script(tmp_path / "out")
    r.OUT.mkdir(parents=True)
    rows = []
    for h in ["1주", "2주", "4주", "12주", "전체"]:  # 'method' 칸 없는 옛 형식
        rows.append({"dataset": "recruit", "mode": ta.MODE_SINGLE, "zero_bin": "전체", "history": h,
                     "n_series": 2, "n_skipped": 0, "n_wape_excluded": 0, "n_series_wape": 2, "n_rows": 20,
                     "mean_days": 10.0, "baseline_wape": 0.4, "timesfm_wape": 0.3, "baseline_bias": 0.0,
                     "timesfm_bias": 0.0, "improve": 0.25, "win_rate": 1.0,
                     "verdict_team": ta.V_YES, "verdict_strict": ta.V_YES})
    pd.DataFrame(rows).to_csv(r.OUT / "recruit_summary.csv", index=False)
    meta = {"test_start": "2017-03-12", "test_end": "2017-04-22", "n_picked": 2, "n_eligible": 2,
            "n_all_series": 2, "total_seconds": 1, "timesfm_seconds": 1, "load_seconds": 0}
    import json

    (r.OUT / "recruit_meta.json").write_text(json.dumps(meta), encoding="utf-8")
    r.write_report()  # 죽지 않아야 한다
    text = r.REPORT.read_text(encoding="utf-8")
    assert "옛 형식" in text
    assert "쌓이는 · 이력 1주 · 팀 기준" in text


def test_robust_closed_next_day_breakdown():
    r = _load_script()
    pred = pd.DataFrame({
        "series": ["A"] * 4, "date": pd.to_datetime(["2024-03-01", "2024-03-02", "2024-03-04", "2024-03-05"]),
        "actual": [10.0, 10.0, 10.0, 10.0], "baseline": [10.0, 10.0, 5.0, 10.0], "timesfm": [10.0, 10.0, 2.0, 10.0],
        "method": ta.METHOD_WINDOW, "mode": ta.MODE_SINGLE, "history": "1주", "prev_closed": [False, False, True, False]})
    t = r.closed_next_day_table(pred)
    row = t[t["prev_closed"]].iloc[0]
    assert row["n_rows"] == 1 and row["baseline_wape"] == 0.5 and row["timesfm_wape"] == 0.8
    row = t[~t["prev_closed"]].iloc[0]
    assert row["n_rows"] == 3 and row["timesfm_wape"] == 0.0


# ---------------------------------------------------------------- 경계: 리포트를 다시 쓸 때 동등성 절 옮기기
S, E = "<!-- equivalence:start -->", "<!-- equivalence:end -->"


@pytest.mark.parametrize("body,expected", [
    (None, []),                                                     # 리포트 없음
    ("본문만", []),                                                  # 표시 없음
    (f"앞\n{S}\n반쪽", []),                                          # 시작 표시만
    (f"앞\n{E}\n뒤", []),                                            # 끝 표시만
    (f"{E}\n뒤집힘\n{S}", []),                                       # 순서 뒤집힘
    (f"OLD\n{E}\nX\n{S}\nSEC\n{E}", [f"{S}\nSEC\n{E}", ""]),         # 앞에 짝 없는 끝 표시 → 뒤 정상 절
    (f"{S}\nhalf\n{S}\nNEW\n{E}", [f"{S}\nNEW\n{E}", ""]),           # 앞에 짝 없는 시작 표시 → 옛 조각 안 끌고 옴
])
def test_edge_kept_equivalence_section_marker_cases(tmp_path, body, expected):
    r = _load_script(tmp_path)
    if body is not None:
        r.REPORT.write_text(body, encoding="utf-8")
    assert r._kept_equivalence_section() == expected


# ---------------------------------------------------------------- 연기 시험: 스크립트가 끝까지 돈다
def test_smoke_run_adoption_csv_with_fake_predictor(tmp_path):
    """합성 CSV(채널 칸 있음) + 가짜 예측기로 run 을 끝까지 돌린다. 산출물은 tmp 에만, TimesFM 은 안 부른다.

    리포트를 다시 써도 run_equivalence 가 넣은 동등성 절(표시 주석 사이)은 남는다.
    """
    r = _load_script(tmp_path / "out")
    hall = _series("돈까스", "2024-01-01", np.tile([12, 10, 11, 13, 20, 25, 18], 10)[:70], drop=["2024-02-12"])
    hall["channel"] = "hall"
    deliv = _series("돈까스", "2024-01-01", np.tile([3, 0, 2, 4, 6, 8, 5], 10)[:70], drop=["2024-02-12"])
    deliv["channel"] = "delivery"
    path = tmp_path / "sales.csv"
    pd.concat([hall, deliv]).to_csv(path, index=False)
    r.OUT.mkdir(parents=True)
    kept = "<!-- equivalence:start -->\n## 동등성 검정 (이전 실행)\n<!-- equivalence:end -->"
    r.REPORT.write_text("옛 리포트\n\n" + kept + "\n", encoding="utf-8")

    assert r.run("csv", str(path), 28, predictor=fake_mean7) == 0

    for name in ("csv_predictions.csv", "csv_per_series.csv", "csv_summary.csv", "csv_meta.json"):
        assert (r.OUT / name).exists(), name
    summary = pd.read_csv(r.OUT / "csv_summary.csv")
    assert set(summary["mode"]) == {ta.MODE_SPLIT, ta.MODE_MERGED}
    assert len(summary) == 2 * (len(ta.WINDOW_WEEKS) + len(ta.HISTORY_WEEKS))
    text = r.REPORT.read_text(encoding="utf-8")
    assert "결정 #24" in text and text.count("#2") == text.count("#24")  # 옛 결정 번호(#2x)가 남지 않는다
    assert text.count(kept) == 1


# ---------------------------------------------------------------- 서비스 입력과 같음
from weglee_ai.batch import predictors as bp  # noqa: E402


def _trailing_closed_df():
    # 03-05(화)·03-06(수) 휴무 → 03-07 예측은 맨 뒤 휴무 2일, 02-20(화) 휴무는 가운데 빈 날
    rng = np.random.default_rng(7)
    return _series("A", "2024-01-01", rng.integers(5, 40, 74).astype(float),
                   drop=["2024-02-20", "2024-03-05", "2024-03-06", "2024-03-10"])


def _service_inputs(s: pd.Series, day: pd.Timestamp):
    """predictors.TimesFmPredictor 가 day 를 예측할 때 모델에 넣는 (맥락, 예측 길이, 꺼내는 칸)."""
    past = s[s.index < day]
    last = past.index.max()
    ctx = bp.to_calendar(past, last.date())[-bp.TimesFmPredictor.MAX_CONTEXT:]
    return ctx, (day - last).days


def test_service_context_and_horizon_match_predictors():
    df = _trailing_closed_df()
    seen = []

    def recorder(contexts, horizons):
        seen.extend((np.asarray(c, float).copy(), int(h)) for c, h in zip(contexts, horizons, strict=False))
        return fake_len(contexts)

    pred = ta.rolling_eval(df, TEST_START, TEST_END, None, recorder).pred
    s = df.set_index("date")["qty"].sort_index()
    assert len(seen) == len(pred) > 0
    horizons = []
    for (ctx, h), day in zip(seen, pred["date"], strict=False):
        want_ctx, want_h = _service_inputs(s, day)
        np.testing.assert_allclose(ctx, want_ctx)
        assert h == want_h
        horizons.append(h)
    assert max(horizons) == 3  # 03-07: 마지막 기록 03-04 → 3일 뒤 (맨 뒤 휴무를 메우지 않는다)


class StepModel:
    """가짜 모델: k 일 뒤(0부터) 값 = 맥락 합 + 1000 × k. 예측 길이를 잘못 꺼내면 값이 달라진다."""

    def compile(self, cfg):
        self.max_context = cfg.max_context

    def forecast(self, horizon, inputs):
        point = np.array([[float(np.sum(c)) + 1000.0 * k for k in range(horizon)] for c in inputs])
        return point, None


def test_service_value_equals_predictors_path(fake_torch_timesfm):
    df = _trailing_closed_df()
    pred = ta.rolling_eval(df, TEST_START, TEST_END, None, ta.TimesFMPredictor(model=StepModel(), batch_size=4)).pred
    s = df.set_index("date")["qty"].sort_index()
    svc = bp.TimesFmPredictor()
    svc._model = StepModel()
    for day, got in zip(pred["date"], pred["timesfm"], strict=False):
        want = svc.predict([s[s.index < day]], [day.date()])[0][0]
        assert got == pytest.approx(max(want, 0.0))


def test_service_forecast_config_is_shared():
    assert ta.FORECAST_CONFIG is bp.FORECAST_CONFIG


# ---------------------------------------------------------------- 누수: 휴무 메우기(요일 평균)는 t 이전 · 창 안 기록만
@pytest.mark.parametrize("weeks,window", [(None, False), (2, True)])
def test_leak_weekday_fill_uses_only_past_inside_window(weeks, window):
    rng = np.random.default_rng(11)
    df = _series("A", "2024-01-01", rng.integers(1, 50, 74).astype(float), drop=["2024-02-27", "2024-03-05"])
    t = pd.Timestamp("2024-03-07")
    seen = {}

    def recorder(contexts, horizons):
        for c in contexts:
            seen.setdefault(len(seen), np.asarray(c, float).copy())
        return fake_len(contexts)

    pred = ta.rolling_eval(df, TEST_START, TEST_END, weeks, recorder, window=window).pred
    i = int(np.flatnonzero((pred["series"] == "A") & (pred["date"] == t))[0])
    s = df.set_index("date")["qty"].sort_index()
    past = s[s.index < t]
    if window:
        past = past[past.index >= t - pd.Timedelta(days=7 * weeks)]
    tue = past[past.index.dayofweek == 1]  # 03-05(화) 휴무를 메우는 값 = 볼 수 있는 화요일 평균
    expect = float(tue.mean()) if len(tue) else float(past.mean())
    ctx = seen[i]
    assert len(ctx) == (past.index.max() - past.index.min()).days + 1
    gap = (pd.Timestamp("2024-03-05") - past.index.min()).days
    assert ctx[gap] == pytest.approx(expect)
    # t 이후 값·창 밖 값을 바꿔도 t 날 맥락은 같다
    df2 = df.copy()
    lo = t - pd.Timedelta(days=7 * weeks) if window else pd.Timestamp("1900-01-01")
    df2.loc[(df2["date"] >= t) | (df2["date"] < lo), "qty"] += 1000.0
    seen.clear()
    ta.rolling_eval(df2, TEST_START, TEST_END, weeks, recorder, window=window)
    np.testing.assert_allclose(seen[i], ctx)


# ---------------------------------------------------------------- 영업일 0 채우기 (판매_데이터_사전 5절)
def test_open_day_csv_fills_unsold_menu_zero_and_drops_closed_channel_day(tmp_path):
    rows = [  # 가게 하나. 03-02: 배달 채널 전체 휴무, 03-03: 짬뽕 홀 미판매
        ("2024-03-01", "짜장", "hall", 5), ("2024-03-01", "짬뽕", "hall", 3),
        ("2024-03-01", "짜장", "delivery", 2), ("2024-03-01", "짬뽕", "delivery", 1),
        ("2024-03-02", "짜장", "hall", 4), ("2024-03-02", "짬뽕", "hall", 2),
        ("2024-03-03", "짜장", "hall", 6), ("2024-03-03", "짜장", "delivery", 2), ("2024-03-03", "짬뽕", "delivery", 1),
    ]
    path = tmp_path / "sales.csv"
    pd.DataFrame(rows, columns=["date", "series", "channel", "qty"]).to_csv(path, index=False)
    df, start, end, info = _load_script().load_csv(path, 2)
    got = {(r.series, r.channel, str(pd.Timestamp(r.date).date())): r.qty for r in df.itertuples(index=False)}
    assert got[("짬뽕", "hall", "2024-03-03")] == 0  # 홀은 영업(짜장이 팔림) → 안 팔린 짬뽕은 0
    assert not any(c == "delivery" and d == "2024-03-02" for _, c, d in got)  # 배달 전체가 쉰 날은 행 없음(휴무)
    assert len(got) == 2 * 3 + 2 * 2
    assert (start, end) == (pd.Timestamp("2024-03-02"), pd.Timestamp("2024-03-03"))


@pytest.mark.parametrize("gap, fallback", [(122, 0), (123, 1)])
def test_service_horizon_skip_boundary_matches_predictors_7day_targets(gap, fallback):
    """서비스는 오늘부터 7일(today..today+6) 구간이 MAX_HORIZON 을 넘으면 TimesFM 을 건너뛴다.
    예측일을 서비스의 '오늘' 로 보면 예측 길이 h 가 MAX_HORIZON − 6 을 넘을 때 기준선 값이어야 한다."""
    hist = pd.date_range("2024-01-01", periods=28)
    day = hist[-1] + pd.Timedelta(days=gap)
    df = pd.DataFrame({"date": list(hist) + [day], "series": "A", "qty": [10.0] * 28 + [12.0]})
    r = ta.rolling_eval(df, day, day, None, lambda cs, hs: np.full(len(cs), 99.0))

    class Flat:
        def forecast(self, horizon, inputs):
            return np.full((len(inputs), horizon), 99.0), None

    svc = bp.TimesFmPredictor()
    svc._model = Flat()
    s = df.set_index("date")["qty"]
    past = s[s.index < day]
    from weglee_ai.batch.run import HORIZON_DAYS
    got = svc.predict([past], [(day + pd.Timedelta(days=k)).date() for k in range(HORIZON_DAYS)])[0]
    want = r.pred["baseline"].iloc[0] if got is None else got[0]
    assert r.pred["timesfm"].iloc[0] == pytest.approx(want)
    assert r.n_tfm_fallback == fallback
    assert (got is None) == bool(fallback)
