"""python -m weglee_ai.sales_forecast <예측표.csv> — 예측표를 DB sales_forecast_week 에 적재 (로컬 실행, seoulevent/README.md)."""

import sys

from weglee_ai.sales_forecast import main

main(sys.argv[1:])
