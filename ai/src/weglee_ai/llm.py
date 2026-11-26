"""LLM chat/completions 호출 한 벌과 답에서 JSON 꺼내기. OpenAI 호환(POST {base_url}/chat/completions), 주소 · 모델은 부르는 쪽이 넘긴다. 지라 S15P31A308-110.

(본문, usage) 를 돌려준다. 재시도 규칙 · 간격 · 시간 초과 처리 · post 주입이 여기 한 곳에 있다.
"""

import json
import re
import time

from weglee_ai.ocr.http import ApiError, is_timeout, post_json

RETRY_WAITS = (1, 2, 4)  # 429·5xx·연결 오류 재시도 전 쉬는 초
USAGE_KEYS = ("prompt_tokens", "completion_tokens", "total_tokens")
ERR_CHARS = 1000  # 오류 본문을 이만큼 남긴다(_run.csv 에서 429 의 quotaId 가 보이게)
RATE_LIMIT_WAIT = 30  # 분당 429 에 retryDelay 가 없을 때 쉬는 초
RATE_LIMIT_MAX = 90  # retryDelay 를 따라도 이보다 오래 쉬지 않는다
RETRY_DELAY = re.compile(r'"retryDelay"\s*:\s*"(\d+(?:\.\d+)?)s"')


class DailyQuotaError(ApiError):
    """하루 한도 소진 429(본문에 PerDay). 폴더 실행은 이걸 받으면 그 자리에서 멈춘다."""


def _usage(doc: dict) -> dict:
    u = doc.get("usage") if isinstance(doc, dict) else None
    u = u if isinstance(u, dict) else {}
    return {k: int(u.get(k) or 0) for k in USAGE_KEYS}


def chat(messages: list, response_format: dict, *, base_url: str, key: str, model: str, post=None,
         sleep=time.sleep, temperature: float | None = 0, label: str = "LLM",
         rate_limit_fallback_wait: float | None = RATE_LIMIT_WAIT, err_chars: int = ERR_CHARS) -> tuple[str, dict]:
    """5xx·연결 오류는 1·2·4초, 분당 429 는 본문 retryDelay + 1초(없으면 30초, 최대 90초) 쉬고 최대 3번 재시도.
    label 은 오류 메시지 접두, err_chars 는 오류 메시지에 붙이는 응답 본문 길이.
    retryDelay 없는 분당 429 의 대기는 rate_limit_fallback_wait 초, None 이면 5xx 와 같은 1·2·4초.

    하루 한도 429(PerDay)는 재시도 없이 DailyQuotaError. 시간 초과는 이미 과금됐을 수 있어 바로 실패. 그 밖 4xx 바로 실패.
    """
    url = base_url.rstrip("/") + "/chat/completions"
    body = {"model": model, "messages": messages, "response_format": response_format}
    if temperature is not None:  # gpt-5 계열은 temperature 를 받지 않는다(기본값만) — None 이면 보내지 않는다
        body["temperature"] = temperature
    headers = {"Authorization": f"Bearer {key}"}
    last = ""
    wait = None
    for attempt in range(len(RETRY_WAITS) + 1):
        if attempt:
            sleep(RETRY_WAITS[attempt - 1] if wait is None else wait)
            wait = None
        try:
            status, text = post_json(post, url, headers, body)
        except OSError as e:
            if is_timeout(e):
                raise ApiError(f"{label} 시간 초과: {e}") from e
            last = f"{label} 연결 오류: {e}"
            continue
        if status == 200:
            try:
                doc = json.loads(text)
                content = doc["choices"][0]["message"]["content"]
            except (ValueError, KeyError, IndexError, TypeError) as e:
                raise ApiError(f"{label} 응답 모양이 이상하다({type(e).__name__}): {text[:err_chars]}") from e
            if not isinstance(content, str):
                raise ApiError(f"{label} 응답에 content 문자열이 없다: {text[:err_chars]}")
            return content, _usage(doc)
        last = f"{label} {status}: {text[:err_chars]}"
        if status != 429 and status < 500:
            raise ApiError(last)
        # 하루 한도 소진(Gemini 무료 등급, quotaId 에 PerDay) — 몇 초 뒤 재시도는 호출만 낭비한다.
        # 분당 한도(PerMinute)는 1분 안에 풀리므로 retryDelay(없으면 30초)만큼 쉬고 재시도한다.
        if status == 429 and "PerDay" in text:
            raise DailyQuotaError(f"{label} 429 하루 한도 소진: {text[:err_chars]}")
        if status == 429:
            if rate_limit_fallback_wait is not None or RETRY_DELAY.search(text):
                wait = rate_limit_wait(text, rate_limit_fallback_wait)
    raise ApiError(f"{last} (재시도 {len(RETRY_WAITS)}번 실패)")


def rate_limit_wait(text: str, fallback: float = RATE_LIMIT_WAIT) -> float:
    """429 본문의 retryDelay("37s") + 1초. 없거나 못 읽으면 RATE_LIMIT_WAIT 초, 많아야 RATE_LIMIT_MAX 초."""
    m = RETRY_DELAY.search(text or "")
    if not m:
        return fallback
    s = float(m.group(1)) + 1
    return min(int(s) if s.is_integer() else s, RATE_LIMIT_MAX)


class ParseError(ValueError):
    """LLM 답이 JSON 이 아니거나 스키마 · 규칙에 어긋난다."""


def extract_json(text: str, *, braces_with_fence: bool = True):
    """답 전체 → ```json 코드 블록 안 → 첫 '{' 부터 마지막 '}' 순으로 JSON 을 찾는다. 못 찾으면 ParseError.

    braces_with_fence=False 면 코드 블록이 있을 때 그 밖의 중괄호 후보는 시도하지 않는다."""
    s = (text or "").strip()
    if not s:
        raise ParseError("빈 응답")
    cands = [s]
    m = re.search(r"```[a-zA-Z]*[ \t]*\n?(.*?)```", s, re.S)
    if m:
        cands.append(m.group(1).strip())
    i, j = s.find("{"), s.rfind("}")
    if 0 <= i < j and (braces_with_fence or not m):
        cands.append(s[i:j + 1])
    err = None
    for c in cands:
        try:
            return json.loads(c)
        except json.JSONDecodeError as e:
            err = err or e
    raise ParseError(f"JSON 파싱 실패: {err}")
