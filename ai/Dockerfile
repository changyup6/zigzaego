# 야간 예측 배치 이미지. 클러스터 CronJob 이 매일 03:00 에 실행한다 (S15P31A308-91).
# TimesFM 가중치를 빌드할 때 받아 이미지에 넣는다. 실행할 때는 받지 않는다(HF_HUB_OFFLINE).
#
#   docker build -t ai-batch ai/
FROM ghcr.io/astral-sh/uv:python3.11-bookworm-slim

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PROJECT_ENVIRONMENT=/app/.venv \
    HF_HOME=/opt/hf

WORKDIR /app

# 의존성 · 가중치 층과 코드 층을 나눠, 코드만 바뀐 빌드는 torch 설치와 가중치 다운로드를 다시 하지 않는다
COPY pyproject.toml uv.lock .python-version README.md ./
RUN uv sync --frozen --no-dev --group batch --no-install-project
RUN /app/.venv/bin/python -c "import timesfm; timesfm.TimesFM_2p5_200M_torch.from_pretrained('google/timesfm-2.5-200m-pytorch')"

COPY src ./src
COPY scripts/run_forecast_batch.py ./scripts/
RUN uv sync --frozen --no-dev --group batch --no-editable

ENV HF_HUB_OFFLINE=1 \
    PYTHONUNBUFFERED=1

RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin batch
USER 10001

ENTRYPOINT ["/app/.venv/bin/python", "scripts/run_forecast_batch.py"]
