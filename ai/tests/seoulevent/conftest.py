# 상권 예측(weglee_ai.seoulevent) 테스트는 의존성 그룹 event 가 있어야 돈다: uv sync --group event
# 기본 설치(CI 의 uv sync --frozen)에서는 모으지 않는다 — CI 가 --group event 를 켜면 그대로 돈다
import importlib.util

collect_ignore_glob = [] if all(importlib.util.find_spec(m) for m in ("xgboost", "sklearn", "pyproj", "holidays", "httpx")) else ["test_*.py"]
