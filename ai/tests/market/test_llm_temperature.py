import json

from weglee_ai.llm import chat


def _post_capture(bodies):
    def post(url, headers, data):
        bodies.append(json.loads(data.decode("utf-8")))
        return 200, json.dumps({"choices": [{"message": {"content": "{}"}}], "usage": {"prompt_tokens": 1, "completion_tokens": 1}})
    return post


def test_temperature_default_zero_and_omitted_when_none():
    bodies = []
    chat([{"role": "user", "content": "x"}], {"type": "json_object"}, base_url="http://x", key="k", model="m", post=_post_capture(bodies))
    assert bodies[0]["temperature"] == 0  # 영수증 · 온보딩 호출은 그대로
    chat([{"role": "user", "content": "x"}], {"type": "json_object"}, base_url="http://x", key="k", model="m", post=_post_capture(bodies),
         temperature=None)
    assert "temperature" not in bodies[1]  # gpt-5 계열은 temperature 를 받지 않는다 — 품목군 배치는 보내지 않는다


def test_temperature_fallback_sends_zero_then_drops_temperature_when_model_rejects_it():
    from weglee_ai.market.classify import chat_with_temperature_fallback, unsupported_temperature
    from weglee_ai.ocr.http import ApiError

    seen = []

    def fake(messages, fmt, *, base_url, key, model, temperature):
        seen.append(temperature)
        if model == "gpt-5" and temperature is not None:
            raise ApiError("LLM 400: {\"error\":{\"message\":\"Unsupported value: 'temperature' does not support 0 with this model.\","
                           "\"param\":\"temperature\",\"code\":\"unsupported_value\"}}")
        return "{}", {}

    f = chat_with_temperature_fallback("http://x", "k", "gpt-4.1-mini", chat=fake)
    f([], {})
    f([], {})
    assert seen == [0, 0]  # 받는 모델은 계속 0
    seen.clear()
    g = chat_with_temperature_fallback("http://x", "k", "gpt-5", chat=fake)
    g([], {})
    g([], {})
    assert seen == [0, None, None]  # 처음 한 번 거절당하면 그 요청을 다시 보내고 그 뒤로는 생략
    assert not unsupported_temperature("LLM 429: rate limit") and not unsupported_temperature("LLM 400: bad schema")
