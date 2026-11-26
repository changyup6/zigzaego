"""예측 vs 실제 채점 (지라 S15P31A308-78, 평가 지표 정의 §4).

forecast(배치가 매일 오늘 ~ 6일 뒤를 다시 씀 → 날짜 D 의 값 = D 당일 03:00 예측, h = 1)와
pos_sale(실제 판매)을 (날짜, 메뉴, 채널)로 맞붙여 모델별 · 채널별 WAPE · Bias 를 낸다.

- 휴무일은 뺀다: 그 채널에 판매가 하나도 없는 날 (baseline.fill_daily 와 같은 규칙)
- 영업일인데 판매 행이 없는 메뉴는 실제 0
- pooled: 모든 행을 한꺼번에 합친다. Σ실제 = 0 이면 nan
- 채널별과 채널 합산(모델별 · 날짜 · 메뉴로 더한 값)을 둘 다 낸다 — 재료 소비로 이어지는 건 채널 합.
  SQL 은 채널별만 낸다. 합산은 score()
- 예측이 있는 (날짜, 메뉴, 채널)만 채점한다. 예측 없는 판매는 분모에 들어가지 않는다

결과는 저장하지 않는다. forecast · pos_sale 이 DB 에 남아 있어 필요할 때 다시 계산한다 (#51: 화면 표 없음).
"""

import pandas as pd

from weglee_ai.baseline import fill_daily
from weglee_ai.batch.db import PostgresRepository
from weglee_ai.metrics import bias, wape

ALL_CHANNELS = "합산"

# 같은 규칙의 SQL(채널별만, 합산은 score()) — DB 에서 바로 볼 때. %(store_id)s · %(start)s · %(end)s
ACCURACY_SQL = """
WITH open_day AS (          -- 채널마다 판매 합이 0 보다 큰 날 = 영업일 (fill_daily 와 같은 기준, qty 0 행만 있으면 휴무)
    SELECT sold_date, channel FROM pos_sale
    WHERE store_id = %(store_id)s AND sold_date BETWEEN %(start)s AND %(end)s
    GROUP BY sold_date, channel HAVING sum(qty) > 0
),
scored AS (
    SELECT f.model, f.channel, f.target_date, f.menu_id, f.predicted_qty AS predicted,
           COALESCE(s.qty, 0) AS actual   -- 영업일 미판매 = 0
    FROM forecast f
    JOIN open_day o ON o.sold_date = f.target_date AND o.channel = f.channel   -- 휴무일 제외
    LEFT JOIN pos_sale s ON s.store_id = f.store_id AND s.menu_id = f.menu_id
                        AND s.sold_date = f.target_date AND s.channel = f.channel
    WHERE f.store_id = %(store_id)s AND f.target_date BETWEEN %(start)s AND %(end)s
)
SELECT model, channel,
       count(*) AS rows,
       sum(actual) AS actual,
       sum(abs(actual - predicted)) / NULLIF(sum(actual), 0) AS wape,
       sum(predicted - actual) / NULLIF(sum(actual), 0) AS bias
FROM scored
GROUP BY model, channel
ORDER BY model, channel
"""


class AccuracyRepository(PostgresRepository):
    """채점 입력 조회. 연결의 생성 · 종료는 호출자가 소유한다."""

    def inputs(self, store_id: int, start, end) -> tuple[pd.DataFrame, pd.DataFrame]:
        with self.conn.cursor() as cur:
            cur.execute("SELECT target_date, menu_id, channel, model, predicted_qty FROM forecast "
                        "WHERE store_id = %s AND target_date BETWEEN %s AND %s", (store_id, start, end))
            forecasts = pd.DataFrame(cur.fetchall(), columns=["target_date", "menu_id", "channel", "model", "predicted_qty"])
            cur.execute("SELECT sold_date, menu_id, channel, qty FROM pos_sale "
                        "WHERE store_id = %s AND sold_date BETWEEN %s AND %s", (store_id, start, end))
            sales = pd.DataFrame(cur.fetchall(), columns=["sold_date", "menu_id", "channel", "qty"])
        forecasts["predicted_qty"] = forecasts["predicted_qty"].astype(float)
        sales["qty"] = sales["qty"].astype(float)
        return forecasts, sales


def join_actual(forecasts: pd.DataFrame, sales: pd.DataFrame) -> pd.DataFrame:
    """forecasts(target_date · menu_id · channel · model · predicted_qty) 에 실제 판매를 붙인다.

    sales: sold_date · menu_id · channel · qty (판매가 있는 행만). 반환에는 영업일 행만 남는다.
    """
    actual = fill_daily(sales.rename(columns={"menu_id": "menu_name"})).rename(
        columns={"menu_name": "menu_id", "sold_date": "target_date", "qty": "actual"}
    )
    f = forecasts.assign(target_date=pd.to_datetime(forecasts["target_date"]))
    open_days = actual[["channel", "target_date"]].drop_duplicates()
    f = f.merge(open_days, on=["channel", "target_date"])  # 휴무일 · 데이터 밖 날짜 제외
    joined = f.merge(actual, on=["channel", "menu_id", "target_date"], how="left")
    joined["actual"] = joined["actual"].fillna(0.0)  # 영업일인데 판매 기록이 없는 메뉴 = 0
    return joined.rename(columns={"predicted_qty": "predicted"})


def score(joined: pd.DataFrame) -> pd.DataFrame:
    """모델 × 채널(+ 합산) 의 행 수 · 실제 합 · WAPE · Bias. 채점할 행이 없으면 같은 열의 빈 표."""
    if joined.empty:
        return pd.DataFrame(columns=["model", "channel", "rows", "actual", "wape", "bias"])

    def summary(g: pd.DataFrame) -> pd.Series:
        return pd.Series({"rows": len(g), "actual": g["actual"].sum(),
                          "wape": wape(g["actual"], g["predicted"]), "bias": bias(g["actual"], g["predicted"])})

    by_channel = joined.groupby(["model", "channel"]).apply(summary, include_groups=False).reset_index()
    summed = joined.groupby(["model", "target_date", "menu_id"], as_index=False)[["actual", "predicted"]].sum()
    total = summed.groupby("model").apply(summary, include_groups=False).reset_index().assign(channel=ALL_CHANNELS)
    out = pd.concat([by_channel, total[by_channel.columns]], ignore_index=True)
    return out.sort_values(["model", "channel"], ignore_index=True)
