-- S15P31A308-344/343: 온보딩 원본 저장과 시연 자료/추천 준비 완료를 분리한다.
CREATE TABLE demo_preparation (
    store_id BIGINT PRIMARY KEY REFERENCES store(id) ON DELETE CASCADE,
    status VARCHAR(24) NOT NULL CHECK (status IN ('NOT_STARTED','PREPARING','DATA_READY','READY','FAILED')),
    phase VARCHAR(24) NOT NULL DEFAULT 'ATTACH' CHECK (phase IN ('ATTACH','RECOMMENDATION')),
    attempt INTEGER NOT NULL DEFAULT 1 CHECK (attempt > 0),
    error_code VARCHAR(80),
    retryable BOOLEAN NOT NULL DEFAULT false,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_demo_preparation_pending ON demo_preparation(status) WHERE status = 'DATA_READY';
COMMENT ON TABLE demo_preparation IS '가게별 시연 자료 준비. Python 자료/주 예측 commit(DATA_READY) 뒤 BE 추천 commit(READY). ADR-0020.';
