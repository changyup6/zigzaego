"""OCR 두 방식. 지라 S15P31A308-109.

방식 A(clova_llm) = CLOVA General OCR 글자 → GMS LLM 정리. 방식 B(llm_only) = 사진을 GMS 멀티모달 LLM 에 바로.
"""

import base64
import time

from .clova import clova_general
from .gms import gms_chat
from .schema import PROMPT, parse_items

METHODS = ("clova_llm", "llm_only")


def _gms(messages, env, post, sleep) -> dict:
    content = gms_chat(messages, base_url=env["GMS_BASE_URL"], key=env["GMS_KEY"], model=env["GMS_MODEL"],
                       post=post, sleep=sleep)
    return parse_items(content)


def method_a(image_bytes: bytes, fmt: str, env: dict, *, post=None, sleep=time.sleep) -> dict:
    """방식 A: CLOVA 글자 → GMS(글자만)."""
    text = clova_general(image_bytes, fmt, url=env["CLOVA_OCR_URL"], secret=env["CLOVA_OCR_SECRET"], post=post)
    messages = [{"role": "system", "content": PROMPT}, {"role": "user", "content": text}]
    return _gms(messages, env, post, sleep)


def method_b(image_bytes: bytes, fmt: str, env: dict, *, post=None, sleep=time.sleep) -> dict:
    """방식 B: GMS 에 사진(data: URL) + 지시문."""
    mime = "image/png" if fmt == "png" else "image/jpeg"
    data_url = f"data:{mime};base64," + base64.b64encode(image_bytes).decode("ascii")
    messages = [{"role": "user", "content": [
        {"type": "text", "text": PROMPT},
        {"type": "image_url", "image_url": {"url": data_url}},
    ]}]
    return _gms(messages, env, post, sleep)
