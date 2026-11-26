"""분기 매출과 승하차 급증일의 관계: 급증일이 전년보다 늘어난 분기에 외식 매출도 늘었나 (상관, 인과 아님)."""

import pandas as pd

from weglee_ai.seoulevent.build.district_quarter import _quarters


def link_quarter(quarter: pd.DataFrame, spikes: pd.DataFrame) -> pd.DataFrame:
    sp = spikes.assign(quarter=[_quarters(d, d)[0] for d in spikes["date"]])
    days = sp.groupby(["trdar_cd", "quarter"])["date"].nunique().rename("spike_days")
    out = quarter.join(days, on=["trdar_cd", "quarter"])
    out["spike_days"] = out["spike_days"].fillna(0).astype("int64")
    prev_q = out["quarter"].map(lambda q: f"{int(q[:4]) - 1}{q[4:]}")
    prev = out.set_index(["trdar_cd", "quarter"])["spike_days"]
    out["spike_days_diff"] = out["spike_days"] - prev.reindex(pd.MultiIndex.from_arrays([out["trdar_cd"], prev_q])).to_numpy()
    return out


def summary(linked: pd.DataFrame) -> dict:
    d = linked.dropna(subset=["spike_days_diff", "yoy_pct"])
    more = d["spike_days_diff"] > 0
    return {
        "n": len(d),
        "spearman": d["spike_days_diff"].corr(d["yoy_pct"], method="spearman"),
        "n_more_spikes": int(more.sum()),
        "median_yoy_more_spikes": d.loc[more, "yoy_pct"].median(),
        "median_yoy_other": d.loc[~more, "yoy_pct"].median(),
    }
