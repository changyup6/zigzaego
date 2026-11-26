"""GMS chat/completions 한 번. 지라 S15P31A308-109.

GMS 는 공개 문서가 없어 OpenAI 호환(POST {base_url}/chat/completions)이라고 가정한다. 주소·모델은 환경 변수로만 받는다.
호출 · 재시도는 weglee_ai.llm.chat 이 하고, 여기서는 OCR 품목 스키마를 걸어 본문 문자열만 돌려준다.
"""

import time

from weglee_ai.llm import chat

from .schema import RESPONSE_FORMAT


def gms_chat(messages: list, *, base_url: str, key: str, model: str, post=None, sleep=time.sleep) -> str:
    """chat/completions 한 번 → 응답 본문 문자열. 재시도 규칙은 weglee_ai.llm.chat 과 같다(하루 한도 429 는 DailyQuotaError)."""
    content, _usage = chat(messages, RESPONSE_FORMAT, base_url=base_url, key=key, model=model, post=post, sleep=sleep,
                           temperature=0, label="GMS", rate_limit_fallback_wait=None, err_chars=200)
    return content
