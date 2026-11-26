import pandas as pd
import pytest

from weglee_ai.seoulevent import collect_std_events as cs


def test_parse_handles_wrapped_and_bare_payloads():
    item = {"fstvlNm": "중랑 서울장미축제", "rdnmadr": "서울특별시 중랑구", "latitude": "37.61"}
    bare = {"header": {"resultCode": "00"}, "body": {"items": {"item": [item]}, "totalCount": "1"}}
    wrapped = {"response": bare}
    single = {"header": {"resultCode": "00"}, "body": {"items": {"item": item}, "totalCount": "1"}}  # 한 건이면 목록이 아닌 사전
    for p in (bare, wrapped, single):
        assert cs.parse(p) == ([item], 1)
    assert cs.parse({"header": {"resultCode": "00"}, "body": {"items": "", "totalCount": "0"}}) == ([], 0)


def test_parse_raises_on_error_code():
    with pytest.raises(RuntimeError):
        cs.parse({"header": {"resultCode": "30", "resultMsg": "SERVICE_KEY_IS_NOT_REGISTERED_ERROR"}})


def test_seoul_keeps_rows_with_seoul_in_either_address():
    df = pd.DataFrame({"rdnmadr": ["서울특별시 송파구 올림픽로", "", "광주광역시 동구"], "lnmadr": ["", "서울특별시 중랑구 묵동", "광주 동구"]})
    assert list(cs.seoul(df).index) == [0, 1]
