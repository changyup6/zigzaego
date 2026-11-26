import pytest


@pytest.fixture(autouse=True, scope="session")
def demo_password_hash_env():
    """seed.sql 을 만드는 모든 테스트가 쓰는 가짜 BCrypt 해시. 실제 값은 환경변수로 받는다."""
    mp = pytest.MonkeyPatch()
    mp.setenv("WEGLEE_DEMO_PASSWORD_HASH", "$2a$10$" + "x" * 53)
    yield
    mp.undo()
